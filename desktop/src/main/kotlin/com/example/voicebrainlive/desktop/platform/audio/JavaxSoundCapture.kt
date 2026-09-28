package com.example.voicebrainlive.desktop.platform.audio

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * javax.sound-based microphone capture (default [AudioCapture]).
 *
 * Owns the input line, the read loop, and hot-plug recovery (device
 * unplugged / Bluetooth switch / wake-from-sleep re-acquire). It pushes
 * *raw* frames upstream — AGC, VAD, and echo handling live in the
 * orchestrator / pipeline stages, not here.
 *
 * Frame contract: 16 kHz mono 16-bit LE, [chunkBytes] per callback
 * (default 1024 bytes = 32 ms).
 */
class JavaxSoundCapture(
    private val sampleRateHz: Float = 16000f,
    private val chunkBytes: Int = 1024,
    private val onError: ((String) -> Unit)? = null,
    private val latencyTracker: AudioLatencyTracker? = null,
) : AudioCapture {

    private var line: TargetDataLine? = null
    private var thread: Thread? = null

    @Volatile private var active = false
    override val isActive: Boolean get() = active

    private fun format() = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        sampleRateHz,
        16,
        1,
        2,
        sampleRateHz,
        false, // little-endian
    )

    private fun acquire(): TargetDataLine? {
        return runCatching {
            (AudioSystem.getTargetDataLine(format())).also {
                it.open(format())
                it.start()
            }
        }.recoverCatching {
            val info = DataLine.Info(TargetDataLine::class.java, format())
            (AudioSystem.getLine(info) as TargetDataLine).also {
                it.open(format())
                it.start()
            }
        }.getOrNull()
    }

    // Synchronized: two rapid start calls must never spawn two capture threads
    // (which would deliver duplicated audio upstream and corrupt VAD state).
    @Synchronized
    override fun start(onFrame: (ByteArray) -> Unit): Boolean {
        if (thread?.isAlive == true) return true
        val first = acquire() ?: run {
            onError?.invoke("Microphone device မတွေ့ပါ။ Windows မှာ microphone permission/input device ကို စစ်ပါ။")
            return false
        }
        line = first
        active = true
        DesktopLogger.info("Audio telemetry: microphone started format=${sampleRateHz.toInt()}Hz/mono/16bit chunkBytes=$chunkBytes")

        thread = Thread {
            var current: TargetDataLine? = first
            val buffer = ByteArray(chunkBytes)
            try {
                while (!Thread.currentThread().isInterrupted && active) {
                    var cl = current
                    if (cl == null || !cl.isOpen) {
                        if (!active) break
                        DesktopLogger.warn("Microphone line not open, attempting acquisition...")
                        cl = acquire()
                        if (cl != null) {
                            current = cl
                            line = cl
                            DesktopLogger.info("Audio telemetry: Microphone line successfully re-acquired")
                        } else {
                            Thread.sleep(1000)
                            continue
                        }
                    }

                    val t0 = System.nanoTime()
                    val count = try {
                        cl.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        DesktopLogger.warn("Microphone read exception: ${e.message}")
                        -1
                    }

                    if (count <= 0) {
                        if (!active) break
                        DesktopLogger.warn("Audio capture line disconnected or empty read ($count). Recovering audio line...")
                        runCatching { cl.stop(); cl.close() }
                        current = null
                        line = null
                        onError?.invoke("Microphone ပြတ်တောက်သွားပါသဖြင့် အလိုအလျောက် ပြန်လည်ရှာဖွေနေပါသည်...")
                        Thread.sleep(800)
                        val recovered = acquire()
                        if (recovered != null) {
                            current = recovered
                            line = recovered
                            DesktopLogger.info("Audio telemetry: Microphone line successfully recovered after disconnect")
                            onError?.invoke("Microphone ပြန်လည်ချိတ်ဆက်မှု အောင်မြင်ပါသည်")
                        }
                        continue
                    }

                    latencyTracker?.record(AudioStage.CAPTURE, System.nanoTime() - t0)
                    onFrame(buffer.copyOf(count))
                }
            } finally {
                runCatching {
                    current?.stop()
                    current?.close()
                }
            }
        }.apply {
            name = "voicebrain-capture"
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    override fun stop() {
        active = false
        thread?.interrupt()
        thread = null
        runCatching {
            line?.stop()
            line?.close()
        }
        line = null
    }
}
