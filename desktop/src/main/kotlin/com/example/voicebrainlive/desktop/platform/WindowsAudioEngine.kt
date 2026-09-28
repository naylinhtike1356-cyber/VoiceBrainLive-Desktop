package com.example.voicebrainlive.desktop.platform

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

private const val INPUT_SAMPLE_RATE = 16_000f
private const val OUTPUT_SAMPLE_RATE = 24_000f
private const val CHANNELS = 1
private const val SAMPLE_SIZE_BITS = 16
private const val JITTER_PREBUFFER_BYTES = 5_760 // ~120ms of 24kHz 16-bit mono audio (smooth anti-stutter buffer)
private const val LINE_BUFFER_SIZE = 28_800 // ~600ms hardware buffer
private const val CAPTURE_CHUNK_BYTES = 1024 // 32ms at 16kHz, mono, 16-bit
private const val BARGE_IN_RMS_THRESHOLD = 0.080f // Distinct human voice volume to intentionally interrupt assistant
private const val SPEECH_HANGOVER_MS = 750L // Keep streaming during natural pauses between words
private const val PRE_ROLL_CHUNKS = 8 // Keep last ~256ms in memory so initial syllable is preserved
private const val PLAYBACK_GAP_GRACE_MS = 900L // Prevent line underflow on network packet jitter
private const val ECHO_COOLDOWN_MS = 400L // Grace period for room acoustic reverb to dissipate

private fun inputPcmFormat() = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    INPUT_SAMPLE_RATE,
    SAMPLE_SIZE_BITS,
    CHANNELS,
    2,
    INPUT_SAMPLE_RATE,
    false, // little-endian
)

private fun outputPcmFormat() = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    OUTPUT_SAMPLE_RATE,
    SAMPLE_SIZE_BITS,
    CHANNELS,
    2,
    OUTPUT_SAMPLE_RATE,
    false,
)

/**
 * High-Fidelity Resilient Audio Engine:
 * - Anti-stuttering pre-buffer and jitter management for smooth assistant speech.
 * - Dynamic Audio Device Hot-Plugging: Auto-recovers input/output lines if headset/mic is unplugged,
 *   Bluetooth device switches, or PC wakes from sleep.
 * - Adaptive Dynamic Noise Floor: Automatically self-calibrates sensitivity for quiet vs noisy rooms.
 * - Software AGC & Soft Limiter: Dynamically boosts quiet speech while preventing digital clipping.
 * - Intelligent Silence Gate: Suppresses empty room noise from flooding Gemini Live,
 *   while preserving pre-roll speech bursts.
 * - Acoustic Echo Cancellation (AEC) Shield: Suppresses speaker echo from feeding into mic,
 *   preventing echo feedback loops, self-interruption, and overlapping speech.
 * - Reliable Barge-In: Detects deliberate user speech during assistant playback.
 */
