package com.example.voicebrainlive.desktop.core

import java.util.regex.Pattern

/**
 * Result of extracting a fact or preference from natural conversation.
 */
data class ExtractedFact(
    val key: String,
    val value: String,
    val category: MemoryCategory,
    val title: String,
    val confidence: Int = 90,
    val requiresConfirmation: Boolean = false,
    val isSensitive: Boolean = false
)

/**
 * Intelligent conversation fact extractor for Burmese and English conversational speech.
 * Automatically learns user habits, preferences, and personal facts from natural user turns
 * without requiring explicit "remember this" commands.
 */
object ConversationFactExtractor {

    private val SENSITIVE_KEYWORDS = listOf(
        "password", "စကားဝှက်", "passcode", "pin code", "secret", "private key",
        "credit card", "bank account", "ဘဏ်အကောင့်", "cvv", "token"
    )

    fun extractFacts(text: String): List<ExtractedFact> {
        val clean = text.trim()
        if (clean.length < 5) return emptyList()

        val results = mutableListOf<ExtractedFact>()
        val lower = clean.lowercase()

        val isSensitive = SENSITIVE_KEYWORDS.any { lower.contains(it) }

        // 1. User Name / Nickname Extraction
        extractName(clean)?.let { results.add(it) }

        // 2. Preferred Browser
        extractBrowser(clean)?.let { results.add(it) }

        // 3. Preferred IDE / Code Editor
        extractIde(clean)?.let { results.add(it) }

        // 4. Working Hours / Shift Habit
        extractWorkingHours(clean)?.let { results.add(it) }

        // 5. Project Directory / Workspace Path
        extractProjectDirectory(clean)?.let { results.add(it) }

        // 6. UI Theme / Preference
        extractTheme(clean)?.let { results.add(it) }

        // 7. Music / Leisure Preference
        extractMusic(clean)?.let { results.add(it) }

        // 8. General Favorite / Habit Extraction (e.g. "ငါ့အကြိုက် ...", "ငါ ... ပဲကြိုက်တယ်")
        extractGeneralPreference(clean)?.let { results.add(it) }

        return if (isSensitive) {
            results.map { it.copy(requiresConfirmation = true, isSensitive = true) }
        } else {
            results
        }
    }

    private fun extractName(text: String): ExtractedFact? {
        val lower = text.lowercase()
        // Burmese patterns: "ငါ့နာမည် [Name]", "ငါ့ကို [Name] လို့ ခေါ်ပါ", "ကျွန်တော့်နာမည် [Name]"
        val mmRegex = Regex("""(?:ငါ့နာမည်(?:က)?|ကျွန်တော့်နာမည်(?:က)?|ကျမနာမည်(?:က)?|ငါ့ကို|ကျွန်တော့်ကို)\s+([A-Za-z0-9\u1000-\u109F\s]+?)(?:\s*လို့\s*ခေါ်(?:ပါ)?|\s*ပါ|\s*ဖြစ်ပါသည်|$)""")
        val mmMatch = mmRegex.find(text)
        if (mmMatch != null) {
            val name = mmMatch.groupValues[1].trim()
            if (name.length in 2..30 && !name.contains("ဘာ") && !name.contains("မသိ")) {
                return ExtractedFact(
                    key = "user_name",
                    value = name,
                    category = MemoryCategory.FACT,
                    title = "အသုံးပြုသူ အမည် ($name)",
                    confidence = 95
                )
            }
        }

        // English patterns: "my name is [Name]", "call me [Name]"
        val enRegex = Regex("""(?:my name is|call me|i am)\s+([A-Za-z\s]+)""", RegexOption.IGNORE_CASE)
        val enMatch = enRegex.find(text)
        if (enMatch != null) {
            val name = enMatch.groupValues[1].trim()
            if (name.length in 2..30 && !name.equals("here", ignoreCase = true)) {
                return ExtractedFact(
                    key = "user_name",
                    value = name,
                    category = MemoryCategory.FACT,
                    title = "User Name ($name)",
                    confidence = 95
                )
            }
        }
        return null
    }

    private fun extractBrowser(text: String): ExtractedFact? {
        val lower = text.lowercase()
        val browsers = listOf("chrome", "firefox", "edge", "brave", "safari", "opera")
        for (b in browsers) {
            if (lower.contains(b)) {
                if (lower.contains("သုံးတယ်") || lower.contains("ကြိုက်တယ်") || lower.contains("အဓိက") ||
                    lower.contains("prefer") || lower.contains("mostly use") || lower.contains("default")
                ) {
                    val browserCapitalized = b.replaceFirstChar { it.uppercase() }
                    return ExtractedFact(
                        key = "preferred_browser",
                        value = browserCapitalized,
                        category = MemoryCategory.PREFERENCE,
                        title = "Favorite Browser ($browserCapitalized)",
                        confidence = 92
                    )
                }
            }
        }
        return null
    }

