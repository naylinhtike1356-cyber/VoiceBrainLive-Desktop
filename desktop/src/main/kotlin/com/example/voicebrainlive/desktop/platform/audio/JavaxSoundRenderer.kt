package com.example.voicebrainlive.desktop.platform.audio

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * javax.sound-based playback (default [AudioRenderer]).
 *
 * Owns the output line and the playback worker: anti-stutter pre-buffering
 * (~60 ms), gap-tolerant streaming, and device hot-plug re-acquire.
 * Reports burst transitions to [playbackListener] so the orchestrator can
 * maintain speaking state, and records RENDER + JITTER_BUFFER telemetry.
 *
 * Frame contract: 24 kHz mono 16-bit LE (Gemini Live output rate).
 */
class JavaxSoundRenderer(
    private val onError: ((String) -> Unit)? = null,
    private val latencyTracker: AudioLatencyTracker? = null,
) : AudioRenderer {

    companion object {
        private const val OUTPUT_SAMPLE_RATE = 24_000f
        private const val JITTER_PREBUFFER_BYTES = 2_880 // ~60 ms @ 24 kHz 16-bit mono (S2S: first byte sooner)
        private const val LINE_BUFFER_SIZE = 28_800 // ~600 ms hardware buffer
        private const val PLAYBACK_GAP_GRACE_MS = 350L // tolerate small network jitter; fail fast on dead streams
    }

    private val audioQueue = LinkedBlockingQueue<ByteArray>(200)
    private var speaker: SourceDataLine? = null
    private var worker: Thread? = null
    private val dropped = AtomicLong(0)
    override val droppedChunkCount: Long get() = dropped.get()

    /**
     * Honest playback meter: RMS (0..1) of the most recently written chunk,
     * measured pre-gain so the speaking orb reflects real output energy.
     */
    private val _playbackLevel = MutableStateFlow(0f)
    override val playbackLevel: StateFlow<Float> = _playbackLevel.asStateFlow()

    @Volatile private var playing = false
    override val isPlaying: Boolean get() = playing || audioQueue.isNotEmpty()

    var playbackListener: PlaybackListener? = null

    private var playingStateListener: ((Boolean) -> Unit)? = null
    override fun setPlayingStateListener(listener: ((Boolean) -> Unit)?) {
        playingStateListener = listener
    }

    /** Set when the first chunk of a burst is enqueued; used for jitter telemetry. */
    @Volatile private var burstEnqueueNanos = 0L

    init {
        startWorker()
    }

    private fun format() = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        OUTPUT_SAMPLE_RATE,
        16,
        1,
        2,
        OUTPUT_SAMPLE_RATE,
        false,
    )

    @Synchronized
    private fun getOrCreateSpeaker(): SourceDataLine? {
        val existing = speaker
        if (existing != null && existing.isOpen && existing.isRunning) return existing
        speaker = runCatching {
            (AudioSystem.getSourceDataLine(format())).also {
                it.open(format(), LINE_BUFFER_SIZE)
                it.start()
            }
        }.recoverCatching {
            val info = DataLine.Info(SourceDataLine::class.java, format())
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format(), LINE_BUFFER_SIZE)
                it.start()
            }
        }.getOrNull()
        return speaker
    }

    private fun setPlaying(value: Boolean) {
        if (playing != value) {
            playing = value
            if (!value) _playbackLevel.value = 0f
            playbackListener?.onBurstStateChanged(value)
            playingStateListener?.invoke(value)
        }
    }

    override fun enqueuePcm16(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        val aligned = if (data.size % 2 != 0) data.copyOf(data.size - 1) else data
        if (!playing && audioQueue.isEmpty()) {
            burstEnqueueNanos = System.nanoTime()
        }
        setPlaying(true)
        return if (audioQueue.offer(aligned)) {
            true
        } else {
            val total = dropped.incrementAndGet()
            DesktopLogger.warn("Audio playback queue full; dropping chunk (${aligned.size} bytes, total dropped=$total)")
            false
        }
    }

    override fun stopPlayback() {
        audioQueue.clear()
        setPlaying(false)
        runCatching {
            speaker?.flush()
            speaker?.stop()
            speaker?.close()
        }
        speaker = null
    }

    private fun startWorker() {
        worker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val firstChunk = audioQueue.poll(150, TimeUnit.MILLISECONDS)
                    if (firstChunk == null) {
                        if (playing) setPlaying(false)
                        continue
                    }

                    // Pre-buffer for smooth playback without stuttering.
                    // S2S: 60 ms is enough to absorb jitter; the first audible
                    // byte must not wait the old 120 ms.
                    val preBufferSize = firstChunk.size
                    val chunks = ArrayDeque<ByteArray>()
                    chunks.add(firstChunk)
                    val deadline = System.currentTimeMillis() + 60L
                    var buffered = preBufferSize
                    while (buffered < JITTER_PREBUFFER_BYTES && System.currentTimeMillis() < deadline) {
                        val next = audioQueue.poll(40, TimeUnit.MILLISECONDS) ?: break
                        chunks.add(next)
                        buffered += next.size
                    }

                    var line = getOrCreateSpeaker()
                    if (line != null) {
                        val burstWaitNanos = System.nanoTime() - burstEnqueueNanos
                        if (burstEnqueueNanos != 0L) {
                            latencyTracker?.record(AudioStage.JITTER_BUFFER, burstWaitNanos)
                            burstEnqueueNanos = 0L
                        }
                        for (chunk in chunks) {
                            val current = line ?: break
                            line = writeChunk(current, chunk) ?: break
                        }

                        // Stream continuously without pausing.
                        while (!Thread.currentThread().isInterrupted) {
                            val chunk = audioQueue.poll(PLAYBACK_GAP_GRACE_MS, TimeUnit.MILLISECONDS)
                            if (chunk != null) {
                                val current = line
                                if (current != null) writeChunk(current, chunk)?.let { line = it }
                            } else {
                                // Stream finished: drain the hardware buffer so no
                                // words are cut off — but skip the (up to ~600 ms)
                                // blocking drain when the buffer is already empty.
                                val drainLine = line
                                if (drainLine != null && drainLine.available() < LINE_BUFFER_SIZE) {
                                    runCatching { drainLine.drain() }
                                }
                                break
                            }
                        }
                        setPlaying(false)
                    } else {
                        onError?.invoke("Speaker output line မဖွင့်နိုင်ပါ။ Windows Sound output device ကို စစ်ပါ။")
                        audioQueue.clear()
                        setPlaying(false)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    // Clear the queue and drop the speaking flag: leaving
                    // playing=true would keep the echo canceller suppressing
                    // the mic as if the assistant were still talking.
                    audioQueue.clear()
                    setPlaying(false)
                    onError?.invoke("Speaker playback error: ${e.message ?: e::class.simpleName}")
                }
            }
        }.apply {
            name = "voicebrain-renderer"
            isDaemon = true
            start()
        }
    }

    /**
     * Writes one chunk, re-acquiring the line once on failure.
     * Returns the (possibly replaced) line, or null when no line is available.
     */
    private fun writeChunk(line: SourceDataLine, chunk: ByteArray): SourceDataLine? {
        var target = line
        val t0 = System.nanoTime()
        // Playback meter: RMS of the chunk about to be written (pre-gain —
        // this renderer applies no gain, so this is the true output energy).
        _playbackLevel.value = rmsOf(chunk)
        try {
            target.write(chunk, 0, chunk.size)
        } catch (e: Exception) {
            DesktopLogger.warn("Speaker write failed, re-acquiring line: ${e.message}")
            runCatching { speaker?.close() }
            speaker = null
            val replacement = getOrCreateSpeaker()
            if (replacement != null) {
                target = replacement
                runCatching { target.write(chunk, 0, chunk.size) }
            } else {
                return null
            }
        }
        latencyTracker?.record(AudioStage.RENDER, System.nanoTime() - t0)
        return target
    }

    /** RMS of 16-bit LE mono PCM, normalized 0..1. */
    private fun rmsOf(chunk: ByteArray): Float {
        val samples = chunk.size / 2
        if (samples == 0) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        return (kotlin.math.sqrt(sum / samples) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    override fun close() {
        stopPlayback()
        worker?.interrupt()
        worker = null
    }
}
