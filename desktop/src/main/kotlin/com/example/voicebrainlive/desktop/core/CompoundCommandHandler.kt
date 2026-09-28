package com.example.voicebrainlive.desktop.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles compound and chained commands:
 * Decomposes multi-intent spoken sentences or structured command pipelines into sequential atomic actions.
 */
class CompoundCommandHandler(
    private val executor: PlatformCommandExecutor
) {

    /**
     * Splits a multi-part natural sentence into individual command phrases.
     * Supports Burmese connectors ("ပြီးရင်", "ပြီးတော့", "ပြီး", "ထို့နောက်", "ဆက်ပြီး")
     * and English/code connectors ("and then", "then", "&&", ";", "\n").
     */
    fun splitCompoundInstruction(rawText: String): List<String> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return emptyList()

        // 1. Check if structured JSON array
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return listOf(trimmed)
        }

        // Normalize spaces and punctuation
        val normalized = trimmed.replace("\r\n", "\n")

        // Regex split on sequence connectors
        val delimiterPattern = Regex(
            "(?:\\s*(?:ပြီးရင်|ပြီးတော့|ထို့နောက်|ဆက်ပြီး|ပြီးလျှင်)\\s*)" +
            "|(?:\\s*\\b(?:and then|then)\\b\\s*)" +
            "|(?:\\s*(?:;|&&|\\|\\||\\n)\\s*)",
            RegexOption.IGNORE_CASE
        )

        val rawParts = normalized.split(delimiterPattern)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Also check if any part contains " ပြီး " as a standalone word (not inside a word like ပြီးစီး)
        val finalParts = mutableListOf<String>()
        val standalonePiPattern = Regex("(?<=\\s)ပြီး(?=\\s)")

        for (part in rawParts) {
            val subParts = part.split(standalonePiPattern)
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (subParts.isNotEmpty()) {
                finalParts.addAll(subParts)
            }
        }

        return if (finalParts.isEmpty()) listOf(trimmed) else finalParts
    }

    /**
     * Parses a raw user string or JSON structure into an executable list of DesktopCommands.
     */
    fun parseCommands(rawText: String): List<DesktopCommand> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return emptyList()

        // 1. JSON Array parsing
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val parsedList = mutableListOf<DesktopCommand>()
            runCatching {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val type = obj.optString("command_type").ifBlank { obj.optString("type") }
                    if (type.isNotBlank()) {
                        val target = obj.optString("target").takeIf { it.isNotBlank() }
                        val value = obj.optString("value").takeIf { it.isNotBlank() }
                        parsedList.add(DesktopCommand(type, target, value))
                    }
                }
            }
            if (parsedList.isNotEmpty()) return parsedList
        }

        // 2. Natural language splitting and matching
        val phrases = splitCompoundInstruction(trimmed)
        val commands = mutableListOf<DesktopCommand>()

        for (phrase in phrases) {
            // First try OfflineCommandMatcher
            val matched = OfflineCommandMatcher.match(phrase)
            if (matched != null) {
                commands.add(matched)
                continue
            }

            // Fallback heuristics for common actions
            val lower = phrase.lowercase()
            when {
                lower.contains("vs code") || lower.contains("vscode") -> commands.add(DesktopCommand("open_app", "VS Code"))
                lower.contains("chrome") -> commands.add(DesktopCommand("open_app", "Google Chrome"))
                lower.contains("notion") -> commands.add(DesktopCommand("open_app", "Notion"))
                lower.contains("spotify") -> commands.add(DesktopCommand("open_app", "Spotify"))
                lower.contains("terminal") || lower.contains("cmd") -> commands.add(DesktopCommand("open_app", "Windows Terminal"))
                lower.contains("volume") || lower.contains("အသံတိုး") -> commands.add(DesktopCommand("volume_up"))
                lower.contains("mute") || lower.contains("အသံပိတ်") -> commands.add(DesktopCommand("mute"))
                lower.contains("screenshot") || lower.contains("စခရင်") -> commands.add(DesktopCommand("take_screenshot"))
                lower.contains("battery") || lower.contains("ဘက်ထရီ") -> commands.add(DesktopCommand("get_battery_status"))
                lower.contains("wifi") || lower.contains("internet") || lower.contains("လိုင်း") -> commands.add(DesktopCommand("diagnose_network"))
                else -> {
                    // Treat as open_app if looks like app or run_powershell_safe
                    commands.add(DesktopCommand("open_app", phrase))
                }
            }
        }

        return commands
    }

    /**
     * Executes a chain of commands in sequential order.
     */
    suspend fun executeChainedCommands(
        commands: List<DesktopCommand>,
        onStepProgress: ((stepIndex: Int, total: Int, current: DesktopCommand, result: CommandResult) -> Unit)? = null
    ): CommandResult {
        if (commands.isEmpty()) {
            return CommandResult(false, "လုပ်ဆောင်ရန် အဆင့်များ မရှိပါ")
        }

        val results = mutableListOf<String>()
        var allSucceeded = true

        for ((index, cmd) in commands.withIndex()) {
            val stepNumber = index + 1
            val res = executor.execute(cmd)
            results.add("အဆင့် $stepNumber (${cmd.type}): ${res.message}")
            onStepProgress?.invoke(stepNumber, commands.size, cmd, res)

            if (!res.success) {
                allSucceeded = false
                // Stop pipeline if a destructive/critical step fails
                if (res.requiresConfirmation) {
                    return CommandResult(
                        success = false,
                        message = "အတည်ပြုရန် လိုအပ်သည်: ${res.message}",
                        requiresConfirmation = true,
                        confirmationAction = res.confirmationAction
                    )
                }
            }
        }

        val summary = if (allSucceeded) {
            "အဆင့် ${commands.size} ခုလုံး အောင်မြင်စွာ ဆောင်ရွက်ပြီးပါပြီရှင်:\n" + results.joinToString("\n")
        } else {
            "လုပ်ဆောင်ချက် အချို့ ပြီးစီးခဲ့ပါသည်:\n" + results.joinToString("\n")
        }

        return CommandResult(allSucceeded, summary)
    }
}
