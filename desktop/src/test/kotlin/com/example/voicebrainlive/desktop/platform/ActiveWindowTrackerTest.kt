package com.example.voicebrainlive.desktop.platform

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWindowTrackerTest {

    @Test
    fun testActiveWindowTrackerReturnsValidObject() {
        val tracker = ActiveWindowTracker()
        val info = tracker.getActiveWindow()
        assertNotNull("ActiveWindowInfo must not be null", info)
        assertNotNull("App category must not be null", info.appCategory)
    }

    @Test
    fun testActiveWindowPromptContextFormat() {
        val info = ActiveWindowInfo(
            processName = "code",
            windowTitle = "Main.kt - VoiceBrainLive-Desktop - Visual Studio Code",
            appCategory = "Code Editor / IDE",
            inferredProjectOrFile = "VoiceBrainLive-Desktop"
        )
        assertTrue(info.isAvailable())
        val promptContext = info.toPromptContext()
        assertTrue(promptContext.contains("Active Foreground App: code"))
        assertTrue(promptContext.contains("Code Editor / IDE"))
        assertTrue(promptContext.contains("VoiceBrainLive-Desktop"))
    }

    @Test
    fun testBurmeseSummaryFormatting() {
        val info = ActiveWindowInfo(
            processName = "chrome",
            windowTitle = "Google Search",
            appCategory = "Web Browser"
        )
        val summary = info.toBurmeseSummary()
        assertTrue(summary.contains("လက်ရှိ အသုံးပြုနေသော Window:"))
        assertTrue(summary.contains("chrome"))
        assertTrue(summary.contains("Web Browser"))
    }
}
