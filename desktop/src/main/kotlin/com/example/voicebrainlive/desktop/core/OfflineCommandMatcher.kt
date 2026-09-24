package com.example.voicebrainlive.desktop.core

/**
 * High-performance instant offline command matcher for Burmese & English voice/text commands.
 * Matches common desktop control phrases locally with 0ms latency.
 */
object OfflineCommandMatcher {

    private val PHRASES = mapOf(
        // Voice typing mode
        "အသံနဲ့စာရိုက်" to Triple("voice_typing_on", null, null),
        "အသံနဲ့ စာရိုက်" to Triple("voice_typing_on", null, null),
        "စာရိုက်မယ်" to Triple("voice_typing_on", null, null),
        "voice typing ဖွင့်" to Triple("voice_typing_on", null, null),
        "voice typing on" to Triple("voice_typing_on", null, null),
        "အသံနဲ့စာရိုက်တာပိတ်" to Triple("voice_typing_off", null, null),
        "စာရိုက်တာရပ်" to Triple("voice_typing_off", null, null),
        "voice typing ပိတ်" to Triple("voice_typing_off", null, null),
        "voice typing off" to Triple("voice_typing_off", null, null),

        // Active Window & Context Awareness
        "လက်ရှိ app" to Triple("get_active_window", null, null),
        "လက်ရှိ window" to Triple("get_active_window", null, null),
        "ဘာ app သုံးနေလဲ" to Triple("get_active_window", null, null),
        "ဘာ window သုံးနေလဲ" to Triple("get_active_window", null, null),
        "active app" to Triple("get_active_window", null, null),
        "active window" to Triple("get_active_window", null, null),
        "what am i doing" to Triple("get_active_window", null, null),
        "current window" to Triple("get_active_window", null, null),
        "current app" to Triple("get_active_window", null, null),

        // Volume & Mute
        "ဝေါလ်ယမ်တိုး" to Triple("volume_up", null, null),
        "ဝေါလ်ယမ်လျော့" to Triple("volume_down", null, null),
        "အသံတိုး" to Triple("volume_up", null, null),
        "အသံတိုးပါ" to Triple("volume_up", null, null),
        "အသံတိုးပေးပါ" to Triple("volume_up", null, null),
        "အသံချဲ့" to Triple("volume_up", null, null),
        "အသံချဲ့ပါ" to Triple("volume_up", null, null),
        "အသံချဲ့ပေးပါ" to Triple("volume_up", null, null),
        "အသံမြှင့်" to Triple("volume_up", null, null),
        "အသံမြှင့်ပါ" to Triple("volume_up", null, null),
        "အသံမြှင့်ပေးပါ" to Triple("volume_up", null, null),
        "အသံတင်" to Triple("volume_up", null, null),
        "အသံတင်ပါ" to Triple("volume_up", null, null),
        "အသံတင်ပေးပါ" to Triple("volume_up", null, null),
        "volume တိုး" to Triple("volume_up", null, null),
        "volume တင်" to Triple("volume_up", null, null),
        "volume up" to Triple("volume_up", null, null),
        "louder" to Triple("volume_up", null, null),

        "အသံလျော့" to Triple("volume_down", null, null),
        "အသံလျော့ပါ" to Triple("volume_down", null, null),
        "အသံလျှော့" to Triple("volume_down", null, null),
        "အသံလျှော့ပါ" to Triple("volume_down", null, null),
        "အသံလျှော့ပေးပါ" to Triple("volume_down", null, null),
        "အသံချ" to Triple("volume_down", null, null),
        "အသံချပါ" to Triple("volume_down", null, null),
        "အသံချပေးပါ" to Triple("volume_down", null, null),
        "volume လျှော့" to Triple("volume_down", null, null),
        "volume ချ" to Triple("volume_down", null, null),
        "volume down" to Triple("volume_down", null, null),
        "quieter" to Triple("volume_down", null, null),

        "အသံပိတ်" to Triple("mute", null, null),
        "အသံပိတ်ပါ" to Triple("mute", null, null),
        "အသံပိတ်ပေးပါ" to Triple("mute", null, null),
        "အသံတိတ်" to Triple("mute", null, null),
        "အသံတိတ်ပါ" to Triple("mute", null, null),
        "mute" to Triple("mute", null, null),
        "mute လုပ်" to Triple("mute", null, null),
        "mute လုပ်ပါ" to Triple("mute", null, null),
        "unmute" to Triple("mute", null, null),
        "အသံပြန်ဖွင့်" to Triple("mute", null, null),

        // Time & Date
        "အခု ဘယ်အချိန်ရှိပြီလဲ" to Triple("get_current_time", null, null),
        "အခုဘယ်အချိန်ရှိပြီလဲ" to Triple("get_current_time", null, null),
        "အချိန်ဘယ်လောက်လဲ" to Triple("get_current_time", null, null),
        "အချိန် ဘယ်လောက်လဲ" to Triple("get_current_time", null, null),
        "အချိန်ပြောပြပါ" to Triple("get_current_time", null, null),
        "အချိန် ပြောပြပါ" to Triple("get_current_time", null, null),
        "လက်ရှိအချိန်" to Triple("get_current_time", null, null),
        "time" to Triple("get_current_time", null, null),
        "what time is it" to Triple("get_current_time", null, null),

        "ဒီနေ့ ဘယ်ရက်လဲ" to Triple("get_current_date", null, null),
        "ဒီနေ့ဘယ်ရက်လဲ" to Triple("get_current_date", null, null),
        "ဒီနေ့ ဘာနေ့လဲ" to Triple("get_current_date", null, null),
        "ယနေ့ရက်စွဲ" to Triple("get_current_date", null, null),
        "date" to Triple("get_current_date", null, null),
        "today date" to Triple("get_current_date", null, null),

        // Laptop Power Management (Shutdown, Sleep, Restart, Lock)
        "စက်ပိတ်" to Triple("shutdown", null, null),
        "စက်ပိတ်ပါ" to Triple("shutdown", null, null),
        "စက်ပိတ်ပေးပါ" to Triple("shutdown", null, null),
        "shutdown" to Triple("shutdown", null, null),
        "turn off" to Triple("shutdown", null, null),
        "စက်အိပ်" to Triple("sleep", null, null),
        "စက်အိပ်ပါ" to Triple("sleep", null, null),
        "sleep" to Triple("sleep", null, null),
        "sleep ဝင်ပါ" to Triple("sleep", null, null),
        "စက်ပြန်စ" to Triple("restart", null, null),
        "စက်ပြန်စပါ" to Triple("restart", null, null),
        "စက် restart" to Triple("restart", null, null),
        "restart" to Triple("restart", null, null),
        "reboot" to Triple("restart", null, null),
        "သော့ခတ်" to Triple("lock_computer", null, null),
        "စက်သော့ခတ်" to Triple("lock_computer", null, null),
        "စက်သော့ခတ်ပါ" to Triple("lock_computer", null, null),
        "lock" to Triple("lock_computer", null, null),
        "lock computer" to Triple("lock_computer", null, null),
        "စက်ပိတ်တာရပ်" to Triple("cancel_shutdown", null, null),
        "စက်ပိတ်တာ ရပ်" to Triple("cancel_shutdown", null, null),
        "စက်ပိတ်တာ ပယ်ဖျက်ပါ" to Triple("cancel_shutdown", null, null),
        "cancel shutdown" to Triple("cancel_shutdown", null, null),

        // Window Controls & Display
        "window ကြီးပါ" to Triple("maximize_window", null, null),
        "window ချဲ့ပါ" to Triple("maximize_window", null, null),
        "window အကြီးချဲ့" to Triple("maximize_window", null, null),
        "window အကြီးချဲ့ပါ" to Triple("maximize_window", null, null),
        "maximize" to Triple("maximize_window", null, null),
        "full screen" to Triple("maximize_window", null, null),
        "window သေးပါ" to Triple("minimize_window", null, null),
        "window သေးပေးပါ" to Triple("minimize_window", null, null),
        "window ချုံ့" to Triple("minimize_window", null, null),
        "window ချုံ့ပါ" to Triple("minimize_window", null, null),
        "window ပိတ်ပါ" to Triple("close_window", null, null),
        "window ပိတ်ပေးပါ" to Triple("close_window", null, null),
        "close window" to Triple("close_window", null, null),
        "tab ပိတ်" to Triple("close_tab", null, null),
        "tab ပိတ်ပါ" to Triple("close_tab", null, null),
        "close tab" to Triple("close_tab", null, null),
        "window အားလုံး သိမ်းပါ" to Triple("minimize_all", null, null),
        "window အားလုံး ချုံ့ပါ" to Triple("minimize_all", null, null),
        "desktop ပြပါ" to Triple("minimize_all", null, null),
        "show desktop" to Triple("minimize_all", null, null),
        "minimize" to Triple("minimize_all", null, null),
        "minimize all" to Triple("minimize_all", null, null),

        "အလင်းတိုး" to Triple("brightness_up", null, null),
        "အလင်းတိုးပါ" to Triple("brightness_up", null, null),
        "အလင်းတိုးပေးပါ" to Triple("brightness_up", null, null),
        "အလင်းတင်ပါ" to Triple("brightness_up", null, null),
        "brightness တိုး" to Triple("brightness_up", null, null),
        "brightness တိုးပါ" to Triple("brightness_up", null, null),
        "brightness တင်ပါ" to Triple("brightness_up", null, null),
        "အလင်းလျှော့" to Triple("brightness_down", null, null),
        "အလင်းလျှော့ပါ" to Triple("brightness_down", null, null),
        "အလင်းချပါ" to Triple("brightness_down", null, null),
        "brightness လျှော့" to Triple("brightness_down", null, null),
        "brightness လျှော့ပါ" to Triple("brightness_down", null, null),
        "brightness ချပါ" to Triple("brightness_down", null, null),

        // System Actions & Utilities
        "စခရင်ရှော့" to Triple("take_screenshot", null, null),
        "စခရင်ရှော့ရိုက်" to Triple("take_screenshot", null, null),
        "စခရင်ရှော့ရိုက်ပါ" to Triple("take_screenshot", null, null),
        "စခရင်ရှော့ရိုက်ပေးပါ" to Triple("take_screenshot", null, null),
        "screenshot" to Triple("take_screenshot", null, null),
        "take screenshot" to Triple("take_screenshot", null, null),
        "system status" to Triple("system_status", null, null),
        "စက်အခြေအနေ" to Triple("system_status", null, null),
        "စက်အခြေအနေစစ်" to Triple("system_status", null, null),
        "စက်အခြေအနေ စစ်ပေးပါ" to Triple("system_status", null, null),
        "စက်အချက်အလက်" to Triple("system_status", null, null),
        "ram ဘယ်လောက်ကျန်လဲ" to Triple("system_status", null, null),
        "cpu ဘယ်လောက်လဲ" to Triple("system_status", null, null),
        "အင်တာနက်စစ်" to Triple("diagnose_network", null, null),
        "အင်တာနက်စစ်ပါ" to Triple("diagnose_network", null, null),
        "အင်တာနက်စစ်ပေးပါ" to Triple("diagnose_network", null, null),
        "network စစ်ပါ" to Triple("diagnose_network", null, null),
        "ping test" to Triple("diagnose_network", null, null),
        "check internet" to Triple("diagnose_network", null, null),
        "wifi စစ်ပါ" to Triple("diagnose_network", null, null),
        "ဘက်ထရီ" to Triple("get_battery_status", null, null),
        "ဘက်ထရီစစ်" to Triple("get_battery_status", null, null),
        "ဘက်ထရီစစ်ပေးပါ" to Triple("get_battery_status", null, null),
        "ဘက်ထရီဘယ်လောက်ကျန်လဲ" to Triple("get_battery_status", null, null),
        "ဘက်ထရီ ဘယ်လောက်ကျန်လဲ" to Triple("get_battery_status", null, null),
        "battery status" to Triple("get_battery_status", null, null),
        "ဖွင့်ထားတဲ့ app" to Triple("list_running_apps", null, null),
        "ဖွင့်ထားတဲ့ apps" to Triple("list_running_apps", null, null),
        "ဖွင့်ထားတဲ့ apps တွေ" to Triple("list_running_apps", null, null),
        "running apps" to Triple("list_running_apps", null, null),
        "list apps" to Triple("list_running_apps", null, null),
        "recycle bin ရှင်းပါ" to Triple("empty_recycle_bin", null, null),
        "recycle bin ရှင်းပေးပါ" to Triple("empty_recycle_bin", null, null),
        "အမှိုက်ပုံးရှင်းပါ" to Triple("empty_recycle_bin", null, null),
        "empty recycle bin" to Triple("empty_recycle_bin", null, null),

        // Screen Vision & Clipboard
        "စခရင်မှာဘာပြလဲ" to Triple("analyze_screen", null, null),
        "စခရင်မှာ ဘာပြလဲ" to Triple("analyze_screen", null, null),
        "ဒီစခရင်မှာဘာပြလဲ" to Triple("analyze_screen", null, null),
        "စခရင်ဖတ်ပေးပါ" to Triple("analyze_screen", null, null),
        "စခရင်ဖတ်ပါ" to Triple("analyze_screen", null, null),
        "စခရင်ကြည့်ပေးပါ" to Triple("analyze_screen", null, null),
        "စခရင်ကြည့်ပါ" to Triple("analyze_screen", null, null),
        "စခရင်ပေါ်က စာတွေ ဖတ်ပြပါ" to Triple("analyze_screen", null, null),
        "အောက်ဘားမှာဘာရှိလဲ" to Triple("analyze_screen", null, null),
        "စခရင်အောက်ဘားမှာဘာရှိလဲ" to Triple("analyze_screen", null, null),
        "taskbar မှာဘာရှိလဲ" to Triple("analyze_screen", null, null),
        "အောက်ဘား" to Triple("analyze_screen", null, null),
        "taskbar" to Triple("analyze_screen", null, null),
        "screen vision" to Triple("analyze_screen", null, null),
        "clipboard ဖတ်ပေးပါ" to Triple("read_clipboard", null, null),
        "clipboard ဖတ်ပါ" to Triple("read_clipboard", null, null),
        "clipboard စာသား" to Triple("read_clipboard", null, null),
        "copy ထားတာဘာလဲ" to Triple("read_clipboard", null, null),
        "read clipboard" to Triple("read_clipboard", null, null),

        // Media Controls
        "သီချင်းဖွင့်" to Triple("media_play_pause", null, null),
        "သီချင်းဖွင့်ပါ" to Triple("media_play_pause", null, null),
        "သီချင်းရပ်" to Triple("media_play_pause", null, null),
        "သီချင်းရပ်ပါ" to Triple("media_play_pause", null, null),
        "သီချင်းခေတ္တရပ်" to Triple("media_play_pause", null, null),
        "play pause" to Triple("media_play_pause", null, null),
        "pause" to Triple("media_play_pause", null, null),
        "resume" to Triple("media_play_pause", null, null),
        "next song" to Triple("media_next", null, null),
        "next track" to Triple("media_next", null, null),
        "နောက်သီချင်း" to Triple("media_next", null, null),
        "နောက်တစ်ပုဒ်" to Triple("media_next", null, null),
        "prev song" to Triple("media_prev", null, null),
        "ရှေ့သီချင်း" to Triple("media_prev", null, null),
        "ရှေ့တစ်ပုဒ်" to Triple("media_prev", null, null),

        // Direct Web Apps
        "youtube ဖွင့်" to Triple("open_url", "https://youtube.com", null),
        "youtube ဖွင့်ပါ" to Triple("open_url", "https://youtube.com", null),
        "open youtube" to Triple("open_url", "https://youtube.com", null),
        "facebook ဖွင့်" to Triple("open_url", "https://facebook.com", null),
        "facebook ဖွင့်ပါ" to Triple("open_url", "https://facebook.com", null),
        "chatgpt ဖွင့်" to Triple("open_url", "https://chatgpt.com", null),
        "chatgpt ဖွင့်ပါ" to Triple("open_url", "https://chatgpt.com", null),
        "claude ဖွင့်" to Triple("open_url", "https://claude.ai", null),
        "claude ဖွင့်ပါ" to Triple("open_url", "https://claude.ai", null),
        "github ဖွင့်" to Triple("open_url", "https://github.com", null),
        "github ဖွင့်ပါ" to Triple("open_url", "https://github.com", null),
        "gmail ဖွင့်" to Triple("open_url", "https://mail.google.com", null),
        "gmail ဖွင့်ပါ" to Triple("open_url", "https://mail.google.com", null),
        "google ဖွင့်" to Triple("open_url", "https://google.com", null),
        "tiktok ဖွင့်" to Triple("open_url", "https://tiktok.com", null),

        // Notion Shortcuts
        "notion စစ်" to Triple("notion_test", null, null),
        "notion စစ်ပါ" to Triple("notion_test", null, null),
        "notion ချိတ်ဆက်မှုစစ်" to Triple("notion_test", null, null),
        "notion check" to Triple("notion_test", null, null),
        "notion test" to Triple("notion_test", null, null),
        "notion ဖွင့်" to Triple("open_notion_page", "https://notion.so", null),
        "notion ဖွင့်ပါ" to Triple("open_notion_page", "https://notion.so", null),
        "open notion" to Triple("open_notion_page", "https://notion.so", null),
        "notion ရှာ" to Triple("notion_search", "", null),
        "notion ဖတ်" to Triple("notion_get_page_content", "", null),

        // Phase 3: Wireless ADB & Multi-Repo Shortcuts
        "wifi adb ဖွင့်" to Triple("adb_enable_tcpip", null, "5555"),
        "wireless adb ဖွင့်" to Triple("adb_enable_tcpip", null, "5555"),
        "wireless adb" to Triple("adb_devices_detailed", null, null),
        "adb devices" to Triple("adb_devices_detailed", null, null),
        "android devices" to Triple("adb_devices_detailed", null, null),
        "ဖုန်းချိတ်ဆက်မှုစစ်" to Triple("adb_devices_detailed", null, null),
        "ဖုန်းချိတ်ထားတာဘာရှိလဲ" to Triple("adb_devices_detailed", null, null),
        "repo အကုန်စစ်" to Triple("git_repo_status_all", null, null),
        "repo အားလုံးစစ်ပေးပါ" to Triple("git_repo_status_all", null, null),
        "git repos" to Triple("git_repo_status_all", null, null),
        "list repos" to Triple("git_repo_status_all", null, null),
        "ပရောဂျက်တွေ စာရင်းပြပါ" to Triple("git_repo_status_all", null, null),
        "ide status" to Triple("ide_status", null, null),
        "editor အခြေအနေ" to Triple("ide_status", null, null),

        // App Shortcuts
        "chrome ဖွင့်" to Triple("open_app", "Chrome", null),
        "edge ဖွင့်" to Triple("open_app", "Edge", null),
        "word ဖွင့်" to Triple("open_app", "Word", null),
        "excel ဖွင့်" to Triple("open_app", "Excel", null),
        "code ဖွင့်" to Triple("open_app", "VS Code", null),
        "vs code ဖွင့်" to Triple("open_app", "VS Code", null),
        "browser ဖွင့်" to Triple("open_app", "Chrome", null),
        "vlc ဖွင့်" to Triple("open_app", "VLC", null),
        "terminal ဖွင့်" to Triple("open_app", "Terminal", null),
        "calculator ဖွင့်" to Triple("open_app", "Calculator", null),
        "notepad ဖွင့်" to Triple("open_app", "Notepad", null),
        "paint ဖွင့်" to Triple("open_app", "Paint", null),
        "task manager ဖွင့်" to Triple("open_app", "Task Manager", null),
        "android studio ဖွင့်" to Triple("open_app", "Android Studio", null),
        "telegram ဖွင့်" to Triple("open_app", "Telegram", null),
        "spotify ဖွင့်" to Triple("open_app", "Spotify", null),
        "capcut ဖွင့်" to Triple("open_app", "CapCut", null),
        "viber ဖွင့်" to Triple("open_app", "Viber", null),
        "brave ဖွင့်" to Triple("open_app", "Brave", null),
        "camera ဖွင့်" to Triple("open_app", "Camera", null),
        "settings ဖွင့်" to Triple("open_app", "Settings", null),
        "downloads ဖွင့်" to Triple("open_downloads", null, null),
        "documents ဖွင့်" to Triple("open_documents", null, null),
        "desktop ဖွင့်" to Triple("open_desktop", null, null),
        "recycle bin ဖွင့်" to Triple("open_recycle_bin", null, null),
    )

