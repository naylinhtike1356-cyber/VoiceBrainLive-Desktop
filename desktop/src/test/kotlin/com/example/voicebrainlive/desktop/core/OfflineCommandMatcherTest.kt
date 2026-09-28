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

    @Test
    fun mouseAndHandsOfflineCommandsMatch() {
        assertEquals("mouse_click", OfflineCommandMatcher.match("မောက်စ် နှိပ်")?.type)
        assertEquals("mouse_click", OfflineCommandMatcher.match("မောက်ကွန်စာ နှိပ်")?.type)
        assertEquals("mouse_double_click", OfflineCommandMatcher.match("double click")?.type)
        assertEquals("mouse_double_click", OfflineCommandMatcher.match("မောက်ကွန်စာ နှစ်ချက်နှိပ်")?.type)
        assertEquals("mouse_right_click", OfflineCommandMatcher.match("right click")?.type)
        assertEquals("mouse_right_click", OfflineCommandMatcher.match("မောက်ကွန်စာ ညာဘက်နှိပ်")?.type)
        assertEquals("show_desktop", OfflineCommandMatcher.match("desktop ပြပါ")?.type)
        assertEquals("screen_eyes", OfflineCommandMatcher.match("စခရင် စစ်ဆေး")?.type)
        assertEquals("refresh_app_catalog", OfflineCommandMatcher.match("refresh app catalog")?.type)
    }

    @Test
    fun dynamicMouseTargetingMatches() {
        val clickCmd = OfflineCommandMatcher.match("မောက်စ်နဲ့ Chrome ကို နှိပ်ပေးပါ")
        assertEquals("mouse_click", clickCmd?.type)
        assertEquals("chrome", clickCmd?.target?.lowercase())

        val cursorClickCmd = OfflineCommandMatcher.match("မောက်ကွန်စာနဲ့ start ကို နှိပ်ပါ")
        assertEquals("mouse_click", cursorClickCmd?.type)
        assertEquals("start", cursorClickCmd?.target?.lowercase())

        val moveCmd = OfflineCommandMatcher.match("မောက်စ်ကို အလယ် ရွှေ့ပါ")
        assertEquals("mouse_move", moveCmd?.type)
        assertEquals("အလယ်", moveCmd?.target)

        val doubleCmd = OfflineCommandMatcher.match("မောက်စ်နဲ့ Chrome ကို နှစ်ချက်နှိပ်")
        assertEquals("mouse_double_click", doubleCmd?.type)
        assertEquals("chrome", doubleCmd?.target?.lowercase())
    }

    @Test
    fun dynamicDesktopIconClickMatches() {
        val openCmd = OfflineCommandMatcher.match("Chrome အိုင်ကွန် နှိပ်ဖွင့်")
        assertEquals("click_desktop_icon", openCmd?.type)
        assertEquals("chrome", openCmd?.target?.lowercase())

        val closeCmd = OfflineCommandMatcher.match("Chrome အိုင်ကွန် နှိပ်ပိတ်")
        assertEquals("close_desktop_icon", closeCmd?.type)
        assertEquals("chrome", closeCmd?.target?.lowercase())

        val toggleCmd = OfflineCommandMatcher.match("Chrome အိုင်ကွန် ဖွင့်ပိတ်")
        assertEquals("toggle_app", toggleCmd?.type)
        assertEquals("chrome", toggleCmd?.target?.lowercase())
    }

    @Test
    fun phase2WindowSnappingAndTabsMatch() {
        assertEquals("snap_window_left", OfflineCommandMatcher.match("window ဘယ်ဘက်ကပ်")?.type)
        assertEquals("snap_window_left", OfflineCommandMatcher.match("snap left")?.type)
        assertEquals("snap_window_right", OfflineCommandMatcher.match("window ညာဘက်ကပ်")?.type)
        assertEquals("snap_window_right", OfflineCommandMatcher.match("snap right")?.type)
        assertEquals("snap_window_up", OfflineCommandMatcher.match("window အပေါ်ကပ်")?.type)
        assertEquals("snap_window_down", OfflineCommandMatcher.match("window အောက်ချ")?.type)
        assertEquals("new_tab", OfflineCommandMatcher.match("tab အသစ်")?.type)
        assertEquals("switch_tab", OfflineCommandMatcher.match("tab ကူး")?.type)
        assertEquals("switch_tab", OfflineCommandMatcher.match("next tab")?.type)
        assertEquals("inspect_window_ui", OfflineCommandMatcher.match("ui စစ်")?.type)
        assertEquals("inspect_window_ui", OfflineCommandMatcher.match("ခလုတ်တွေပြ")?.type)
    }

    @Test
    fun phase2DynamicButtonClickMatches() {
        val saveBtn = OfflineCommandMatcher.match("save ခလုတ်နှိပ်")
        assertEquals("click_ui_element", saveBtn?.type)
        assertEquals("save", saveBtn?.target?.lowercase())

        val searchBtn = OfflineCommandMatcher.match("ရှာဖွေရန် ခလုတ်နှိပ်ပါ")
        assertEquals("click_ui_element", searchBtn?.type)
        assertEquals("ရှာဖွေရန်", searchBtn?.target)

        val clickOk = OfflineCommandMatcher.match("click ok button")
        assertEquals("click_ui_element", clickOk?.type)
        assertEquals("ok", clickOk?.target?.lowercase())

        val cancelBtn = OfflineCommandMatcher.match("cancel ခလုတ် နှိပ်ပေးပါ")
        assertEquals("click_ui_element", cancelBtn?.type)
        assertEquals("cancel", cancelBtn?.target?.lowercase())
    }

    @Test
    fun latencyReportTriggerMatches() {
        assertEquals("latency_report", OfflineCommandMatcher.match("latency စစ်")?.type)
        assertEquals("latency_report", OfflineCommandMatcher.match("latency စစ်ဆေး")?.type)
        assertEquals("latency_report", OfflineCommandMatcher.match("အသံ latency စစ်")?.type)
        assertEquals("latency_report", OfflineCommandMatcher.match("latency check")?.type)
    }
}
