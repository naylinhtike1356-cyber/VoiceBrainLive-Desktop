package com.example.voicebrainlive.desktop.core

import org.json.JSONObject

/**
 * Decomposes natural language goals or structured requests into executable GoalStep sequences.
 */
class PlanDecomposer(
    private val compoundHandler: CompoundCommandHandler
) {

    /**
     * Decomposes a raw goal prompt into a GoalDefinition.
     */
    fun decompose(intent: String, optionalProject: String? = null): GoalDefinition {
        val trimmed = intent.trim()
        val lower = trimmed.lowercase()

        // 1. Check if structured JSON plan
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val jsonPlan = parseJsonPlan(trimmed)
            if (jsonPlan != null) return jsonPlan
        }

        // 2. Specialized Templates for common high-impact workflows
        when {
            // Developer Workspace Setup
            (lower.contains("work mode") || lower.contains("workspace") || lower.contains("အလုပ်လုပ်")) -> {
                return GoalDefinition(
                    title = "Developer Workspace အပြည့်အစုံ ပြင်ဆင်ပေးခြင်း",
                    rawIntent = trimmed,
                    steps = listOf(
                        GoalStep(
                            index = 1,
                            title = "VS Code Editor ဖွင့်လှစ်ခြင်း",
                            command = DesktopCommand("open_app", target = "VS Code")
                        ),
                        GoalStep(
                            index = 2,
                            title = "စနစ် အခြေအနေ စစ်ဆေးခြင်း",
                            command = DesktopCommand("system_status")
                        ),
                        GoalStep(
                            index = 3,
                            title = "Notion Workspace ဖွင့်လှစ်ခြင်း",
                            command = DesktopCommand("open_app", target = "Notion")
                        ),
                        GoalStep(
                            index = 4,
                            title = "အသံ Level အား 50% သို့ ချိန်ညှိခြင်း",
                            command = DesktopCommand("volume_up")
                        )
                    )
                )
            }

            // System Diagnostic & Health Check
            (lower.contains("health") || lower.contains("စစ်ဆေး") || lower.contains("status")) &&
            (lower.contains("system") || lower.contains("စက်") || lower.contains("computer")) -> {
                return GoalDefinition(
                    title = "ကွန်ပျူတာ အခြေအနေ အပြည့်အစုံ စစ်ဆေးခြင်း",
                    rawIntent = trimmed,
                    steps = listOf(
                        GoalStep(
                            index = 1,
                            title = "CPU, RAM & Disk System Status စစ်ဆေးခြင်း",
                            command = DesktopCommand("system_status")
                        ),
                        GoalStep(
                            index = 2,
                            title = "အင်တာနက် ကွန်ရက် ချိတ်ဆက်မှု စစ်ဆေးခြင်း",
                            command = DesktopCommand("diagnose_network")
                        ),
                        GoalStep(
                            index = 3,
                            title = "ဘက်ထရီ အခြေအနေ စစ်ဆေးခြင်း",
                            command = DesktopCommand("get_battery_status")
                        )
                    )
                )
            }
        }

        // 3. Fallback: Parse via compound command pipeline
        val commands = compoundHandler.parseCommands(trimmed)
        if (commands.isNotEmpty()) {
            val steps = commands.mapIndexed { idx, cmd ->
                val title = describeCommandStep(cmd, idx + 1)
                GoalStep(
                    index = idx + 1,
                    title = title,
                    command = cmd
                )
            }
            return GoalDefinition(
                title = if (steps.size > 1) "အဆင့် ${steps.size} ဆင့် ဆက်တိုက် ဆောင်ရွက်ခြင်း" else (steps.firstOrNull()?.title ?: trimmed),
                rawIntent = trimmed,
                steps = steps
            )
        }

        // Single fallback step
        return GoalDefinition(
            title = trimmed.take(40),
            rawIntent = trimmed,
            steps = listOf(
                GoalStep(
                    index = 1,
                    title = trimmed,
                    command = DesktopCommand("open_app", target = trimmed)
                )
            )
        )
    }

    private fun describeCommandStep(cmd: DesktopCommand, stepNum: Int): String {
        return when (cmd.type.lowercase()) {
            "open_app" -> "App '${cmd.target ?: "Application"}' ဖွင့်လှစ်ခြင်း"
            "close_app" -> "App '${cmd.target ?: "Application"}' ပိတ်သိမ်းခြင်း"
            "system_status" -> "System Status စစ်ဆေးခြင်း"
            "diagnose_network" -> "အင်တာနက် ကွန်ရက် စစ်ဆေးခြင်း"
            "get_battery_status" -> "ဘက်ထရီ စစ်ဆေးခြင်း"
            "volume_up" -> "အသံတိုးခြင်း"
            "volume_down" -> "အသံလျှော့ခြင်း"
            "mute" -> "အသံပိတ်/ပြန်ဖွင့်ခြင်း"
            "take_screenshot" -> "စခရင် ပုံရိပ် ရယူခြင်း"
            else -> "အဆင့် $stepNum: ${cmd.type} (${cmd.target ?: ""})"
        }
    }

    private fun parseJsonPlan(jsonText: String): GoalDefinition? {
        return runCatching {
            val json = JSONObject(jsonText)
            val title = json.optString("title", "Autonomous Goal Plan")
            val stepsArray = json.optJSONArray("steps") ?: return null
            val steps = mutableListOf<GoalStep>()
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.optJSONObject(i) ?: continue
                val stepTitle = stepObj.optString("title", "Step ${i + 1}")
                val cmdType = stepObj.optString("command_type").ifBlank { stepObj.optString("type") }
                val target = stepObj.optString("target").takeIf { it.isNotBlank() }
                val value = stepObj.optString("value").takeIf { it.isNotBlank() }
                steps.add(
                    GoalStep(
                        index = i + 1,
                        title = stepTitle,
                        command = DesktopCommand(cmdType, target, value)
                    )
                )
            }
            GoalDefinition(
                title = title,
                rawIntent = jsonText,
                steps = steps
            )
        }.getOrNull()
    }

    private fun extractProjectName(text: String): String? {
        val words = text.split(" ", "-", "_")
        for (w in words) {
            if (w.equals("VoiceBrainLive", ignoreCase = true) || w.equals("VoiceBrainLive-Desktop", ignoreCase = true)) {
                return "VoiceBrainLive-Desktop"
            }
        }
        return null
    }
}
