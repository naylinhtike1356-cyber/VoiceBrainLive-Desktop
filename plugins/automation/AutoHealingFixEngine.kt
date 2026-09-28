package com.example.voicebrainlive.desktop.automation

import java.io.File

/**
 * Manages autonomous self-healing fix cycles:
 * Runs builds, captures compiler/runtime errors, synthesizes fix patches, and tracks iteration state.
 */
class AutoHealingFixEngine(
    private val buildPipeline: AndroidBuildPipeline = AndroidBuildPipeline(),
    private val taskFormatter: CodingTaskFormatter = CodingTaskFormatter(),
    private val antigravityBridge: AntigravityBridge = AntigravityBridge(),
) {

    data class HealingIterationResult(
        val success: Boolean,
        val attempts: Int,
        val lastErrorMessage: String?,
        val finalStatusReport: String
    )

    /**
     * Attempts to diagnose and fix project issues with self-healing iterations.
     */
    fun runAutoHealingCycle(
        projectDir: File,
        initialIssue: String,
        maxAttempts: Int = 3
    ): HealingIterationResult {
        if (!projectDir.exists()) {
            return HealingIterationResult(
                success = false,
                attempts = 0,
                lastErrorMessage = "Directory not found",
                finalStatusReport = "Project directory ${projectDir.absolutePath} not found."
            )
        }

        var currentProblem = initialIssue
        var attempt = 0
        var lastError: String? = null

        while (attempt < maxAttempts) {
            attempt++

            // 1. Synthesize targeted prompt with current error context
            val prompt = if (attempt == 1) {
                taskFormatter.formatTaskPrompt(projectDir.name, projectDir, currentProblem)
            } else {
                buildSelfHealingPrompt(projectDir, currentProblem, lastError, attempt)
            }

            // 2. Dispatch task to workspace
            antigravityBridge.dispatchTaskToWorkspace(projectDir, prompt)

            // 3. Run build check
            val checkResult = buildPipeline.runGradleCheck(projectDir, listOf("compileKotlin", "test"))

            if (checkResult.success) {
                val report = "Auto-Healing အောင်မြင်ပါသည် (Pass on attempt $attempt)။ Project '${projectDir.name}' build & tests များအားလုံး အဆင်ပြေသွားပါပြီ။"
                return HealingIterationResult(
                    success = true,
                    attempts = attempt,
                    lastErrorMessage = null,
                    finalStatusReport = report
                )
            } else {
                lastError = checkResult.message
                currentProblem = "Gradle compilation failed with error:\n$lastError"
            }
        }

        val failureReport = "Auto-Healing ကြိုးပမ်းမှု $maxAttempts ကြိမ် ပြည့်သွားသော်လည်း အောက်ပါ Error ကျန်ရှိနေပါသည်:\n${lastError?.takeLast(500)}"
        return HealingIterationResult(
            success = false,
            attempts = attempt,
            lastErrorMessage = lastError,
            finalStatusReport = failureReport
        )
    }

    private fun buildSelfHealingPrompt(
        projectDir: File,
        originalIssue: String,
        buildError: String?,
        iteration: Int
    ): String {
        return """
            ## 🔄 Autonomous Self-Healing Fix Attempt #$iteration
            - **Project**: `${projectDir.name}`
            - **Workspace Directory**: `${projectDir.absolutePath}`

            ### Previous Build Error / Compiler Failure:
            ```
            ${buildError?.take(1500)}
            ```

            ### Original Requirement:
            $originalIssue

            ### Self-Correction Instructions:
            1. Analyze the exact compiler / syntax error above.
            2. Apply surgical fixes to resolve the broken types, missing imports, or runtime exceptions.
            3. Ensure the project builds cleanly with `./gradlew.bat compileKotlin test`.
        """.trimIndent()
    }
}
