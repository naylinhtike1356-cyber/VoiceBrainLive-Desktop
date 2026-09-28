package com.example.voicebrainlive.desktop.platform.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic-signal tests for [SpectralVad].
 *
 * A virtual clock (32 ms per frame) drives the VAD so hangover and onset
 * windows are deterministic.
 */
class SpectralVadTest {

    private val sampleRate = 16000
    private val frameSamples = 512
    private val rnd = Random(1234)

    private fun pcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun silenceFrame() = ByteArray(frameSamples * 2)

    private fun noiseFrame(amplitude: Double): ByteArray =
        pcm16(FloatArray(frameSamples) { ((rnd.nextFloat() * 2f - 1f) * amplitude / 32768).toFloat() })

    private fun sineFrame(freqHz: Double, amplitude: Double, frameIndex: Int): ByteArray =
        pcm16(FloatArray(frameSamples) { j ->
            val t = (frameIndex * frameSamples + j).toDouble() / sampleRate
            (sin(2 * PI * freqHz * t) * amplitude / 32768).toFloat()
        })

    /** Amplitude-modulated harmonic burst — crude synthetic "speech". */
    private fun burstFrame(frameIndex: Int, amplitude: Double = 4000.0): ByteArray =
        pcm16(FloatArray(frameSamples) { j ->
            val t = (frameIndex * frameSamples + j).toDouble() / sampleRate
            val am = 0.5 + 0.5 * sin(2 * PI * 5 * t)
            val s = (sin(2 * PI * 150 * t) + 0.6 * sin(2 * PI * 300 * t) +
                0.4 * sin(2 * PI * 450 * t) + 0.25 * sin(2 * PI * 600 * t)) / 2.25
            (s * am * amplitude / 32768).toFloat()
        })

    /** Feeds frames on a virtual 32 ms clock, returns per-frame decisions. */
    private fun feed(vad: SpectralVad, frames: List<ByteArray>, clock: () -> Long, advance: (Long) -> Unit): List<Boolean> =
        frames.map { frame ->
            advance(32L)
            vad.isSpeech(frame)
        }

    @Test
    fun silenceIsNotSpeech() {
        var t = 0L
        val vad = SpectralVad(clock = { t })
        val decisions = feed(vad, List(60) { silenceFrame() }, { t }, { d -> t += d })
        assertTrue("silence must never be speech", decisions.none { it })
    }

    @Test
    fun steadyToneIsNotSpeech() {
        var t = 0L
        val vad = SpectralVad(clock = { t })
        val decisions = feed(vad, List(60) { i -> sineFrame(440.0, 3000.0, i) }, { t }, { d -> t += d })
        assertTrue("steady tone must not be speech, got ${decisions.count { it }}", decisions.none { it })
    }

    @Test
    fun steadyNoiseAdaptsToNonSpeech() {
        var t = 0L
        val vad = SpectralVad(clock = { t })
        val decisions = feed(vad, List(150) { noiseFrame(200.0) }, { t }, { d -> t += d })
        assertTrue(
            "adapted noise floor must settle to non-speech, tail=${decisions.takeLast(50).count { it }}",
            decisions.takeLast(50).none { it },
        )
    }

    @Test
    fun speechLikeBurstIsSpeech() {
        var t = 0L
        val vad = SpectralVad(clock = { t })
        val decisions = feed(vad, List(40) { i -> burstFrame(i) }, { t }, { d -> t += d })
        assertTrue(
            "speech-like burst must be detected, got ${decisions.count { it }}/40",
            decisions.count { it } >= 35,
        )
    }

    @Test
    fun speechOverAdaptedNoiseFloorIsDetected() {
        var t = 0L
        val vad = SpectralVad(clock = { t })
        feed(vad, List(150) { noiseFrame(400.0) }, { t }, { d -> t += d })
        val decisions = feed(vad, List(40) { i -> burstFrame(i, 4000.0) }, { t }, { d -> t += d })
        assertTrue(
            "burst over noise must be detected, got ${decisions.count { it }}/40",
            decisions.count { it } >= 35,
        )
    }

    @Test
    fun hangoverBridgesShortPauses() {
        var t = 0L
        val vad = SpectralVad(clock = { t }, hangoverMs = 300L)
        feed(vad, List(20) { i -> burstFrame(i) }, { t }, { d -> t += d })
        t += 100 // short pause inside hangover
        assertTrue("speech must persist 100ms after burst", vad.isSpeech(silenceFrame()))
        t += 500 // well past hangover
        assertFalse("speech must end 600ms after burst", vad.isSpeech(silenceFrame()))
    }

    @Test
    fun resetClearsAdaptiveState() {
        var t = 0L
        val vad = SpectralVad(clock = { t })
        feed(vad, List(20) { i -> burstFrame(i) }, { t }, { d -> t += d })
        vad.reset()
        val decisions = feed(vad, List(10) { silenceFrame() }, { t }, { d -> t += d })
        assertTrue("silence after reset must not be speech", decisions.none { it })
    }
}
