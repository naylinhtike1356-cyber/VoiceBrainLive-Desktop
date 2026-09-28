package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.DesktopCommand
import com.example.voicebrainlive.desktop.core.OfflineCommandMatcher
import com.example.voicebrainlive.desktop.platform.audio.AudioCapture
import com.example.voicebrainlive.desktop.platform.audio.AudioRenderer
import com.example.voicebrainlive.desktop.platform.audio.SpectralVad
import com.example.voicebrainlive.desktop.platform.audio.SuppressionEchoCanceller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 2 — safety hardening tests:
 * - open_file never launches executable/script types,
 * - recycle-bin deletion and forced process termination require voice
 *   confirmation (first pass gates, confirmed pass executes),
 * - barge-in sensitivity levels map correctly and persist,
 * - client-side interruption protocol drops the interrupted turn's audio tail.
 */
class CommandSafetyTest {

    // ---------- openFile executable refusal ----------

    @Test
    fun openFileRefusesExecutableTypes() {
        val executor = WindowsCommandExecutor()
        val dir = Files.createTempDirectory("vbl-safety").toFile()
        try {
            for (ext in listOf("exe", "bat", "cmd", "ps1", "msi", "vbs", "js", "scr")) {
                val f = File(dir, "payload.$ext").apply { writeText("dummy") }
                val result = runBlocking {
                    executor.execute(DesktopCommand("open_file", target = f.absolutePath))
                }
                assertFalse(".$ext must never be launched via voice", result.success)
                assertFalse("refusal is not a confirmation gate", result.requiresConfirmation)
                assertTrue("message must explain the refusal", result.message.contains("ဖွင့်မပေး"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openFileRefusalIsCaseInsensitive() {
        val executor = WindowsCommandExecutor()
        val dir = Files.createTempDirectory("vbl-safety").toFile()
        try {
            val f = File(dir, "PAYLOAD.EXE").apply { writeText("dummy") }
            val result = runBlocking {
                executor.execute(DesktopCommand("open_file", target = f.absolutePath))
            }
            assertFalse(result.success)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openFileMissingFileStillReportsNotFound() {
        val executor = WindowsCommandExecutor()
        val result = runBlocking {
            executor.execute(DesktopCommand("open_file", target = "/no/such/file_xyz.txt"))
        }
        assertFalse(result.success)
        assertTrue(result.message.contains("ရှာမတွေ့"))
    }

    // ---------- confirmation gating ----------

    @Test
    fun emptyRecycleBinRequiresConfirmationFirst() {
        val executor = WindowsCommandExecutor()
        val result = runBlocking { executor.execute(DesktopCommand("empty_recycle_bin")) }
        assertFalse("first pass must not execute", result.success)
        assertTrue("first pass must request voice confirmation", result.requiresConfirmation)
    }

    @Test
    fun emptyRecycleBinConfirmedPassDoesNotThrow() {
        val executor = WindowsCommandExecutor()
        // On Linux powershell.exe is missing -> failure result; on Windows it
        // would execute. Either way it must not throw and must not gate again.
        val result = runBlocking {
            executor.execute(DesktopCommand("empty_recycle_bin", value = "confirmed"))
        }
        assertFalse(result.requiresConfirmation)
    }

    @Test
    fun closeAppRequiresConfirmationFirst() {
        val executor = WindowsCommandExecutor()
        val result = runBlocking {
            executor.execute(DesktopCommand("close_app", target = "some-test-app-xyz"))
        }
        assertFalse("first pass must not kill anything", result.success)
        assertTrue("first pass must request voice confirmation", result.requiresConfirmation)
    }

    @Test
    fun closeAppConfirmedBogusNameFailsSafely() {
        val executor = WindowsCommandExecutor()
        val result = runBlocking {
            executor.execute(
                DesktopCommand("close_app", target = "definitely-not-a-real-process-xyz", value = "confirmed"),
            )
        }
        assertFalse(result.requiresConfirmation)
        assertFalse("bogus name must not report success", result.success)
    }

    @Test
    fun closeAppConfirmedBlankNameDoesNotKill() {
        val executor = WindowsCommandExecutor()
        // Guards the edge where the name was only in value (now "confirmed").
        val result = runBlocking {
            executor.execute(DesktopCommand("close_app", value = "confirmed"))
        }
        assertFalse(result.success)
    }

    // ---------- barge-in sensitivity ----------

    @Test
    fun sensitivityLevelMapping() {
        assertEquals(0.25f, SpectralVad.sensitivityForLevel("low"))
        assertEquals(0.5f, SpectralVad.sensitivityForLevel("normal"))
        assertEquals(0.75f, SpectralVad.sensitivityForLevel("high"))
        assertEquals(0.5f, SpectralVad.sensitivityForLevel("bogus"))
        assertEquals(0.5f, SpectralVad.sensitivityForLevel(""))
    }

    @Test
    fun sensitivityLevelNormalization() {
        assertEquals("high", SpectralVad.normalizeLevel("မြင့်"))
        assertEquals("low", SpectralVad.normalizeLevel("နိမ့်"))
        assertEquals("normal", SpectralVad.normalizeLevel(null))
        assertEquals("normal", SpectralVad.normalizeLevel("whatever"))
        assertEquals("high", SpectralVad.normalizeLevel(" HIGH "))
    }

    @Test
    fun sensitivityPersistsViaStore() {
        val store = ApiKeyStore()
        try {
            store.saveBargeInSensitivity("high")
            assertEquals("high", store.loadBargeInSensitivity())
            store.saveBargeInSensitivity("bogus")
            assertEquals("normal", store.loadBargeInSensitivity())
            store.saveBargeInSensitivity("low")
            assertEquals("low", store.loadBargeInSensitivity())
        } finally {
            store.saveBargeInSensitivity("normal")
        }
    }

    @Test
    fun sensitivityPhrasesMatchOffline() {
        assertEquals("high", OfflineCommandMatcher.match("sensitivity မြင့်")?.value)
        assertEquals("low", OfflineCommandMatcher.match("sensitivity နိမ့်")?.value)
        assertEquals("normal", OfflineCommandMatcher.match("sensitivity ပုံမှန်ထား")?.value)
        assertEquals(
            "set_barge_in_sensitivity",
            OfflineCommandMatcher.match("ဖြတ်ပြောတာ ထိခိုက်လွယ်အောင်လုပ်")?.type,
        )
    }

    @Test
    fun engineAppliesSensitivityLive() {
        val engine = WindowsAudioEngine(vad = SpectralVad())
        // Must not throw; the mapping itself is covered above.
        engine.updateBargeInSensitivity("high")
        engine.updateBargeInSensitivity("bogus")
        engine.close()
    }

    // ---------- interruption protocol ----------

    @Test
    fun clientBargeInSuppressesInterruptedTailThenRecovers() {
        // Generous window: notifyClientBargeIn() performs logger I/O, so the
        // drop window must comfortably exceed any logging latency.
        val session = GeminiLiveSession(apiKey = "test-key", interruptedTailDropMs = 400L)
        assertFalse(session.shouldSuppressServerAudio())
        session.notifyClientBargeIn()
        assertTrue("in-flight tail of the interrupted turn must be dropped", session.shouldSuppressServerAudio())
        Thread.sleep(700)
        assertFalse("after the window, new-turn audio must play", session.shouldSuppressServerAudio())
        assertFalse("marker stays cleared", session.shouldSuppressServerAudio())
    }

    @Test
    fun userTurnEndMarkerDoesNotThrow() {
        val session = GeminiLiveSession(apiKey = "test-key")
        session.noteUserTurnEnd()
        assertFalse(session.shouldSuppressServerAudio())
    }

    @Test
    fun speechStartHookReportsBargeInFlag() {
        val idleFlags = mutableListOf<Boolean>()
        val idleEngine = TestEngine(onUserSpeechStart = { bargeIn -> idleFlags.add(bargeIn) })
        idleEngine.start()
        repeat(10) { idleEngine.feedSilence() }
        repeat(4) { i -> idleEngine.feedBurst(i) } // ordinary turn start, idle
        assertEquals("idle speech onset is not a barge-in", listOf(false), idleFlags)
        idleEngine.close()

        val bargeFlags = mutableListOf<Boolean>()
        val bargeEngine = TestEngine(onUserSpeechStart = { bargeIn -> bargeFlags.add(bargeIn) })
        bargeEngine.start()
        bargeEngine.renderer.setPlaying(true) // assistant is speaking
        repeat(10) { bargeEngine.feedSilence() }
        repeat(4) { i -> bargeEngine.feedBurst(i) } // user interrupts
        assertEquals("interruption while playing is a barge-in", listOf(true), bargeFlags)
        bargeEngine.close()
    }

    // ---------- helpers ----------

    private class TestCapture : AudioCapture {
        private var listener: ((ByteArray) -> Unit)? = null
        override val isActive: Boolean get() = listener != null
        override fun start(onFrame: (ByteArray) -> Unit): Boolean {
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

    private class TestRenderer : AudioRenderer {
        var playingState = false
        private var playingListener: ((Boolean) -> Unit)? = null
        override fun setPlayingStateListener(listener: ((Boolean) -> Unit)?) {
            playingListener = listener
        }

        fun setPlaying(value: Boolean) {
            if (playingState != value) {
                playingState = value
                playingListener?.invoke(value)
            }
        }

        override fun enqueuePcm16(data: ByteArray): Boolean = true
        override fun stopPlayback() {
            setPlaying(false)
        }

        override val isPlaying: Boolean get() = playingState
        override val droppedChunkCount: Long get() = 0L
        override fun close() {}
    }

    private class TestEngine(onUserSpeechStart: ((Boolean) -> Unit)?) {
        private var t = 0L
        val capture = TestCapture()
        val renderer = TestRenderer()
        private val frameSamples = 512
        private val engine = WindowsAudioEngine(
            onUserSpeechStart = onUserSpeechStart,
            captureFactory = { _ -> capture },
            rendererFactory = { _ -> renderer },
            echoCancellerFactory = { isPlaying -> SuppressionEchoCanceller(isPlaying) },
            vad = SpectralVad(clock = { t }),
        )

        fun start(): Boolean = engine.startMicrophone(onPcmChunk = {})

        fun feedSilence() {
            t += 32
            capture.push(ByteArray(frameSamples * 2))
        }

        fun feedBurst(frameIndex: Int) {
            t += 32
            val samples = FloatArray(frameSamples) { j ->
                val time = (frameIndex * frameSamples + j).toDouble() / 16000
                val am = 0.5 + 0.5 * sin(2 * PI * 5 * time)
                val s = (sin(2 * PI * 150 * time) + 0.6 * sin(2 * PI * 300 * time)) / 1.6
                (s * am * 4000 / 32768).toFloat()
            }
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            capture.push(out)
        }

        fun close() = engine.close()
    }
}