    fun match(input: String): DesktopCommand? {
        val clean = normalize(input)
        val direct = PHRASES[clean] ?: PHRASES[stripPoliteEnding(clean)]
        if (direct != null) {
            return DesktopCommand(direct.first, direct.second, direct.third)
        }

        // Web Search with Dynamic Query: "google မှာ <query> ရှာပေးပါ", "<query> ရှာပေးပါ", "<query> ရှာပါ"
        val googleSearchRegex = Regex("^(?:google\\s+မှာ\\s+|search\\s+for\\s+|search\\s+)?(.+?)(?:\\s+ရှာပေးပါ|\\s+ရှာပါ|\\s+ကို\\s+ရှာပေးပါ)?$", RegexOption.IGNORE_CASE).find(clean)
        if (clean.contains("ရှာပေးပါ") || clean.contains("ရှာပါ") || clean.startsWith("search ")) {
            var query = clean.replace("google မှာ", "").replace("google", "").replace("ရှာပေးပါ", "").replace("ရှာပါ", "").replace("ကို", "").replace("search", "").trim()
            if (query.isNotBlank() && !query.contains("notion")) {
                if (clean.contains("youtube")) {
                    query = query.replace("youtube မှာ", "").replace("youtube", "").trim()
                    return DesktopCommand("search_youtube", query, null)
                }
                return DesktopCommand("search_web", query, null)
            }
        }

        // Dynamic English App Close (e.g. "close chrome", "kill notepad", "stop telegram")
        val englishCloseMatch = Regex("^(?:close|kill|stop|terminate)\\s+([a-z0-9\\s\\.\\+\\-]+)$", RegexOption.IGNORE_CASE).find(clean)
        if (englishCloseMatch != null) {
            val app = englishCloseMatch.groupValues[1].trim()
            if (app.isNotBlank() && app != "window" && app != "tab") {
                return DesktopCommand("close_app", app, null)
            }
        }

        // Dynamic Burmese App Close (e.g. "chrome ပိတ်ပေးပါ", "notepad ပိတ်ပါ", "vlc ပိတ်")
        val burmeseCloseVerbs = listOf("ပိတ်ပေးပါ", "ပိတ်ပါ", "ပိတ်ပေး", "ပိတ်မယ်", "ပိတ်လိုက်ပါ", "ပိတ်")
        for (verb in burmeseCloseVerbs) {
            if (clean.endsWith(verb) && !clean.contains("စက်") && !clean.contains("window") && !clean.contains("tab") && !clean.contains("အသံ")) {
                val candidate = clean.removeSuffix(verb).trim()
                if (candidate.isNotBlank()) {
                    return DesktopCommand("close_app", candidate, null)
                }
            }
        }

        // Dynamic English App Launcher (e.g., "open telegram", "launch chrome")
        val englishMatch = Regex("^(?:open|launch)\\s+([a-z0-9\\s\\.\\+\\-]+)$", RegexOption.IGNORE_CASE).find(clean)
        if (englishMatch != null) {
            val app = englishMatch.groupValues[1].trim()
            if (app.isNotBlank()) {
                return DesktopCommand("open_app", app, null)
            }
        }

        // Dynamic Burmese App Launcher (e.g., "<app> ဖွင့်ပေးပါ", "<app> ဖွင့်ပါ", "<app> ဖွင့်")
        val burmeseVerbs = listOf("ဖွင့်ပေးပါ", "ဖွင့်ပါ", "ဖွင့်ပေး", "ဖွင့်မယ်", "ဖွင့်စမ်းပါ", "ဖွင့်လိုက်ပါ", "ဖွင့်")
        for (verb in burmeseVerbs) {
            if (clean.endsWith(verb)) {
                val candidate = clean.removeSuffix(verb).trim()
                if (candidate.isNotBlank() && !candidate.contains("စက်") && !candidate.contains("notion")) {
                    return DesktopCommand("open_app", candidate, null)
                }
            }
        }

        return null
    }

    private fun normalize(input: String): String = input
        .lowercase()
        .replace(Regex("[၊။,!?.]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripPoliteEnding(input: String): String {
        var value = input.trim()
        val endings = listOf(
            "ပေးပါ", "ပေးပါရှင်", "ပါရှင်", "ရှင်", "ပါ", "မယ်", "နော်"
        )
        var changed: Boolean
        do {
            changed = false
            for (ending in endings) {
                if (value.endsWith(ending) && value.length > ending.length + 1) {
                    value = value.removeSuffix(ending).trim()
                    changed = true
                    break
                }
            }
        } while (changed)
        return value
    }
}
