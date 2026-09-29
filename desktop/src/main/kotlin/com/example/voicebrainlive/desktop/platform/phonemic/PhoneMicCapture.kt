package com.example.voicebrainlive.desktop.platform.phonemic

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.example.voicebrainlive.desktop.platform.audio.AudioCapture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * [AudioCapture] implementation fed by the phone's microphone over the LAN.
 *
 * The phone streams 20 ms chunks (320 samples @16 kHz PCM16 LE, 640 bytes)
 * with a uint32 LE sequence number. Chunks pass through an adaptive jitter
 * buffer before reaching the assistant pipeline:
 *
 * - Target buffer: 80 ms, adapts within 60–120 ms based on observed jitter.
 * - Underrun: emit silence (never block the consumer thread).
 * - Overflow beyond 2x target: drop oldest (prevents latency ratchet).
 * - Clock drift: if buffered audio exceeds 150 ms, skip oldest to re-anchor
 *   (phone and PC audio clocks drift ~0.1–0.3%).
 *
 * Threading: [onChunk] is called from Ktor WS reader threads;
 * [start]'s consumer loop runs on its own thread.
 */
class PhoneMicCapture : AudioCapture {

    // ---- Jitter buffer state ----
    private data class Chunk(val seq: Long, val pcm: ByteArray)

    /** Ordered by sequence number; drained by the consumer thread. */
    private val jitterBuffer = ConcurrentHashMap<Long, Chunk>()
    private val expectedSeq = AtomicLong(0)
    private val firstSeqSeen = AtomicBoolean(false)

    /** Adaptive target buffer depth in milliseconds. */
    @Volatile private var targetBufferMs = 80L

    /** Smoothed jitter estimate (ms) for adaptation. */
    @Volatile private var jitterEstimateMs = 20.0

    @Volatile private var lastChunkArrivalNanos = 0L
    @Volatile private var lastPlayedSeq = -1L

    // ---- Capture lifecycle ----
    private val running = AtomicBoolean(false)
    private var consumerThread: Thread? = null

    @Volatile private var lastUnderrunLogMs = 0L
    @Volatile private var totalDropped = 0L
    @Volatile private var totalUnderruns = 0L

    override val isActive: Boolean get() = running.get()

    /**
     * Called by [PhoneMicServer] for each chunk arriving from the phone.
     * Safe to call from any thread.
     */
    fun onChunk(seq: Long, pcm16: ByteArray) {
        if (!running.get()) return
        if (pcm16.isEmpty()) return
        if (firstSeqSeen.compareAndSet(false, true)) {
            expectedSeq.set(seq)
        }
        // Drop ancient duplicates / reordered-beyond-window.
        val exp = expectedSeq.get()
        if (seq < exp - MAX_REORDER_WINDOW) {
            totalDropped++
            return
        }
        jitterBuffer[seq] = Chunk(seq, pcm16)
        // Bound the buffer: drop oldest beyond 2x target to stop latency ratchet.
        val maxChunks = ((targetBufferMs * 2) / CHUNK_MS).toInt().coerceAtLeast(4)
        while (jitterBuffer.size > maxChunks) {
            val oldest = jitterBuffer.keys.minOrNull() ?: break
            jitterBuffer.remove(oldest)
            totalDropped++
        }
        // Jitter estimation from inter-arrival times.
        val now = System.nanoTime()
        val last = lastChunkArrivalNanos
        if (last != 0L) {
            val gapMs = (now - last) / 1_000_000.0
            val deviation = kotlin.math.abs(gapMs - CHUNK_MS)
            jitterEstimateMs = jitterEstimateMs * 0.95 + deviation * 0.05
            // Adapt target: 60–120 ms based on observed jitter.
            targetBufferMs = (60 + (jitterEstimateMs * 2).toLong()).coerceIn(60, 120)
        }
        lastChunkArrivalNanos = now
    }

