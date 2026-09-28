package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.CommandResult
import com.example.voicebrainlive.desktop.core.DesktopCommand
import com.example.voicebrainlive.desktop.core.PlatformCommandExecutor
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Windows-only commands. Phone calls, SMS, contacts, health, and Android services are intentionally absent. */
class WindowsCommandExecutor : PlatformCommandExecutor {
    @Volatile
    private var indexedFiles: List<File> = emptyList()
    @Volatile
    private var indexBuiltAt = 0L

    private val apps = mapOf(
        "notepad" to AppSpec("Notepad", "notepad.exe"),
        "calculator" to AppSpec("Calculator", "calc.exe"),
        "calc" to AppSpec("Calculator", "calc.exe"),
        "paint" to AppSpec("Paint", "mspaint.exe"),
        "chrome" to AppSpec("Chrome", "chrome.exe"),
        "edge" to AppSpec("Microsoft Edge", "msedge.exe"),
        "explorer" to AppSpec("File Explorer", "explorer.exe"),
        "file explorer" to AppSpec("File Explorer", "explorer.exe"),
        "settings" to AppSpec("Windows Settings", "ms-settings:"),
        "task manager" to AppSpec("Task Manager", "taskmgr.exe"),
        "spotify" to AppSpec("Spotify", "spotify:"),
        "telegram" to AppSpec("Telegram", "telegram.exe"),
        "whatsapp" to AppSpec("WhatsApp", "whatsapp:"),
        "zoom" to AppSpec("Zoom", "zoom.exe"),
        "teams" to AppSpec("Microsoft Teams", "ms-teams:"),
    )

    override suspend fun execute(command: DesktopCommand): CommandResult {
        return runCatching {
            when (command.type.lowercase()) {
                "open_app", "launch_app" -> openApp(command.target ?: command.value.orEmpty())
                "close_app", "stop_app" -> closeApp(command.target ?: command.value.orEmpty())
                "open_url" -> openUrl(command.target ?: command.value.orEmpty())
                "search_web", "web_search" -> searchWeb(command.target ?: command.value.orEmpty())
                "search_files", "find_file", "find_files" -> searchFiles(command.target ?: command.value.orEmpty())
                "refresh_file_index" -> refreshFileIndex()
                "open_file" -> openFile(command.target ?: command.value.orEmpty())
                "open_file_explorer", "open_folder" -> openFolder(command.target ?: command.value)
                "open_downloads" -> openFolder(System.getProperty("user.home") + "\\Downloads")
                "open_documents" -> openFolder(System.getProperty("user.home") + "\\Documents")
                "open_desktop" -> openFolder(System.getProperty("user.home") + "\\Desktop")
                "open_recycle_bin" -> openShellFolder("shell:RecycleBinFolder", "Recycle Bin")
                "open_settings" -> openUrl("ms-settings:")
                "open_network_settings" -> openUrl("ms-settings:network")
                "open_bluetooth_settings" -> openUrl("ms-settings:bluetooth")
                "open_display_settings" -> openUrl("ms-settings:display")
                "open_sound_settings" -> openUrl("ms-settings:sound")
                "take_screenshot" -> takeScreenshot()
                "volume_up" -> sendMediaKey(175, "အသံတိုးလိုက်ပါပြီ")
                "volume_down" -> sendMediaKey(174, "အသံလျှော့လိုက်ပါပြီ")
                "mute", "toggle_mute" -> sendMediaKey(173, "Mute ပြောင်းလိုက်ပါပြီ")
                "lock_computer" -> lockComputer()
                "system_status", "computer_status" -> systemStatus()
                "shutdown", "restart", "sleep" -> powerAction(command.type.lowercase(), command.value == "confirmed")
                else -> CommandResult(false, "ဒီ Windows command ကို မထည့်ရသေးပါ: ${command.type}")
            }
        }.getOrElse { error ->
            CommandResult(false, error.message ?: "Windows command မအောင်မြင်ပါ")
        }
    }

    private fun openApp(rawName: String): CommandResult {
        val spec = apps[normalize(rawName)] ?: return CommandResult(
            false,
            "Notepad, Calculator, Paint, Chrome, Edge, Explorer, Settings, Task Manager, Spotify, Teams, Zoom တို့ကို ဖွင့်နိုင်ပါတယ်။",
        )
        if (spec.command.endsWith(":") || spec.command == "spotify:") {
            Desktop.getDesktop().browse(URI(spec.command))
        } else {
            ProcessBuilder("cmd", "/c", "start", "", spec.command).start()
        }
        return CommandResult(true, "${spec.displayName} ကို ဖွင့်လိုက်ပါပြီ။")
    }

