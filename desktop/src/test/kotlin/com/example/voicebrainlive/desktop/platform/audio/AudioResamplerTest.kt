package com.example.voicebrainlive.desktop.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioResamplerTest {

    private fun sine24k(freqHz: Double, seconds: Double, amplitude: Short = 8000): ByteArray {
        val samples = (seconds * 24000).toInt()
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val s = (sin(2 * PI * freqHz * i / 24000) * amplitude).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun zeroCrossingRate(data: ByteArray): Double {
        var crossings = 0
        val n = data.size / 2
        var prev = AudioResampler.getSample(data, 0) >= 0
        for (i in 1 until n) {
            val cur = AudioResampler.getSample(data, i) >= 0
            if (cur != prev) crossings++
            prev = cur
        }
        return crossings.toDouble() / (n - 1)
    }

    @Test
    fun outputLengthIsTwoThirdsOfInput() {
        val input = ByteArray(3000)
        val output = AudioResampler.resample24kTo16kMono16(input)
        assertEquals(2000, output.size)
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(0, AudioResampler.resample24kTo16kMono16(ByteArray(0)).size)
    }

    @Test
    fun oddInputIsHandledGracefully() {
        val output = AudioResampler.resample24kTo16kMono16(ByteArray(7))
        assertEquals(4, output.size) // 3 samples in -> 2 samples out
    }

    @Test
    fun sineFrequencyIsPreserved() {
        val input = sine24k(freqHz = 440.0, seconds = 0.5)
        val output = AudioResampler.resample24kTo16kMono16(input)
        // 440 Hz at 16 kHz -> ZCR ~= 2*440/16000 = 0.055
        val zcr = zeroCrossingRate(output)
        assertTrue("resampled 440Hz sine ZCR should be ~0.055, was $zcr", zcr in 0.04..0.07)
    }

    @Test
    fun constantSignalStaysConstant() {
        val input = ByteArray(3000)
        for (i in 0 until 1500) {
            input[i * 2] = 0x34
            input[i * 2 + 1] = 0x12
        }
        val output = AudioResampler.resample24kTo16kMono16(input)
        for (i in 0 until output.size / 2) {
            assertEquals(0x1234.toShort(), AudioResampler.getSample(output, i))
        }
    }
}
