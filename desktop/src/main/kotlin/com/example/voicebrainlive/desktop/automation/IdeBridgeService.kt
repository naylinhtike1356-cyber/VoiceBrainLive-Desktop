package com.example.voicebrainlive.desktop.automation

import java.io.File
import java.util.concurrent.TimeUnit

data class IdeStatus(
    val isVsCodeRunning: Boolean,
    val isAndroidStudioRunning: Boolean,
    val isAntigravityAvailable: Boolean,
    val activeProjectName: String?,
    val activeFilePath: String?,
) {
    fun toBurmeseSummary(): String = buildString {
        appendLine("IDE / Code Editor အခြေအနေ:")
        appendLine("• VS Code: ${if (isVsCodeRunning) "🟢 Running" else "⚪ Not Running"}")
        appendLine("• Android Studio: ${if (isAndroidStudioRunning) "🟢 Running" else "⚪ Not Running"}")
        appendLine("• Antigravity Agent: ${if (isAntigravityAvailable) "🟢 Available" else "⚪ Not Configured"}")
        if (!activeProjectName.isNullOrBlank()) appendLine("• Active Workspace: $activeProjectName")
        if (!activeFilePath.isNullOrBlank()) appendLine("• Active File: $activeFilePath")
    }.trim()
}

/**
 * Local IDE Bridge service for interacting with Android Studio, VS Code, and Antigravity:
 * Handles opening files at line numbers, dispatching refactoring tasks, and running workspace tasks.
 */
class IdeBridgeService(
    private val projectResolver: ProjectResolver = ProjectResolver()
) {

    /**
     * Opens a specific file (and optional line number) in the preferred editor.
     */
    fun openFileInEditor(
        filePath: String,
        lineNumber: Int = 1,
        preferEditor: String = "auto"
    ): ProcessResult {
        val file = File(filePath.trim())
        if (!file.exists()) {
            return ProcessResult(false, "ဖိုင် ရှာမတွေ့ပါ: ${file.absolutePath}")
        }

        return try {
            val cmd = when (preferEditor.lowercase()) {
                "studio", "androidstudio", "android studio" -> {
                    listOf("studio64.exe", "--line", lineNumber.toString(), file.absolutePath)
                }
                "vscode", "code" -> {
                    listOf("code.cmd", "-g", "${file.absolutePath}:$lineNumber")
                }
                else -> {
                    // Auto-detect: VS Code first, then default system editor
                    listOf("cmd.exe", "/c", "code", "-g", "${file.absolutePath}:$lineNumber")
                }
            }

            ProcessBuilder(cmd).start()
            ProcessResult(true, "'${file.name}' ကို Line $lineNumber ဖြင့် Editor တွင် ဖွင့်လိုက်ပါပြီရှင်။")
        } catch (e: Exception) {
            // Fallback to Desktop open
            runCatching {
                java.awt.Desktop.getDesktop().open(file)
                ProcessResult(true, "'${file.name}' ကို Default Editor ဖြင့် ဖွင့်လိုက်ပါပြီရှင်။")
            }.getOrElse {
                ProcessResult(false, "Editor ဖွင့်ရာတွင် အခက်အခဲရှိပါသည်: ${e.message}")
            }
        }
    }

    /**
     * Opens an entire project folder in the specified or auto-detected IDE (VS Code or Android Studio).
     */
    fun openProject(projectNameOrPath: String, preferEditor: String = "auto"): ProcessResult {
        val targetDir = projectResolver.resolveProjectDirectory(projectNameOrPath)
            ?: File(projectNameOrPath).takeIf { it.exists() && it.isDirectory }
            ?: return ProcessResult(false, "'$projectNameOrPath' ပရောဂျက်ကို ရှာမတွေ့ပါ။")

        return try {
            val cmd = when (preferEditor.lowercase()) {
                "studio", "androidstudio", "android studio" -> {
                    listOf("studio64.exe", targetDir.absolutePath)
                }
                "vscode", "code" -> {
                    listOf("cmd.exe", "/c", "code", targetDir.absolutePath)
                }
                else -> {
                    val hasAndroidApp = File(targetDir, "app").exists() || File(targetDir, "settings.gradle.kts").exists()
                    if (hasAndroidApp && isProcessRunning("studio64")) {
                        listOf("studio64.exe", targetDir.absolutePath)
                    } else {
                        listOf("cmd.exe", "/c", "code", targetDir.absolutePath)
                    }
                }
            }

            ProcessBuilder(cmd).start()
            val editorName = if (cmd.any { it.contains("studio", ignoreCase = true) }) "Android Studio" else "VS Code"
            ProcessResult(true, "Project '${targetDir.name}' ကို $editorName တွင် ဖွင့်လှစ်လိုက်ပါပြီရှင်။")
        } catch (e: Exception) {
            ProcessResult(false, "Project ဖွင့်ရာတွင် အခက်အခဲရှိပါသည်: ${e.message}")
        }
    }

    /**
     * Checks currently running IDE processes and workspaces.
     */
    fun getIdeStatus(activeProcessName: String? = null, activeWindowTitle: String? = null): IdeStatus {
        val vsCodeRunning = isProcessRunning("Code") || activeProcessName?.equals("code", ignoreCase = true) == true
        val studioRunning = isProcessRunning("studio64") || activeProcessName?.equals("studio64", ignoreCase = true) == true
        val antigravityFound = runCatching {
            val proc = ProcessBuilder("where.exe", "agy").start()
            proc.waitFor(2, TimeUnit.SECONDS)
            proc.exitValue() == 0
        }.getOrDefault(false)

        var projectName: String? = null
        var activeFile: String? = null

        if (!activeWindowTitle.isNullOrBlank()) {
            if (activeWindowTitle.contains("Visual Studio Code")) {
                val parts = activeWindowTitle.split(" - ")
                if (parts.size >= 2) {
                    activeFile = parts.firstOrNull()
                    projectName = parts.getOrNull(parts.size - 2)
                }
            } else if (activeWindowTitle.contains("Android Studio")) {
                val match = Regex("\\[([^\\]]+)\\]").find(activeWindowTitle)
                projectName = match?.groupValues?.getOrNull(1) ?: activeWindowTitle.split(" – ", " - ").firstOrNull()
            }
        }

        return IdeStatus(
            isVsCodeRunning = vsCodeRunning,
            isAndroidStudioRunning = studioRunning,
            isAntigravityAvailable = antigravityFound,
            activeProjectName = projectName,
            activeFilePath = activeFile,
        )
    }

    private fun isProcessRunning(processName: String): Boolean {
        return runCatching {
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Get-Process -Name '$processName' -ErrorAction SilentlyContinue").start()
            proc.waitFor(2, TimeUnit.SECONDS)
            proc.exitValue() == 0
        }.getOrDefault(false)
    }
}
