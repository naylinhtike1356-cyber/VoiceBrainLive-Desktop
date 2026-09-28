package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.platform.audio.AudioCapture
import com.example.voicebrainlive.desktop.platform.audio.AudioLatencyTracker
import com.example.voicebrainlive.desktop.platform.audio.AudioRenderer
import com.example.voicebrainlive.desktop.platform.audio.AudioResampler
import com.example.voicebrainlive.desktop.platform.audio.AudioStage
import com.example.voicebrainlive.desktop.platform.audio.EchoCanceller
import com.example.voicebrainlive.desktop.platform.audio.EchoDecision
import com.example.voicebrainlive.desktop.platform.audio.JavaxSoundCapture
import com.example.voicebrainlive.desktop.platform.audio.JavaxSoundRenderer
import com.example.voicebrainlive.desktop.platform.audio.SpectralVad
import com.example.voicebrainlive.desktop.platform.audio.SuppressionEchoCanceller
import com.example.voicebrainlive.desktop.platform.audio.VoiceActivityDetector
import com.example.voicebrainlive.desktop.platform.audio.WebRtcAec3EchoCanceller
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

private const val ECHO_COOLDOWN_MS = 400L // Grace period for room acoustic reverb to dissipate
private const val PRE_ROLL_CHUNKS = 8 // Keep last ~256ms in memory so initial syllable is preserved
private const val RENDER_REF_KEEP_BYTES = 12_000 // ~250ms of 24kHz render audio kept as AEC reference
private const val RENDER_REF_FRAME_BYTES_24K = 1_536 // 32ms @ 24kHz — resamples to a 1024-byte 16kHz frame
private const val VAD_STALL_TIMEOUT_MS = 5_000L // Warn if healthy mic RMS but VAD never fires for this long
private const val VAD_STALL_HEALTHY_RMS = 0.008f // RMS floor that counts as "the mic hears something"

/**
 * Phase 2 — TURN-TAKING TIMING (why each value is what it is).
 *
 * - Client VAD hangover (SpectralVad, 300 ms): marks the *local* end of user
 *   speech. Only drives UI/telemetry hooks (onUserSpeechEnd) and the
 *   audioStreamEnd flush below — it never cuts audio.
 * - Server end-of-speech (GeminiLiveSession setup realtimeInputConfig,
 *   silenceDurationMs = 700 ms): when the *model* decides the user finished
 *   and starts responding. Deliberately longer than the client hangover so a
 *   natural mid-sentence pause (300–700 ms) does not trigger a premature
 *   reply, while the client still marks the boundary early for telemetry.
 * - onSilenceDetected → session.flushAudioTurn() sends realtimeInput
 *   audioStreamEnd: valid only in automatic-VAD mode; tells the server the
 *   mic stream paused so it flushes cached audio. The stream reopens on the
 *   next audio chunk — the mic itself is never turned off mid-conversation.
 * - Barge-in confirmation (SuppressionEchoCanceller, 2 frames ≈ 64 ms):
 *   kept short on purpose — interruption latency is the most noticeable
 *   part of "natural" conversation. The 2-frame gate (not 1) rejects
 *   single-frame VAD flicker from noise.
 */

/**
 * Phase 1 — Full-duplex-ready audio orchestrator.
 *
 * Owns the conversation audio state machine; the physical I/O lives in
 * swappable pipeline stages (see `platform/audio/`):
 *
 * - [AudioCapture] (default [JavaxSoundCapture]) — mic frames in,
 * - [AudioRenderer] (default [JavaxSoundRenderer]) — assistant audio out,
 * - [VoiceActivityDetector] (default [SpectralVad]) — speech/silence + hangover,
 * - [EchoCanceller] (default [SuppressionEchoCanceller]) — echo handling.
 *
 * Default wiring preserves the long-standing behavior exactly: the mic is
 * suppressed while the assistant speaks (half-duplex) with VAD-confirmed
 * barge-in. When a full-duplex-capable canceller is active
 * (WebRTC AEC3 — see `native/BUILD_WINDOWS.md`), the mic stays open during
 * playback and the canceller consumes the render reference instead.
 *
 * All stages are constructor-injectable for unit tests.
 */
