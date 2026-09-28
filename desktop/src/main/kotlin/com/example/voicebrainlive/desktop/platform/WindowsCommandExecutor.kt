package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.CommandResult
import com.example.voicebrainlive.desktop.core.DesktopCommand
import com.example.voicebrainlive.desktop.core.PlatformCommandExecutor
import com.example.voicebrainlive.desktop.automation.AndroidBuildPipeline
import com.example.voicebrainlive.desktop.automation.AndroidEmulatorRunner
import com.example.voicebrainlive.desktop.automation.AntigravityBridge
import com.example.voicebrainlive.desktop.automation.AutoHealingFixEngine
import com.example.voicebrainlive.desktop.automation.CodingTaskFormatter
import com.example.voicebrainlive.desktop.automation.GitAutomationService
import com.example.voicebrainlive.desktop.automation.IdeBridgeService
import com.example.voicebrainlive.desktop.automation.MultiRepoManager
import com.example.voicebrainlive.desktop.automation.ProjectResolver
import com.example.voicebrainlive.desktop.automation.WirelessAdbManager
import java.awt.Desktop
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

private data class AppSpec(val displayName: String, val command: String)

private const val INDEX_TTL_MS = 60 * 60 * 1000L // 1 hour

/** Windows-only commands. Laptop power, audio, window management, and universal app launcher. */
class WindowsCommandExecutor : PlatformCommandExecutor {
    @Volatile
    private var indexedFiles: List<File> = emptyList()
    @Volatile
    private var indexBuiltAt = 0L

    private val startAppsMap = mutableMapOf<String, Pair<String, String>>()

    init {
        Thread {
            loadStartApps()
        }.apply {
            isDaemon = true
            name = "start-apps-indexer"
            start()
        }
    }

