package com.example.voicebrainlive.desktop.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLatencyTrackerTest {

    @Test
    fun percentilesAreComputedInMs() {
        val tracker = AudioLatencyTracker()
        // 1ms..100ms in nanos.
        for (i in 1..100) tracker.record(AudioStage.CAPTURE, i * 1_000_000L)
        assertEquals(100, tracker.count(AudioStage.CAPTURE))
        assertEquals(50.0, tracker.percentileMs(AudioStage.CAPTURE, 50.0)!!, 1.0)
        assertEquals(95.0, tracker.percentileMs(AudioStage.CAPTURE, 95.0)!!, 1.0)
    }

    @Test
    fun emptyStageHasNoPercentile() {
        val tracker = AudioLatencyTracker()
        assertNull(tracker.percentileMs(AudioStage.TTFA, 50.0))
        assertEquals(0, tracker.count(AudioStage.TTFA))
        assertTrue(tracker.getReport().contains("TTFA: no samples yet"))
    }

    @Test
    fun windowIsBounded() {
        val tracker = AudioLatencyTracker(maxSamplesPerStage = 10)
        repeat(25) { tracker.record(AudioStage.VAD, 1_000_000L) }
        assertEquals(10, tracker.count(AudioStage.VAD))
    }

    @Test
    fun negativeSamplesAreIgnored() {
        val tracker = AudioLatencyTracker()
        tracker.record(AudioStage.RENDER, -5L)
        assertEquals(0, tracker.count(AudioStage.RENDER))
    }

    @Test
    fun resetClearsAllStages() {
        val tracker = AudioLatencyTracker()
        tracker.record(AudioStage.AEC, 1_000_000L)
        tracker.reset()
        assertEquals(0, tracker.count(AudioStage.AEC))
    }

    @Test
    fun reportContainsTimingsAndCounts() {
        val tracker = AudioLatencyTracker()
        repeat(10) { tracker.record(AudioStage.JITTER_BUFFER, 2_000_000L) }
        val report = tracker.getReport()
        assertTrue(report.contains("JITTER_BUFFER"))
        assertTrue(report.contains("p50=2.00"))
        assertTrue(report.contains("n=10"))
    }
}
