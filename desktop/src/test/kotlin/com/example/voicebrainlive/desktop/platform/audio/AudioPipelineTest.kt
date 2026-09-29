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
    private var playingListener: ((Boolean) -> Unit)? = null

    override fun setPlayingStateListener(listener: ((Boolean) -> Unit)?) {
        playingListener = listener
    }

    /** Simulates the renderer firing a burst transition (start / natural end). */
    fun setPlaying(value: Boolean) {
        if (playingState != value) {
            playingState = value
            playingListener?.invoke(value)
        }
    }

    override fun enqueuePcm16(data: ByteArray): Boolean {
        enqueued.add(data)
        return true
    }

    override fun stopPlayback() {
        stopCount++
        setPlaying(false)
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
        val canceller = SuppressionEchoCanceller(isPlaying = { true }, burstStartGuardMs = 0L)
        val frame = burstFrame(0)
        // First speech frame: held for confirmation, not forwarded.
        assertTrue(canceller.processCapture(frame, null, speechDetected = true) is EchoDecision.Suppress)
        // Second consecutive speech frame: confirmed barge-in; the held onset
        // frame is returned so the first syllable is preserved.
        val second = canceller.processCapture(frame, null, speechDetected = true)
        assertTrue(second is EchoDecision.BargeIn)
        second as EchoDecision.BargeIn
        assertTrue("onset frame must be preserved", second.firstFrame?.contentEquals(frame) == true)
        assertTrue(second.frame.contentEquals(frame))
        // Non-speech resets the confirmation counter and drops the held frame.
        assertTrue(canceller.processCapture(frame, null, speechDetected = false) is EchoDecision.Suppress)
        assertTrue(canceller.processCapture(frame, null, speechDetected = true) is EchoDecision.Suppress)
    }

    /** Sine frame at the given peak amplitude (RMS ~= amplitude / sqrt(2)). */
    private fun toneFrame(amplitude: Float): ByteArray =
        pcm16(FloatArray(frameSamples) { j ->
            val t = j.toDouble() / 16000
            (sin(2 * PI * 220 * t) * amplitude).toFloat()
        })

    @Test
    fun echoGateBlocksSelfInterruption() {
        // Speaker emitting loudly; the mic hears only attenuated echo.
        // The echo trips the VAD, but the gate must never let it become a
        // barge-in — otherwise the assistant interrupts itself and its
        // responses are never heard.
        val canceller = SuppressionEchoCanceller(isPlaying = { true }, burstStartGuardMs = 0L)
        val render = toneFrame(0.30f) // RMS ~0.21
        val echo = toneFrame(0.06f) // room-attenuated echo, RMS ~0.042
        repeat(40) {
            val decision = canceller.processCapture(echo, render, speechDetected = true)
            assertTrue(
                "echo frame $it must not barge in",
                decision is EchoDecision.Suppress,
            )
        }
    }

    @Test
    fun echoGateAllowsRealBargeInOverSpeaker() {
        // User speaks firmly over the playing speaker: the mic level sits
        // clearly above the learned echo floor → barge-in must still work.
        val canceller = SuppressionEchoCanceller(isPlaying = { true }, burstStartGuardMs = 0L)
        val render = toneFrame(0.30f) // RMS ~0.21
        val echo = toneFrame(0.06f)
        // Let the floor converge on the echo first (a few frames).
        repeat(10) {
            canceller.processCapture(echo, render, speechDetected = true)
        }
        // User voice: clearly above floor*2.5.
        val userVoice = toneFrame(0.60f) // RMS ~0.42
        assertTrue(
            canceller.processCapture(userVoice, render, speechDetected = true) is EchoDecision.Suppress,
        )
        val second = canceller.processCapture(userVoice, render, speechDetected = true)
        assertTrue("firm user voice over the speaker must barge in", second is EchoDecision.BargeIn)
    }

    @Test
    fun bargeInWorksWhenSpeakerIsSilent() {
        // Speaker-silent gap during playback (render reference silent): the
        // VAD verdict alone is trustworthy — no echo to gate against.
        val canceller = SuppressionEchoCanceller(isPlaying = { true }, burstStartGuardMs = 0L)
        val silentRender = ByteArray(frameSamples * 2)
        val frame = burstFrame(0)
        assertTrue(canceller.processCapture(frame, silentRender, speechDetected = true) is EchoDecision.Suppress)
        assertTrue(canceller.processCapture(frame, silentRender, speechDetected = true) is EchoDecision.BargeIn)
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
        // The held onset frame + confirming frame + following frames are forwarded.
        repeat(4) { i -> h.feed(burstFrame(i)) }
        assertEquals("barge-in must stop playback", 1, h.renderer.stopCount)
        assertFalse(h.renderer.playingState)
        assertEquals("onset + 3 speech frames must be forwarded", 4, h.forwarded.size)
        assertEquals(1, h.speechStarted)
        h.close()
    }

    @Test
    fun naturalEndStartsEchoCooldownButBargeInCutsThrough() {
        val h = Harness()
        assertTrue(h.start())
        // Assistant playback ends naturally -> echo cooldown anchors at the
        // actual completion time.
        h.renderer.setPlaying(true)
        h.renderer.setPlaying(false)
        // Ambient noise inside the cooldown window stays suppressed.
        repeat(2) { h.feed(noiseFrame()) }
        assertTrue(h.forwarded.isEmpty())
        // A VAD-confirmed barge-in still cuts through during the cooldown,
        // with the onset frame preserved.
        repeat(2) { i -> h.feed(burstFrame(i)) }
        assertEquals(2, h.forwarded.size)
        assertEquals(1, h.renderer.stopCount)
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

    //region Barge-in cooldown (false-barge-in oscillation guard)

    /**
     * After a confirmed barge-in, a second barge-in candidate within the
     * 2-second cooldown is suppressed. This breaks the false-barge-in
     * oscillation where speaker echo stops playback, the server keeps
     * sending, playback resumes, echo triggers again — permanently chopping
     * the response.
     */
    @Test
    fun bargeInCooldownSuppressesRapidSecondBargeIn() {
        val canceller = SuppressionEchoCanceller(isPlaying = { true }, burstStartGuardMs = 0L)
        // Render reference: active speaker. Floor seeds at render*0.5.
        val render = loudFrame(0.2f)
        // Mic: user voice well above the 3.0x gate (simulates a real barge-in).
        val voice = loudFrame(0.5f)

        // First barge-in: 2 consecutive speech frames confirm it.
        var d1 = canceller.processCapture(voice, render, speechDetected = true)
        assertTrue(d1 is EchoDecision.Suppress) // first frame held as onset
        d1 = canceller.processCapture(voice, render, speechDetected = true)
        assertTrue(d1 is EchoDecision.BargeIn)

        // Immediate second attempt (within cooldown): must be suppressed,
        // not a second BargeIn.
        var d2 = canceller.processCapture(voice, render, speechDetected = true)
        assertTrue(d2 is EchoDecision.Suppress)
        d2 = canceller.processCapture(voice, render, speechDetected = true)
        assertTrue(
            "Second barge-in within cooldown should be suppressed, was $d2",
            d2 is EchoDecision.Suppress,
        )
    }

    /**
     * Burst-start guard: during the first 500ms of assistant playback,
     * even loud mic input must NOT trigger barge-in. This prevents the
     * assistant's own opening syllables from false-triggering self-
     * interruption before the echo floor stabilizes.
     */
    @Test
    fun burstStartGuardSuppressesBargeIn() {
        val canceller = SuppressionEchoCanceller(
            isPlaying = { true },
            burstStartGuardMs = 500L,
        )
        val render = loudFrame(0.2f)
        // Very loud mic input that would normally barge in immediately.
        val voice = loudFrame(0.8f)

        // Within the guard window: must be suppressed, not BargeIn.
        repeat(5) {
            val d = canceller.processCapture(voice, render, speechDetected = true)
            assertTrue("Barge-in during burst guard should be suppressed, was $d", d is EchoDecision.Suppress)
        }
    }

    /** PCM16 frame with (approximately) the given RMS. */
    private fun loudFrame(rms: Float): ByteArray =
        pcm16(FloatArray(frameSamples) { j ->
            val t = j.toDouble() / 16000
            (sin(2 * PI * 200 * t) * rms * 1.4142).toFloat()
        })
    //endregion
}
