package com.example.voicebrainlive.desktop.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsAudioEngineTest {

    @Test
    fun testRmsCalculationOnSilenceAndSpeech() {
        val engine = WindowsAudioEngine()

        // 1. Completely silent buffer (zeros)
        val silence = ByteArray(1024) { 0 }
        val silentRms = engine.calculateRms(silence, silence.size)
        assertEquals(0.0f, silentRms, 0.0001f)

        // 2. Full scale max amplitude square wave
        val maxAmp = ByteArray(1024)
        for (i in 0 until 1024 step 2) {
            val s: Short = 32767
            maxAmp[i] = (s.toInt() and 0xFF).toByte()
            maxAmp[i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        val maxRms = engine.calculateRms(maxAmp, maxAmp.size)
        assertTrue("Max RMS should be close to 1.0, was $maxRms", maxRms > 0.99f)

        // 3. Moderate speech amplitude (~3000 amplitude)
        val speech = ByteArray(1024)
        for (i in 0 until 1024 step 2) {
            val s: Short = 3000
            speech[i] = (s.toInt() and 0xFF).toByte()
            speech[i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        val speechRms = engine.calculateRms(speech, speech.size)
        assertTrue("Speech RMS should be ~0.091, was $speechRms", speechRms in 0.08f..0.10f)

        engine.close()
    }

    @Test
    fun testSoftwareAgcAmplifiesQuietAudio() {
        val engine = WindowsAudioEngine()

        // Create quiet audio (~800 amplitude, RMS ~0.024)
        val quietAudio = ByteArray(512)
        val initialSample: Short = 800
        for (i in 0 until 512 step 2) {
            quietAudio[i] = (initialSample.toInt() and 0xFF).toByte()
            quietAudio[i + 1] = ((initialSample.toInt() shr 8) and 0xFF).toByte()
        }

        val rawRms = engine.calculateRms(quietAudio, quietAudio.size)
        assertTrue("Raw RMS should be in quiet speech range", rawRms in 0.02f..0.03f)

        // Simulate gain update
        engine.smoothedAgcGain = 2.0f
        val boosted = engine.applySoftwareAgcAndLimiter(quietAudio, quietAudio.size, rawRms)

        // Read back first sample from boosted
        val low = boosted[0].toInt() and 0xFF
        val high = boosted[1].toInt() shl 8
        val boostedSample = (low or high).toShort()

        assertTrue(
            "Boosted sample should be approximately double (~1600), was $boostedSample",
            boostedSample in 1500..1700
        )

        engine.close()
    }

    @Test
    fun testSoftwareLimiterPreventsClipping() {
        val engine = WindowsAudioEngine()

        // Create high-level audio near max (30,000)
        val loudAudio = ByteArray(512)
        val loudSample: Short = 30000
        for (i in 0 until 512 step 2) {
            loudAudio[i] = (loudSample.toInt() and 0xFF).toByte()
            loudAudio[i + 1] = ((loudSample.toInt() shr 8) and 0xFF).toByte()
        }

        // Even with high gain, limiter should softly clamp at 32000 without overflow
        engine.smoothedAgcGain = 2.0f
        val limited = engine.applySoftwareAgcAndLimiter(loudAudio, loudAudio.size, 0.9f)

        val low = limited[0].toInt() and 0xFF
        val high = limited[1].toInt() shl 8
        val clampedSample = (low or high).toShort()

        assertTrue(
            "Sample should be clamped at 32000 and not overflow to negative, was $clampedSample",
            clampedSample == 32000.toShort()
        )

        engine.close()
    }
}