    private fun extractIde(text: String): ExtractedFact? {
        val lower = text.lowercase()
        val ides = mapOf(
            "android studio" to "Android Studio",
            "vscode" to "VS Code",
            "vs code" to "VS Code",
            "visual studio code" to "VS Code",
            "intellij" to "IntelliJ IDEA",
            "pycharm" to "PyCharm",
            "sublime" to "Sublime Text",
            "fleet" to "Fleet",
            "cursor" to "Cursor"
        )
        for ((trigger, ideName) in ides) {
            if (lower.contains(trigger)) {
                if (lower.contains("သုံးတယ်") || lower.contains("ရေးတယ်") || lower.contains("code") ||
                    lower.contains("ide") || lower.contains("editor") || lower.contains("ကြိုက်") ||
                    lower.contains("develop") || lower.contains("coding")
                ) {
                    return ExtractedFact(
                        key = "preferred_ide",
                        value = ideName,
                        category = MemoryCategory.PREFERENCE,
                        title = "Code Editor ($ideName)",
                        confidence = 94
                    )
                }
            }
        }
        return null
    }

    private fun extractWorkingHours(text: String): ExtractedFact? {
        val lower = text.lowercase()
        if (lower.contains("ညဘက်") && (lower.contains("code") || lower.contains("အလုပ်") || lower.contains("လုပ်တယ်"))) {
            return ExtractedFact(
                key = "working_hours",
                value = "ညဘက် အလုပ်လုပ်လေ့ရှိသည် (Night Shift / Owl)",
                category = MemoryCategory.HABIT,
                title = "Working Habit (Night Owl)",
                confidence = 88
            )
        }
        if (lower.contains("မနက်") && (lower.contains("စောစော") || lower.contains("စော")) && lower.contains("အလုပ်")) {
            return ExtractedFact(
                key = "working_hours",
                value = "မနက်စောစော အလုပ်လုပ်လေ့ရှိသည် (Early Bird)",
                category = MemoryCategory.HABIT,
                title = "Working Habit (Early Bird)",
                confidence = 88
            )
        }
        val timeRegex = Regex("""(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)\s*(?:မှ|ကနေ|to|-)\s*(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)""", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(text)
        if (match != null && (lower.contains("အလုပ်") || lower.contains("work") || lower.contains("ရုံး"))) {
            val hours = "${match.groupValues[1]} မှ ${match.groupValues[2]} ထိ"
            return ExtractedFact(
                key = "working_hours",
                value = hours,
                category = MemoryCategory.HABIT,
                title = "Working Hours ($hours)",
                confidence = 90
            )
        }
        return null
    }

    private fun extractProjectDirectory(text: String): ExtractedFact? {
        val pathRegex = Regex("""([A-Za-z]:\\[^ \t\r\n<>:"|?*]+)""")
        val match = pathRegex.find(text)
        if (match != null) {
            val path = match.groupValues[1]
            if (text.lowercase().contains("project") || text.contains("ပရောဂျက်") || text.lowercase().contains("workspace") || text.contains("ဖိုင်")) {
                return ExtractedFact(
                    key = "default_project_dir",
                    value = path,
                    category = MemoryCategory.PROJECT,
                    title = "Project Folder ($path)",
                    confidence = 95
                )
            }
        }
        return null
    }

    private fun extractTheme(text: String): ExtractedFact? {
        val lower = text.lowercase()
        if (lower.contains("dark mode") || lower.contains("dark theme") || lower.contains("အမည်းရောင်")) {
            return ExtractedFact(
                key = "ui_theme",
                value = "Dark Mode",
                category = MemoryCategory.PREFERENCE,
                title = "UI Theme (Dark)",
                confidence = 92
            )
        }
        if (lower.contains("light mode") || lower.contains("light theme") || lower.contains("အဖြူရောင်")) {
            return ExtractedFact(
                key = "ui_theme",
                value = "Light Mode",
                category = MemoryCategory.PREFERENCE,
                title = "UI Theme (Light)",
                confidence = 92
            )
        }
        return null
    }

    private fun extractMusic(text: String): ExtractedFact? {
        val lower = text.lowercase()
        if (lower.contains("lofi") || lower.contains("lo-fi")) {
            if (lower.contains("သီချင်း") || lower.contains("music") || lower.contains("နားထောင်") || lower.contains("ကြိုက်")) {
                return ExtractedFact(
                    key = "music_preference",
                    value = "Lofi / Relaxing Beats",
                    category = MemoryCategory.PREFERENCE,
                    title = "Music Preference (Lofi)",
                    confidence = 90
                )
            }
        }
        return null
    }

    private fun extractGeneralPreference(text: String): ExtractedFact? {
        // "ငါ့အကြိုက်က [Item]" or "ငါ [Item] ပဲ ကြိုက်တယ်"
        val regex = Regex("""ငါ့အကြိုက်(?:က)?\s+([A-Za-z0-9\u1000-\u109F\s]{2,30})""")
        val match = regex.find(text)
        if (match != null) {
            val item = match.groupValues[1].trim()
            return ExtractedFact(
                key = "general_favorite",
                value = item,
                category = MemoryCategory.PREFERENCE,
                title = "User Favorite ($item)",
                confidence = 85
            )
        }
        return null
    }
}
