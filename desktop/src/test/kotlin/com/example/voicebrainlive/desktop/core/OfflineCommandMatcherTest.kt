package com.example.voicebrainlive.desktop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineCommandMatcherTest {
    @Test
    fun ordinaryConversationDoesNotTriggerScreenVision() {
        assertNull(OfflineCommandMatcher.match("ဒီနေ့ ရာသီဥတုအကြောင်း ဆွေးနွေးကြရအောင်"))
        assertNull(OfflineCommandMatcher.match("မင်းနဲ့ စကားပြောချင်တယ်"))
    }

    @Test
    fun screenVisionRequiresAnExplicitScreenIntent() {
        assertEquals("analyze_screen", OfflineCommandMatcher.match("စခရင်မှာ ဘာပြလဲ")?.type)
        assertEquals("analyze_screen", OfflineCommandMatcher.match("စခရင်မှာ ဘာပြလဲ ပေးပါ")?.type)
        assertNull(OfflineCommandMatcher.match("ဒီနေ့ taskbar အကြောင်း ပြောကြရအောင်"))
    }

    @Test
    fun exactAppCommandsStillWorkWithPoliteEndings() {
        assertEquals("open_app", OfflineCommandMatcher.match("Chrome ဖွင့်ပေးပါ")?.type)
        assertEquals("close_app", OfflineCommandMatcher.match("close chrome")?.type)
        assertEquals("get_current_time", OfflineCommandMatcher.match("အချိန်ပြောပြပါ")?.type)
    }
}
