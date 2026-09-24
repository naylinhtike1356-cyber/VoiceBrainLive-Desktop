package com.example.voicebrainlive.desktop.platform

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ActiveWindowInfo(
    val processName: String = "",
    val windowTitle: String = "",
    val appCategory: String = "Unknown",
    val inferredProjectOrFile: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun isAvailable(): Boolean = processName.isNotBlank() || windowTitle.isNotBlank()

    fun toPromptContext(): String {
        if (!isAvailable()) return ""
        val categoryPart = if (appCategory != "Unknown") " ($appCategory)" else ""
        val projectPart = if (!inferredProjectOrFile.isNullOrBlank()) " | Project/File: $inferredProjectOrFile" else ""
        return "[Active Foreground App: $processName$categoryPart | Window Title: '$windowTitle'$projectPart]"
    }

    fun toBurmeseSummary(): String {
        if (!isAvailable()) return "လက်ရှိ အသုံးပြုနေသော App ကို စစ်ဆေး၍ မရသေးပါရှင်။"
        val projectInfo = if (!inferredProjectOrFile.isNullOrBlank()) "\n• လုပ်ဆောင်နေသော ဖိုင်/ပရောဂျက်: $inferredProjectOrFile" else ""
        return buildString {
            appendLine("လက်ရှိ အသုံးပြုနေသော Window:")
            appendLine("• App အမည်: $processName ($appCategory)")
            if (windowTitle.isNotBlank()) appendLine("• Window Title: $windowTitle")
            if (projectInfo.isNotBlank()) append(projectInfo)
        }.trim()
    }
}

/**
 * Tracks the current active foreground window on Windows with cached TTL
 * to provide real-time context awareness to Gemini AI assistant.
 */
class ActiveWindowTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = AtomicReference(ActiveWindowInfo())
    @Volatile private var lastFetchTime = 0L

    init {
        // Start background lightweight periodic tracker (refreshes every 2 seconds)
        scope.launch {
            while (isActive) {
                runCatching {
                    val info = fetchActiveWindowDirect()
                    if (info.isAvailable()) {
                        cache.set(info)
                        lastFetchTime = System.currentTimeMillis()
                    }
                }
                delay(2000)
            }
        }
    }

    fun getActiveWindow(): ActiveWindowInfo {
        val current = cache.get()
        if (System.currentTimeMillis() - lastFetchTime < 3000 && current.isAvailable()) {
            return current
        }
        // If cache is stale, refresh in background and return current
        scope.launch {
            val fresh = fetchActiveWindowDirect()
            if (fresh.isAvailable()) {
                cache.set(fresh)
                lastFetchTime = System.currentTimeMillis()
            }
        }
        return current
    }

    private fun fetchActiveWindowDirect(): ActiveWindowInfo {
        return runCatching {
            val script = "Add-Type @'\nusing System;\nusing System.Runtime.InteropServices;\nusing System.Text;\npublic class WinFocus {\n    [DllImport(\"user32.dll\")]\n    public static extern IntPtr GetForegroundWindow();\n    [DllImport(\"user32.dll\", CharSet = CharSet.Auto, SetLastError = true)]\n    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);\n    [DllImport(\"user32.dll\", SetLastError = true)]\n    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);\n\n    public static string GetInfo() {\n        IntPtr handle = GetForegroundWindow();\n        if (handle == IntPtr.Zero) return \"|\";\n        StringBuilder buff = new StringBuilder(512);\n        GetWindowText(handle, buff, 512);\n        uint pid = 0;\n        GetWindowThreadProcessId(handle, out pid);\n        string pName = \"\";\n        try {\n            pName = System.Diagnostics.Process.GetProcessById((int)pid).ProcessName;\n        } catch {}\n        return pName + \"|\" + buff.ToString();\n    }\n}\n'@ -ErrorAction SilentlyContinue; [WinFocus]::GetInfo()"

            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runCatching fallbackProcessScan()
            }

            val line = proc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
            if (!line.contains("|")) {
                return@runCatching fallbackProcessScan()
            }

            val parts = line.split("|", limit = 2)
            val procName = parts.getOrNull(0)?.trim().orEmpty()
            val winTitle = parts.getOrNull(1)?.trim().orEmpty()

            if (procName.isBlank() && winTitle.isBlank()) {
                return@runCatching fallbackProcessScan()
            }

            val category = categorizeApp(procName, winTitle)
            val inferred = inferProjectOrFile(procName, winTitle)

            ActiveWindowInfo(
                processName = procName,
                windowTitle = winTitle,
                appCategory = category,
                inferredProjectOrFile = inferred,
                timestamp = System.currentTimeMillis()
            )
        }.getOrElse {
            fallbackProcessScan()
        }
    }

    private fun fallbackProcessScan(): ActiveWindowInfo {
        return runCatching {
            val script = "Get-Process | Where-Object { \$_.MainWindowHandle -ne 0 -and \$_.MainWindowTitle -ne '' } | Select-Object -First 1 | ForEach-Object { \$_.ProcessName + '|' + \$_.MainWindowTitle }"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).start()
            proc.waitFor(2, TimeUnit.SECONDS)
            val line = proc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
            if (line.contains("|")) {
                val parts = line.split("|", limit = 2)
                val procName = parts.getOrNull(0)?.trim().orEmpty()
                val winTitle = parts.getOrNull(1)?.trim().orEmpty()
                ActiveWindowInfo(
                    processName = procName,
                    windowTitle = winTitle,
                    appCategory = categorizeApp(procName, winTitle),
                    inferredProjectOrFile = inferProjectOrFile(procName, winTitle),
                    timestamp = System.currentTimeMillis()
                )
            } else {
                ActiveWindowInfo()
            }
        }.getOrDefault(ActiveWindowInfo())
    }

    private fun categorizeApp(proc: String, title: String): String {
        val p = proc.lowercase()
        val t = title.lowercase()
        return when {
            p in listOf("code", "idea64", "studio64", "pycharm64", "cursor", "devenv", "sublime_text", "notepad++") ||
                t.contains("visual studio") || t.contains("android studio") || t.contains("intellij") -> "Code Editor / IDE"

            p in listOf("chrome", "msedge", "firefox", "brave", "opera", "vivaldi") -> "Web Browser"

            p in listOf("wt", "powershell", "cmd", "conhost", "windowsterminal", "bash") -> "Terminal / Console"

            p in listOf("notion", "obsidian", "onenote", "evernote") -> "Notes & Workspace"

            p in listOf("winword", "excel", "powerpnt", "notepad", "wordpad", "acrobat") -> "Document & Office"

            p in listOf("telegram", "discord", "whatsapp", "slack", "ms-teams", "zoom", "viber") -> "Communication"

            p in listOf("spotify", "vlc", "wmplayer", "obs64", "capcut", "premiere") -> "Media & Video"

            p in listOf("photoshop", "illustrator", "figma", "canva") -> "Design & Graphics"

            p in listOf("explorer", "taskmgr", "settings", "control") -> "Windows System / Files"

            else -> "Application"
        }
    }

    private fun inferProjectOrFile(proc: String, title: String): String? {
        val p = proc.lowercase()
        if (p in listOf("code", "cursor")) {
            // VS Code title format: "filename.kt - ProjectName - Visual Studio Code" or "ProjectName - Visual Studio Code"
            val parts = title.split(" - ")
            if (parts.size >= 2) return parts.dropLast(1).joinToString(" - ")
        }
        if (p in listOf("studio64", "idea64", "pycharm64")) {
            // Android Studio / IntelliJ: "ProjectName [path] - ... - Android Studio"
            val match = Regex("\\[([^\\]]+)\\]").find(title)
            if (match != null) return match.value
            val first = title.split(" – ", " - ").firstOrNull()
            if (!first.isNullOrBlank()) return first
        }
        if (p in listOf("notepad", "notepad++", "winword", "excel")) {
            val filePart = title.split(" - ").firstOrNull()
            if (!filePart.isNullOrBlank()) return filePart
        }
        return null
    }
}