    override fun start(onFrame: (ByteArray) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return true
        firstSeqSeen.set(false)
        jitterBuffer.clear()
        totalDropped = 0
        totalUnderruns = 0
        consumerThread = Thread({
            runConsumer(onFrame)
        }, "phone-mic-consumer").apply {
            isDaemon = true
            start()
        }
        DesktopLogger.info("PhoneMicCapture started (adaptive jitter buffer)")
        return true
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        consumerThread?.interrupt()
        consumerThread = null
        jitterBuffer.clear()
        DesktopLogger.info(
            "PhoneMicCapture stopped (dropped=$totalDropped underruns=$totalUnderruns)"
        )
    }

    private fun runConsumer(onFrame: (ByteArray) -> Unit) {
        // Pre-roll: wait until the target buffer fills before emitting,
        // so the first frames don't underrun immediately.
        val preRollDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        while (running.get() && bufferedMs() < targetBufferMs && System.nanoTime() < preRollDeadline) {
            try { Thread.sleep(5) } catch (_: InterruptedException) { return }
        }
        var nextSeq = -1L
        while (running.get()) {
            val frameStart = System.nanoTime()
            val chunk = pollNext(nextSeq)
            if (chunk != null) {
                nextSeq = chunk.seq + 1
                lastPlayedSeq = chunk.seq
                onFrame(chunk.pcm)
            } else {
                // Underrun: emit silence, never block.
                totalUnderruns++
                onFrame(SILENCE_20MS)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUnderrunLogMs > 5000) {
                    lastUnderrunLogMs = nowMs
                    DesktopLogger.info(
                        "PhoneMicCapture underrun (total=$totalUnderruns buffered=${bufferedMs()}ms)"
                    )
                }
                // Resync: jump to the newest chunk to avoid playing stale audio.
                val newest = jitterBuffer.keys.maxOrNull()
                if (newest != null) nextSeq = newest
            }
            // Pace at 20 ms per chunk; account for processing time.
            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L
            val sleepMs = CHUNK_MS - elapsedMs
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { return }
            }
        }
    }

    /**
     * Returns the next chunk in sequence, or null on underrun.
     * Applies the clock-drift recovery: if buffered audio exceeds
     * [DRIFT_RECOVERY_MS], skips oldest to re-anchor.
     */
    private fun pollNext(nextSeq: Long): Chunk? {
        // Drift recovery first.
        if (bufferedMs() > DRIFT_RECOVERY_MS) {
            val oldest = jitterBuffer.keys.minOrNull()
            if (oldest != null) {
                jitterBuffer.remove(oldest)
                totalDropped++
                return pollNext(if (nextSeq < 0) oldest + 1 else nextSeq)
            }
        }
        if (nextSeq < 0) {
            // First poll: take the oldest available.
            val oldest = jitterBuffer.keys.minOrNull() ?: return null
            return jitterBuffer.remove(oldest)
        }
        // In-order fast path.
        jitterBuffer.remove(nextSeq)?.let {
            expectedSeq.set(nextSeq + 1)
            return it
        }
        // Small reorder window: accept up to N chunks ahead.
        for (s in nextSeq + 1..nextSeq + MAX_REORDER_WINDOW) {
            jitterBuffer.remove(s)?.let {
                expectedSeq.set(s + 1)
                return it
            }
        }
        return null
    }

    private fun bufferedMs(): Long = jitterBuffer.size * CHUNK_MS

    /** Stats for UI / diagnostics. */
    fun stats(): String =
        "buffered=${bufferedMs()}ms target=${targetBufferMs}ms " +
                "jitter=${"%.1f".format(jitterEstimateMs)}ms " +
                "dropped=$totalDropped underruns=$totalUnderruns"

    companion object {
        const val CHUNK_MS = 20L
        private const val MAX_REORDER_WINDOW = 8L
        private const val DRIFT_RECOVERY_MS = 150L
        private val SILENCE_20MS = ByteArray(640) // 320 samples x 2 bytes
    }
}
