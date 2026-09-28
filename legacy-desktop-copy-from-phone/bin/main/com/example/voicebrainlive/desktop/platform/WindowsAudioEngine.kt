package com.example.voicebrainlive.desktop.platform

import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

private const val SAMPLE_RATE = 16_000f
private const val CHANNELS = 1
private const val SAMPLE_SIZE_BITS = 16

private fun pcmFormat() = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    SAMPLE_RATE,
    SAMPLE_SIZE_BITS,
    CHANNELS,
    2,
    SAMPLE_RATE,
    false, // little-endian
)

class WindowsAudioEngine {
    private var microphone: TargetDataLine? = null
    private var captureThread: Thread? = null
    private var speaker: SourceDataLine? = null

    fun startMicrophone(onPcmChunk: (base64Pcm: String) -> Unit) {
        if (captureThread?.isAlive == true) return
        val format = pcmFormat()
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format)
        line.start()
        microphone = line

        captureThread = Thread {
            val buffer = ByteArray(3200) // 100 ms at 16 kHz, mono, 16-bit
            try {
                while (!Thread.currentThread().isInterrupted && line.isOpen) {
                    val count = line.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        val chunk = buffer.copyOf(count)
                        onPcmChunk(Base64.getEncoder().encodeToString(chunk))
                    }
                }
            } finally {
                line.stop()
                line.close()
            }
        }.apply {
            name = "voicebrain-microphone"
            isDaemon = true
            start()
        }
    }

    fun stopMicrophone() {
        captureThread?.interrupt()
        captureThread = null
        microphone?.stop()
        microphone?.close()
        microphone = null
    }

    @Synchronized
    fun playPcmBase64(base64Pcm: String) {
        val data = Base64.getDecoder().decode(base64Pcm)
        val format = pcmFormat()
        val line = speaker ?: run {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format)
                it.start()
                speaker = it
            }
        }
        line.write(data, 0, data.size)
    }

    fun stopPlayback() {
        speaker?.drain()
        speaker?.stop()
        speaker?.close()
        speaker = null
    }

    fun close() {
        stopMicrophone()
        stopPlayback()
    }
}
