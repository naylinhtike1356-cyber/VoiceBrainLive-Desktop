package com.example.voicebrainlive.desktop.automation

import java.io.File

/**
 * Formats user problem reports into structured technical prompts suitable for AI Coding Agents.
 */
class CodingTaskFormatter {

    /**
     * Converts a raw problem description into a well-structured prompt for Antigravity / Coding Agent.
     */
    fun formatTaskPrompt(
        projectName: String,
        projectDirectory: File,
        problemDescription: String,
        targetFiles: List<String> = emptyList()
    ): String {
        val promptBuilder = StringBuilder()

        promptBuilder.appendLine("## Autonomous Bug Fix & Task Request")
        promptBuilder.appendLine("- **Target Project**: `${projectDirectory.name}`")
        promptBuilder.appendLine("- **Workspace Directory**: `${projectDirectory.absolutePath}`")
        promptBuilder.appendLine()
        promptBuilder.appendLine("### Reported Problem / Requirement:")
        promptBuilder.appendLine(problemDescription.trim())
        promptBuilder.appendLine()

        if (targetFiles.isNotEmpty()) {
            promptBuilder.appendLine("### Suspected / Relevant Files:")
            for (file in targetFiles) {
                promptBuilder.appendLine("- `$file`")
            }
            promptBuilder.appendLine()
        }

        promptBuilder.appendLine("### Instructions for Coding Agent:")
        promptBuilder.appendLine("1. **Analyze & Diagnose**: Search the workspace to identify the exact cause of the reported issue.")
        promptBuilder.appendLine("2. **Implement Fix**: Apply minimal, robust code changes following the existing design patterns.")
        promptBuilder.appendLine("3. **Build & Verify**: Execute local tests or build check (e.g., `./gradlew.bat compileKotlin` or `./gradlew.bat test`) to guarantee zero compile errors.")
        promptBuilder.appendLine("4. **Summary**: Provide a clear walkthrough of the changes made.")

        return promptBuilder.toString()
    }
}