    private fun closeApp(rawName: String): CommandResult {
        val spec = apps[normalize(rawName)] ?: return CommandResult(false, "ပိတ်ရန် app အမည်ကို မသိပါ။")
        val executable = when (spec.command) {
            "ms-settings:", "spotify:", "whatsapp:", "ms-teams:" -> return CommandResult(false, "${spec.displayName} ကို URI ဖြင့်ဖွင့်ထားသောကြောင့် အလိုအလျောက်ပိတ်ခြင်း မလုပ်ထားပါ။")
            else -> spec.command
        }
        ProcessBuilder("taskkill", "/IM", executable, "/T", "/F").start()
        return CommandResult(true, "${spec.displayName} ကို ပိတ်ရန် command ပို့လိုက်ပါပြီ။")
    }

    private fun openUrl(rawUrl: String): CommandResult {
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://") || rawUrl.startsWith("ms-settings:")) rawUrl else "https://$rawUrl"
        Desktop.getDesktop().browse(URI(url))
        return CommandResult(true, "ဖွင့်လိုက်ပါပြီ: $url")
    }

    private fun searchWeb(query: String): CommandResult {
        if (query.isBlank()) return CommandResult(false, "ရှာဖွေရန် စကားလုံးလိုအပ်ပါတယ်။")
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8)
        Desktop.getDesktop().browse(URI("https://www.google.com/search?q=$encoded"))
        return CommandResult(true, "Google မှာ ရှာဖွေနေပါတယ်: $query")
    }

    private fun searchFiles(query: String): CommandResult {
        val cleanQuery = query.trim().removeSurrounding("\"").lowercase()
        if (cleanQuery.isBlank()) return CommandResult(false, "ရှာဖွေရန် file name သို့မဟုတ် extension လိုအပ်ပါတယ်။")

        val files = ensureFileIndex()
        val tokens = cleanQuery.split(Regex("\\s+"))
        val matches = files.mapNotNull { file ->
            val haystack = file.name.lowercase()
            val score = tokens.sumOf { token ->
                when {
                    haystack == token -> 100
                    haystack.startsWith(token) -> 50
                    haystack.contains(token) -> 20
                    else -> 0
                }
            }
            if (score > 0) file to score else null
        }.sortedByDescending { it.second }.take(25).map { it.first }

        if (matches.isEmpty()) return CommandResult(true, "‘$query’ နဲ့ ကိုက်ညီတဲ့ file မတွေ့ပါ။ Approved user folders အတွင်းမှာ ရှာထားပါတယ်။")
        val summary = matches.joinToString("\n") { "• ${it.name} — ${it.absolutePath}" }
        return CommandResult(true, "‘$query’ နဲ့ ကိုက်ညီတဲ့ file ${matches.size} ခုတွေ့ပါတယ် (indexed search):\n$summary")
    }

    private fun ensureFileIndex(): List<File> {
        val now = System.currentTimeMillis()
        if (indexedFiles.isNotEmpty() && now - indexBuiltAt < INDEX_TTL_MS) return indexedFiles
        return synchronized(this) {
            if (indexedFiles.isEmpty() || now - indexBuiltAt >= INDEX_TTL_MS) {
                indexedFiles = buildFileIndex()
                indexBuiltAt = System.currentTimeMillis()
            }
            indexedFiles
        }
    }

    private fun refreshFileIndex(): CommandResult {
        synchronized(this) {
            indexedFiles = buildFileIndex()
            indexBuiltAt = System.currentTimeMillis()
        }
        return CommandResult(true, "Desktop, Documents, Downloads, Pictures, Videos, Music နဲ့ OneDrive file index ကို ပြန်တည်ဆောက်ပြီးပါပြီ။ File ${indexedFiles.size} ခုကို ရှာနိုင်ပါပြီ။")
    }

    private fun buildFileIndex(): List<File> {
        val result = mutableListOf<File>()
        for (root in approvedSearchRoots()) {
            if (!root.isDirectory) continue
            runCatching {
                Files.walk(root.toPath(), MAX_SEARCH_DEPTH).use { paths ->
                    paths.filter { Files.isRegularFile(it) }
                        .limit(MAX_INDEX_FILES.toLong())
                        .forEach { result += it.toFile() }
                }
            }
            if (result.size >= MAX_INDEX_FILES) break
        }
        return result
    }

    private fun openFile(path: String): CommandResult {
        val file = File(path).canonicalFile
        if (!isUnderApprovedRoot(file)) return CommandResult(false, "လုံခြုံရေးအရ user folders အတွင်းက file သာ ဖွင့်နိုင်ပါတယ်။")
        if (!file.isFile) return CommandResult(false, "File မတွေ့ပါ: $path")
        Desktop.getDesktop().open(file)
        return CommandResult(true, "${file.name} ကို ဖွင့်လိုက်ပါပြီ။")
    }

    private fun approvedSearchRoots(): List<File> {
        val home = File(System.getProperty("user.home"))
        return listOf("Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music", "OneDrive")
            .map { File(home, it) }
    }

    private fun isUnderApprovedRoot(file: File): Boolean {
        val candidate = file.canonicalPath.lowercase()
        return approvedSearchRoots().any { root ->
            val rootPath = root.canonicalPath.lowercase() + File.separator
            candidate.startsWith(rootPath)
        }
    }

    private fun openFolder(path: String?): CommandResult {
        val folder = if (path.isNullOrBlank()) System.getProperty("user.home") else path
        val file = File(folder)
        if (!file.exists() || !file.isDirectory) return CommandResult(false, "Folder မတွေ့ပါ: $folder")
        Desktop.getDesktop().open(file)
        return CommandResult(true, "Folder ကို ဖွင့်လိုက်ပါပြီ။")
    }

    private fun openShellFolder(shellPath: String, name: String): CommandResult {
        ProcessBuilder("explorer.exe", shellPath).start()
        return CommandResult(true, "$name ကို ဖွင့်လိုက်ပါပြီ။")
    }

    private fun takeScreenshot(): CommandResult {
        ProcessBuilder("SnippingTool.exe", "/clip").start()
        return CommandResult(true, "Screenshot tool ကို ဖွင့်လိုက်ပါပြီ။ Snip လုပ်ပြီး clipboard ထဲ သိမ်းနိုင်ပါတယ်။")
    }

    private fun sendMediaKey(keyCode: Int, message: String): CommandResult {
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            "(New-Object -ComObject WScript.Shell).SendKeys([char]$keyCode)",
        ).start()
        return CommandResult(true, message)
    }

    private fun powerAction(action: String, confirmed: Boolean): CommandResult {
        if (!confirmed) {
            val name = when (action) {
                "shutdown" -> "စက်ပိတ်ခြင်း"
                "restart" -> "စက်ပြန်စခြင်း"
                else -> "Sleep ဝင်ခြင်း"
            }
            return CommandResult(
                success = false,
                message = "$name လုပ်မလား? လက်ရှိအလုပ်များကို သိမ်းပြီး အသံနဲ့ Confirm လုပ်ပါ။",
                requiresConfirmation = true,
                confirmationAction = action,
            )
        }

        when (action) {
            "shutdown" -> ProcessBuilder("shutdown.exe", "/s", "/t", "30").start()
            "restart" -> ProcessBuilder("shutdown.exe", "/r", "/t", "30").start()
            "sleep" -> ProcessBuilder(
                "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend', \$false, \$false)",
            ).start()
        }
        val message = when (action) {
            "shutdown" -> "ကွန်ပျူတာပိတ်ရန် ၃၀ စက္ကန့် timer စတင်ပါပြီ။ Cancel လုပ်ရန် `shutdown /a` သုံးပါ။"
            "restart" -> "ကွန်ပျူတာပြန်စရန် ၃၀ စက္ကန့် timer စတင်ပါပြီ။ Cancel လုပ်ရန် `shutdown /a` သုံးပါ။"
            else -> "ကွန်ပျူတာကို Sleep ဝင်စေပါပြီ။"
        }
        return CommandResult(true, message)
    }

    private fun lockComputer(): CommandResult {
        ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start()
        return CommandResult(true, "ကွန်ပျူတာကို lock လုပ်လိုက်ပါပြီ။")
    }

    private fun systemStatus(): CommandResult {
        val os = System.getProperty("os.name")
        val version = System.getProperty("os.version")
        val memoryGb = Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024)
        return CommandResult(true, "Windows computer အခြေအနေ: $os $version၊ app JVM memory limit ${memoryGb} GB ခန့် ရှိပါတယ်။")
    }

    private fun normalize(value: String) = value.trim().lowercase()

    private data class AppSpec(val displayName: String, val command: String)

    private companion object {
        const val MAX_SEARCH_DEPTH = 6
        const val MAX_INDEX_FILES = 10_000
        const val INDEX_TTL_MS = 60_000L
    }
}
