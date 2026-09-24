package com.example.voicebrainlive.desktop.automation

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Automates Git workflow:
 * Creates fix branches, stages changes, commits verified fixes, and generates diff summaries.
 */
class GitAutomationService {

    data class GitOperationResult(
        val success: Boolean,
        val branchName: String?,
        val message: String,
        val diffSummary: String = ""
    )

    /**
     * Checks if the directory is a git repository and has modified files.
     */
    fun hasUncommittedChanges(projectDir: File): Boolean {
        val status = runGitCommand(projectDir, listOf("status", "-s"))
        return status.isNotBlank()
    }

    /**
     * Creates a fix branch and commits all staged changes.
     */
    fun createBranchAndCommit(
        projectDir: File,
        commitMessage: String,
        branchPrefix: String = "fix/auto"
    ): GitOperationResult {
        if (!File(projectDir, ".git").exists()) {
            return GitOperationResult(
                success = false,
                branchName = null,
                message = "Project directory is not a Git repository: ${projectDir.absolutePath}"
            )
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val branchName = "$branchPrefix-$timestamp"

        // 1. Create and switch to new branch
        val checkoutResult = runGitCommand(projectDir, listOf("checkout", "-b", branchName))

        // 2. Stage all modified files
        runGitCommand(projectDir, listOf("add", "-A"))

        // 3. Commit changes
        val safeMessage = commitMessage.ifBlank { "Auto-fix applied and verified via VoiceBrainLive" }
        val commitResult = runGitCommand(projectDir, listOf("commit", "-m", safeMessage))

        // 4. Get diff summary
        val diffStat = runGitCommand(projectDir, listOf("show", "--stat", "--oneline", "HEAD"))

        return if (commitResult.contains("error", ignoreCase = true) && !commitResult.contains("create mode", ignoreCase = true)) {
            GitOperationResult(
                success = false,
                branchName = branchName,
                message = "Git commit error: $commitResult"
            )
        } else {
            GitOperationResult(
                success = true,
                branchName = branchName,
                message = "Git branch '$branchName' တွင် ပြင်ဆင်မှုများကို အောင်မြင်စွာ Commit ထိုးလိုက်ပါပြီ။",
                diffSummary = diffStat
            )
        }
    }

    /**
     * Returns current git status summary.
     */
    fun getStatusSummary(projectDir: File): String {
        return runGitCommand(projectDir, listOf("status", "-s"))
    }

    private fun runGitCommand(projectDir: File, args: List<String>): String {
        return try {
            val command = mutableListOf("git")
            command.addAll(args)
            val process = ProcessBuilder(command)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(10, TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            "Git command failed: ${e.message}"
        }
    }
}
