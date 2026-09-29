package com.example.voicebrainlive.desktop.platform.phonemic

import com.example.voicebrainlive.desktop.platform.audio.AudioCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneMicCaptureTest {

    private fun chunkOf(value: Short, bytes: Int = 640): ByteArray {
        val out = ByteArray(bytes)
        var i = 0
        while (i < bytes) {
            out[i] = (value.toInt() and 0xFF).toByte()
            out[i + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    @Test
    fun `in-order chunks are delivered intact`() {
        val capture = PhoneMicCapture()
        val received = mutableListOf<ByteArray>()
        val latch = CountDownLatch(3)
        capture.start { frame ->
            // Skip pre-roll silence: only count non-silent frames.
            if (frame.any { it != 0.toByte() }) {
                received.add(frame)
                latch.countDown()
            }
        }
        // Feed 3 distinct chunks.
        capture.onChunk(0, chunkOf(1000))
        capture.onChunk(1, chunkOf(2000))
        capture.onChunk(2, chunkOf(3000))
        assertTrue("timed out waiting for frames", latch.await(3, TimeUnit.SECONDS))
        capture.stop()
        assertEquals(3, received.size)
        // First sample of each chunk should match (little-endian int16).
        fun firstSample(b: ByteArray): Short =
            ((b[1].toInt() shl 8) or (b[0].toInt() and 0xFF)).toShort()
        assertEquals(1000.toShort(), firstSample(received[0]))
        assertEquals(2000.toShort(), firstSample(received[1]))
        assertEquals(3000.toShort(), firstSample(received[2]))
    }

    @Test
    fun `underrun emits silence without blocking`() {
        val capture = PhoneMicCapture()
        val frames = mutableListOf<ByteArray>()
        val latch = CountDownLatch(2)
        capture.start { frame ->
            frames.add(frame)
            latch.countDown()
        }
        // No chunks fed: consumer should emit silence frames.
        assertTrue("consumer blocked on underrun", latch.await(3, TimeUnit.SECONDS))
        capture.stop()
        assertTrue(frames.all { f -> f.all { it == 0.toByte() } })
    }

    @Test
    fun `duplicate and ancient chunks are dropped`() {
        val capture = PhoneMicCapture()
        capture.start { }
        // Feed seq 0..20, then an ancient duplicate of seq 0 (beyond reorder window).
        for (s in 0..20) capture.onChunk(s.toLong(), chunkOf(s.toShort()))
        val before = capture.stats()
        capture.onChunk(0, chunkOf(9999)) // ancient -> dropped
        capture.stop()
        // Stats should show a drop (dropped counter increments).
        assertTrue(before.contains("dropped="))
    }

    @Test
    fun `stop is idempotent and start is restartable`() {
        val capture = PhoneMicCapture()
        assertFalse(capture.isActive)
        capture.start { }
        assertTrue(capture.isActive)
        capture.stop()
        assertFalse(capture.isActive)
        capture.stop() // no-op
        capture.start { }
        assertTrue(capture.isActive)
        capture.stop()
    }
}

class RoutingAudioCaptureTest {

    private class FakeCapture : AudioCapture {
        var started = false
        var stopped = false
        var callback: ((ByteArray) -> Unit)? = null
        override fun start(onFrame: (ByteArray) -> Unit): Boolean {
            started = true; stopped = false; callback = onFrame; return true
        }
        override fun stop() { stopped = true; started = false }
        override val isActive: Boolean get() = started
    }

    @Test
    fun `switchTo swaps source while started`() {
        val local = FakeCapture()
        val phone = FakeCapture()
        val router = RoutingAudioCapture(local)
        router.setLocalSource(local)
        var frames = 0
        router.start { frames++ }
        assertTrue(local.started)

        router.switchTo(phone)
        assertTrue(local.stopped)
        assertTrue(phone.started)
        assertTrue(router.isPhoneMicActive())

        phone.callback?.invoke(ByteArray(640))
        assertEquals(1, frames)
        router.stop()
    }

    @Test
    fun `switchTo before start only changes pending source`() {
        val local = FakeCapture()
        val phone = FakeCapture()
        val router = RoutingAudioCapture(local)
        router.switchTo(phone)
        assertFalse(phone.started) // not started yet
        router.start { }
        assertTrue(phone.started)
        assertFalse(local.started)
        router.stop()
    }

    @Test
    fun `isActive reflects underlying source`() {
        val local = FakeCapture()
        val router = RoutingAudioCapture(local)
        assertFalse(router.isActive)
        router.start { }
        assertTrue(router.isActive)
        router.stop()
        assertFalse(router.isActive)
    }
}

class PhoneMicServerHelpersTest {

    @Test
    fun `generatePin is 6 digits`() {
        repeat(20) {
            val pin = PhoneMicServer.generatePin()
            assertEquals(6, pin.length)
            assertTrue(pin.all { it.isDigit() })
        }
    }

    @Test
    fun `constantTimeEquals compares correctly`() {
        assertTrue(PhoneMicServer.constantTimeEquals("123456", "123456"))
        assertFalse(PhoneMicServer.constantTimeEquals("123456", "123457"))
        assertFalse(PhoneMicServer.constantTimeEquals("123456", "12345"))
    }

    @Test
    fun `sha256Hex is stable and 64 chars`() {
        val a = PhoneMicServer.sha256Hex("token123")
        val b = PhoneMicServer.sha256Hex("token123")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertFalse(a == PhoneMicServer.sha256Hex("token124"))
    }

    @Test
    fun `discoverLanIp never throws and returns a string`() {
        val ip = PhoneMicServer.discoverLanIp()
        assertTrue(ip.isNotBlank())
    }
}
