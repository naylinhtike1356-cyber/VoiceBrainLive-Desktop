package com.example.voicebrainlive.desktop.platform

import java.io.File
import java.util.Base64
import java.util.prefs.Preferences

/**
 * Stores credentials in the current Windows user's Java Preferences hive.
 * Values are never written to the project directory, logs, or UI state.
 */
class ApiKeyStore {
    private val preferences = Preferences.userRoot().node(PREFERENCES_NODE)

    fun load(): String {
        // 0. DPAPI-encrypted key (preferred on Windows).
        val dpapi = preferences.get(KEY_GEMINI_DPAPI, "").trim()
        if (dpapi.isNotBlank()) {
            val plain = runCatching {
                DpapiCredentialStore.unprotect(Base64.getDecoder().decode(dpapi))
                    ?.toString(Charsets.UTF_8)?.trim()
            }.getOrNull().orEmpty()
            if (plain.isNotBlank()) return plain
            DesktopLogger.warn("Stored DPAPI credential could not be decrypted; trying other key sources.")
        }

        val stored = preferences.get(KEY_GEMINI, "").trim()
        if (stored.isNotBlank()) {
            // Migrate legacy plaintext to DPAPI when available.
            if (DpapiCredentialStore.isAvailable()) save(stored)
            return stored
        }

        // 1. Try local.properties in project root or sibling phone project
        val localPropKey = readPropertyFromFiles("GEMINI_API_KEY")
        if (localPropKey.isNotBlank()) return localPropKey

        // 2. Try environment variables
        val envKey = System.getenv("GEMINI_API_KEY")?.trim().orEmpty()
        if (envKey.isNotBlank()) return envKey
        val googleEnvKey = System.getenv("GOOGLE_API_KEY")?.trim().orEmpty()
        if (googleEnvKey.isNotBlank()) return googleEnvKey

        // 3. Try JVM system property
        return System.getProperty("GEMINI_API_KEY", "").trim()
    }

    private fun readPropertyFromFiles(propName: String): String {
        val paths = listOf(
            File("local.properties"),
            File("../local.properties"),
            File("c:/Users/nayli/AndroidStudioProjects/VoiceBrainLive-Desktop/local.properties"),
            File("c:/Users/nayli/AndroidStudioProjects/VoiceBrainLive/local.properties")
        )
        for (f in paths) {
            try {
                if (f.exists() && f.isFile) {
                    val props = java.util.Properties()
                    f.inputStream().use { props.load(it) }
                    val v = props.getProperty(propName, "").trim()
                    if (v.isNotBlank()) return v
                }
            } catch (_: Exception) { }
        }
        return ""
    }

    fun hasGeminiKey(): Boolean = load().isNotBlank()

