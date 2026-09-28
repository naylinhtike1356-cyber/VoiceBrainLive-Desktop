package com.example.voicebrainlive.desktop.platform.audio

/**
 * Minimal sample-rate converters for the audio pipeline.
 *
 * Only the conversions the pipeline actually needs are provided, using
 * linear interpolation — good enough for an AEC *reference* signal
 * (the canceller is robust to small resampling artifacts).
 */
object AudioResampler {

    /**
     * 24 kHz mono 16-bit LE → 16 kHz mono 16-bit LE.
     * Used to build the AEC render reference from Gemini's 24 kHz output.
     */
    fun resample24kTo16kMono16(input: ByteArray): ByteArray {
        val inSamples = input.size / 2
        if (inSamples == 0) return ByteArray(0)
        val outSamples = (inSamples * 2) / 3
        if (outSamples == 0) return ByteArray(0)
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            val srcPos = i * 1.5
            val i0 = srcPos.toInt().coerceAtMost(inSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
            val frac = (srcPos - i0).toFloat()
            val s0 = getSample(input, i0).toFloat()
            val s1 = getSample(input, i1).toFloat()
            val s = (s0 * (1f - frac) + s1 * frac).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    internal fun getSample(data: ByteArray, index: Int): Short {
        val low = data[index * 2].toInt() and 0xFF
        val high = data[index * 2 + 1].toInt() shl 8
        return (low or high).toShort()
    }
}
