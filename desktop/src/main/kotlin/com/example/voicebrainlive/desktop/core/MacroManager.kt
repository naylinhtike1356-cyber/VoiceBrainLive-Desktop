package com.example.voicebrainlive.desktop.core

import com.example.voicebrainlive.desktop.platform.WindowsCommandExecutor

/**
 * Manages pre-configured work routines / desktop automation macros.
 */
class MacroManager(
    private val executor: WindowsCommandExecutor = WindowsCommandExecutor(),
) {
    suspend fun executeMacro(name: String): CommandResult {
        val clean = name.lowercase().trim()
        return when {
            clean.contains("work") || clean.contains("အလုပ်") -> runWorkMode()
            clean.contains("study") || clean.contains("စာလေ့လာ") -> runStudyMode()
            clean.contains("clean") || clean.contains("သန့်ရှင်းရေး") -> runCleanDesktopMode()
            else -> CommandResult(false, "Macro '$name' ရှာမတွေ့ပါ။ (ရနိုင်သော မုဒ်များ: Work Mode, Study Mode, Clean Desktop)")
        }
    }

    private suspend fun runWorkMode(): CommandResult {
        executor.execute(DesktopCommand("open_app", "VS Code", null))
        executor.execute(DesktopCommand("open_app", "Notion", null))
        executor.execute(DesktopCommand("set_volume", null, "50"))
        return CommandResult(true, "Work Mode အောင်မြင်စွာ ဖွင့်လိုက်ပါပြီ။ (VS Code, Notion နှင့် Volume 50% ဖွင့်ပေးထားပါသည်။)")
    }

    private suspend fun runStudyMode(): CommandResult {
        executor.execute(DesktopCommand("open_app", "Chrome", null))
        executor.execute(DesktopCommand("open_app", "Notion", null))
        executor.execute(DesktopCommand("mute", null, null))
        return CommandResult(true, "Study Mode အောင်မြင်စွာ ဖွင့်လိုက်ပါပြီ။ (Browser, Notion ဖွင့်၍ အသံပိတ်ပေးထားပါသည်။)")
    }

    private suspend fun runCleanDesktopMode(): CommandResult {
        executor.execute(DesktopCommand("open_downloads", null, null))
        executor.execute(DesktopCommand("open_documents", null, null))
        return CommandResult(true, "Clean Desktop Mode အောင်မြင်စွာ လုပ်ဆောင်ပေးလိုက်ပါပြီ။")
    }
}
