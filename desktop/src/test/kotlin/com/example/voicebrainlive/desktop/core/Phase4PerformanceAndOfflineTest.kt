package com.example.voicebrainlive.desktop.core

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.example.voicebrainlive.desktop.platform.HealthWatchdog
import com.example.voicebrainlive.desktop.platform.LowPowerOptimizer
import com.example.voicebrainlive.desktop.platform.PowerMode
import com.example.voicebrainlive.desktop.platform.WindowsCommandExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase4PerformanceAndOfflineTest {

    @Test
    fun testDesktopLoggerDailyRotationAndPruning() {
        // Test daily log file format
        val todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val logDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "VoiceBrainLive/logs")
        logDir.mkdirs()

        // Write sample logs
        DesktopLogger.info("Phase 4 test log info message")
        DesktopLogger.warn("Phase 4 test log warning message")
        DesktopLogger.error("Phase 4 test log error message", RuntimeException("Simulated test error"))

        val todayFile = File(logDir, "app-$todayStr.log")
        assertTrue(todayFile.exists(), "Today's daily log file should exist: ${todayFile.name}")
        val content = todayFile.readText()
        assertTrue(content.contains("Phase 4 test log info message"))
        assertTrue(content.contains("Simulated test error"))

        // Create an expired dummy log file (from 2020)
        val oldLogFile = File(logDir, "app-2020-01-01.log")
        oldLogFile.writeText("Expired test log from 2020")
        assertTrue(oldLogFile.exists())

        // Trigger log prune
        DesktopLogger.pruneOldLogs()

        // Verify expired file was pruned
        assertFalse(oldLogFile.exists(), "Logs older than 7 days should be automatically pruned")
    }

    @Test
    fun testHealthWatchdogDiagnosticsAndSafeTrap() {
        var autoReconnectCount = 0
        var safeResetMessage = ""
        val watchdog = HealthWatchdog(
            isOnlineProvider = { true },
            onAutoReconnect = { autoReconnectCount++ },
            onSafeStateReset = { msg -> safeResetMessage = msg }
        )

        // Test diagnostics report
        val report = watchdog.getHealthReport(isAudioActive = true)
        assertTrue(report.totalMemoryMb > 0)
        assertTrue(report.activeThreads > 0)
        assertTrue(report.isAudioActive)
        assertTrue(report.isOnline)

        val summary = report.toSummary()
        assertTrue(summary.contains("စနစ် ကျန်းမာရေး အခြေအနေ"))
        assertTrue(summary.contains("RAM သုံးစွဲမှု"))
        assertTrue(summary.contains("🟢 အသင့် (Online)"))
        assertTrue(summary.contains("Audio Engine: ဖွင့်ထားသည်"))

        // Test global exception trapping without crashing
        watchdog.install()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler)

        // Simulate an uncaught exception
        val testThread = Thread({
            throw RuntimeException("Watchdog test uncaught simulation")
        }, "TestFaultyThread")

        handler.uncaughtException(testThread, RuntimeException("Watchdog test uncaught simulation"))
        assertTrue(safeResetMessage.contains("safe state"), "Watchdog should catch uncaught exception and trigger safe state reset")
    }

    @Test
    fun testLowPowerOptimizerModeTransitions() {
        val optimizer = LowPowerOptimizer(
            onThrottleStateChanged = { _ -> }
        )

        assertEquals(PowerMode.ACTIVE, optimizer.powerMode.value)

        // Simulate user activity keeps active
        optimizer.onUserActivity()
        assertEquals(PowerMode.ACTIVE, optimizer.powerMode.value)

        // Test memory trimming call runs safely
        val reclaimed = optimizer.trimMemoryWorkingSet()
        assertTrue(reclaimed >= 0, "Reclaimed memory should be non-negative")
    }

    @Test
    fun testHybridOfflineFallbackEngineCommands() = runBlocking {
        val executor = WindowsCommandExecutor()
        val offlineEngine = HybridOfflineFallbackEngine(executor)

        // 1. Matched offline volume command
        val volRes = offlineEngine.handleOfflineTurn("အသံတိုး")
        assertTrue(volRes.success)
        assertTrue(volRes.message.contains("အသံကို တိုးပေးလိုက်ပါပြီရှင်"))

        // 2. Matched offline time command
        val timeRes = offlineEngine.handleOfflineTurn("အချိန်ဘယ်လောက်ရှိပြီလဲ")
        assertTrue(timeRes.success)
        assertTrue(timeRes.message.contains("လက်ရှိအချိန်မှာ"))

        // 3. Matched offline RAM optimization
        val ramRes = offlineEngine.handleOfflineTurn("clean ram")
        assertTrue(ramRes.success)
        assertTrue(ramRes.message.contains("RAM Memory ကို ရှင်းလင်းပြီးပါပြီရှင်"))

        // 4. Matched offline Health Check
        val healthRes = offlineEngine.handleOfflineTurn("watchdog စစ်")
        assertTrue(healthRes.success)
        assertTrue(healthRes.message.contains("စနစ် ကျန်းမာရေး အခြေအနေ"))

        // 5. Unmatched offline command fallback message
        val unknownRes = offlineEngine.handleOfflineTurn("ကမ္ဘာ့သမိုင်းအကြောင်း ရှင်းပြပေးပါ")
        assertFalse(unknownRes.success)
        assertTrue(unknownRes.message.contains("အင်တာနက် မရှိချိန်တွင် အခြေခံ အသံထိန်းချုပ်မှုများ"))

        // 6. Local SAPI verbal feedback runs safely
        offlineEngine.speakOfflineFeedback("မင်္ဂလာပါ နီလာ စမ်းသပ်ချက် အောင်မြင်ပါသည်")
    }

    @Test
    fun testOfflineCommandMatcherPhase4Triggers() {
        // Test RAM optimization triggers
        val matchRam1 = OfflineCommandMatcher.match("clean ram")
        assertNotNull(matchRam1)
        assertEquals("optimize_ram", matchRam1.type)

        val matchRam2 = OfflineCommandMatcher.match("ram ရှင်းပေး")
        assertNotNull(matchRam2)
        assertEquals("optimize_ram", matchRam2.type)

        // Test Health watchdog triggers
        val matchHealth1 = OfflineCommandMatcher.match("watchdog စစ်")
        assertNotNull(matchHealth1)
        assertEquals("health_check", matchHealth1.type)

        val matchHealth2 = OfflineCommandMatcher.match("system health စစ်")
        assertNotNull(matchHealth2)
        assertEquals("health_check", matchHealth2.type)
    }
}