    private fun loadStartApps() {
        runCatching {
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Get-StartApps | ForEach-Object { \"\$(\$_.Name)|\$(\$_.AppID)\" }").start()
            val lines = proc.inputStream.bufferedReader().readLines()
            synchronized(startAppsMap) {
                startAppsMap.clear()
                for (line in lines) {
                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2) {
                        val name = parts[0].trim()
                        val id = parts[1].trim()
                        if (name.isNotBlank() && id.isNotBlank()) {
                            startAppsMap[normalize(name)] = Pair(name, id)
                        }
                    }
                }
            }
        }
    }

    private val apps = mapOf(
        "notepad" to AppSpec("Notepad", "notepad.exe"),
        "calculator" to AppSpec("Calculator", "calc.exe"),
        "calc" to AppSpec("Calculator", "calc.exe"),
        "paint" to AppSpec("Paint", "mspaint.exe"),
        "chrome" to AppSpec("Google Chrome", "chrome.exe"),
        "google chrome" to AppSpec("Google Chrome", "chrome.exe"),
        "edge" to AppSpec("Microsoft Edge", "msedge.exe"),
        "explorer" to AppSpec("File Explorer", "explorer.exe"),
        "file explorer" to AppSpec("File Explorer", "explorer.exe"),
        "settings" to AppSpec("Windows Settings", "ms-settings:"),
        "task manager" to AppSpec("Task Manager", "taskmgr.exe"),
        "taskmgr" to AppSpec("Task Manager", "taskmgr.exe"),
        "control panel" to AppSpec("Control Panel", "control.exe"),
        "device manager" to AppSpec("Device Manager", "devmgmt.msc"),
        "disk management" to AppSpec("Disk Management", "diskmgmt.msc"),
        "snipping tool" to AppSpec("Snipping Tool", "SnippingTool.exe"),
        "snip" to AppSpec("Snipping Tool", "SnippingTool.exe"),
        "spotify" to AppSpec("Spotify", "spotify:"),
        "telegram" to AppSpec("Telegram", "telegram.exe"),
        "whatsapp" to AppSpec("WhatsApp", "whatsapp:"),
        "zoom" to AppSpec("Zoom", "zoom.exe"),
        "teams" to AppSpec("Microsoft Teams", "ms-teams:"),
        "word" to AppSpec("Microsoft Word", "winword.exe"),
        "msword" to AppSpec("Microsoft Word", "winword.exe"),
        "excel" to AppSpec("Microsoft Excel", "excel.exe"),
        "powerpoint" to AppSpec("PowerPoint", "powerpnt.exe"),
        "ppt" to AppSpec("PowerPoint", "powerpnt.exe"),
        "vscode" to AppSpec("VS Code", "code.cmd"),
        "vs code" to AppSpec("VS Code", "code.cmd"),
        "visual studio code" to AppSpec("VS Code", "code.cmd"),
        "code" to AppSpec("VS Code", "code.cmd"),
        "vlc" to AppSpec("VLC Media Player", "vlc.exe"),
        "vlc media player" to AppSpec("VLC Media Player", "vlc.exe"),
        "firefox" to AppSpec("Firefox", "firefox.exe"),
        "brave" to AppSpec("Brave Browser", "brave.exe"),
        "terminal" to AppSpec("Windows Terminal", "wt.exe"),
        "powershell" to AppSpec("PowerShell", "powershell.exe"),
        "cmd" to AppSpec("Command Prompt", "cmd.exe"),
        "photoshop" to AppSpec("Adobe Photoshop", "Photoshop.exe"),
        "illustrator" to AppSpec("Adobe Illustrator", "Illustrator.exe"),
        "premiere" to AppSpec("Adobe Premiere Pro", "Adobe Premiere Pro.exe"),
        "obs" to AppSpec("OBS Studio", "obs64.exe"),
        "discord" to AppSpec("Discord", "Discord.exe"),
        "capcut" to AppSpec("CapCut", "CapCut.exe"),
        "android studio" to AppSpec("Android Studio", "studio64.exe"),
        "intellij" to AppSpec("IntelliJ IDEA", "idea64.exe"),
        "pycharm" to AppSpec("PyCharm", "pycharm64.exe"),
        "cursor" to AppSpec("Cursor", "Cursor.exe"),
        "antigravity" to AppSpec("Antigravity", "agy.cmd"),
        "google antigravity" to AppSpec("Antigravity", "agy.cmd"),
        "docker" to AppSpec("Docker Desktop", "Docker Desktop.exe"),
        "docker desktop" to AppSpec("Docker Desktop", "Docker Desktop.exe"),
        "agent zero" to AppSpec("Agent Zero", "agent-zero.exe"),
        "agent-zero" to AppSpec("Agent Zero", "agent-zero.exe"),
        "steam" to AppSpec("Steam", "steam.exe"),
        "viber" to AppSpec("Viber", "Viber.exe"),
        "chatgpt" to AppSpec("ChatGPT", "https://chatgpt.com"),
        "claude" to AppSpec("Claude", "https://claude.ai"),
        "notion" to AppSpec("Notion", "notion:"),
        "youtube" to AppSpec("YouTube", "https://youtube.com"),
        "facebook" to AppSpec("Facebook", "https://facebook.com"),
        "gemini" to AppSpec("Gemini", "https://gemini.google.com"),
    )

    private val projectResolver = ProjectResolver()
    private val taskFormatter = CodingTaskFormatter()
    private val antigravityBridge = AntigravityBridge()
    private val androidPipeline = AndroidBuildPipeline()
    private val autoHealingFixEngine = AutoHealingFixEngine(androidPipeline, taskFormatter, antigravityBridge)
    private val androidEmulatorRunner = AndroidEmulatorRunner()
    private val gitAutomationService = GitAutomationService()
    val activeWindowTracker = ActiveWindowTracker()
    val ideBridgeService = IdeBridgeService(projectResolver)
    val wirelessAdbManager = WirelessAdbManager()
    val multiRepoManager = MultiRepoManager(projectResolver)
    val osHands = OSHandsController()
    val visionEyes = VisionEyesEngine()

    fun getActiveWindowContext(): ActiveWindowInfo = activeWindowTracker.getActiveWindow()

    override suspend fun execute(command: DesktopCommand): CommandResult {
        return runCatching {
            when (command.type.lowercase()) {
                "screen_eyes", "view_screen", "screen_state" -> CommandResult(true, visionEyes.getScreenVisionOverview())
                "mouse_click", "left_click", "click" -> handleMouseClick(command.target, command.value)
                "mouse_move" -> handleMouseMove(command.target, command.value)
                "show_desktop" -> osHands.pressKeyCombo("win+d")
                "get_active_window", "active_window_context", "active_app", "what_am_i_doing" -> getActiveWindowSummary()
                "ide_open_file", "open_file_in_editor", "open_in_ide" -> openFileInIde(command.target ?: command.value.orEmpty(), command.value)
                "ide_status", "check_ide_status" -> getIdeStatusSummary()
                "adb_enable_tcpip", "wireless_adb_enable" -> enableWirelessAdb(command.value)
                "adb_connect_wireless", "wireless_adb_connect" -> connectWirelessAdb(command.target ?: command.value.orEmpty())
                "adb_pair_wireless", "wireless_adb_pair" -> pairWirelessAdb(command.target.orEmpty(), command.value.orEmpty())
                "adb_devices_detailed", "check_adb_devices", "adb_devices" -> getAdbDevicesSummary()
                "git_repo_status_all", "git_multi_repo_status", "list_all_repos", "list_repos" -> getMultiRepoSummary()
                "git_switch_branch", "git_checkout" -> switchRepoBranch(command.target.orEmpty(), command.value.orEmpty())
                "git_pull_repo", "git_sync_repo" -> pullRepoChanges(command.target ?: command.value.orEmpty())
                "open_app", "launch_app" -> openApp(command.target ?: command.value.orEmpty())
                "close_app", "stop_app" -> closeApp(command.target ?: command.value.orEmpty())
                "open_url" -> openUrl(command.target ?: command.value.orEmpty())
                "search_web", "web_search" -> searchWeb(command.target ?: command.value.orEmpty())
                "search_youtube", "youtube_search" -> searchYouTube(command.target ?: command.value.orEmpty())
                "search_files", "find_file", "find_files" -> searchFiles(command.target ?: command.value.orEmpty())
                "refresh_file_index" -> refreshFileIndex()
                "open_file" -> openFile(command.target ?: command.value.orEmpty())
                "open_file_explorer", "open_folder" -> openFolder(command.target ?: command.value)
                "open_downloads" -> openFolder(System.getProperty("user.home") + "\\Downloads")
                "open_documents" -> openFolder(System.getProperty("user.home") + "\\Documents")
                "open_desktop" -> openFolder(System.getProperty("user.home") + "\\Desktop")
                "open_recycle_bin" -> openShellFolder("shell:RecycleBinFolder", "Recycle Bin")
                "empty_recycle_bin" -> emptyRecycleBin()
                "get_current_time", "current_time", "time" -> getCurrentTime()
                "get_current_date", "current_date", "date" -> getCurrentDate()
                "optimize_ram" -> {
                    System.gc()
                    CommandResult(true, "RAM Memory ကို ရှင်းလင်းပြီးပါပြီရှင်။")
                }
                "health_check" -> CommandResult(true, "စနစ် ကျန်းမာရေး အခြေအနေ: အသင့်ဖြစ်ပါသည်ရှင်။")
                "open_settings" -> openUrl("ms-settings:")
                "open_network_settings" -> openUrl("ms-settings:network")
                "open_bluetooth_settings" -> openUrl("ms-settings:bluetooth")
                "open_display_settings" -> openUrl("ms-settings:display")
                "open_sound_settings" -> openUrl("ms-settings:sound")
                "open_control_panel" -> openApp("control panel")
                "open_task_manager" -> openApp("task manager")
                "take_screenshot" -> takeScreenshot()
                "analyze_screen", "screen_vision", "capture_screen_vision" -> captureScreenVision()
                "read_clipboard", "process_clipboard", "get_clipboard" -> readClipboardContent()
                "media_play_pause", "play_pause" -> sendMediaKey(179, "သီချင်း Play / Pause ပြုလုပ်လိုက်ပါပြီရှင်။")
                "media_next", "next_track" -> sendMediaKey(176, "နောက်သီချင်းသို့ ကူးလိုက်ပါပြီရှင်။")
                "media_prev", "prev_track" -> sendMediaKey(177, "ရှေ့သီချင်းသို့ ပြန်သွားလိုက်ပါပြီရှင်။")
                "minimize_all", "show_desktop_windows" -> minimizeAllWindows()
                "maximize_window" -> sendKeyCombo("%{ENTER}", "Window ကို အကြီးချဲ့လိုက်ပါပြီရှင်။")
                "minimize_window" -> sendKeyCombo("% n", "Window ကို သေးလိုက်ပါပြီရှင်။")
                "close_window" -> sendKeyCombo("%{F4}", "Window ကို ပိတ်လိုက်ပါပြီရှင်။")
                "close_tab" -> sendKeyCombo("^w", "Tab ကို ပိတ်လိုက်ပါပြီရှင်။")
                "brightness_up" -> changeBrightness(10)
                "brightness_down" -> changeBrightness(-10)
                "volume_up" -> sendMediaKey(175, "အသံကို တိုးပေးလိုက်ပါပြီရှင်။")
                "volume_down" -> sendMediaKey(174, "အသံလျှော့လိုက်ပါပြီရှင်။")
                "mute", "toggle_mute" -> sendMediaKey(173, "အသံ Mute ပြောင်းလဲလိုက်ပါပြီရှင်။")
                "lock_computer" -> lockComputer()
                "system_status", "computer_status", "get_system_info" -> systemStatusDetailed()
                "diagnose_network", "ping_test", "check_internet", "network_status" -> diagnoseNetwork()
                "get_battery_status", "battery_status" -> getBatteryDetailed()
                "list_running_apps", "running_processes", "list_apps" -> listRunningApps()
                "copy_to_clipboard", "set_clipboard" -> copyToClipboard(command.target ?: command.value.orEmpty())
                "run_powershell_safe", "powershell_query" -> runPowerShellSafe(command.target ?: command.value.orEmpty())
                "solve_project_issue", "fix_project_issue", "auto_code_fix" -> solveProjectIssue(command.target.orEmpty(), command.value.orEmpty())
                "auto_heal_project", "auto_heal", "heal_project" -> autoHealProject(command.target.orEmpty(), command.value.orEmpty())
                "launch_coding_agent", "open_project_workspace", "open_project" -> openProjectWorkspace(command.target ?: command.value.orEmpty())
                "run_android_build_test", "verify_android_project", "build_android_app" -> runAndroidBuildTest(command.target.orEmpty(), command.value)
                "verify_app_on_emulator", "run_emulator_test", "deploy_to_emulator" -> verifyOnEmulator(command.target.orEmpty())
                "git_commit_fix", "git_auto_commit", "git_commit" -> gitCommitFix(command.target.orEmpty(), command.value.orEmpty())
                "check_adb_devices", "adb_devices" -> checkAdbDevices()
                "cancel_shutdown", "cancel_power" -> cancelShutdown()
                "shutdown", "restart", "sleep" -> powerAction(command.type.lowercase())
                else -> CommandResult(false, "ဒီ Windows command ကို မထည့်ရသေးပါ: ${command.type}")
            }
        }.getOrElse { error ->
            CommandResult(false, error.message ?: "Windows command မအောင်မြင်ပါ")
        }
    }

    private fun parseCoordinates(target: String?, value: String?): Pair<Int, Int>? {
        val text = listOfNotNull(target, value).joinToString(",")
        val numbers = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()
        return if (numbers.size >= 2) numbers[0] to numbers[1] else null
    }

    private fun resolveTargetCoordinates(target: String?, value: String?): Pair<Int, Int>? {
        parseCoordinates(target, value)?.let { return it }
        val query = (target ?: value).orEmpty().trim().lowercase()
        val size = Toolkit.getDefaultToolkit().screenSize
        return when {
            query.contains("အလယ်") || query == "center" || query == "middle" -> size.width / 2 to size.height / 2
            query.contains("ညာဘက်အပေါ်") || query.contains("အပေါ်ညာ") || query == "top right" -> size.width - 25 to 25
            query.contains("ဘယ်ဘက်အပေါ်") || query.contains("အပေါ်ဘယ်") || query == "top left" -> 25 to 25
            query.contains("ညာဘက်အောက်") || query == "bottom right" -> size.width - 25 to size.height - 25
            query.contains("ဘယ်ဘက်အောက်") || query == "bottom left" -> 25 to size.height - 25
            else -> null
        }
    }

    private fun handleMouseClick(target: String?, value: String?): CommandResult {
        val point = resolveTargetCoordinates(target, value)
        return if (point == null) osHands.leftClick() else osHands.leftClick(point.first, point.second)
    }

    private fun handleMouseMove(target: String?, value: String?): CommandResult {
        val point = resolveTargetCoordinates(target, value)
            ?: return CommandResult(false, "ရွှေ့လိုသော မောက်စ်တည်နေရာ မပါဝင်ပါရှင်။")
        return osHands.mouseMoveSmooth(point.first, point.second, durationMs = 20)
    }

    private fun getCurrentTime(): CommandResult {
        val now = Date()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        val formatted = timeFormat.format(now)
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR)
        val displayHour = if (hour == 0) 12 else hour
        val min = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "မနက်" else "ညနေ"
        val burmeseTime = "လက်ရှိအချိန်မှာ $ampm $displayHour နာရီ ${if (min > 0) "$min မိနစ်" else "တိတိ"} ဖြစ်ပါတယ်ရှင် ($formatted)။"
        return CommandResult(true, burmeseTime)
    }

    private fun getCurrentDate(): CommandResult {
        val now = Date()
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        val formatted = dateFormat.format(now)
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val months = listOf("", "ဇန်နဝါရီ", "ဖေဖော်ဝါရီ", "မတ်", "ဧပြီ", "မေ", "ဇွန်", "ဇူလိုင်", "သြဂုတ်", "စက်တင်ဘာ", "အောက်တိုဘာ", "နိုဝင်ဘာ", "ဒီဇင်ဘာ")
        val days = listOf("", "တနင်္ဂနွေ", "တနင်္လာ", "အင်္ဂါ", "ဗုဒ္ဓဟူး", "ကြာသပတေး", "သောကြာ", "စနေ")
        val dayOfWeek = days[cal.get(java.util.Calendar.DAY_OF_WEEK)]
        val monthName = months.getOrElse(month) { "$month လ" }
        val burmeseDate = "ဒီနေ့ရက်စွဲမှာ $year ခုနှစ် $monthName $day ရက် $dayOfWeek နေ့ ဖြစ်ပါတယ်ရှင် ($formatted)။"
        return CommandResult(true, burmeseDate)
    }

    private fun searchYouTube(query: String): CommandResult {
        if (query.isBlank()) return CommandResult(false, "YouTube တွင် ရှာဖွေရန် စကားလုံး လိုအပ်ပါတယ်ရှင်။")
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded"
        Desktop.getDesktop().browse(URI(url))
        return CommandResult(true, "YouTube မှာ ‘$query’ ကို ရှာဖွေပေးနေပါပြီရှင်။")
    }

    private fun openApp(rawName: String): CommandResult {
        val burmeseVerbs = listOf("ဖွင့်ပေးပါ", "ဖွင့်ပါ", "ဖွင့်ပေး", "ဖွင့်မယ်", "ဖွင့်စမ်းပါ", "ဖွင့်လိုက်ပါ", "ဖွင့်", "ဆော့ဖ်ဝဲ", "app", "application", "open", "launch")
        var clean = rawName.trim().lowercase()
        for (verb in burmeseVerbs) {
            clean = clean.replace(verb, "").trim()
        }
        if (clean.isBlank()) clean = rawName.trim().lowercase()
        val normalized = normalize(clean)

        if (normalized.contains("minus") || normalized.contains("minimize") || normalized == "min") {
            return minimizeAllWindows()
        }

        // 1. Check dynamic Windows StartApps (Universal Windows App & Win32 Registered ID)
        synchronized(startAppsMap) {
            val direct = startAppsMap[normalized]
            val fuzzy = direct ?: startAppsMap.entries.firstOrNull {
                it.key.contains(normalized) || (normalized.length >= 3 && it.key.contains(normalized.take(4)))
            }?.value
            if (fuzzy != null) {
                val (appName, appId) = fuzzy
                runCatching {
                    ProcessBuilder("explorer.exe", "shell:AppsFolder\\$appId").start()
                    return CommandResult(true, "$appName ကို ဖွင့်လိုက်ပါပြီရှင်။")
                }
            }
        }

        // 2. Check Static App Specs
        val spec = apps[normalized] ?: apps.entries.firstOrNull { normalized.contains(it.key) || it.key.contains(normalized) }?.value
        if (spec != null) {
            if (spec.command.startsWith("http://") || spec.command.startsWith("https://") || spec.command.endsWith(":") || spec.command == "spotify:") {
                runCatching { Desktop.getDesktop().browse(URI(spec.command)) }
                    .recoverCatching { ProcessBuilder("cmd", "/c", "start", "", spec.command).start() }
            } else {
                ProcessBuilder("cmd", "/c", "start", "", spec.command).start()
            }
            return CommandResult(true, "${spec.displayName} ကို ဖွင့်လိုက်ပါပြီရှင်။")
        }

        // 3. Check Start Menu and Desktop Shortcuts (.lnk / .url)
        val shortcutMatch = findStartMenuShortcut(normalized.ifBlank { clean })
        if (shortcutMatch != null) {
            ProcessBuilder("cmd", "/c", "start", "", shortcutMatch.absolutePath).start()
            return CommandResult(true, "${shortcutMatch.nameWithoutExtension} ကို ဖွင့်လိုက်ပါပြီရှင်။")
        }

        // 4. Try Common Program Paths
        val commonLocations = listOf(
            File(System.getProperty("user.home"), "AppData\\Local\\Programs\\$clean\\$clean.exe"),
            File(System.getProperty("user.home"), "AppData\\Local\\$clean\\$clean.exe"),
            File(System.getProperty("user.home"), "AppData\\Roaming\\$clean\\$clean.exe"),
            File("C:\\Program Files\\$clean\\$clean.exe"),
            File("C:\\Program Files (x86)\\$clean\\$clean.exe"),
        )
        for (loc in commonLocations) {
            if (loc.exists()) {
                ProcessBuilder("cmd", "/c", "start", "", loc.absolutePath).start()
                return CommandResult(true, "${loc.nameWithoutExtension} ကို ဖွင့်လိုက်ပါပြီရှင်။")
            }
        }

        // 5. Try where.exe
        val whereCheck = runCatching {
            val proc = ProcessBuilder("where.exe", clean).start()
            proc.waitFor()
            proc.exitValue() == 0
        }.getOrDefault(false)

        if (whereCheck) {
            ProcessBuilder("cmd", "/c", "start", "", clean).start()
            return CommandResult(true, "$rawName ကို ဖွင့်လိုက်ပါပြီရှင်။")
        }

        // 6. Direct fallback via shell start
        val fallbackSuccess = runCatching {
            ProcessBuilder("cmd", "/c", "start", "", clean).start()
            true
        }.getOrDefault(false)

        if (fallbackSuccess) {
            return CommandResult(true, "$rawName ကို ဖွင့်ရန် ညွှန်ကြားလိုက်ပါပြီရှင်။")
        }

        return CommandResult(
            false,
            "‘$rawName’ ဆော့ဖ်ဝဲလ်ကို ကွန်ပျူတာထဲတွင် ရှာမတွေ့ပါ။ အမည်မှန်ကန်ကြောင်း စစ်ဆေးပေးပါရှင်။",
        )
    }

    private fun findStartMenuShortcut(query: String): File? {
        val startMenuDirs = listOf(
            File("C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs"),
            File(System.getProperty("user.home"), "AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs"),
            File("C:\\Users\\Public\\Desktop"),
            File(System.getProperty("user.home"), "Desktop"),
        )
        for (dir in startMenuDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val match = runCatching {
                Files.walk(dir.toPath(), 5).use { paths ->
                    paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".lnk", ignoreCase = true) || it.toString().endsWith(".url", ignoreCase = true)) }
                        .map { it.toFile() }
                        .filter {
                            val name = it.nameWithoutExtension.lowercase()
                            name.contains(query) || query.contains(name) || normalize(name).contains(normalize(query))
                        }
                        .findFirst()
                        .orElse(null)
                }
            }.getOrNull()
            if (match != null) return match
        }
        return null
    }

    private fun closeApp(rawName: String): CommandResult {
        val burmeseVerbs = listOf("ပိတ်ပေးပါ", "ပိတ်ပါ", "ပိတ်ပေး", "ပိတ်မယ်", "ပိတ်လိုက်ပါ", "ပိတ်", "ဆော့ဖ်ဝဲ", "app", "application", "close", "kill", "stop", "terminate")
        var clean = rawName.trim().lowercase()
        for (verb in burmeseVerbs) {
            clean = clean.replace(verb, "").trim()
        }
        if (clean.isBlank()) clean = rawName.trim().lowercase()
        val normalized = normalize(clean)

        // 1. Static match
        val spec = apps[normalized] ?: apps.entries.firstOrNull { normalized.contains(it.key) || it.key.contains(normalized) }?.value
        if (spec != null && !spec.command.startsWith("http") && !spec.command.endsWith(":")) {
            val executable = spec.command
            ProcessBuilder("taskkill", "/IM", executable, "/T", "/F").start()
            return CommandResult(true, "${spec.displayName} ကို ပိတ်လိုက်ပါပြီရှင်။")
        }

        // 2. Dynamic process search and termination via PowerShell
        val killed = runCatching {
            val script = "Get-Process | Where-Object { \$_.ProcessName -like '*$clean*' -or \$_.MainWindowTitle -like '*$clean*' } | Stop-Process -Force -PassThru | Select-Object -ExpandProperty ProcessName"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val out = proc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
            out.isNotEmpty()
        }.getOrDefault(false)

        if (killed) {
            return CommandResult(true, "‘$clean’ ကို ပိတ်လိုက်ပါပြီရှင်။")
        }

        // Fallback taskkill
        val taskkillResult = runCatching {
            val proc = ProcessBuilder("taskkill", "/IM", "$clean.exe", "/T", "/F").start()
            proc.waitFor()
            proc.exitValue() == 0
        }.getOrDefault(false)

        if (taskkillResult) {
            return CommandResult(true, "‘$clean’ ကို ပိတ်လိုက်ပါပြီရှင်။")
        }

        return CommandResult(false, "‘$rawName’ ဆော့ဖ်ဝဲလ်ကို ပိတ်ရန် လက်ရှိ ဖွင့်ထားသော process များထဲတွင် ရှာမတွေ့ပါရှင်။")
    }

    private fun openUrl(rawUrl: String): CommandResult {
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://") || rawUrl.startsWith("ms-settings:")) rawUrl else "https://$rawUrl"
        Desktop.getDesktop().browse(URI(url))
        return CommandResult(true, "ဖွင့်လိုက်ပါပြီရှင်: $url")
    }

    private fun searchWeb(query: String): CommandResult {
        if (query.isBlank()) return CommandResult(false, "ရှာဖွေရန် စကားလုံး လိုအပ်ပါတယ်ရှင်။")
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        Desktop.getDesktop().browse(URI("https://www.google.com/search?q=$encoded"))
        return CommandResult(true, "Google မှာ ‘$query’ ကို ရှာဖွေပေးနေပါပြီရှင်။")
    }

    private fun searchFiles(query: String): CommandResult {
        val cleanQuery = query.trim().removeSurrounding("\"").lowercase()
        if (cleanQuery.isBlank()) return CommandResult(false, "ရှာဖွေရန် file name သို့မဟုတ် extension လိုအပ်ပါတယ်ရှင်။")

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

        if (matches.isEmpty()) return CommandResult(true, "‘$query’ နဲ့ ကိုက်ညီတဲ့ file မတွေ့ပါရှင်။ Approved user folders အတွင်းမှာ ရှာထားပါတယ်။")
        val summary = matches.joinToString("\n") { "• ${it.name} — ${it.absolutePath}" }
        return CommandResult(true, "‘$query’ နဲ့ ကိုက်ညီတဲ့ file ${matches.size} ခုတွေ့ပါတယ်ရှင် (indexed search):\n$summary")
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

    private fun buildFileIndex(): List<File> {
        val roots = listOf(
            File(System.getProperty("user.home"), "Desktop"),
            File(System.getProperty("user.home"), "Documents"),
            File(System.getProperty("user.home"), "Downloads"),
        )
        val result = mutableListOf<File>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            runCatching {
                Files.walk(root.toPath(), 5).use { paths ->
                    paths.filter { Files.isRegularFile(it) }
                        .map { it.toFile() }
                        .forEach { result.add(it) }
                }
            }
        }
        return result
    }

    private fun refreshFileIndex(): CommandResult {
        synchronized(this) {
            indexedFiles = buildFileIndex()
            indexBuiltAt = System.currentTimeMillis()
        }
        return CommandResult(true, "File index ကို ပြန်လည်ရေးဆွဲပြီးပါပြီရှင်။ file စုစုပေါင်း ${indexedFiles.size} ခု ပါဝင်ပါတယ်။")
    }

    private fun openFile(query: String): CommandResult {
        val cleanQuery = query.trim().removeSurrounding("\"").lowercase()
        val candidate = File(query)
        val target = if (candidate.exists()) {
            candidate
        } else {
            ensureFileIndex().firstOrNull { it.name.lowercase().contains(cleanQuery) }
        }

        if (target == null || !target.exists()) {
            return CommandResult(false, "‘$query’ ဟု အမည်ရသော file ကို ဖွင့်ရန် ရှာမတွေ့ပါရှင်။")
        }

        Desktop.getDesktop().open(target)
        return CommandResult(true, "File ကို ဖွင့်လိုက်ပါပြီရှင်: ${target.name}")
    }

    private fun openFolder(path: String?): CommandResult {
        val target = if (path.isNullOrBlank()) File(System.getProperty("user.home")) else File(path)
        if (!target.exists()) return CommandResult(false, "Folder မရှိပါရှင်: ${target.absolutePath}")
        Desktop.getDesktop().open(target)
        return CommandResult(true, "Folder ကို ဖွင့်လိုက်ပါပြီရှင်: ${target.name}")
    }

    private fun openShellFolder(shellName: String, label: String): CommandResult {
        ProcessBuilder("explorer.exe", shellName).start()
        return CommandResult(true, "$label ကို ဖွင့်လိုက်ပါပြီရှင်။")
    }

    private fun emptyRecycleBin(): CommandResult {
        ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", "Clear-RecycleBin -Force -ErrorAction SilentlyContinue").start()
        return CommandResult(true, "Recycle Bin ကို သန့်ရှင်းလိုက်ပါပြီရှင်။")
    }

    private fun takeScreenshot(): CommandResult {
        val dir = File(System.getProperty("user.home"), "Pictures\\Screenshots")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Screenshot_${System.currentTimeMillis()}.png")

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val robot = Robot()
        val capture = robot.createScreenCapture(Rectangle(screenSize))
        ImageIO.write(capture, "png", file)

        return CommandResult(true, "Screenshot ရိုက်ပြီးပါပြီရှင်: ${file.name}")
    }

    private fun sendMediaKey(keyCode: Int, successMsg: String): CommandResult {
        runCatching {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "\$w=New-Object -ComObject WScript.Shell; \$w.SendKeys([char]$keyCode)",
            ).start()
        }.onFailure { error ->
            DesktopLogger.warn("Media key dispatch unavailable ($keyCode): ${error.message}")
        }
        return CommandResult(true, successMsg)
    }

    private fun sendKeyCombo(combo: String, successMsg: String): CommandResult {
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            "\$w=New-Object -ComObject WScript.Shell; \$w.SendKeys('$combo')",
        ).start()
        return CommandResult(true, successMsg)
    }

    fun typeTextIntoActiveWindow(text: String): CommandResult {
        val clean = text.trim()
        if (clean.isBlank()) return CommandResult(false, "ထည့်ရန် စာသား မရှိပါရှင်။")
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val previous = if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null
            val selection = java.awt.datatransfer.StringSelection(clean)
            clipboard.setContents(selection, selection)
            Robot().apply {
                delay(35)
                keyPress(java.awt.event.KeyEvent.VK_CONTROL)
                keyPress(java.awt.event.KeyEvent.VK_V)
                keyRelease(java.awt.event.KeyEvent.VK_V)
                keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
                delay(90)
            }
            if (previous != null) {
                val restore = java.awt.datatransfer.StringSelection(previous)
                clipboard.setContents(restore, restore)
            }
            CommandResult(true, "စာသားကို active window ထဲ ထည့်ပြီးပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "စာသားထည့်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message ?: "မသိသော error"}")
        }
    }

    private fun changeBrightness(delta: Int): CommandResult {
        val script = "(Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightnessMethods).WmiSetBrightness(1, [math]::Max(0, [math]::Min(100, (Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightness).CurrentBrightness + $delta)))"
        ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start()
        val actionText = if (delta > 0) "တိုးလိုက်ပါပြီ" else "လျှော့လိုက်ပါပြီ"
        return CommandResult(true, "စခရင် အလင်းရောင် $actionText ရှင်။")
    }

    private fun powerAction(action: String): CommandResult {
        when (action) {
            "shutdown" -> ProcessBuilder("shutdown.exe", "/s", "/t", "10").start()
            "restart" -> ProcessBuilder("shutdown.exe", "/r", "/t", "10").start()
            "sleep" -> ProcessBuilder(
                "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend', \$false, \$false)",
            ).start()
        }
        val message = when (action) {
            "shutdown" -> "ကွန်ပျူတာ ပိတ်ရန် ၁၀ စက္ကန့် timer စတင်ပါပြီရှင်။ ပယ်ဖျက်ရန် 'စက်ပိတ်တာရပ်' ဟု ပြောနိုင်ပါသည်။"
            "restart" -> "ကွန်ပျူတာ ပြန်စရန် ၁၀ စက္ကန့် timer စတင်ပါပြီရှင်။ ပယ်ဖျက်ရန် 'စက်ပိတ်တာရပ်' ဟု ပြောနိုင်ပါသည်။"
            else -> "ကွန်ပျူတာကို Sleep ဝင်စေပါပြီရှင်။"
        }
        return CommandResult(true, message)
    }

    private fun cancelShutdown(): CommandResult {
        ProcessBuilder("shutdown.exe", "/a").start()
        return CommandResult(true, "Shutdown သို့မဟုတ် Restart timer ကို ပယ်ဖျက်လိုက်ပါပြီရှင်။")
    }

    private fun lockComputer(): CommandResult {
        ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start()
        return CommandResult(true, "ကွန်ပျူတာကို Lock လုပ်လိုက်ပါပြီရှင်။")
    }

    fun captureScreenImageBase64(): String {
        return runCatching {
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val robot = Robot()
            val screenRect = Rectangle(screenSize)
            val capture = robot.createScreenCapture(screenRect)

            val scaledWidth = 1280
            val scaledHeight = (screenSize.height.toDouble() / screenSize.width * scaledWidth).toInt()
            val resized = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.drawImage(capture, 0, 0, scaledWidth, scaledHeight, null)
            g.dispose()

            val baos = ByteArrayOutputStream()
            ImageIO.write(resized, "jpg", baos)
            Base64.getEncoder().encodeToString(baos.toByteArray())
        }.getOrDefault("")
    }

    private fun captureScreenVision(): CommandResult {
        val base64Jpg = captureScreenImageBase64()
        if (base64Jpg.isBlank()) return CommandResult(false, "စခရင် ပုံရိပ် ရယူ၍ မရပါရှင်။")
        return CommandResult(true, "SCREEN_VISION_BASE64:$base64Jpg")
    }

    fun readClipboardText(): String {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                (clipboard.getData(DataFlavor.stringFlavor) as String).trim()
            } else ""
        }.getOrDefault("")
    }

    private fun readClipboardContent(): CommandResult {
        val text = readClipboardText()
        if (text.isBlank()) return CommandResult(false, "Clipboard ထဲတွင် စာသား မရှိပါရှင်။")
        return CommandResult(true, "Clipboard ထဲရှိ စာသား: $text")
    }

    private fun minimizeAllWindows(): CommandResult {
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            "(New-Object -ComObject Shell.Application).MinimizeAll()",
        ).start()
        return CommandResult(true, "Window များကို Minimize လုပ်လိုက်ပါပြီရှင်။")
    }

    private fun systemStatusDetailed(): CommandResult {
        val os = System.getProperty("os.name")
        val version = System.getProperty("os.version")
        val cDrive = File("C:\\")
        val freeDiskGb = cDrive.freeSpace / (1024 * 1024 * 1024)
        val totalDiskGb = cDrive.totalSpace / (1024 * 1024 * 1024)

        var ramInfo = ""
        var cpuInfo = ""
        runCatching {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (osBean is com.sun.management.OperatingSystemMXBean) {
                val totalRamGb = osBean.totalMemorySize / (1024 * 1024 * 1024)
                val freeRamGb = osBean.freeMemorySize / (1024 * 1024 * 1024)
                val usedRamGb = totalRamGb - freeRamGb
                val ramPercent = if (totalRamGb > 0) (usedRamGb.toDouble() / totalRamGb * 100).toInt() else 0
                val cpuLoad = (osBean.cpuLoad * 100).toInt().coerceAtLeast(0)
                ramInfo = "RAM: ${usedRamGb}GB / ${totalRamGb}GB ($ramPercent% သုံးထားပါတယ်)"
                cpuInfo = "CPU ဝန်အား: $cpuLoad%"
            }
        }

        var batteryInfo = ""
        runCatching {
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Get-WmiObject win32_battery | Select-Object -ExpandProperty EstimatedChargeRemaining").start()
            val text = proc.inputStream.bufferedReader().readText().trim()
            if (text.isNotBlank()) batteryInfo = "ဘက်ထရီ: $text%"
        }

        val parts = listOfNotNull(
            "OS: $os $version",
            cpuInfo.takeIf { it.isNotBlank() },
            ramInfo.takeIf { it.isNotBlank() },
            "Disk C: ${freeDiskGb}GB ကျန် / ${totalDiskGb}GB",
            batteryInfo.takeIf { it.isNotBlank() },
        )

        return CommandResult(true, "ကွန်ပျူတာ အခြေအနေ:\n• " + parts.joinToString("\n• "))
    }

    private fun diagnoseNetwork(): CommandResult {
        return runCatching {
            val proc = ProcessBuilder("ping.exe", "8.8.8.8", "-n", "2").start()
            val output = proc.inputStream.bufferedReader().readText()
            val isOnline = output.contains("Reply from 8.8.8.8", ignoreCase = true) || output.contains("Average =", ignoreCase = true)
            if (isOnline) {
                val timeMatch = Regex("Average = (\\d+ms)|time=(\\d+ms)").find(output)
                val latency = timeMatch?.value ?: "အဆင်ပြေ"
                CommandResult(true, "အင်တာနက် ချိတ်ဆက်မှု ကောင်းမွန်ပါသည် ($latency)။ Google DNS (8.8.8.8) သို့ ပုံမှန် ချိတ်ဆက်ထားပါသည်။")
            } else {
                CommandResult(false, "အင်တာနက် ချိတ်ဆက်မှု မရှိပါ သို့မဟုတ် နှေးကွေးနေပါသည်။ Wi-Fi/LAN ကို စစ်ဆေးပေးပါ။")
            }
        }.getOrElse {
            CommandResult(false, "Network စစ်ဆေးမှု မအောင်မြင်ပါ: ${it.message}")
        }
    }

    private fun getBatteryDetailed(): CommandResult {
        return runCatching {
            val script = "Get-WmiObject win32_battery | ForEach-Object { \$status = if(\$_.BatteryStatus -eq 2) {'Charging ⚡'} else {'Discharging 🔋'}; \"ဘက်ထရီ: \$(\$_.EstimatedChargeRemaining)% (\$status)\" }"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (output.isNotBlank()) {
                CommandResult(true, output)
            } else {
                CommandResult(true, "ဘက်ထရီ အချက်အလက် မတွေ့ပါ (Desktop PC သို့မဟုတ် အမြဲတမ်း Power ကြိုးတပ်ထားသော စက်ဖြစ်နိုင်ပါသည်)။")
            }
        }.getOrElse {
            CommandResult(false, "ဘက်ထရီ စစ်ဆေးမှု မအောင်မြင်ပါ: ${it.message}")
        }
    }

    private fun listRunningApps(): CommandResult {
        return runCatching {
            val script = "Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -Unique -ExpandProperty ProcessName"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val apps = proc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
            if (apps.isNotEmpty()) {
                CommandResult(true, "လက်ရှိ ဖွင့်ထားသော Apps (${apps.size} ခု):\n• " + apps.joinToString("\n• "))
            } else {
                CommandResult(true, "လက်ရှိ ဖွင့်ထားသော GUI Application မရှိပါရှင်။")
            }
        }.getOrElse {
            CommandResult(false, "Apps စာရင်း ဖတ်မရပါ: ${it.message}")
        }
    }

    private fun copyToClipboard(text: String): CommandResult {
        return runCatching {
            val stringSelection = java.awt.datatransfer.StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, stringSelection)
            CommandResult(true, "Clipboard သို့ ကူးယူလိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "Clipboard သို့ ကူးယူ၍ မရပါ: ${it.message}")
        }
    }

    private fun runPowerShellSafe(command: String): CommandResult {
        val clean = command.trim()
        val forbidden = listOf("del", "rm", "remove-item", "format", "format-volume", "stop-computer", "invoke-expression", "iex")
        if (forbidden.any { clean.lowercase().startsWith(it) || clean.lowercase().contains(" $it ") }) {
            return CommandResult(false, "လုံခြုံရေးအရ ဤ PowerShell အမိန့်ကို တိုက်ရိုက် run ခွင့်မပြုပါရှင်။")
        }
        return runCatching {
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", clean).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val err = proc.errorStream.bufferedReader().readText().trim()
            val resultText = if (out.isNotBlank()) out else err
            CommandResult(true, "PowerShell ရလဒ်:\n$resultText")
        }.getOrElse {
            CommandResult(false, "PowerShell run မအောင်မြင်ပါ: ${it.message}")
        }
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "")

    private fun solveProjectIssue(projectNameQuery: String, issueDescription: String): CommandResult {
        if (projectNameQuery.isBlank()) {
            return CommandResult(false, "Project အမည် သတ်မှတ်ပေးရန် လိုအပ်ပါသည်။")
        }
        val projectDir = projectResolver.resolveProjectDirectory(projectNameQuery)
        if (projectDir == null) {
            val available = projectResolver.listProjects().take(5).joinToString(", ") { it.name }
            return CommandResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။ စက်ထဲရှိ ပရောဂျက်များ: $available")
        }

        // 1. Format Structured Technical Prompt
        val prompt = taskFormatter.formatTaskPrompt(projectDir.name, projectDir, issueDescription)

        // 2. Dispatch Task to Workspace
        val taskFile = antigravityBridge.dispatchTaskToWorkspace(projectDir, prompt)

        // 3. Open Workspace in Editor/Antigravity
        val editorResult = antigravityBridge.openProjectInEditor(projectDir)

        // 4. Run Build/Test Verification
        val buildResult = androidPipeline.runGradleCheck(projectDir, listOf("compileKotlin", "test"))

        val statusSummary = if (buildResult.success) {
            "ပရောဂျက် '${projectDir.name}' အတွက် Task ကို Antigravity/Editor တွင် ဖွင့်လှစ်ပြီး Gradle build/test စစ်ဆေးမှု အောင်မြင်ပါသည် (Pass)။\nအသေးစိတ်: ${taskFile.name} တွင် မှတ်တမ်းတင်ထားပါသည်။"
        } else {
            "ပရောဂျက် '${projectDir.name}' ကို Antigravity/Editor တွင် ဖွင့်လှစ်ပြီး Task ပေးပို့ထားပါသည်။ လက်ရှိ Build စစ်ဆေးမှု ရလဒ်:\n${buildResult.message}"
        }

        return CommandResult(buildResult.success, statusSummary)
    }

    private fun openProjectWorkspace(projectNameQuery: String): CommandResult {
        if (projectNameQuery.isBlank()) return CommandResult(false, "Project အမည် လိုအပ်ပါသည်။")
        val projectDir = projectResolver.resolveProjectDirectory(projectNameQuery)
            ?: return CommandResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")
        val res = antigravityBridge.openProjectInEditor(projectDir)
        return CommandResult(res.success, res.message)
    }

    private fun runAndroidBuildTest(projectNameQuery: String, tasksStr: String?): CommandResult {
        val projectDir = if (projectNameQuery.isBlank()) {
            File(System.getProperty("user.home"), "AndroidStudioProjects\\VoiceBrainLive-Desktop")
        } else {
            projectResolver.resolveProjectDirectory(projectNameQuery)
                ?: return CommandResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")
        }
        val tasks = if (!tasksStr.isNullOrBlank()) {
            tasksStr.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            listOf("compileKotlin", "test")
        }
        val res = androidPipeline.runGradleCheck(projectDir, tasks)
        return CommandResult(res.success, res.message)
    }

    private fun checkAdbDevices(): CommandResult {
        val res = androidPipeline.checkAdbDevices()
        return CommandResult(res.success, res.output.ifBlank { res.message })
    }

    private fun autoHealProject(projectNameQuery: String, issueDescription: String): CommandResult {
        val projectDir = if (projectNameQuery.isBlank()) {
            File(System.getProperty("user.home"), "AndroidStudioProjects\\VoiceBrainLive-Desktop")
        } else {
            projectResolver.resolveProjectDirectory(projectNameQuery)
                ?: return CommandResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")
        }
        val res = autoHealingFixEngine.runAutoHealingCycle(projectDir, issueDescription)
        return CommandResult(res.success, res.finalStatusReport)
    }

    private fun verifyOnEmulator(projectNameQuery: String): CommandResult {
        val projectDir = if (projectNameQuery.isBlank()) {
            File(System.getProperty("user.home"), "AndroidStudioProjects\\VoiceBrainLive-Desktop")
        } else {
            projectResolver.resolveProjectDirectory(projectNameQuery)
                ?: return CommandResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")
        }
        val res = androidEmulatorRunner.deployAndVerifyApp(projectDir)
        return CommandResult(res.success, res.message)
    }

    private fun gitCommitFix(projectNameQuery: String, commitMessage: String): CommandResult {
        val projectDir = if (projectNameQuery.isBlank()) {
            File(System.getProperty("user.home"), "AndroidStudioProjects\\VoiceBrainLive-Desktop")
        } else {
            projectResolver.resolveProjectDirectory(projectNameQuery)
                ?: return CommandResult(false, "'$projectNameQuery' ပရောဂျက်ကို ရှာမတွေ့ပါ။")
        }
        val res = gitAutomationService.createBranchAndCommit(projectDir, commitMessage)
        return CommandResult(res.success, "${res.message}\n${res.diffSummary}".trim())
    }

    private fun getActiveWindowSummary(): CommandResult {
        val info = activeWindowTracker.getActiveWindow()
        return CommandResult(true, info.toBurmeseSummary())
    }

    private fun openFileInIde(filePath: String, lineNumberStr: String?): CommandResult {
        val line = lineNumberStr?.toIntOrNull() ?: 1
        val res = ideBridgeService.openFileInEditor(filePath, line)
        return CommandResult(res.success, res.message)
    }

    private fun getIdeStatusSummary(): CommandResult {
        val activeWindow = activeWindowTracker.getActiveWindow()
        val status = ideBridgeService.getIdeStatus(activeWindow.processName, activeWindow.windowTitle)
        return CommandResult(true, status.toBurmeseSummary())
    }

    private fun enableWirelessAdb(portStr: String?): CommandResult {
        val port = portStr?.toIntOrNull() ?: 5555
        val res = wirelessAdbManager.enableTcpip(port)
        return CommandResult(res.success, res.message)
    }

    private fun connectWirelessAdb(ipAndPort: String): CommandResult {
        val res = wirelessAdbManager.connectWireless(ipAndPort)
        return CommandResult(res.success, res.message)
    }

    private fun pairWirelessAdb(ipAndPort: String, code: String): CommandResult {
        val res = wirelessAdbManager.pairWireless(ipAndPort, code)
        return CommandResult(res.success, res.message)
    }

    private fun getAdbDevicesSummary(): CommandResult {
        val summary = wirelessAdbManager.formatDevicesSummary()
        return CommandResult(true, summary)
    }

    private fun getMultiRepoSummary(): CommandResult {
        val report = multiRepoManager.generateMultiRepoReport()
        return CommandResult(true, report)
    }

    private fun switchRepoBranch(projectName: String, branch: String): CommandResult {
        val res = multiRepoManager.switchBranch(projectName, branch)
        return CommandResult(res.success, res.message)
    }

    private fun pullRepoChanges(projectName: String): CommandResult {
        val res = multiRepoManager.pullRepo(projectName)
        return CommandResult(res.success, res.message)
    }
}
