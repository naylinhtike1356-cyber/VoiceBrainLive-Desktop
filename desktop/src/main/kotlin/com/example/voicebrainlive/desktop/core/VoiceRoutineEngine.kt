package com.example.voicebrainlive.desktop.core

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.example.voicebrainlive.desktop.platform.WindowsCommandExecutor
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Data structure representing a multi-step automation Voice Routine.
 */
data class VoiceRoutine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerPhrases: List<String>,
    val description: String,
    val actions: List<DesktopCommand>,
    val volumePercent: Int? = null,
    val responseBurmese: String,
    val enabled: Boolean = true
)

/**
 * Advanced Voice Routine Engine executing multi-step composite workflows with a single voice command.
 * Replaces and extends MacroManager with customizable, persistent JSON routine definitions.
 */
class VoiceRoutineEngine(
    private val executor: WindowsCommandExecutor = WindowsCommandExecutor(),
    private val routinesFile: File = File(System.getProperty("user.home"), ".voicebrainlive/voice_routines.json")
) {
    private val routines = mutableListOf<VoiceRoutine>()

    init {
        loadRoutines()
        if (routines.isEmpty()) {
            registerDefaultRoutines()
            saveRoutines()
        }
    }

    @Synchronized
    fun getAllRoutines(): List<VoiceRoutine> = ArrayList(routines)

    @Synchronized
    fun findMatchingRoutine(phrase: String): VoiceRoutine? {
        val clean = phrase.trim().lowercase()
        // M6: blank or very short phrases must never match — an empty trigger
        // would match every routine via String.contains("").
        if (clean.length < MIN_TRIGGER_LENGTH) return null
        return routines.find { r ->
            r.enabled && r.triggerPhrases.any { trigger ->
                val t = trigger.trim().lowercase()
                t.length >= MIN_TRIGGER_LENGTH &&
                    (clean.contains(t) || t.contains(clean))
            }
        }
    }

    suspend fun executeRoutine(nameOrPhrase: String): CommandResult {
        val routine = findMatchingRoutine(nameOrPhrase)
            ?: return CommandResult(false, "Routine '$nameOrPhrase' ရှာမတွေ့ပါ။ (ရနိုင်သော Routines များ: ${routines.joinToString { it.name }})")

        DesktopLogger.info("Executing Voice Routine: ${routine.name} (${routine.actions.size} actions)")
        val failedSteps = mutableListOf<String>()

        for (action in routine.actions) {
            val res = runCatching { executor.execute(action) }
                .getOrElse { CommandResult(false, it.message ?: "exception") }
            if (!res.success) failedSteps.add(action.type)
            delay(150) // Smooth delay between multi-step launches
        }

        // M6: the volume step counts too — a failed set_volume must not be
        // silently swallowed by an unconditional success = true.
        if (routine.volumePercent != null) {
            val volumeRes = runCatching {
                executor.execute(DesktopCommand("set_volume", null, routine.volumePercent.toString()))
            }.getOrElse { CommandResult(false, it.message ?: "exception") }
            if (!volumeRes.success) failedSteps.add("set_volume")
        }

        return if (failedSteps.isEmpty()) {
            CommandResult(success = true, message = routine.responseBurmese)
        } else {
            CommandResult(
                success = false,
                message = "${routine.responseBurmese}\n⚠️ အောက်ပါ အဆင့်များ မအောင်မြင်ပါ: ${failedSteps.joinToString(", ")}"
            )
        }
    }

    @Synchronized
    fun saveCustomRoutine(routine: VoiceRoutine) {
        val idx = routines.indexOfFirst { it.id == routine.id || it.name.equals(routine.name, ignoreCase = true) }
        if (idx >= 0) {
            routines[idx] = routine
        } else {
            routines.add(routine)
        }
        saveRoutines()
    }

    @Synchronized
    fun deleteRoutine(id: String): Boolean {
        val removed = routines.removeIf { it.id == id }
        if (removed) saveRoutines()
        return removed
    }

    private fun registerDefaultRoutines() {
        // 1. Work Mode Routine
        routines.add(
            VoiceRoutine(
                id = "routine_work_mode",
                name = "Work Mode (အလုပ်စမယ်)",
                triggerPhrases = listOf("အလုပ်စမယ်", "စလုပ်မယ်", "work mode", "start work", "စတင်အလုပ်လုပ်မယ်"),
                description = "Launch IDE, open Notion docs, adjust volume to 30%",
                actions = listOf(
                    DesktopCommand("open_app", "VS Code", null),
                    DesktopCommand("open_url", "https://www.notion.so", null),
                    DesktopCommand("set_volume", null, "30")
                ),
                volumePercent = 30,
                responseBurmese = "Work Mode စတင်ပေးလိုက်ပါပြီရှင်။ IDE နှင့် Notion ကို ဖွင့်ပေးထားပြီး အသံပမာဏကို ၃၀% သို့ ညှိပေးထားပါသည်ရှင်။"
            )
        )

        // 2. Study & Research Mode
        routines.add(
            VoiceRoutine(
                id = "routine_study_mode",
                name = "Study Mode (စာလေ့လာမယ်)",
                triggerPhrases = listOf("စာလေ့လာမယ်", "study mode", "research လုပ်မယ်", "စာဖတ်မယ်"),
                description = "Open Chrome for research, Notion for notes, mute distractions",
                actions = listOf(
                    DesktopCommand("open_app", "Chrome", null),
                    DesktopCommand("open_app", "Notion", null),
                    DesktopCommand("mute", null, null)
                ),
                responseBurmese = "Study Mode စတင်ပေးလိုက်ပါပြီရှင်။ သုတေသနအတွက် Chrome နှင့် မှတ်စုအတွက် Notion ကို ဖွင့်ထားပေးပါသည်ရှင်။"
            )
        )

        // 3. Clean Workspace
        routines.add(
            VoiceRoutine(
                id = "routine_clean_workspace",
                name = "Clean Workspace (စက်ရှင်းမယ်)",
                triggerPhrases = listOf("စက်ရှင်းမယ်", "clean workspace", "သန့်ရှင်းရေးလုပ်မယ်", "clean desktop"),
                description = "Open Downloads, Documents, and check files",
                actions = listOf(
                    DesktopCommand("open_downloads", null, null),
                    DesktopCommand("open_documents", null, null)
                ),
                responseBurmese = "Clean Workspace လုပ်ဆောင်ပေးလိုက်ပါပြီရှင်။ Downloads နှင့် Documents ဖိုင်တွဲများကို ဖွင့်လှစ်စစ်ဆေးနိုင်ပါပြီ။"
            )
        )

        // 4. Relax / Rest Mode
        routines.add(
            VoiceRoutine(
                id = "routine_rest_mode",
                name = "Rest Mode (အနားယူမယ်)",
                triggerPhrases = listOf("အနားယူမယ်", "နားမယ်", "rest mode", "break time", "သီချင်းဖွင့်"),
                description = "Minimize all work windows, set volume to 25%, play chill Lofi beats",
                actions = listOf(
                    DesktopCommand("minimize_all", null, null),
                    DesktopCommand("set_volume", null, "25"),
                    DesktopCommand("search_youtube", "lofi hip hop radio chill study relax", null)
                ),
                volumePercent = 25,
                responseBurmese = "အနားယူချိန် ဖြစ်သည့်အတွက် မျက်နှာပြင်များကို သိမ်းဆည်းပေးပြီး စိတ်လက်အပန်းဖြေရန် Lofi သီချင်း ဖွင့်ပေးထားပါသည်ရှင်။"
            )
        )
    }

    @Synchronized
    private fun loadRoutines() {
        runCatching {
            if (routinesFile.exists()) {
                val arr = JSONArray(routinesFile.readText(Charsets.UTF_8))
                routines.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val triggersArr = obj.optJSONArray("triggerPhrases") ?: JSONArray()
                    val triggers = (0 until triggersArr.length()).map { triggersArr.getString(it) }

                    val actionsArr = obj.optJSONArray("actions") ?: JSONArray()
                    val actions = (0 until actionsArr.length()).mapNotNull { idx ->
                        val actObj = actionsArr.optJSONObject(idx) ?: return@mapNotNull null
                        DesktopCommand(
                            type = actObj.optString("type", ""),
                            target = actObj.optString("target", null).takeIf { !it.isNullOrBlank() },
                            value = actObj.optString("value", null).takeIf { !it.isNullOrBlank() }
                        )
                    }

                    routines.add(
                        VoiceRoutine(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name", "Custom Routine"),
                            triggerPhrases = triggers,
                            description = obj.optString("description", ""),
                            actions = actions,
                            volumePercent = if (obj.has("volumePercent")) obj.optInt("volumePercent") else null,
                            responseBurmese = obj.optString("responseBurmese", "လုပ်ဆောင်ချက် ပြီးမြောက်ပါပြီ။"),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.onFailure {
            DesktopLogger.warn("Failed to load voice routines: ${it.message}")
            AtomicFileIO.backupCorruptFile(routinesFile, it.message ?: "parse error")
        }
    }

    @Synchronized
    private fun saveRoutines() {
        runCatching {
            routinesFile.parentFile?.mkdirs()
            val arr = JSONArray()
            routines.forEach { r ->
                val obj = JSONObject()
                obj.put("id", r.id)
                obj.put("name", r.name)
                val trArr = JSONArray()
                r.triggerPhrases.forEach { trArr.put(it) }
                obj.put("triggerPhrases", trArr)
                obj.put("description", r.description)
                
                val actArr = JSONArray()
                r.actions.forEach { act ->
                    actArr.put(JSONObject().apply {
                        put("type", act.type)
                        act.target?.let { put("target", it) }
                        act.value?.let { put("value", it) }
                    })
                }
                obj.put("actions", actArr)
                r.volumePercent?.let { obj.put("volumePercent", it) }
                obj.put("responseBurmese", r.responseBurmese)
                obj.put("enabled", r.enabled)
                arr.put(obj)
            }
            AtomicFileIO.writeTextAtomic(routinesFile, arr.toString(2))
        }.onFailure {
            DesktopLogger.warn("Failed to save voice routines: ${it.message}")
        }
    }

    companion object {
        /**
         * Minimum trigger/phrase length (in UTF-16 units) for routine matching.
         * Rejects blank input and 1-2 character noise that would otherwise
         * match unrelated routines.
         */
        const val MIN_TRIGGER_LENGTH = 3
    }
}
