package com.example.voicebrainlive.desktop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

data class AppHealthReport(
    val uptimeSeconds: Long,
    val usedMemoryMb: Long,
    val totalMemoryMb: Long,
    val maxMemoryMb: Long,
    val isOnline: Boolean,
    val activeThreads: Int,
    val isAudioActive: Boolean
) {
    fun toSummary(): String = buildString {
        appendLine("စနစ် ကျန်းမာရေး အခြေအနေ (System Health Report):")
        appendLine("• Uptime: ${uptimeSeconds / 60} မိနစ် (${uptimeSeconds}s)")
        appendLine("• RAM သုံးစွဲမှု: $usedMemoryMb MB / $totalMemoryMb MB (Max: $maxMemoryMb MB)")
        appendLine("• အင်တာနက်/Live ဆက်သွယ်မှု: ${if (isOnline) "🟢 အသင့် (Online)" else "🔴 ပြတ်တောက် (Offline)"}")
        appendLine("• Active Threads: $activeThreads")
        append("• Audio Engine: ${if (isAudioActive) "ဖွင့်ထားသည်" else "ငြိမ်နေသည် (Low-power)"}")
    }
}

/**
 * Health Watchdog & Self-Healing Monitor:
 * - Traps unhandled JVM thread exceptions, prevents app crash, and resets state safely.
 * - Monitors WebSocket and network liveness with exponential backoff auto-recovery.
 * - Tracks memory usage and runtime health diagnostics.
 */
class HealthWatchdog(
    private val isOnlineProvider: () -> Boolean,
    private val onAutoReconnect: () -> Unit,
    private val onSafeStateReset: (String) -> Unit,
    /**
     * Invoked on every 10s liveness tick with the current online value so
     * callers (DesktopRuntime.sessionHealthy) can expose session health to
     * the UI without polling the session themselves.
     */
    private val onLivenessTick: ((Boolean) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startTimeMillis = System.currentTimeMillis()
    @Volatile private var consecutiveFailures = 0
    @Volatile private var isRunning = false
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    private var installedUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        if (isRunning) return
        isRunning = true

        // 1. Global Uncaught Exception Handler
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousHandler = previousUncaughtHandler
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            DesktopLogger.error("HealthWatchdog caught unhandled exception on [${thread.name}]", throwable)
            recordCrashLog(thread.name, throwable)
            onSafeStateReset("စနစ်တွင် မမျှော်လင့်သော error တစ်ခုဖြစ်ပေါ်ခဲ့သဖြင့် safe state သို့ အလိုအလျောက် ပြန်လည်နိုးထစေခဲ့ပါသည်")
            // Delegate if needed, without crashing the process
            if (throwable !is RuntimeException && throwable !is Exception) {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
        installedUncaughtHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)

        // 2. Network & Liveness Heartbeat Watchdog
        scope.launch {
            while (isActive) {
                delay(10_000) // check every 10 seconds
                // M1: an exception thrown by the provider itself is a bug in the
                // check, NOT evidence of lost connectivity — never treat it as
                // offline and never let it kill the watchdog loop.
                val online = try {
                    isOnlineProvider()
                } catch (t: Throwable) {
                    DesktopLogger.error(
                        "HealthWatchdog: online check threw ${t::class.simpleName}; treating as unknown (not offline)",
                        t
                    )
                    true
                }
                if (!online) {
                    consecutiveFailures++
                    val backoffDelay = calculateBackoffDelay(consecutiveFailures)
                    DesktopLogger.info("HealthWatchdog: Connection offline (attempt #$consecutiveFailures). Retrying in ${backoffDelay / 1000}s...")
                    delay(backoffDelay)
                    onAutoReconnect()
                } else {
                    consecutiveFailures = 0
                }
                runCatching { onLivenessTick?.invoke(online) }
            }
        }
        DesktopLogger.info("HealthWatchdog installed and monitoring.")
    }

    /**
     * Stops the heartbeat and restores the uncaught exception handler that was
     * in place before [install] (M4). Safe to call when not installed.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { scope.cancel("HealthWatchdog stopped") }
        if (Thread.getDefaultUncaughtExceptionHandler() === installedUncaughtHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
        }
        installedUncaughtHandler = null
        previousUncaughtHandler = null
        DesktopLogger.info("HealthWatchdog stopped.")
    }

    fun getHealthReport(isAudioActive: Boolean): AppHealthReport {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory() / (1024 * 1024)
        val freeMem = runtime.freeMemory() / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        val usedMem = totalMem - freeMem
        val uptime = (System.currentTimeMillis() - startTimeMillis) / 1000

        return AppHealthReport(
            uptimeSeconds = uptime,
            usedMemoryMb = usedMem,
            totalMemoryMb = totalMem,
            maxMemoryMb = maxMem,
            isOnline = isOnlineProvider(),
            activeThreads = Thread.activeCount(),
            isAudioActive = isAudioActive
        )
    }

    private fun calculateBackoffDelay(failures: Int): Long {
        return when (failures) {
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            4 -> 16_000L
            else -> 30_000L
        }
    }

    private fun recordCrashLog(threadName: String, throwable: Throwable) {
        runCatching {
            val crashFile = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "VoiceBrainLive/logs/crash.log")
            crashFile.parentFile?.mkdirs()
            val entry = buildString {
                appendLine("==================================================")
                appendLine("Timestamp: ${Instant.now()}")
                appendLine("Thread: $threadName")
                appendLine("Exception: ${throwable::class.qualifiedName}: ${throwable.message}")
                appendLine("Stack Trace:")
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                throwable.printStackTrace(pw)
                appendLine(sw.toString().take(2000))
                appendLine("==================================================")
            }
            crashFile.appendText(entry)
        }
    }
}
