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
     * Currently informational (logged by DesktopRuntime); the server-side
     * interruption protocol lands in Phase 2.
     */
    private val onUserSpeechStart: (() -> Unit)? = null,
    private val onUserSpeechEnd: (() -> Unit)? = null,
    captureFactory: ((AudioLatencyTracker) -> AudioCapture)? = null,
    rendererFactory: ((AudioLatencyTracker) -> AudioRenderer)? = null,
    echoCancellerFactory: ((isPlaying: () -> Boolean) -> EchoCanceller)? = null,
    val latencyTracker: AudioLatencyTracker = AudioLatencyTracker(),
    private val vad: VoiceActivityDetector = SpectralVad(),
) {

    private val renderer: AudioRenderer =
        (rendererFactory ?: { tracker -> JavaxSoundRenderer(onAudioError, tracker) })(latencyTracker)
    private val capture: AudioCapture =
        (captureFactory ?: { tracker -> JavaxSoundCapture(onError = onAudioError, latencyTracker = tracker) })(latencyTracker)
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
        val desiredGain = if (rawRms in 0.008f..0.045f) {
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

        fun handleSpeechOnset() {
            if (!isUserSpeaking) {
                isUserSpeaking = true
                onSpeechStarted()
                onUserSpeechStart?.invoke()
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
                    handleSpeechOnset()
                    decision.firstFrame?.let { forwardChunk(it) }
                    forwardChunk(decision.frame)
                }
                is EchoDecision.Forward -> {
                    if (speech) {
                        handleSpeechOnset()
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
