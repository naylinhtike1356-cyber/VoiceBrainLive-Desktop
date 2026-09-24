package com.example.voicebrainlive.desktop.automation

import java.io.File

/**
 * Resolves spoken or natural language project names to absolute filesystem paths.
 */
class ProjectResolver(
    private val searchRoots: List<File> = defaultSearchRoots()
) {
    companion object {
        fun defaultSearchRoots(): List<File> {
            val userHome = System.getProperty("user.home") ?: "C:\\Users\\nayli"
            return listOfNotNull(
                File(userHome, "AndroidStudioProjects"),
                File(userHome, "Projects"),
                File(userHome, "IdeaProjects"),
                File(userHome, "Desktop"),
                File(userHome, "source\\repos")
            ).filter { it.exists() && it.isDirectory }
        }
    }

    /**
     * Resolves a query (e.g., "VoiceBrainLive", "VoiceBrainLive-Desktop", "my app") to an absolute File directory.
     */
    fun resolveProjectDirectory(projectNameQuery: String): File? {
        val cleanQuery = projectNameQuery.trim().lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")

        if (cleanQuery.isBlank()) return null

        // 1. Direct path check if full path passed
        val directFile = File(projectNameQuery.trim())
        if (directFile.exists() && directFile.isDirectory) {
            return directFile
        }

        // 2. Scan search roots
        for (root in searchRoots) {
            val children = root.listFiles { f -> f.isDirectory } ?: continue
            for (child in children) {
                val normalizedName = child.name.lowercase()
                    .replace(" ", "")
                    .replace("-", "")
                    .replace("_", "")
                if (normalizedName == cleanQuery || normalizedName.contains(cleanQuery) || cleanQuery.contains(normalizedName)) {
                    return child
                }
            }
        }

        return null
    }

    /**
     * Lists all discoverable projects across the search roots.
     */
    fun listProjects(): List<File> {
        val results = mutableListOf<File>()
        for (root in searchRoots) {
            val children = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: continue
            results.addAll(children)
        }
        return results
    }
}
