package com.example.voicebrainlive.desktop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent neural memory manager for VoiceBrainLive Desktop (~/.voicebrainlive/user_memory.json).
 * Stores structured user facts, preferences, habits, projects, and voice routines.
 * Fully backwards-compatible with key-value access while supporting rich UnifiedMemoryItem models.
 */
class UserMemoryStore(
    private val memoryFile: File = File(System.getProperty("user.home"), ".voicebrainlive/user_memory.json"),
    private val unifiedFile: File = File(System.getProperty("user.home"), ".voicebrainlive/unified_memories.json")
) {
    private val memoryData = HashMap<String, String>()
    private val unifiedItems = ArrayList<UnifiedMemoryItem>()

    init {
        loadMemories()
        seedDefaultsIfEmpty()
    }

    @Synchronized
    fun rememberFact(key: String, value: String, category: MemoryCategory = MemoryCategory.FACT) {
        val cleanKey = key.lowercase().replace(" ", "_").trim()
        val cleanVal = value.trim()
        if (cleanKey.isNotBlank() && cleanVal.isNotBlank()) {
            memoryData[cleanKey] = cleanVal
            val existing = unifiedItems.find { it.rawKey == cleanKey }
            if (existing != null) {
                val updated = existing.copy(
                    content = cleanVal,
                    timestamp = System.currentTimeMillis()
                )
                unifiedItems[unifiedItems.indexOf(existing)] = updated
            } else {
                unifiedItems.add(
                    UnifiedMemoryItem(
                        id = UUID.randomUUID().toString(),
                        category = category,
                        title = formatKeyToTitle(cleanKey),
                        content = cleanVal,
                        timestamp = System.currentTimeMillis(),
                        confidencePercent = 100,
                        source = "user_explicit",
                        rawKey = cleanKey
                    )
                )
            }
            saveMemories()
        }
    }

    @Synchronized
    fun rememberItem(item: UnifiedMemoryItem) {
        val idx = unifiedItems.indexOfFirst { it.id == item.id || (item.rawKey != null && it.rawKey == item.rawKey) }
        if (idx >= 0) {
            unifiedItems[idx] = item
        } else {
            unifiedItems.add(item)
        }
        item.rawKey?.let { memoryData[it] = item.content }
        saveMemories()
    }

    @Synchronized
    fun forgetFact(key: String): Boolean {
        val cleanKey = key.lowercase().replace(" ", "_").trim()
        val removedData = memoryData.remove(cleanKey) != null
        val removedItem = unifiedItems.removeIf { it.rawKey == cleanKey || it.id == key }
        if (removedData || removedItem) {
            saveMemories()
            return true
        }
        return false
    }

    @Synchronized
    fun deleteMemoryItem(id: String): Boolean {
        val removed = unifiedItems.removeIf { it.id == id }
        if (removed) {
            saveMemories()
        }
        return removed
    }

    @Synchronized
    fun getFact(key: String): String? {
        val cleanKey = key.lowercase().replace(" ", "_").trim()
        return memoryData[cleanKey] ?: unifiedItems.find { it.rawKey == cleanKey || it.title.equals(key, ignoreCase = true) }?.content
    }

    @Synchronized
    fun getAllMemories(): Map<String, String> {
        return HashMap(memoryData)
    }

    @Synchronized
    fun getAllUnifiedMemories(): List<UnifiedMemoryItem> {
        return ArrayList(unifiedItems)
    }

    @Synchronized
    fun getMemoriesByCategory(category: MemoryCategory): List<UnifiedMemoryItem> {
        return unifiedItems.filter { it.category == category }
    }

    /**
     * Inspects conversation input text and automatically learns facts, habits, and preferences.
     * Returns any sensitive facts that require explicit confirmation from the user.
     */
    @Synchronized
    fun learnFromConversation(text: String): List<ExtractedFact> {
        val facts = ConversationFactExtractor.extractFacts(text)
        val sensitivePending = mutableListOf<ExtractedFact>()

        facts.forEach { fact ->
            if (fact.requiresConfirmation || fact.isSensitive) {
                sensitivePending.add(fact)
            } else {
                // Automatically save verified fact
                val existing = unifiedItems.find { it.rawKey == fact.key }
                if (existing != null) {
                    val updated = existing.copy(
                        content = fact.value,
                        title = fact.title,
                        timestamp = System.currentTimeMillis(),
                        confidencePercent = fact.confidence
                    )
                    unifiedItems[unifiedItems.indexOf(existing)] = updated
                } else {
                    unifiedItems.add(
                        UnifiedMemoryItem(
                            id = UUID.randomUUID().toString(),
                            category = fact.category,
                            title = fact.title,
                            content = fact.value,
                            timestamp = System.currentTimeMillis(),
                            confidencePercent = fact.confidence,
                            source = "conversation_fact",
                            rawKey = fact.key
                        )
                    )
                }
                memoryData[fact.key] = fact.value
            }
        }

        if (facts.isNotEmpty()) {
            saveMemories()
        }
        return sensitivePending
    }

    @Synchronized
    fun toSystemInstructionContext(): String {
        if (unifiedItems.isEmpty() && memoryData.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("USER PERSONAL MEMORY & NEURAL FACTS (အသုံးပြုသူအကြောင်း မှတ်သားထားရှိမှုများ - လိုအပ်သလို အခြေအနေအရ ထည့်သွင်းသုံးစွဲပါ):")
        
        val facts = unifiedItems.filter { it.category == MemoryCategory.FACT }
        if (facts.isNotEmpty()) {
            sb.appendLine("- Facts: " + facts.joinToString("; ") { "${it.title}: ${it.content}" })
        }
        val prefs = unifiedItems.filter { it.category == MemoryCategory.PREFERENCE }
        if (prefs.isNotEmpty()) {
            sb.appendLine("- Preferences: " + prefs.joinToString("; ") { "${it.title}: ${it.content}" })
        }
        val habits = unifiedItems.filter { it.category == MemoryCategory.HABIT }
        if (habits.isNotEmpty()) {
            sb.appendLine("- Habits: " + habits.joinToString("; ") { "${it.title}: ${it.content}" })
        }
        val projects = unifiedItems.filter { it.category == MemoryCategory.PROJECT }
        if (projects.isNotEmpty()) {
            sb.appendLine("- Projects: " + projects.joinToString("; ") { "${it.title}: ${it.content}" })
        }
        val routines = unifiedItems.filter { it.category == MemoryCategory.ROUTINE }
        if (routines.isNotEmpty()) {
            sb.appendLine("- Routines: " + routines.joinToString("; ") { "${it.title}: ${it.content}" })
        }

        return sb.toString().trim()
    }

    private fun formatKeyToTitle(key: String): String {
        return key.split("_").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }

    private fun seedDefaultsIfEmpty() {
        if (unifiedItems.isEmpty()) {
            unifiedItems.add(
                UnifiedMemoryItem(
                    id = "default_ide",
                    category = MemoryCategory.PREFERENCE,
                    title = "Default IDE",
                    content = "Android Studio & VS Code",
                    confidencePercent = 100,
                    source = "default",
                    rawKey = "preferred_ide"
                )
            )
            unifiedItems.add(
                UnifiedMemoryItem(
                    id = "default_browser",
                    category = MemoryCategory.PREFERENCE,
                    title = "Default Browser",
                    content = "Google Chrome",
                    confidencePercent = 100,
                    source = "default",
                    rawKey = "preferred_browser"
                )
            )
            unifiedItems.add(
                UnifiedMemoryItem(
                    id = "default_theme",
                    category = MemoryCategory.PREFERENCE,
                    title = "UI Theme",
                    content = "Dark Mode",
                    confidencePercent = 100,
                    source = "default",
                    rawKey = "ui_theme"
                )
            )
            unifiedItems.add(
                UnifiedMemoryItem(
                    id = "default_project",
                    category = MemoryCategory.PROJECT,
                    title = "VoiceBrainLive Project",
                    content = "C:\\Users\\nayli\\AndroidStudioProjects\\VoiceBrainLive-Desktop",
                    confidencePercent = 100,
                    source = "system",
                    rawKey = "active_project_path"
                )
            )
            unifiedItems.add(
                UnifiedMemoryItem(
                    id = "routine_work_mode",
                    category = MemoryCategory.ROUTINE,
                    title = "Work Mode Routine",
                    content = "အလုပ်စမယ် (Launch IDE + Notion + Volume 30%)",
                    confidencePercent = 100,
                    source = "routine_engine",
                    rawKey = "routine_work"
                )
            )
            unifiedItems.forEach { it.rawKey?.let { k -> memoryData[k] = it.content } }
            saveMemories()
        }
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
            if (unifiedFile.exists()) {
                val arr = JSONArray(unifiedFile.readText(Charsets.UTF_8))
                unifiedItems.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val catStr = obj.optString("category", MemoryCategory.FACT.name)
                    val cat = runCatching { MemoryCategory.valueOf(catStr) }.getOrDefault(MemoryCategory.FACT)
                    unifiedItems.add(
                        UnifiedMemoryItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            category = cat,
                            title = obj.optString("title", "Fact"),
                            content = obj.optString("content", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            confidencePercent = obj.optInt("confidencePercent", 100),
                            source = obj.optString("source", "saved"),
                            requiresConfirmation = obj.optBoolean("requiresConfirmation", false),
                            rawKey = obj.optString("rawKey", null).takeIf { !it.isNullOrBlank() },
                            isEditable = obj.optBoolean("isEditable", true)
                        )
                    )
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

            val arr = JSONArray()
            unifiedItems.forEach { item ->
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("category", item.category.name)
                obj.put("title", item.title)
                obj.put("content", item.content)
                obj.put("timestamp", item.timestamp)
                obj.put("confidencePercent", item.confidencePercent)
                obj.put("source", item.source)
                obj.put("requiresConfirmation", item.requiresConfirmation)
                obj.put("rawKey", item.rawKey ?: "")
                obj.put("isEditable", item.isEditable)
                arr.put(obj)
            }
            unifiedFile.writeText(arr.toString(2), Charsets.UTF_8)
        }
    }
}
