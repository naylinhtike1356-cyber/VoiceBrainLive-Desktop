package com.example.voicebrainlive.desktop.platform.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Pure-JVM voice activity detector (no native downloads, no ML model).
 *
 * Per 32 ms frame (512 samples @ 16 kHz mono 16-bit LE) it computes:
 * - log-energy (dB) against an *adaptive* noise floor,
 * - zero-crossing rate (rejects rumble/wind and pure DC),
 * - spectral flux from a 512-point FFT (rejects steady tones/noise; speech
 *   onsets and fricatives have high flux, vowels ride on the onset window).
 *
 * Decision: `onset = energyGate && zcrGate && fluxGate`; speech continues
 * while `energyGate && zcrGate` hold within [onsetWindowMs] of the last onset
 * (covers steady vowels), plus a [hangoverMs] tail so natural word pauses do
 * not chop a turn.
 *
 * Tuned on synthetic signals in SpectralVadTest; see `debugFeatures()` for
 * per-frame feature inspection while tuning.
 */
class SpectralVad(
    /** 0..1 — higher = more sensitive (lower energy margin). */
    private val sensitivity: Float = 0.5f,
    private val hangoverMs: Long = 300L,
    private val onsetWindowMs: Long = 400L,
    /** Injectable clock (ms) — defaults to wall time; tests advance it per frame. */
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceActivityDetector {

    companion object {
        const val FRAME_SAMPLES = 512 // 32 ms @ 16 kHz
        private const val MIN_ZCR = 0.015f
        private const val MAX_ZCR = 0.45f
        private const val NOISE_ADAPT_RATE = 0.02f
        private const val FLUX_ADAPT_RATE = 0.02f
    }

    private var prevMagnitude: FloatArray? = null
    private var noiseFloorDb = -50f
    private var fluxFloor = 0.05f
    private var lastOnsetTimeMs = Long.MIN_VALUE / 2
    private var lastRawSpeechTimeMs = Long.MIN_VALUE / 2
    private var speechActive = false

    private val energyMarginDb: Float get() = 14f - 8f * sensitivity.coerceIn(0f, 1f)

    override fun isSpeech(frame: ByteArray): Boolean {
        val now = clock()
        val samples = frameToFloat(frame, FRAME_SAMPLES)

        val logEnergyDb = 10f * log10(meanSquare(samples) + 1e-12f)
        val zcr = zeroCrossingRate(samples)
        val magnitude = magnitudeSpectrum(samples)
        val flux = spectralFlux(magnitude, prevMagnitude)
        prevMagnitude = magnitude

        val energyGate = logEnergyDb > noiseFloorDb + energyMarginDb
        val zcrGate = zcr in MIN_ZCR..MAX_ZCR
        val fluxGate = flux > fluxFloor * 2f + 0.015f

        val onset = energyGate && zcrGate && fluxGate
        if (onset) lastOnsetTimeMs = now
        val recentOnset = now - lastOnsetTimeMs < onsetWindowMs
        val rawSpeech = onset || (recentOnset && energyGate && zcrGate)

        // Adapt the noise/flux floors only on confidently-non-speech frames.
        if (!rawSpeech) {
            noiseFloorDb += NOISE_ADAPT_RATE * (logEnergyDb - noiseFloorDb)
            if (!onset) {
                fluxFloor += FLUX_ADAPT_RATE * (flux - fluxFloor)
                fluxFloor = fluxFloor.coerceIn(0.01f, 0.5f)
            }
        } else {
            lastRawSpeechTimeMs = now
        }

        speechActive = rawSpeech || (now - lastRawSpeechTimeMs < hangoverMs)
        return speechActive
    }

    override fun reset() {
        prevMagnitude = null
        noiseFloorDb = -50f
        fluxFloor = 0.05f
        lastOnsetTimeMs = Long.MIN_VALUE / 2
        lastRawSpeechTimeMs = Long.MIN_VALUE / 2
        speechActive = false
    }

    /** Test/tuning hook: per-frame feature values for the last analyzed frame. */
    internal fun debugFeatures(frame: ByteArray): String {
        val samples = frameToFloat(frame, FRAME_SAMPLES)
        val logEnergyDb = 10f * log10(meanSquare(samples) + 1e-12f)
        val zcr = zeroCrossingRate(samples)
        val magnitude = magnitudeSpectrum(samples)
        val flux = spectralFlux(magnitude, prevMagnitude)
        return "logE=${"%.1f".format(logEnergyDb)}dB floor=${"%.1f".format(noiseFloorDb)}dB " +
            "zcr=${"%.3f".format(zcr)} flux=${"%.3f".format(flux)} fluxFloor=${"%.3f".format(fluxFloor)}"
    }

    private fun frameToFloat(frame: ByteArray, wantSamples: Int): FloatArray {
        val haveSamples = frame.size / 2
        val out = FloatArray(wantSamples)
        val n = minOf(haveSamples, wantSamples)
        for (i in 0 until n) {
            val low = frame[i * 2].toInt() and 0xFF
            val high = frame[i * 2 + 1].toInt() shl 8
            out[i] = (low or high).toShort() / 32768f
        }
        return out
    }

    private fun meanSquare(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s * s
        return (sum / samples.size).toFloat()
    }

    private fun zeroCrossingRate(samples: FloatArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0f) != (samples[i - 1] >= 0f)) crossings++
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    private fun spectralFlux(magnitude: FloatArray, prev: FloatArray?): Float {
        if (prev == null || prev.size != magnitude.size) return 0f
        var positive = 0f
        var total = 0f
        for (i in magnitude.indices) {
            val m = magnitude[i]
            total += m
            val diff = m - prev[i]
            if (diff > 0f) positive += diff
        }
        return if (total > 1e-9f) positive / total else 0f
    }

    /** Iterative radix-2 FFT; returns magnitude bins 0..n/2. */
    private fun magnitudeSpectrum(samples: FloatArray): FloatArray {
        val n = samples.size
        val re = DoubleArray(n) { samples[it].toDouble() }
        val im = DoubleArray(n)

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wr = kotlin.math.cos(angle)
            val wi = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var curWr = 1.0
                var curWi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * curWr - im[i + k + len / 2] * curWi
                    val vi = re[i + k + len / 2] * curWi + im[i + k + len / 2] * curWr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = curWr * wr - curWi * wi
                    curWi = curWr * wi + curWi * wr
                    curWr = nwr
                }
                i += len
            }
            len = len shl 1
        }

        return FloatArray(n / 2 + 1) { k ->
            sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
        }
    }
}
