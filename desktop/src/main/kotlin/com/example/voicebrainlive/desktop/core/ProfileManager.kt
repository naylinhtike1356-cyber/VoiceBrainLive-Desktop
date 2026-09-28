package com.example.voicebrainlive.desktop.core

import org.json.JSONObject
import com.example.voicebrainlive.desktop.platform.DesktopLogger
import java.io.File

/**
 * Handles multiple assistant profiles (Personal, Work, Developer).
 */
class ProfileManager(
    private val profileFile: File = File(System.getProperty("user.home"), ".voicebrainlive/profiles.json"),
) {
    private var activeProfile: String = "Personal"
    private val profiles = mutableListOf("Personal", "Work", "Developer")

    init {
        loadProfiles()
    }

    fun getActiveProfile(): String = activeProfile

    fun getAllProfiles(): List<String> = profiles.toList()

    fun switchProfile(name: String): String {
        val target = profiles.firstOrNull { it.equals(name, ignoreCase = true) }
        if (target != null) {
            activeProfile = target
            saveProfiles()
            return "$target profile သို့ ပြောင်းလိုက်ပါပြီ။"
        }
        if (profiles.size < 5) {
            val clean = name.trim().lowercase().capitalize()
            profiles.add(clean)
            activeProfile = clean
            saveProfiles()
            return "Profile အသစ် '$clean' ကို ဖန်တီးပြီး ပြောင်းလိုက်ပါပြီ။"
        }
        return "Profile ရှာမတွေ့ပါ သို့မဟုတ် Profile အရေအတွက် ပြည့်နေပါပြီ။"
    }

    private fun loadProfiles() {
        runCatching {
            if (profileFile.exists()) {
                val json = JSONObject(profileFile.readText(Charsets.UTF_8))
                activeProfile = json.optString("active_profile", "Personal")
                val array = json.optJSONArray("profiles")
                if (array != null) {
                    profiles.clear()
                    for (i in 0 until array.length()) {
                        profiles.add(array.getString(i))
                    }
                }
            }
        }.onFailure {
            DesktopLogger.warn("Failed to load profiles: ${it.message}")
            AtomicFileIO.backupCorruptFile(profileFile, it.message ?: "parse error")
        }
    }

    private fun saveProfiles() {
        runCatching {
            profileFile.parentFile?.mkdirs()
            val json = JSONObject()
            json.put("active_profile", activeProfile)
            json.put("profiles", profiles)
            AtomicFileIO.writeTextAtomic(profileFile, json.toString(2))
        }
    }
}
