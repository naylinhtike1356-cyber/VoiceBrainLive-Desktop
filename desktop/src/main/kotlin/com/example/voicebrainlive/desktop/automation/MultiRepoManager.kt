package com.example.voicebrainlive.desktop.automation

import java.io.File
import java.util.concurrent.TimeUnit

data class GitRepoInfo(
    val name: String,
    val path: String,
    val currentBranch: String,
    val hasUncommittedChanges: Boolean,
    val uncommittedFilesCount: Int,
    val lastCommitMessage: String? = null,
    val remoteUrl: String? = null
)

/**
 * Discovers and manages multiple Git repositories across development workspaces.
 */
class MultiRepoManager(
    private val projectResolver: ProjectResolver = ProjectResolver()
) {

    /**
     * Scans and returns info for all Git repositories across the search roots.
     */
    fun scanAllRepositories(): List<GitRepoInfo> {
        val projects = projectResolver.listProjects()
        val repos = mutableListOf<GitRepoInfo>()

        for (dir in projects) {
            val gitFolder = File(dir, ".git")
            if (gitFolder.exists()) {
                val info = inspectRepository(dir)
                if (info != null) repos.add(info)
            }
        }
        return repos
    }

    /**
     * Inspects a specific repository directory.
     */
    fun inspectRepository(projectDir: File): GitRepoInfo? {
        if (!File(projectDir, ".git").exists()) return null

        val branch = runGitCommand(projectDir, listOf("rev-parse", "--abbrev-ref", "HEAD")).ifBlank { "main" }
        val statusLines = runGitCommand(projectDir, listOf("status", "--porcelain"))
            .lines()
            .filter { it.isNotBlank() }

        val lastCommit = runGitCommand(projectDir, listOf("log", "-1", "--pretty=format:%s (%cr)"))
        val remoteUrl = runGitCommand(projectDir, listOf("config", "--get", "remote.origin.url"))

        return GitRepoInfo(
            name = projectDir.name,
            path = projectDir.absolutePath,
            currentBranch = branch,
            hasUncommittedChanges = statusLines.isNotEmpty(),
            uncommittedFilesCount = statusLines.size,
            lastCommitMessage = lastCommit.takeIf { it.isNotBlank() },
            remoteUrl = remoteUrl.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Generates a concise Burmese status report for all discovered repositories.
     */
    fun generateMultiRepoReport(): String {
        val repos = scanAllRepositories()
        if (repos.isEmpty()) {
            return "Git Repository တစ်ခုမျှ ရှာမတွေ့ပါရှင်။"
        }

        return buildString {
            appendLine("🔍 တွေ့ရှိသော Git Repositories (${repos.size} ခု):")
            for (r in repos) {
                val dirtyStatus = if (r.hasUncommittedChanges) "⚠️ Uncommitted (${r.uncommittedFilesCount} files)" else "✓ Clean"
                appendLine("• **${r.name}** [Branch: `${r.currentBranch}` | $dirtyStatus]")
                if (!r.lastCommitMessage.isNullOrBlank()) {
                    appendLine("  - Last Commit: ${r.lastCommitMessage}")
                }
            }
        }.trim()
    }

    /**
     * Switches branch for a specific project.
     */
    fun switchBranch(projectNameQuery: String, targetBranch: String): ProcessResult {
        val projectDir = projectResolver.resolveProjectDirectory(projectNameQuery)
            ?: return ProcessResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")

        if (!File(projectDir, ".git").exists()) {
            return ProcessResult(false, "'${projectDir.name}' သည် Git repo မဟုတ်ပါရှင်။")
        }

        val output = runGitCommand(projectDir, listOf("checkout", targetBranch.trim()))
        return if (output.contains("Switched to", ignoreCase = true) || output.contains("Already on", ignoreCase = true)) {
            ProcessResult(true, "Project '${projectDir.name}' ၏ Branch အား '$targetBranch' သို့ ပြောင်းလဲလိုက်ပါပြီရှင်။")
        } else {
            ProcessResult(false, "Branch ပြောင်းမရပါ: $output")
        }
    }

    /**
     * Pulls latest changes from remote origin.
     */
    fun pullRepo(projectNameQuery: String): ProcessResult {
        val projectDir = projectResolver.resolveProjectDirectory(projectNameQuery)
            ?: return ProcessResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")

        val output = runGitCommand(projectDir, listOf("pull"))
        return if (output.contains("Already up to date", ignoreCase = true) || output.contains("Updating", ignoreCase = true)) {
            ProcessResult(true, "Project '${projectDir.name}' တွင် Remote မှ နောက်ဆုံးကုဒ်များကို Pull လုပ်ပြီးပါပြီ ($output)။")
        } else {
            ProcessResult(false, "Git pull မအောင်မြင်ပါ: $output")
        }
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
            process.waitFor(8, TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            ""
        }
    }
}
