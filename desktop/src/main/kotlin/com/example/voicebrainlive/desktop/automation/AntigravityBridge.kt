package com.example.voicebrainlive.desktop.automation

import java.io.File

/**
 * Automates launching Antigravity, Android Studio, or VS Code workspace with target task contexts.
 */
class AntigravityBridge {

    /**
     * Launches Antigravity or the default IDE for the specified workspace.
     */
    fun openProjectInEditor(projectDir: File, preferAntigravity: Boolean = true): ProcessResult {
        if (!projectDir.exists()) {
            return ProcessResult(false, "Project directory not found: ${projectDir.absolutePath}")
        }

        return try {
            // Check for Antigravity or VS Code / Android Studio
            val command = if (preferAntigravity) {
                // Try Antigravity or code command
                listOf("powershell.exe", "-NoProfile", "-Command", "Start-Process 'agy' -ArgumentList '${projectDir.absolutePath}' -ErrorAction SilentlyContinue; if (!\$?) { Start-Process 'code' -ArgumentList '${projectDir.absolutePath}' }")
            } else {
                listOf("powershell.exe", "-NoProfile", "-Command", "Start-Process 'studio64.exe' -ArgumentList '${projectDir.absolutePath}' -ErrorAction SilentlyContinue; if (!\$?) { Start-Process 'code' -ArgumentList '${projectDir.absolutePath}' }")
            }

            val process = ProcessBuilder(command).start()
            ProcessResult(true, "Project '${projectDir.name}' opened in workspace editor.")
        } catch (e: Exception) {
            ProcessResult(false, "Failed to launch editor: ${e.message}")
        }
    }

    /**
     * Saves task prompt file in project workspace so Coding Agent / Antigravity can immediately pick it up.
     */
    fun dispatchTaskToWorkspace(projectDir: File, promptContent: String): File {
        val taskFile = File(projectDir, "AGENT_TASK.md")
        taskFile.writeText(promptContent, Charsets.UTF_8)
        return taskFile
    }
}

data class ProcessResult(
    val success: Boolean,
    val message: String,
    val output: String = ""
)
