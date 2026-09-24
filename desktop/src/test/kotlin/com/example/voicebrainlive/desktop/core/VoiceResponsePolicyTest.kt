package com.example.voicebrainlive.desktop.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceResponsePolicyTest {

    @Test
    fun `text fallback remains enabled when a live turn has no audio`() {
        assertTrue(VoiceResponsePolicy.shouldSpeakTextFallback(false, "မင်္ဂလာပါရှင်"))
    }

    @Test
    fun `text fallback avoids duplicate speech after native audio`() {
        assertFalse(VoiceResponsePolicy.shouldSpeakTextFallback(true, "မင်္ဂလာပါရှင်"))
    }

    @Test
    fun `blank text is never sent to the speech fallback`() {
        assertFalse(VoiceResponsePolicy.shouldSpeakTextFallback(false, "   "))
    }

    @Test
    fun `native live mode never needs a text speech fallback`() {
        assertFalse(VoiceResponsePolicy.shouldSpeakTextFallback(true, "စာသား transcript သာ ရှိပါသည်"))
    }
}
