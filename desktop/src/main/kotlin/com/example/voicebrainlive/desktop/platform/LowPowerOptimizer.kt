package com.example.voicebrainlive.desktop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class PowerMode {
    ACTIVE,          // Window focused, user active, standard 2s polling
    THROTTLED_IDLE   // Minimized to tray or inactive > 2 mins, 30s polling, trimmed working set
}

/**
 * Low-Power Idle Throttling & RAM Working Set Optimizer:
 * Reduces CPU and battery drain when running in background or idle,
 * and compacts RAM consumption by freeing unused JVM heap and working set pages.
 */
class LowPowerOptimizer(
    private val onThrottleStateChanged: (PowerMode) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _powerMode = MutableStateFlow(PowerMode.ACTIVE)
    val powerMode: StateFlow<PowerMode> = _powerMode.asStateFlow()

    private val lastActivityTime = AtomicLong(System.currentTimeMillis())
    @Volatile private var isWindowVisible = true
    private val IDLE_THRESHOLD_MS = 2 * 60 * 1000L // 2 minutes

    init {
        startIdleMonitor()
    }

    private fun startIdleMonitor() {
        scope.launch {
            while (isActive) {
                delay(15_000) // check idle every 15 seconds
                val now = System.currentTimeMillis()
                val idleDuration = now - lastActivityTime.get()

                if (!isWindowVisible || idleDuration > IDLE_THRESHOLD_MS) {
                    if (_powerMode.value != PowerMode.THROTTLED_IDLE) {
                        enterThrottledMode()
                    }
                }
            }
        }
    }

    fun onUserActivity() {
        lastActivityTime.set(System.currentTimeMillis())
        if (_powerMode.value == PowerMode.THROTTLED_IDLE) {
            wakeUpToActive()
        }
    }

    fun onWindowVisibilityChanged(visible: Boolean) {
        isWindowVisible = visible
        if (visible) {
            onUserActivity()
        } else {
            // Minimized to tray -> enter low power immediately after short grace period
            scope.launch {
                delay(3000)
                if (!isWindowVisible && _powerMode.value != PowerMode.THROTTLED_IDLE) {
                    enterThrottledMode()
                }
            }
        }
    }

    private fun enterThrottledMode() {
        _powerMode.value = PowerMode.THROTTLED_IDLE
        onThrottleStateChanged(PowerMode.THROTTLED_IDLE)
        DesktopLogger.info("LowPowerOptimizer: Entered THROTTLED_IDLE mode. Trimming working set & slowing polling.")
        trimMemoryWorkingSet()
    }

    private fun wakeUpToActive() {
        _powerMode.value = PowerMode.ACTIVE
        onThrottleStateChanged(PowerMode.ACTIVE)
        DesktopLogger.info("LowPowerOptimizer: Resumed ACTIVE mode. Polling & responsiveness restored.")
    }

    /**
     * Executes Garbage Collection and Windows EmptyWorkingSet API to trim RAM working set.
     * Returns the memory reclaimed in Megabytes.
     */
    fun trimMemoryWorkingSet(): Long {
        return runCatching {
            val runtime = Runtime.getRuntime()
            val memBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

            // 1. JVM Garbage Collection
            System.gc()
            System.runFinalization()

            // 2. Windows OS Working Set Trim via PowerShell call
            val trimScript = "Add-Type @'\nusing System;\nusing System.Runtime.InteropServices;\npublic class WinMem {\n    [DllImport(\"psapi.dll\")]\n    public static extern int EmptyWorkingSet(IntPtr hwProc);\n}\n'@ -ErrorAction SilentlyContinue; [WinMem]::EmptyWorkingSet([System.Diagnostics.Process]::GetCurrentProcess().Handle)"
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", trimScript)
                .start()
                .waitFor(2, TimeUnit.SECONDS)

            val memAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val reclaimed = (memBefore - memAfter).coerceAtLeast(0)
            DesktopLogger.info("LowPowerOptimizer: Memory trimmed. Reclaimed ~$reclaimed MB.")
            reclaimed
        }.getOrDefault(0L)
    }
}
