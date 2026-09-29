package com.example.voicebrainlive.desktop.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies session-resumption handle bookkeeping in [GeminiLiveSession]:
 * the server-sent `sessionResumptionUpdate` message must be parsed and the
 * handle stored so reconnect can resume the same Live session.
 */
class SessionResumptionTest {

    private fun newSession(): GeminiLiveSession {
        // Constructor params mirror the production call site in DesktopRuntime.
        return GeminiLiveSession(
            apiKey = "test-key",
            onStatus = {},
            onAudioResponse = {},
            onInputTranscript = {},
            onOutputTranscript = {},
            onTurnComplete = {},
            onSetupComplete = {},
            onInterrupted = {},
            onToolCall = { _, _, _, _ -> },
        )
    }

    private fun readHandle(session: GeminiLiveSession): String? {
        val field = GeminiLiveSession::class.java.getDeclaredField("resumptionHandle")
        field.isAccessible = true
        return field.get(session) as String?
    }

    private fun handleMessage(session: GeminiLiveSession, text: String) {
        val method = GeminiLiveSession::class.java.getDeclaredMethod(
            "handleServerMessage", String::class.java,
        )
        method.isAccessible = true
        method.invoke(session, text)
    }

    @Test
    fun `sessionResumptionUpdate stores handle when resumable`() {
        val session = newSession()
        handleMessage(
            session,
            """{"sessionResumptionUpdate":{"newHandle":"handle-abc-123","resumable":true}}""",
        )
        assertEquals("handle-abc-123", readHandle(session))
    }

    @Test
    fun `sessionResumptionUpdate ignored when not resumable`() {
        val session = newSession()
        handleMessage(
            session,
            """{"sessionResumptionUpdate":{"newHandle":"handle-xyz","resumable":false}}""",
        )
        assertNull(readHandle(session))
    }

    @Test
    fun `latest handle wins across updates`() {
        val session = newSession()
        handleMessage(
            session,
            """{"sessionResumptionUpdate":{"newHandle":"first","resumable":true}}""",
        )
        handleMessage(
            session,
            """{"sessionResumptionUpdate":{"newHandle":"second","resumable":true}}""",
        )
        assertEquals("second", readHandle(session))
    }
}