    fun save(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            remove(KEY_GEMINI)
            remove(KEY_GEMINI_DPAPI)
            return
        }
        val encrypted = DpapiCredentialStore.protect(clean.toByteArray(Charsets.UTF_8))
        if (encrypted != null) {
            preferences.put(KEY_GEMINI_DPAPI, Base64.getEncoder().encodeToString(encrypted))
            preferences.remove(KEY_GEMINI) // drop legacy plaintext once encrypted
            flush()
            DesktopLogger.info("API key stored with DPAPI encryption.")
        } else {
            // Non-Windows or JNA missing: keep legacy plaintext so dev/test
            // flows keep working. Never expected on the production machine.
            DesktopLogger.warn("DPAPI unavailable on this platform — API key stored unencrypted (dev/test only).")
            saveValue(KEY_GEMINI, clean)
        }
    }

    fun clear() {
        remove(KEY_GEMINI)
        remove(KEY_GEMINI_DPAPI)
    }

    fun loadRobotVisible(): Boolean = preferences.getBoolean(KEY_ROBOT_VISIBLE, true)

    fun saveRobotVisible(value: Boolean) {
        preferences.putBoolean(KEY_ROBOT_VISIBLE, value)
        flush()
    }

    fun loadDesktopAutomationEnabled(): Boolean =
        preferences.getBoolean(KEY_DESKTOP_AUTOMATION_ENABLED, false)

    fun saveDesktopAutomationEnabled(value: Boolean) {
        preferences.putBoolean(KEY_DESKTOP_AUTOMATION_ENABLED, value)
        flush()
    }

    /**
     * Phase 1 — echo canceller selection for the audio pipeline.
     * "suppression" (default, pure JVM) or "webrtc_aec3" (true full-duplex;
     * needs webrtc_aec3.dll — see native/BUILD_WINDOWS.md).
     *
     * Overridable without UI via env var VBL_ECHO_CANCELLER or JVM property
     * vbl.echo.canceller. Read once at engine construction; takes effect on
     * the next app start.
     */
    fun loadEchoCancellerKind(): String {
        val override = (System.getenv("VBL_ECHO_CANCELLER")
            ?: System.getProperty("vbl.echo.canceller", "")).trim().lowercase()
        if (override == "webrtc_aec3" || override == "suppression") return override
        val stored = preferences.get(KEY_ECHO_CANCELLER, "suppression")
        return com.example.voicebrainlive.desktop.platform.audio.EchoCancellerFactory.normalizeKind(stored)
    }

    fun saveEchoCancellerKind(kind: String) {
        preferences.put(
            KEY_ECHO_CANCELLER,
            com.example.voicebrainlive.desktop.platform.audio.EchoCancellerFactory.normalizeKind(kind),
        )
        flush()
    }

    /**
     * Phase 2 — barge-in sensitivity for the client VAD ("low"|"normal"|"high",
     * default "normal"). Read once at audio-engine construction; voice command
     * `set_barge_in_sensitivity` also applies it live via
     * WindowsAudioEngine.updateBargeInSensitivity.
     */
    fun loadBargeInSensitivity(): String {
        val stored = preferences.get(KEY_BARGE_IN_SENSITIVITY, "normal")
        return com.example.voicebrainlive.desktop.platform.audio.SpectralVad.normalizeLevel(stored)
    }

    fun saveBargeInSensitivity(level: String) {
        preferences.put(
            KEY_BARGE_IN_SENSITIVITY,
            com.example.voicebrainlive.desktop.platform.audio.SpectralVad.normalizeLevel(level),
        )
        flush()
    }

    fun loadRobotAlwaysOnTop(): Boolean = preferences.getBoolean(KEY_ROBOT_ALWAYS_ON_TOP, true)

    fun saveRobotAlwaysOnTop(value: Boolean) {
        preferences.putBoolean(KEY_ROBOT_ALWAYS_ON_TOP, value)
        flush()
    }

    fun loadRobotX(): Int = preferences.getInt(KEY_ROBOT_X, 80)

    fun loadRobotY(): Int = preferences.getInt(KEY_ROBOT_Y, 34)

    fun saveRobotPosition(x: Int, y: Int) {
        preferences.putInt(KEY_ROBOT_X, x.coerceAtLeast(0))
        preferences.putInt(KEY_ROBOT_Y, y.coerceAtLeast(0))
        flush()
    }

    fun loadGeminiModel(): String {
        val stored = preferences.get(KEY_GEMINI_MODEL, GeminiLiveSession.DEFAULT_LIVE_MODEL).trim()
        // This setting controls a bidirectional Live session, not the ordinary
        // text-only GenerateContent API. Migrate any text-only selection so a
        // voice session cannot silently start on a non-audio model.
        val liveModels = setOf(
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025",
        )
        val isOutdated = stored !in liveModels
        if (isOutdated) {
            val defaultModel = GeminiLiveSession.DEFAULT_LIVE_MODEL
            saveGeminiModel(defaultModel)
            return defaultModel
        }
        return stored
    }

    fun saveGeminiModel(value: String) {
        saveValue(KEY_GEMINI_MODEL, value.trim())
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
        private const val KEY_GEMINI_DPAPI = "gemini_api_key_dpapi"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_ROBOT_VISIBLE = "robot_visible"
        private const val KEY_DESKTOP_AUTOMATION_ENABLED = "desktop_automation_enabled"
        private const val KEY_ROBOT_ALWAYS_ON_TOP = "robot_always_on_top"
        private const val KEY_ROBOT_X = "robot_x"
        private const val KEY_ROBOT_Y = "robot_y"
        private const val KEY_ECHO_CANCELLER = "audio_echo_canceller"
        private const val KEY_BARGE_IN_SENSITIVITY = "audio_barge_in_sensitivity"
    }
}

fun String.maskSecret(): String = when {
    length <= 6 -> "••••••••"
    else -> take(3) + "••••••••" + takeLast(3)
}
