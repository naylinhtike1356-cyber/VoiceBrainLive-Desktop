package com.example.voicebrainlive.desktop.platform

import java.util.prefs.Preferences

/**
 * Stores credentials in the current Windows user's Java Preferences hive.
 * Values are never written to the project directory, logs, or UI state.
 */
class ApiKeyStore {
    private val preferences = Preferences.userRoot().node(PREFERENCES_NODE)

    fun load(): String = preferences.get(KEY_GEMINI, "").trim()

    fun hasGeminiKey(): Boolean = load().isNotBlank()

    fun save(value: String) {
        saveValue(KEY_GEMINI, value)
    }

    fun clear() {
        remove(KEY_GEMINI)
    }

    fun loadNotionToken(): String = preferences.get(KEY_NOTION_TOKEN, "").trim()

    fun hasNotionCredentials(): Boolean =
        loadNotionToken().isNotBlank() && loadNotionParentPageId().isNotBlank()

    fun saveNotionToken(value: String) {
        saveValue(KEY_NOTION_TOKEN, value)
    }

    fun loadNotionParentPageId(): String =
        preferences.get(KEY_NOTION_PARENT_PAGE, "").trim()

    fun saveNotionParentPageId(value: String) {
        saveValue(KEY_NOTION_PARENT_PAGE, value)
    }

    fun clearNotion() {
        remove(KEY_NOTION_TOKEN)
        remove(KEY_NOTION_PARENT_PAGE)
    }

    fun loadRobotVisible(): Boolean = preferences.getBoolean(KEY_ROBOT_VISIBLE, true)

    fun saveRobotVisible(value: Boolean) {
        preferences.putBoolean(KEY_ROBOT_VISIBLE, value)
        flush()
    }

    private fun saveValue(key: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            remove(key)
        } else {
            preferences.put(key, clean)
            flush()
        }
    }

    private fun remove(key: String) {
        preferences.remove(key)
        flush()
    }

    private fun flush() {
        runCatching { preferences.flush() }
            .onFailure { /* Do not expose credential persistence details in the UI. */ }
    }

    companion object {
        private const val PREFERENCES_NODE = "VoiceBrainLive"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_NOTION_TOKEN = "notion_token"
        private const val KEY_NOTION_PARENT_PAGE = "notion_parent_page_id"
        private const val KEY_ROBOT_VISIBLE = "robot_visible"
    }
}

fun String.maskSecret(): String = when {
    length <= 6 -> "••••••••"
    else -> take(3) + "••••••••" + takeLast(3)
}
