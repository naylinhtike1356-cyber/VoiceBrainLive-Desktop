package com.example.voicebrainlive.desktop.platform.audio

import com.example.voicebrainlive.desktop.platform.WindowsAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private class FakeCapture : AudioCapture {
    private var listener: ((ByteArray) -> Unit)? = null
    override val isActive: Boolean get() = listener != null
    var startCount = 0
        private set

    override fun start(onFrame: (ByteArray) -> Unit): Boolean {
        if (listener != null) return true
        startCount++
        listener = onFrame
        return true
    }

    override fun stop() {
        listener = null
    }

    fun push(frame: ByteArray) {
        listener?.invoke(frame)
    }
}

private class FakeRenderer : AudioRenderer {
    val enqueued = mutableListOf<ByteArray>()
    var playingState = false
    var stopCount = 0
        private set

    override fun enqueuePcm16(data: ByteArray): Boolean {
        enqueued.add(data)
        return true
    }

    override fun stopPlayback() {
        stopCount++
        playingState = false
        enqueued.clear()
    }

    override val isPlaying: Boolean get() = playingState
    override val droppedChunkCount: Long = 0L
    override fun close() {}
}

/**
 * Verifies the Phase 1 pipeline wiring: suppression fallback behavior,
 * VAD-driven barge-in, pre-roll preservation, and the echo-canceller
 * factory fallback — all with fake I/O (no audio devices needed).
 */
class AudioPipelineTest {

    private val rnd = Random(99)
    private val frameSamples = 512

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

    private fun burstFrame(frameIndex: Int): ByteArray =
        pcm16(FloatArray(frameSamples) { j ->
            val t = (frameIndex * frameSamples + j).toDouble() / 16000
            val am = 0.5 + 0.5 * sin(2 * PI * 5 * t)
            val s = (sin(2 * PI * 150 * t) + 0.6 * sin(2 * PI * 300 * t)) / 1.6
            (s * am * 4000 / 32768).toFloat()
        })

    private fun noiseFrame(): ByteArray =
        pcm16(FloatArray(frameSamples) { ((rnd.nextFloat() * 2f - 1f) * 300 / 32768).toFloat() })

    //region EchoDecision unit behavior

    @Test
    fun suppressionForwardsWhenIdle() {
        val canceller = SuppressionEchoCanceller(isPlaying = { false })
        val frame = silenceFrame()
        val decision = canceller.processCapture(frame, null, speechDetected = false)
        assertTrue(decision is EchoDecision.Forward)
        assertFalse(canceller.isFullDuplexCapable)
    }

    @Test
    fun suppressionSuppressesDuringPlaybackUntilBargeIn() {
        val canceller = SuppressionEchoCanceller(isPlaying = { true })
        val frame = burstFrame(0)
        // First speech frame: held for confirmation.
        assertTrue(canceller.processCapture(frame, null, speechDetected = true) is EchoDecision.Suppress)
        // Second consecutive speech frame: confirmed barge-in.
        val second = canceller.processCapture(frame, null, speechDetected = true)
        assertTrue(second is EchoDecision.BargeIn)
        // Non-speech resets the confirmation counter.
        assertTrue(canceller.processCapture(frame, null, speechDetected = false) is EchoDecision.Suppress)
        assertTrue(canceller.processCapture(frame, null, speechDetected = true) is EchoDecision.Suppress)
    }

    @Test
    fun factoryFallsBackToSuppressionWithoutNativeLib() {
        var fallbackMessage: String? = null
        val canceller = EchoCancellerFactory.create(
            EchoCancellerFactory.KIND_WEBRTC_AEC3,
            isPlaying = { false },
            onFallback = { fallbackMessage = it },
        )
        // webrtc_aec3.dll is not on java.library.path in this sandbox.
        assertTrue(canceller is SuppressionEchoCanceller)
        assertTrue(fallbackMessage?.contains("falling back") == true)
    }

    @Test
    fun factoryNormalizesUnknownKinds() {
        val canceller = EchoCancellerFactory.create("bogus", isPlaying = { false })
        assertTrue(canceller is SuppressionEchoCanceller)
        assertEquals("suppression", EchoCancellerFactory.normalizeKind(null))
        assertEquals("suppression", EchoCancellerFactory.normalizeKind("  "))
        assertEquals("webrtc_aec3", EchoCancellerFactory.normalizeKind("WebRTC_AEC3"))
    }
    //endregion

    //region Engine orchestration with fakes

    private class Harness {
        var t = 0L
        val capture = FakeCapture()
        val renderer = FakeRenderer()
        val forwarded = mutableListOf<ByteArray>()
        var speechStarted = 0
        var silenceDetected = 0
        val engine = WindowsAudioEngine(
            captureFactory = { _ -> capture },
            rendererFactory = { _ -> renderer },
            vad = SpectralVad(clock = { t }),
        )

        fun start(): Boolean = engine.startMicrophone(
            onPcmChunk = { base64 -> forwarded.add(Base64.getDecoder().decode(base64)) },
            onSilenceDetected = { silenceDetected++ },
            onSpeechStarted = { speechStarted++ },
        )

        fun feed(frame: ByteArray) {
            t += 32
            capture.push(frame)
        }

        fun close() = engine.close()
    }

    @Test
    fun engineForwardsSpeechWhenIdleAndDetectsSilence() {
        val h = Harness()
        assertTrue(h.start())
        repeat(10) { h.feed(silenceFrame()) }
        assertTrue("silence must not be forwarded", h.forwarded.isEmpty())
        repeat(10) { i -> h.feed(burstFrame(i)) }
        assertTrue("speech must be forwarded, got ${h.forwarded.size}", h.forwarded.size >= 8)
        assertEquals(1, h.speechStarted)
        // Let the VAD hangover elapse, then silence ends the turn.
        repeat(20) { h.feed(silenceFrame()) }
        assertEquals(1, h.silenceDetected)
        h.close()
    }

    @Test
    fun engineSuppressesDuringPlaybackAndBargeInStopsIt() {
        val h = Harness()
        assertTrue(h.start())
        h.renderer.playingState = true // assistant is speaking
        repeat(5) { h.feed(noiseFrame()) }
        assertTrue("suppressed frames must not be forwarded", h.forwarded.isEmpty())
        assertEquals(0, h.renderer.stopCount)

        // Deliberate interruption: VAD-confirmed barge-in stops playback.
        repeat(4) { i -> h.feed(burstFrame(i)) }
        assertEquals("barge-in must stop playback", 1, h.renderer.stopCount)
        assertFalse(h.renderer.playingState)
        assertTrue("barge-in audio must be forwarded, got ${h.forwarded.size}", h.forwarded.isNotEmpty())
        assertEquals(1, h.speechStarted)
        h.close()
    }

    @Test
    fun enginePreRollPreservesSpeechOnset() {
        val h = Harness()
        assertTrue(h.start())
        // Fill the pre-roll buffer with silence (8 chunks).
        repeat(8) { h.feed(silenceFrame()) }
        assertTrue(h.forwarded.isEmpty())
        // Speech onset flushes pre-roll first, then the speech frames.
        repeat(2) { i -> h.feed(burstFrame(i)) }
        assertEquals("pre-roll (8) + speech (2)", 10, h.forwarded.size)
        h.close()
    }

    @Test
    fun engineStartIsIdempotent() {
        val h = Harness()
        assertTrue(h.start())
        assertTrue(h.start())
        assertEquals(1, h.capture.startCount)
        h.engine.stopMicrophone()
        assertFalse(h.capture.isActive)
        h.close()
    }
    //endregion
}