class WindowsAudioEngine(
    private val onSpeakingStateChanged: ((Boolean) -> Unit)? = null,
    private val onAudioError: ((String) -> Unit)? = null,
) {
    private var microphone: TargetDataLine? = null
    private var captureThread: Thread? = null
    private var speaker: SourceDataLine? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(200)
    private var playbackThread: Thread? = null
    private val capturedChunks = AtomicLong(0)
    private val queuedOutputChunks = AtomicLong(0)
    private val droppedOutputChunks = AtomicLong(0)

    @Volatile private var isSpeaking = false
    @Volatile private var lastPlaybackTime = 0L
    @Volatile private var isCaptureActive = false
    fun isCaptureActive(): Boolean = isCaptureActive
    @Volatile var smoothedAgcGain = 1.0f
        internal set
    @Volatile var estimatedNoiseFloor = 0.008f
        internal set

    init {
        startPlaybackWorker()
    }

    private fun acquireTargetDataLine(format: AudioFormat): TargetDataLine? {
        return runCatching {
            (AudioSystem.getTargetDataLine(format)).also {
                it.open(format)
                it.start()
            }
        }.recoverCatching {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            (AudioSystem.getLine(info) as TargetDataLine).also {
                it.open(format)
                it.start()
            }
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateSpeaker(format: AudioFormat): SourceDataLine? {
        val existing = speaker
        if (existing != null && existing.isOpen && existing.isRunning) {
            return existing
        }
        speaker = runCatching {
            (AudioSystem.getSourceDataLine(format)).also {
                it.open(format, LINE_BUFFER_SIZE)
                it.start()
            }
        }.recoverCatching {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format, LINE_BUFFER_SIZE)
                it.start()
            }
        }.getOrNull()
        return speaker
    }

    private fun startPlaybackWorker() {
        playbackThread = Thread {
            val format = outputPcmFormat()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    // Wait for initial audio chunk
                    val firstChunk = audioQueue.poll(150, TimeUnit.MILLISECONDS)
                    if (firstChunk == null) {
                        if (isSpeaking && System.currentTimeMillis() - lastPlaybackTime > (ECHO_COOLDOWN_MS + 200L)) {
                            setSpeakingState(false)
                        }
                        continue
                    }

                    // Pre-buffer for smooth playback without stuttering
                    val preBuffer = ByteArrayOutputStream()
                    preBuffer.write(firstChunk)
                    val preBufferDeadline = System.currentTimeMillis() + 120L
                    while (preBuffer.size() < JITTER_PREBUFFER_BYTES && System.currentTimeMillis() < preBufferDeadline) {
                        val next = audioQueue.poll(40, TimeUnit.MILLISECONDS)
                        if (next != null) {
                            preBuffer.write(next)
                        } else {
                            break
                        }
                    }

                    setSpeakingState(true)
                    var line = getOrCreateSpeaker(format)
                    if (line != null) {
                        val initialBytes = preBuffer.toByteArray()
                        try {
                            line.write(initialBytes, 0, initialBytes.size)
                        } catch (e: Exception) {
                            DesktopLogger.warn("Speaker write failed on preBuffer, re-acquiring line: ${e.message}")
                            runCatching { speaker?.close() }
                            speaker = null
                            line = getOrCreateSpeaker(format)
                            runCatching { line?.write(initialBytes, 0, initialBytes.size) }
                        }
                        lastPlaybackTime = System.currentTimeMillis()

                        // Stream continuously without pausing
                        while (!Thread.currentThread().isInterrupted) {
                            val chunk = audioQueue.poll(PLAYBACK_GAP_GRACE_MS, TimeUnit.MILLISECONDS)
                            if (chunk != null) {
                                try {
                                    line?.write(chunk, 0, chunk.size)
                                } catch (e: Exception) {
                                    DesktopLogger.warn("Speaker write error (device switched/disconnected): ${e.message}. Re-acquiring output line...")
                                    runCatching { speaker?.close() }
                                    speaker = null
                                    line = getOrCreateSpeaker(format)
                                    runCatching { line?.write(chunk, 0, chunk.size) }
                                }
                                lastPlaybackTime = System.currentTimeMillis()
                            } else {
                                // Stream finished. Drain the hardware line buffer so no words
                                // are cut off — but skip the (up to ~600ms) blocking drain when
                                // the buffer is already empty, so freshly arriving audio chunks
                                // are never stuck behind it. available() reports free buffer
                                // space; free == full size means nothing left to drain.
                                val drainLine = line
                                if (drainLine != null && drainLine.available() < LINE_BUFFER_SIZE) {
                                    runCatching { drainLine.drain() }
                                }
                                lastPlaybackTime = System.currentTimeMillis()
                                break
                            }
                        }
                        setSpeakingState(false)
                    } else {
                        reportAudioError("Speaker output line မဖွင့်နိုင်ပါ။ Windows Sound output device ကို စစ်ပါ။")
                        audioQueue.clear()
                        setSpeakingState(false)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    reportAudioError("Speaker playback error: ${e.message ?: e::class.simpleName}")
                }
            }
        }.apply {
            name = "voicebrain-speaker"
            isDaemon = true
            start()
        }
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

    // Synchronized: two rapid start calls must never spawn two capture threads
    // (which would send duplicated audio to Gemini and corrupt VAD state).
    @Synchronized
    fun startMicrophone(
        onPcmChunk: (base64Pcm: String) -> Unit,
        onVolumeLevel: (level: Float) -> Unit = {},
        onSilenceDetected: () -> Unit = {},
        onSpeechStarted: () -> Unit = {},
    ): Boolean {
        if (captureThread?.isAlive == true) return true
        val format = inputPcmFormat()
        val line = acquireTargetDataLine(format) ?: run {
            reportAudioError("Microphone device မတွေ့ပါ။ Windows မှာ microphone permission/input device ကို စစ်ပါ။")
            return false
        }
        microphone = line
        isCaptureActive = true
        DesktopLogger.info("Audio telemetry: microphone started format=16kHz/mono/16bit chunkBytes=$CAPTURE_CHUNK_BYTES")

        captureThread = Thread {
            var activeLine: TargetDataLine? = line
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            val preRollBuffer = ConcurrentLinkedDeque<ByteArray>()
            var isUserSpeaking = false
            var lastSpeechTimestamp = 0L
            var consecutiveBargeInCount = 0
            // Holds the first loud chunk heard while the assistant is speaking.
            // The barge-in detector needs 2 consecutive loud chunks to confirm a
            // deliberate interruption; without this the first chunk (the actual
            // syllable onset) would be dropped and Gemini would hear a clipped word.
            var bargeInFirstChunk: ByteArray? = null

            try {
                while (!Thread.currentThread().isInterrupted && isCaptureActive) {
                    var currentLine = activeLine
                    if (currentLine == null || !currentLine.isOpen) {
                        if (!isCaptureActive) break
                        DesktopLogger.warn("Microphone line not open, attempting acquisition...")
                        currentLine = acquireTargetDataLine(format)
                        if (currentLine != null) {
                            activeLine = currentLine
                            microphone = currentLine
                            DesktopLogger.info("Audio telemetry: Microphone line successfully re-acquired")
                        } else {
                            Thread.sleep(1000)
                            continue
                        }
                    }

                    val count = try {
                        currentLine.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        DesktopLogger.warn("Microphone read exception: ${e.message}")
                        -1
                    }

                    if (count <= 0) {
                        if (!isCaptureActive) break
                        DesktopLogger.warn("Audio capture line disconnected or empty read ($count). Recovering audio line...")
                        runCatching { currentLine.stop(); currentLine.close() }
                        activeLine = null
                        microphone = null
                        onAudioError?.invoke("Microphone ပြတ်တောက်သွားပါသဖြင့် အလိုအလျောက် ပြန်လည်ရှာဖွေနေပါသည်...")
                        Thread.sleep(800)
                        val newLine = acquireTargetDataLine(format)
                        if (newLine != null) {
                            activeLine = newLine
                            microphone = newLine
                            DesktopLogger.info("Audio telemetry: Microphone line successfully recovered after disconnect")
                            onAudioError?.invoke("Microphone ပြန်လည်ချိတ်ဆက်မှု အောင်မြင်ပါသည်")
                        }
                        continue
                    }

                    val rawChunk = buffer.copyOf(count)
                    val rawRms = calculateRms(rawChunk, count)
                    onVolumeLevel(rawRms)

                    // Dynamic noise floor tracking
                    if (rawRms < estimatedNoiseFloor) {
                        estimatedNoiseFloor = estimatedNoiseFloor * 0.90f + rawRms * 0.10f
                    } else {
                        estimatedNoiseFloor = estimatedNoiseFloor * 0.998f + rawRms * 0.002f
                    }
                    val dynamicSpeechThreshold = (estimatedNoiseFloor + 0.012f).coerceIn(0.014f, 0.038f)

                    // Software AGC & Soft Limiter
                    val chunk = applySoftwareAgcAndLimiter(rawChunk, count, rawRms) { targetGain ->
                        smoothedAgcGain = smoothedAgcGain * 0.85f + targetGain * 0.15f
                    }

                    val currentlySpeaking = isSpeaking()
                    val now = System.currentTimeMillis()

                    if (currentlySpeaking) {
                        // Assistant is actively speaking out of speakers:
                        // Suppress mic forwarding to prevent acoustic echo loop and self-interruption!
                        if (rawRms > BARGE_IN_RMS_THRESHOLD) {
                            if (consecutiveBargeInCount == 0) {
                                // Possible syllable onset — hold it until the interruption is confirmed.
                                bargeInFirstChunk = chunk
                            }
                            consecutiveBargeInCount++
                            if (consecutiveBargeInCount >= 2) {
                                // True user barge-in! Stop assistant playback immediately.
                                DesktopLogger.info("Audio telemetry: User barge-in detected (rms=${String.format("%.3f", rawRms)}), stopping playback")
                                stopPlayback()
                                isUserSpeaking = true
                                lastSpeechTimestamp = now
                                onSpeechStarted()
                                // Flush the saved onset chunk first so the first
                                // syllable of the interruption is not clipped.
                                bargeInFirstChunk?.let { first ->
                                    bargeInFirstChunk = null
                                    onPcmChunk(Base64.getEncoder().encodeToString(first))
                                    capturedChunks.incrementAndGet()
                                }
                                val base64Chunk = Base64.getEncoder().encodeToString(chunk)
                                onPcmChunk(base64Chunk)
                                capturedChunks.incrementAndGet()
                            }
                        } else {
                            consecutiveBargeInCount = 0
                            bargeInFirstChunk = null
                        }
                    } else {
                        consecutiveBargeInCount = 0
                        bargeInFirstChunk = null

                        if (rawRms > dynamicSpeechThreshold) {
                            // User speech burst detected
                            if (!isUserSpeaking) {
                                isUserSpeaking = true
                                onSpeechStarted()
                                // Flush pre-roll chunks so initial syllable is preserved
                                while (!preRollBuffer.isEmpty()) {
                                    val preChunk = preRollBuffer.poll() ?: break
                                    onPcmChunk(Base64.getEncoder().encodeToString(preChunk))
                                    capturedChunks.incrementAndGet()
                                }
                            }
                            lastSpeechTimestamp = now
                            onPcmChunk(Base64.getEncoder().encodeToString(chunk))
                            val c = capturedChunks.incrementAndGet()
                            if (c == 1L || c % 50L == 0L) {
                                DesktopLogger.info("Audio telemetry: capturedChunks=$c (speechActive=true rms=${String.format("%.3f", rawRms)} dynamicThresh=${String.format("%.3f", dynamicSpeechThreshold)} agcGain=${String.format("%.2f", smoothedAgcGain)})")
                            }
                        } else {
                            // Below threshold (silence/ambient)
                            if (isUserSpeaking) {
                                if (now - lastSpeechTimestamp < SPEECH_HANGOVER_MS) {
                                    // Natural pause between words: keep forwarding
                                    onPcmChunk(Base64.getEncoder().encodeToString(chunk))
                                    capturedChunks.incrementAndGet()
                                } else {
                                    // User has finished speaking this turn!
                                    isUserSpeaking = false
                                    DesktopLogger.info("Audio telemetry: Turn silence detected (hangover elapsed)")
                                    onSilenceDetected()
                                }
                            } else {
                                // Ambient room noise: DO NOT send to Gemini Live!
                                // Store in rolling pre-roll buffer
                                preRollBuffer.add(chunk)
                                while (preRollBuffer.size > PRE_ROLL_CHUNKS) {
                                    preRollBuffer.poll()
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching {
                    activeLine?.stop()
                    activeLine?.close()
                }
            }
        }.apply {
            name = "voicebrain-microphone"
            isDaemon = true
            start()
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
        captureThread?.interrupt()
        captureThread = null
        runCatching {
            microphone?.stop()
            microphone?.close()
        }
        microphone = null
    }

    fun playPcmBase64(base64Pcm: String) {
        runCatching {
            val data = Base64.getDecoder().decode(base64Pcm)
            if (data.isNotEmpty()) {
                setSpeakingState(true)
                lastPlaybackTime = System.currentTimeMillis()
                val aligned = if (data.size % 2 != 0) data.copyOf(data.size - 1) else data
                if (!audioQueue.offer(aligned)) {
                    val dropped = droppedOutputChunks.incrementAndGet()
                    DesktopLogger.warn("Audio playback queue full; dropping chunk (${aligned.size} bytes, total dropped=$dropped)")
                }
                val outputCount = queuedOutputChunks.incrementAndGet()
                if (outputCount == 1L || outputCount % 20L == 0L) {
                    DesktopLogger.info("Audio telemetry: queuedOutputChunks=$outputCount bytes=${aligned.size}")
                }
            }
        }.onFailure {
            reportAudioError("အသံ response decode မအောင်မြင်ပါ: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun reportAudioError(message: String) {
        DesktopLogger.warn(message)
        onAudioError?.invoke(message)
    }

    fun playRaw(bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            setSpeakingState(true)
            lastPlaybackTime = System.currentTimeMillis()
            val aligned = if (bytes.size % 2 != 0) bytes.copyOf(bytes.size - 1) else bytes
            if (!audioQueue.offer(aligned)) {
                val dropped = droppedOutputChunks.incrementAndGet()
                DesktopLogger.warn("Audio playback queue full; dropping chunk (${aligned.size} bytes, total dropped=$dropped)")
            }
        }
    }

    fun stopPlayback() {
        audioQueue.clear()
        setSpeakingState(false)
        // Intentional stop (barge-in / user takeover): clear the echo-cooldown
        // timestamp too, otherwise isSpeaking() stays true for another ~400ms
        // and the capture loop keeps applying the high barge-in threshold to the
        // user's already-started speech, suppressing its beginning.
        // (Natural end-of-playback never calls this; it uses setSpeakingState(false)
        // directly so the reverb guard still applies there.)
        lastPlaybackTime = 0L
        runCatching {
            speaker?.flush()
            speaker?.stop()
            speaker?.close()
        }
        speaker = null
    }

    fun isSpeaking(): Boolean = isSpeaking || !audioQueue.isEmpty() || (System.currentTimeMillis() - lastPlaybackTime < ECHO_COOLDOWN_MS)

    fun close() {
        stopMicrophone()
        stopPlayback()
        playbackThread?.interrupt()
        playbackThread = null
    }
}