class WindowsAudioEngine(
    private val onSpeakingStateChanged: ((Boolean) -> Unit)? = null,
    private val onAudioError: ((String) -> Unit)? = null,
    /**
     * Phase-2 turn-taking hooks, fired on VAD speech transitions.
     *
     * [onUserSpeechStart] receives true when the onset was a *barge-in*
     * (user speech confirmed while the assistant was playing — local
     * playback is already stopped at that point) and false for an ordinary
     * turn start. Callers must use the flag rather than polling
     * [isSpeaking], which is already false by the time a barge-in hook
     * fires (stopPlayback clears the echo-cooldown timestamp).
     */
    private val onUserSpeechStart: ((bargeIn: Boolean) -> Unit)? = null,
    private val onUserSpeechEnd: (() -> Unit)? = null,
    /**
     * Fired once when the mic level is healthy but the VAD has not fired for
     * [VAD_STALL_TIMEOUT_MS] — the VAD gate looks stuck. Callers surface this
     * on the status pill so the user can check sensitivity/device.
     */
    private val onVadStallWarning: (() -> Unit)? = null,
    /**
     * Mixer name chosen in Settings ("" = system default). Read once at
     * engine construction; changing it takes effect on the next app start.
     */
    private val selectedMixerName: String? = null,
    captureFactory: ((AudioLatencyTracker) -> AudioCapture)? = null,
    rendererFactory: ((AudioLatencyTracker) -> AudioRenderer)? = null,
    echoCancellerFactory: ((isPlaying: () -> Boolean) -> EchoCanceller)? = null,
    val latencyTracker: AudioLatencyTracker = AudioLatencyTracker(),
    private val vad: VoiceActivityDetector = SpectralVad(),
) {

    private val renderer: AudioRenderer =
        (rendererFactory ?: { tracker -> JavaxSoundRenderer(onAudioError, tracker) })(latencyTracker)
    private val capture: AudioCapture =
        (captureFactory ?: { tracker ->
            JavaxSoundCapture(onError = onAudioError, latencyTracker = tracker, mixerName = selectedMixerName)
        })(latencyTracker)
    private val echoCanceller: EchoCanceller =
        (echoCancellerFactory ?: { isPlaying -> SuppressionEchoCanceller(isPlaying) })({ isSpeaking() })

    private val capturedChunks = AtomicLong(0)
    private val queuedOutputChunks = AtomicLong(0)

    @Volatile private var isSpeaking = false
    @Volatile private var lastPlaybackTime = 0L
    @Volatile private var isCaptureActive = false
    fun isCaptureActive(): Boolean = isCaptureActive

    /** Last uplink-audio timestamp; used to approximate turn-around (TTFA). */
    @Volatile private var pendingTurnUplinkNanos = 0L

    /** Recent render audio (@24kHz) kept as AEC reference for full-duplex mode. */
    private val renderRefBuffer = ConcurrentLinkedDeque<ByteArray>()
    private var renderRefBytes = 0

    @Volatile var smoothedAgcGain = 1.0f
        internal set

    init {
        // Burst transitions drive speaking state and anchor the post-playback
        // echo-cooldown at the *actual* end of audio (not at enqueue time).
        renderer.setPlayingStateListener { playing -> onBurstStateChanged(playing) }
        DesktopLogger.info("Audio engine: echoCanceller=${echoCanceller.kind} fullDuplex=${echoCanceller.isFullDuplexCapable}")
    }

    private fun onBurstStateChanged(playing: Boolean) {
        if (!playing) {
            // Natural end of playback (or stopPlayback): timestamp the actual
            // completion so the reverb guard covers real room decay.
            // engine.stopPlayback() clears this right after for intentional
            // stops (barge-in), opening the mic immediately.
            lastPlaybackTime = System.currentTimeMillis()
        }
        setSpeakingState(playing)
    }

    private fun setSpeakingState(speaking: Boolean) {
        if (isSpeaking != speaking) {
            isSpeaking = speaking
            onSpeakingStateChanged?.invoke(speaking)
        }
    }

    internal fun applySoftwareAgcAndLimiter(
        buffer: ByteArray,
        count: Int,
        rawRms: Float,
        gainTracker: (Float) -> Unit = {},
    ): ByteArray {
        val targetRms = 0.065f
        // AGC dead-zone fix: the old 0.008 lower bound left very quiet (but
        // real) speech at gain 1.0, so it could never rise into VAD range.
        // Extend the boost down to 0.0015; below that is the noise floor and
        // boosting it would only amplify hiss.
        val desiredGain = if (rawRms in 0.0015f..0.045f) {
            (targetRms / rawRms).coerceIn(1.0f, 2.5f)
        } else {
            1.0f
        }
        gainTracker(desiredGain)

        val currentGain = smoothedAgcGain
        if (Math.abs(currentGain - 1.0f) < 0.05f) {
            return buffer.copyOf(count)
        }

        val output = ByteArray(count)
        for (i in 0 until count step 2) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt() shl 8
            val sample = (low or high).toShort()
            val boosted = (sample.toFloat() * currentGain).toInt()
            // Soft peak limiter to avoid harsh digital clipping
            val clamped = boosted.coerceIn(-32000, 32000).toShort()
            output[i] = (clamped.toInt() and 0xFF).toByte()
            output[i + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()
        }
        return output
    }

    // Synchronized: two rapid start calls must never start capture twice
    // (which would send duplicated audio upstream and corrupt VAD state).
    @Synchronized
    fun startMicrophone(
        onPcmChunk: (base64Pcm: String) -> Unit,
        onVolumeLevel: (level: Float) -> Unit = {},
        onSilenceDetected: () -> Unit = {},
        onSpeechStarted: () -> Unit = {},
    ): Boolean {
        if (capture.isActive) return true
        vad.reset()
        echoCanceller.reset()
        isCaptureActive = true

        val preRollBuffer = ConcurrentLinkedDeque<ByteArray>()
        var isUserSpeaking = false
        var lastVadFireNanos = System.nanoTime()
        var vadStallWarned = false

        fun forwardChunk(chunk: ByteArray) {
            onPcmChunk(Base64.getEncoder().encodeToString(chunk))
            val c = capturedChunks.incrementAndGet()
            if (c == 1L || c % 50L == 0L) {
                DesktopLogger.info("Audio telemetry: capturedChunks=$c canceller=${echoCanceller.kind}")
            }
        }

        fun flushPreRoll() {
            while (true) {
                forwardChunk(preRollBuffer.poll() ?: break)
            }
        }

        fun handleSpeechOnset(bargeIn: Boolean) {
            if (!isUserSpeaking) {
                isUserSpeaking = true
                onSpeechStarted()
                onUserSpeechStart?.invoke(bargeIn)
                // Flush pre-roll so the initial syllable is preserved.
                flushPreRoll()
            }
        }

        fun handleSpeechEnd() {
            if (isUserSpeaking) {
                isUserSpeaking = false
                DesktopLogger.info("Audio telemetry: Turn silence detected (VAD hangover elapsed)")
                onSilenceDetected()
                onUserSpeechEnd?.invoke()
            }
        }

        val started = capture.start { rawFrame ->
            val rawRms = calculateRms(rawFrame, rawFrame.size)
            onVolumeLevel(rawRms)

            // Software AGC & Soft Limiter (before VAD/canceller).
            val chunk = applySoftwareAgcAndLimiter(rawFrame, rawFrame.size, rawRms) { targetGain ->
                smoothedAgcGain = smoothedAgcGain * 0.85f + targetGain * 0.15f
            }

            val vadStart = System.nanoTime()
            val speech = vad.isSpeech(chunk)
            latencyTracker.record(AudioStage.VAD, System.nanoTime() - vadStart)

            // VAD-gate watchdog: healthy mic level but no VAD fire for ~5s
            // usually means the gate is stuck (or the mic hears only noise).
            // Warn once per stall. Skipped while the assistant is speaking —
            // echo suppression legitimately keeps the VAD quiet then.
            val nowNanos = System.nanoTime()
            if (speech) {
                lastVadFireNanos = nowNanos
                vadStallWarned = false
            } else if (!vadStallWarned && !isSpeaking() && rawRms >= VAD_STALL_HEALTHY_RMS &&
                nowNanos - lastVadFireNanos > VAD_STALL_TIMEOUT_MS * 1_000_000L
            ) {
                vadStallWarned = true
                DesktopLogger.warn("VAD gate stall: healthy mic RMS but no speech detected for ${VAD_STALL_TIMEOUT_MS}ms")
                onVadStallWarning?.invoke()
            }

            val renderRef = if (echoCanceller.isFullDuplexCapable) takeRenderReference16k() else null
            val aecStart = System.nanoTime()
            val decision = echoCanceller.processCapture(chunk, renderRef, speech)
            latencyTracker.record(AudioStage.AEC, System.nanoTime() - aecStart)

            when (decision) {
                is EchoDecision.BargeIn -> {
                    // Confirmed user interruption: stop assistant playback immediately,
                    // then forward the held onset frame first so the first syllable
                    // is not clipped.
                    DesktopLogger.info("Audio telemetry: User barge-in detected, stopping playback")
                    stopPlayback()
                    handleSpeechOnset(bargeIn = true)
                    decision.firstFrame?.let { forwardChunk(it) }
                    forwardChunk(decision.frame)
                }
                is EchoDecision.Forward -> {
                    if (speech) {
                        handleSpeechOnset(bargeIn = false)
                        forwardChunk(decision.frame)
                    } else {
                        handleSpeechEnd()
                        // Ambient room noise: do NOT send upstream; keep rolling pre-roll.
                        preRollBuffer.add(decision.frame)
                        while (preRollBuffer.size > PRE_ROLL_CHUNKS) {
                            preRollBuffer.poll()
                        }
                    }
                }
                EchoDecision.Suppress -> {
                    // Echo suppressed while the assistant speaks; frame dropped.
                }
            }
        }

        if (!started) {
            isCaptureActive = false
            reportAudioError("Microphone device မတွေ့ပါ။ Windows မှာ microphone permission/input device ကို စစ်ပါ။")
            return false
        }
        return true
    }

    internal fun calculateRms(buffer: ByteArray, count: Int): Float {
        var sum = 0.0
        val samples = count / 2
        for (i in 0 until count step 2) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt() shl 8
            val sample = (low or high).toShort()
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = if (samples > 0) Math.sqrt(sum / samples) else 0.0
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    @Synchronized
    fun stopMicrophone() {
        isCaptureActive = false
        capture.stop()
    }

    /**
     * Called by the session layer for every uplink audio chunk it sends.
     * Lets the engine approximate turn-around latency (TTFA) when the first
     * response chunk arrives.
     */
    fun noteUplinkAudioSent() {
        pendingTurnUplinkNanos = System.nanoTime()
    }

    fun playPcmBase64(base64Pcm: String) {
        runCatching {
            val data = Base64.getDecoder().decode(base64Pcm)
            if (data.isNotEmpty()) {
                enqueueRenderBytes(data)
                val outputCount = queuedOutputChunks.incrementAndGet()
                if (outputCount == 1L || outputCount % 20L == 0L) {
                    DesktopLogger.info("Audio telemetry: queuedOutputChunks=$outputCount bytes=${data.size}")
                }
            }
        }.onFailure {
            reportAudioError("အသံ response decode မအောင်မြင်ပါ: ${it.message ?: it::class.simpleName}")
        }
    }

    fun playRaw(bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            enqueueRenderBytes(bytes)
        }
    }

    private fun enqueueRenderBytes(data: ByteArray) {
        val aligned = if (data.size % 2 != 0) data.copyOf(data.size - 1) else data
        // NOTE: lastPlaybackTime is anchored by the renderer's natural-end
        // burst callback (onBurstStateChanged(false)), not here, so the echo
        // cooldown starts at actual playback completion.
        rememberRenderReference(aligned)
        renderer.enqueuePcm16(aligned)
        // TTFA: first response chunk after uplink activity approximates
        // network + model turn-around for this turn.
        val uplink = pendingTurnUplinkNanos
        if (uplink != 0L) {
            pendingTurnUplinkNanos = 0L
            latencyTracker.record(AudioStage.TTFA, System.nanoTime() - uplink)
        }
    }

    private fun rememberRenderReference(data: ByteArray) {
        if (!echoCanceller.isFullDuplexCapable) return
        synchronized(renderRefBuffer) {
            renderRefBuffer.addLast(data)
            renderRefBytes += data.size
            while (renderRefBytes > RENDER_REF_KEEP_BYTES && renderRefBuffer.isNotEmpty()) {
                renderRefBytes -= (renderRefBuffer.pollFirst()?.size ?: 0)
            }
        }
    }

    /**
     * Returns the most recent 32 ms of render audio resampled to 16 kHz mono,
     * length-matched to a capture frame — the AEC reference. Null when there
     * is not enough recent render audio.
     */
    private fun takeRenderReference16k(): ByteArray? {
        synchronized(renderRefBuffer) {
            if (renderRefBytes < RENDER_REF_FRAME_BYTES_24K) return null
            var need = RENDER_REF_FRAME_BYTES_24K
            val parts = ArrayDeque<ByteArray>()
            val iter = renderRefBuffer.descendingIterator()
            while (need > 0 && iter.hasNext()) {
                val bytes = iter.next()
                val take = minOf(need, bytes.size)
                parts.addFirst(bytes.copyOfRange(bytes.size - take, bytes.size))
                need -= take
            }
            var combined = ByteArray(0)
            for (part in parts) combined += part
            return AudioResampler.resample24kTo16kMono16(combined)
        }
    }

    private fun reportAudioError(message: String) {
        DesktopLogger.warn(message)
        onAudioError?.invoke(message)
    }

    fun stopPlayback() {
        renderer.stopPlayback()
        // Intentional stop (barge-in / user takeover): clear the echo-cooldown
        // timestamp too, otherwise isSpeaking() stays true for another ~400ms
        // and the capture loop keeps suppressing the user's already-started speech.
        // (Natural end-of-playback never calls this; the burst listener clears
        // the speaking flag directly so the reverb guard still applies there.)
        lastPlaybackTime = 0L
    }

    fun isSpeaking(): Boolean =
        renderer.isPlaying || (System.currentTimeMillis() - lastPlaybackTime < ECHO_COOLDOWN_MS)

    /**
     * Phase 2 — applies a new barge-in sensitivity level (low|normal|high)
     * immediately, without restarting capture. No-op unless the active VAD
     * is a [SpectralVad] (the default).
     */
    fun updateBargeInSensitivity(level: String) {
        val normalized = SpectralVad.normalizeLevel(level)
        (vad as? SpectralVad)?.setSensitivity(SpectralVad.sensitivityForLevel(normalized))
        DesktopLogger.info("Audio engine: barge-in sensitivity set to $normalized")
    }

    /** Human-readable diagnostics: canceller kind + per-stage p50/p95. */
    fun getAudioLatencyReport(): String =
        "Echo canceller: ${echoCanceller.kind} (fullDuplex=${echoCanceller.isFullDuplexCapable})\n" +
            latencyTracker.getReport()

    fun close() {
        stopMicrophone()
        stopPlayback()
        renderer.close()
        (echoCanceller as? WebRtcAec3EchoCanceller)?.close()
    }
}
