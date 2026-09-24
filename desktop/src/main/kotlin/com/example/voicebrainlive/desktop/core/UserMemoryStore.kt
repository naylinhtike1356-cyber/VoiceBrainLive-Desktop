package com.example.voicebrainlive.desktop.core

import org.json.JSONObject
import java.io.File

/**
 * Persistent memory manager for VoiceBrainLive Desktop (~/.voicebrainlive/user_memory.json).
 * Stores user facts, preferences, custom instructions, and personal assistant memories.
 */
class UserMemoryStore(
    private val memoryFile: File = File(System.getProperty("user.home"), ".voicebrainlive/user_memory.json"),
) {
    private val memoryData = HashMap<String, String>()

    init {
        loadMemories()
    }

    @Synchronized
    fun rememberFact(key: String, value: String) {
        val cleanKey = key.lowercase().replace(" ", "_").trim()
        val cleanVal = value.trim()
        if (cleanKey.isNotBlank() && cleanVal.isNotBlank()) {
            memoryData[cleanKey] = cleanVal
            saveMemories()
        }
    }

    @Synchronized
    fun forgetFact(key: String): Boolean {
        val cleanKey = key.lowercase().replace(" ", "_").trim()
        val removed = memoryData.remove(cleanKey) != null
        if (removed) saveMemories()
        return removed
    }

    @Synchronized
    fun getFact(key: String): String? {
        val cleanKey = key.lowercase().replace(" ", "_").trim()
        return memoryData[cleanKey]
    }

    @Synchronized
    fun getAllMemories(): Map<String, String> {
        return HashMap(memoryData)
    }

    @Synchronized
    fun toSystemInstructionContext(): String {
        if (memoryData.isEmpty()) return ""
        val entries = memoryData.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return "User Personal Memory & Facts (use when relevant): $entries"
    }

    @Synchronized
    private fun loadMemories() {
        runCatching {
            if (memoryFile.exists()) {
                val json = JSONObject(memoryFile.readText(Charsets.UTF_8))
                json.keys().forEach { key ->
                    memoryData[key] = json.optString(key, "")
                }
            }
        }
    }

    @Synchronized
    private fun saveMemories() {
        runCatching {
            memoryFile.parentFile?.mkdirs()
            val json = JSONObject()
            memoryData.forEach { (k, v) -> json.put(k, v) }
            memoryFile.writeText(json.toString(2), Charsets.UTF_8)
        }
    }
}
