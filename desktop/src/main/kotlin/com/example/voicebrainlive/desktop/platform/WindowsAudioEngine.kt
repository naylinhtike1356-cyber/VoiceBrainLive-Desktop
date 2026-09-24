package com.example.voicebrainlive.desktop.platform

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

private const val INPUT_SAMPLE_RATE = 16_000f
private const val OUTPUT_SAMPLE_RATE = 24_000f
private const val CHANNELS = 1
private const val SAMPLE_SIZE_BITS = 16
private const val JITTER_PREBUFFER_BYTES = 14_400 // ~300ms of 24kHz 16-bit mono audio
private const val LINE_BUFFER_SIZE = 48_000 // ~1000ms hardware buffer for network jitter
private const val CAPTURE_CHUNK_BYTES = 2048 // 64ms at 16kHz, mono, 16-bit
private const val END_OF_TURN_SILENCE_MS = 800L
private const val SPEECH_RMS_THRESHOLD = 0.018f
private const val PLAYBACK_GAP_GRACE_MS = 1_500L
private const val ECHO_COOLDOWN_MS = 350L // Grace period after playback to absorb room reverberations

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

class WindowsAudioEngine(
    private val onSpeakingStateChanged: ((Boolean) -> Unit)? = null,
) {
    private var microphone: TargetDataLine? = null
    private var captureThread: Thread? = null
    private var speaker: SourceDataLine? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null

    @Volatile private var isSpeaking = false
    @Volatile private var lastPlaybackTime = 0L

    init {
        startPlaybackWorker()
    }

    @Synchronized
    private fun getOrCreateSpeaker(format: AudioFormat): SourceDataLine? {
        val existing = speaker
        if (existing != null && existing.isOpen && existing.isRunning) {
            return existing
        }
        return runCatching {
            (AudioSystem.getSourceDataLine(format)).also {
                it.open(format, LINE_BUFFER_SIZE)
                it.start()
                speaker = it
            }
        }.recoverCatching {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format, LINE_BUFFER_SIZE)
                it.start()
                speaker = it
            }
        }.getOrNull()
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
                    val preBufferDeadline = System.currentTimeMillis() + 200L
                    while (preBuffer.size() < JITTER_PREBUFFER_BYTES && System.currentTimeMillis() < preBufferDeadline) {
                        val next = audioQueue.poll(30, TimeUnit.MILLISECONDS)
                        if (next != null) {
                            preBuffer.write(next)
                        } else {
                            break
                        }
                    }

                    setSpeakingState(true)
                    val line = getOrCreateSpeaker(format)
                    if (line != null) {
                        val initialBytes = preBuffer.toByteArray()
                        line.write(initialBytes, 0, initialBytes.size)
                        lastPlaybackTime = System.currentTimeMillis()

                        // Stream continuously without pausing
                        while (!Thread.currentThread().isInterrupted) {
                            val chunk = audioQueue.poll(PLAYBACK_GAP_GRACE_MS, TimeUnit.MILLISECONDS)
                            if (chunk != null) {
                                line.write(chunk, 0, chunk.size)
                                lastPlaybackTime = System.currentTimeMillis()
                            } else {
                                // Stream finished. Drain hardware line buffer completely so no words are cut off!
                                runCatching { line.drain() }
                                lastPlaybackTime = System.currentTimeMillis()
                                break
                            }
                        }
                        setSpeakingState(false)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    // Ignore transient audio line error
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

    fun startMicrophone(
        onPcmChunk: (base64Pcm: String) -> Unit,
        onVolumeLevel: (level: Float) -> Unit = {},
        onSilenceDetected: () -> Unit = {},
        onSpeechStarted: () -> Unit = {},
    ) {
        if (captureThread?.isAlive == true) return
        val format = inputPcmFormat()
        val line = runCatching { AudioSystem.getTargetDataLine(format) }
            .recoverCatching {
                val info = DataLine.Info(TargetDataLine::class.java, format)
                AudioSystem.getLine(info) as TargetDataLine
            }.getOrNull() ?: return
        runCatching {
            line.open(format)
            line.start()
        }.onFailure { return }
        microphone = line

        captureThread = Thread {
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            var hasSpoken = false
            var lastSpeechTime = System.currentTimeMillis()
            var silenceTriggered = false
            var speechActive = false
            // Pre-roll queue to preserve the first ~192ms of speech before RMS threshold is crossed
            val preRollQueue = java.util.ArrayDeque<String>(3)

            try {
                while (!Thread.currentThread().isInterrupted && line.isOpen) {
                    val count = line.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        val chunk = buffer.copyOf(count)
                        val rms = calculateRms(chunk, count)
                        onVolumeLevel(rms)

                        val now = System.currentTimeMillis()
                        val currentlySpeaking = isSpeaking()

                        // If speaker is active or cooling down, suppress mic input to eliminate acoustic echo feedback
                        if (currentlySpeaking) {
                            speechActive = false
                            hasSpoken = false
                            preRollQueue.clear()
                            continue
                        }

                        val base64Chunk = Base64.getEncoder().encodeToString(chunk)
                        val isUserSpeaking = rms > SPEECH_RMS_THRESHOLD

                        if (isUserSpeaking) {
                            if (!speechActive) {
                                speechActive = true
                                onSpeechStarted()
                                // Flush pre-roll chunks so the initial phonemes/words are not clipped
                                while (preRollQueue.isNotEmpty()) {
                                    onPcmChunk(preRollQueue.removeFirst())
                                }
                            }
                            hasSpoken = true
                            lastSpeechTime = now
                            silenceTriggered = false
                            // Forward user speech immediately
                            onPcmChunk(base64Chunk)
                        } else {
                            if (speechActive && now - lastSpeechTime > 200L) {
                                speechActive = false
                            }
                            if (hasSpoken) {
                                // While in an active speech turn, forward pause chunks so VAD senses natural speech pauses
                                onPcmChunk(base64Chunk)
                                if (!silenceTriggered && (now - lastSpeechTime > END_OF_TURN_SILENCE_MS)) {
                                    silenceTriggered = true
                                    hasSpoken = false
                                    speechActive = false
                                    onSilenceDetected()
                                }
                            } else {
                                // When idle (user not speaking), only keep a small rolling pre-roll buffer
                                // DO NOT send continuous idle silence to Gemini Live to prevent <no speech detected> loops
                                if (preRollQueue.size >= 3) {
                                    preRollQueue.removeFirst()
                                }
                                preRollQueue.addLast(base64Chunk)
                            }
                        }
                    }
                }
            } finally {
                runCatching {
                    line.stop()
                    line.close()
                }
            }
        }.apply {
            name = "voicebrain-microphone"
            isDaemon = true
            start()
        }
    }

    private fun calculateRms(buffer: ByteArray, count: Int): Float {
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

    fun stopMicrophone() {
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
                audioQueue.offer(aligned)
            }
        }
    }

    fun playRaw(bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            setSpeakingState(true)
            lastPlaybackTime = System.currentTimeMillis()
            val aligned = if (bytes.size % 2 != 0) bytes.copyOf(bytes.size - 1) else bytes
            audioQueue.offer(aligned)
        }
    }

    fun stopPlayback() {
        audioQueue.clear()
        setSpeakingState(false)
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
