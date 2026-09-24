package com.example.voicebrainlive.desktop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** Short, unobtrusive UI tones generated locally so no external audio file is required. */
class SoundEffects {
    private var current: Job? = null

    fun playListeningStarted(scope: CoroutineScope) = play(scope, listOf(Tone(660, 70), Tone(880, 105)))

    fun playListeningStopped(scope: CoroutineScope) = play(scope, listOf(Tone(520, 90), Tone(390, 120)))

    fun playGreetingChime(scope: CoroutineScope) = play(scope, listOf(Tone(523, 110), Tone(659, 130), Tone(784, 180)))

    private fun play(scope: CoroutineScope, tones: List<Tone>) {
        current?.cancel()
        current = scope.launch(Dispatchers.IO) {
            runCatching {
                tones.forEach { tone -> playTone(tone.frequency, tone.durationMs) }
            }
        }
    }

    private fun playTone(frequency: Int, durationMs: Int) {
        val sampleRate = 22_050f
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val samples = (sampleRate * durationMs / 1000).toInt()
        val buffer = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val envelope = when {
                index < samples * 0.08f -> index / (samples * 0.08f)
                index > samples * 0.82f -> (samples - index) / (samples * 0.18f)
                else -> 1f
            }.coerceIn(0f, 1f)
            val wave = sin(2.0 * PI * frequency * index / sampleRate).toFloat()
            val value = (wave * envelope * 0.16f * Short.MAX_VALUE).toInt().toShort()
            buffer[index * 2] = (value.toInt() and 0xFF).toByte()
            buffer[index * 2 + 1] = (value.toInt() shr 8 and 0xFF).toByte()
        }
        AudioSystem.getSourceDataLine(format).use { line ->
            line.open(format)
            line.start()
            line.write(buffer, 0, buffer.size)
            line.drain()
        }
    }

    private data class Tone(val frequency: Int, val durationMs: Int)
}
