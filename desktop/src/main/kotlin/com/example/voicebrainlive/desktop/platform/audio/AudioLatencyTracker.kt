package com.example.voicebrainlive.desktop.platform.audio

/** Pipeline stages timed by [AudioLatencyTracker]. */
enum class AudioStage {
    /** Microphone line.read → frame handed to the pipeline. */
    CAPTURE,

    /** EchoCanceller.processCapture per-frame cost. */
    AEC,

    /** VoiceActivityDetector.isSpeech per-frame cost. */
    VAD,

    /** GeminiLiveSession.sendAudioChunk call cost (measured by the caller). */
    SESSION_SEND,

    /**
     * Turn-around latency: last uplink audio chunk → first response audio
     * chunk of the assistant's turn. Approximates network + model TTFA.
     */
    TTFA,

    /** First chunk enqueued → first bytes written to the speaker line. */
    JITTER_BUFFER,

    /** Speaker line.write per-chunk cost. */
    RENDER,
}

/**
 * Rolling per-stage latency telemetry.
 *
 * Keeps the last [maxSamplesPerStage] samples per stage and reports p50/p95
 * in milliseconds. Privacy: timings and counts only — never audio content
 * or transcripts.
 */
class AudioLatencyTracker(private val maxSamplesPerStage: Int = 300) {

    private val samples = mutableMapOf<AudioStage, ArrayDeque<Long>>()

    @Synchronized
    fun record(stage: AudioStage, nanos: Long) {
        if (nanos < 0) return
        val queue = samples.getOrPut(stage) { ArrayDeque() }
        queue.addLast(nanos)
        while (queue.size > maxSamplesPerStage) queue.removeFirst()
    }

    @Synchronized
    fun count(stage: AudioStage): Int = samples[stage]?.size ?: 0

    @Synchronized
    fun percentileMs(stage: AudioStage, percentile: Double): Double? {
        val queue = samples[stage] ?: return null
        if (queue.isEmpty()) return null
        val sorted = queue.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).toInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[index] / 1_000_000.0
    }

    @Synchronized
    fun reset() = samples.clear()

    @Synchronized
    fun getReport(): String {
        val sb = StringBuilder("Audio latency (ms; rolling last $maxSamplesPerStage samples per stage):")
        for (stage in AudioStage.values()) {
            val n = count(stage)
            if (n == 0) {
                sb.append("\n  ${stage.name}: no samples yet")
            } else {
                val p50 = "%.2f".format(percentileMs(stage, 50.0))
                val p95 = "%.2f".format(percentileMs(stage, 95.0))
                sb.append("\n  ${stage.name}: p50=$p50 p95=$p95 (n=$n)")
            }
        }
        return sb.toString()
    }
}
