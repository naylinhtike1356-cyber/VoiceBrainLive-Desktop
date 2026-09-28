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

        // Physical Hands & Eyes (Mouse & Desktop actions)
        "မောက်စ် နှိပ်" to Triple("mouse_click", null, null),
        "မောက်စ် နှိပ်ပါ" to Triple("mouse_click", null, null),
        "မောက်စ် နှိပ်ပေးပါ" to Triple("mouse_click", null, null),
        "မောက်ကွန်စာ နှိပ်" to Triple("mouse_click", null, null),
        "မောက်ကွန်စာ နှိပ်ပါ" to Triple("mouse_click", null, null),
        "မောက်ကွန်စာ နှိပ်ပေးပါ" to Triple("mouse_click", null, null),
        "မောက်ကဆာ နှိပ်" to Triple("mouse_click", null, null),
        "mouse click" to Triple("mouse_click", null, null),
        "click" to Triple("mouse_click", null, null),
        "လက်ဝဲကလစ်" to Triple("mouse_click", null, null),
        "ဒဘယ်ကလစ်" to Triple("mouse_double_click", null, null),
        "double click" to Triple("mouse_double_click", null, null),
        "မောက်စ် နှစ်ချက်နှိပ်" to Triple("mouse_double_click", null, null),
        "မောက်ကွန်စာ နှစ်ချက်နှိပ်" to Triple("mouse_double_click", null, null),
        "ရိုက်ကလစ်" to Triple("mouse_right_click", null, null),
        "right click" to Triple("mouse_right_click", null, null),
        "ညာဘက်ကလစ်" to Triple("mouse_right_click", null, null),
        "မောက်စ် ညာဘက်နှိပ်" to Triple("mouse_right_click", null, null),
        "မောက်ကွန်စာ ညာဘက်နှိပ်" to Triple("mouse_right_click", null, null),
        "desktop ပြပါ" to Triple("show_desktop", null, null),
        "desktop ပြ" to Triple("show_desktop", null, null),
        "show desktop" to Triple("show_desktop", null, null),
        "ဒက်စတော့ ပြပါ" to Triple("show_desktop", null, null),
        "စခရင် အခြေအနေ" to Triple("screen_eyes", null, null),
        "မျက်စိ" to Triple("screen_eyes", null, null),
        "စခရင် စစ်ဆေး" to Triple("screen_eyes", null, null),
        "screen eyes" to Triple("screen_eyes", null, null),
        "ဆော့ဖ်ဝဲ စာရင်း ပြန်စစ်" to Triple("refresh_app_catalog", null, null),
        "refresh app catalog" to Triple("refresh_app_catalog", null, null),

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
        "အချိန်ဘယ်လောက်ရှိပြီလဲ" to Triple("get_current_time", null, null),
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

        // Autonomous Goal & Multi-step Controls
        "ပန်းတိုင် ရပ်" to Triple("cancel_goal", null, null),
        "ပန်းတိုင် ရပ်ပါ" to Triple("cancel_goal", null, null),
        "ပန်းတိုင် ဖျက်ပါ" to Triple("cancel_goal", null, null),
        "အလုပ်ရပ်" to Triple("cancel_goal", null, null),
        "အလုပ်ရပ်ပါ" to Triple("cancel_goal", null, null),
        "cancel goal" to Triple("cancel_goal", null, null),
        "stop goal" to Triple("cancel_goal", null, null),
        "work mode" to Triple("run_voice_routine", "Work Mode (အလုပ်စမယ်)", null),
        "work mode ဖွင့်" to Triple("run_voice_routine", "Work Mode (အလုပ်စမယ်)", null),
        "work mode ဖွင့်ပါ" to Triple("run_voice_routine", "Work Mode (အလုပ်စမယ်)", null),
        "အလုပ်စမယ်" to Triple("run_voice_routine", "Work Mode (အလုပ်စမယ်)", null),
        "အလုပ်စတင်မယ်" to Triple("run_voice_routine", "Work Mode (အလုပ်စမယ်)", null),
        "စလုပ်မယ်" to Triple("run_voice_routine", "Work Mode (အလုပ်စမယ်)", null),
        "စာလေ့လာမယ်" to Triple("run_voice_routine", "Study Mode (စာလေ့လာမယ်)", null),
        "study mode" to Triple("run_voice_routine", "Study Mode (စာလေ့လာမယ်)", null),
        "စက်ရှင်းမယ်" to Triple("run_voice_routine", "Clean Workspace (စက်ရှင်းမယ်)", null),
        "clean workspace" to Triple("run_voice_routine", "Clean Workspace (စက်ရှင်းမယ်)", null),
        "clean desktop" to Triple("run_voice_routine", "Clean Workspace (စက်ရှင်းမယ်)", null),
        "အနားယူမယ်" to Triple("run_voice_routine", "Rest Mode (အနားယူမယ်)", null),
        "rest mode" to Triple("run_voice_routine", "Rest Mode (အနားယူမယ်)", null),
        "break time" to Triple("run_voice_routine", "Rest Mode (အနားယူမယ်)", null),
        "ဦးနှောက်မှတ်ဉာဏ်" to Triple("show_neural_brain", null, null),
        "neural brain" to Triple("show_neural_brain", null, null),
        "မှတ်ဉာဏ်ပြ" to Triple("show_neural_brain", null, null),
        "brain matrix" to Triple("show_neural_brain", null, null),

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
        "desktop ပြပါ" to Triple("show_desktop", null, null),
        "show desktop" to Triple("show_desktop", null, null),
        "minimize" to Triple("minimize_all", null, null),
        "minimize all" to Triple("minimize_all", null, null),

        // Phase 2: Native Windows Snapping & Tab Controls
        "window ဘယ်ဘက်ကပ်" to Triple("snap_window_left", null, null),
        "window ဘယ်ဘက်" to Triple("snap_window_left", null, null),
        "window ဘယ်ကပ်" to Triple("snap_window_left", null, null),
        "ဘယ်ဘက်ကပ်" to Triple("snap_window_left", null, null),
        "ဘယ်ကပ်" to Triple("snap_window_left", null, null),
        "snap left" to Triple("snap_window_left", null, null),
        "window ညာဘက်ကပ်" to Triple("snap_window_right", null, null),
        "window ညာဘက်" to Triple("snap_window_right", null, null),
        "window ညာကပ်" to Triple("snap_window_right", null, null),
        "ညာဘက်ကပ်" to Triple("snap_window_right", null, null),
        "ညာကပ်" to Triple("snap_window_right", null, null),
        "snap right" to Triple("snap_window_right", null, null),
        "window အပေါ်ကပ်" to Triple("snap_window_up", null, null),
        "snap up" to Triple("snap_window_up", null, null),
        "window အောက်ချ" to Triple("snap_window_down", null, null),
        "snap down" to Triple("snap_window_down", null, null),
        "tab အသစ်" to Triple("new_tab", null, null),
        "tab အသစ်ဖွင့်" to Triple("new_tab", null, null),
        "new tab" to Triple("new_tab", null, null),
        "tab ကူး" to Triple("switch_tab", null, null),
        "next tab" to Triple("switch_tab", null, null),
        "tab ပြောင်း" to Triple("switch_tab", null, null),
        "ui စစ်" to Triple("inspect_window_ui", null, null),
        "ui စစ်ဆေး" to Triple("inspect_window_ui", null, null),
        "ခလုတ်တွေပြ" to Triple("inspect_window_ui", null, null),
        "ခလုတ်များပြ" to Triple("inspect_window_ui", null, null),
        "inspect ui" to Triple("inspect_window_ui", null, null),
        "list controls" to Triple("inspect_window_ui", null, null),

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
        "ram ရှင်း" to Triple("optimize_ram", null, null),
        "ram ရှင်းပါ" to Triple("optimize_ram", null, null),
        "ram ရှင်းပေး" to Triple("optimize_ram", null, null),
        "ram ရှင်းပေးပါ" to Triple("optimize_ram", null, null),
        "ram ချုံ့" to Triple("optimize_ram", null, null),
        "ram ချုံ့ပါ" to Triple("optimize_ram", null, null),
        "clean ram" to Triple("optimize_ram", null, null),
        "optimize ram" to Triple("optimize_ram", null, null),
        "memory ရှင်း" to Triple("optimize_ram", null, null),
        "health check" to Triple("health_check", null, null),
        "health စစ်" to Triple("health_check", null, null),
        "app health" to Triple("health_check", null, null),
        "watchdog စစ်" to Triple("health_check", null, null),
        "system health စစ်" to Triple("health_check", null, null),
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

        // 0. Dynamic Mouse Actions with Target:
        // "မောက်စ်နဲ့ <target> ကို နှိပ်", "မောက်ကွန်စာနဲ့ <target> နှိပ်", "မောက်စ်ကို <target> ရွှေ့", "<target> ကို မောက်စ်နဲ့ နှိပ်"
        val hasMouseWord = clean.contains("မောက်စ်") || clean.contains("မောက်ကွန်စာ") || clean.contains("မောက်ကဆာ") || clean.contains("mouse")
        if (hasMouseWord) {
            // Check double click
            if (clean.contains("နှစ်ချက်နှိပ်") || clean.contains("double click") || clean.contains("ဒဘယ်ကလစ်")) {
                var target = clean
                    .replace("မောက်စ်နဲ့", "").replace("မောက်စ်", "")
                    .replace("မောက်ကွန်စာနဲ့", "").replace("မောက်ကွန်စာ", "")
                    .replace("မောက်ကဆာနဲ့", "").replace("မောက်ကဆာ", "")
                    .replace("mouse with", "").replace("mouse", "")
                    .replace("နှစ်ချက်နှိပ်ပေးပါ", "").replace("နှစ်ချက်နှိပ်ပါ", "").replace("နှစ်ချက်နှိပ်", "")
                    .replace("double click", "").replace("ဒဘယ်ကလစ်", "")
                    .replace("ကို", "").replace("မှာ", "").replace("သို့", "")
                    .trim()
                target = stripPoliteEnding(target)
                return DesktopCommand("mouse_double_click", target.ifBlank { null }, null)
            }

            // Check right click
            if (clean.contains("ညာဘက်နှိပ်") || clean.contains("ညာကလစ်") || clean.contains("right click") || clean.contains("ရိုက်ကလစ်")) {
                var target = clean
                    .replace("မောက်စ်နဲ့", "").replace("မောက်စ်", "")
                    .replace("မောက်ကွန်စာနဲ့", "").replace("မောက်ကွန်စာ", "")
                    .replace("မောက်ကဆာနဲ့", "").replace("မောက်ကဆာ", "")
                    .replace("mouse with", "").replace("mouse", "")
                    .replace("ညာဘက်နှိပ်ပေးပါ", "").replace("ညာဘက်နှိပ်ပါ", "").replace("ညာဘက်နှိပ်", "")
                    .replace("ညာကလစ်", "").replace("right click", "").replace("ရိုက်ကလစ်", "")
                    .replace("ကို", "").replace("မှာ", "").replace("သို့", "")
                    .trim()
                target = stripPoliteEnding(target)
                return DesktopCommand("mouse_right_click", target.ifBlank { null }, null)
            }

            // Check mouse move
            if (clean.contains("ရွှေ့") || clean.contains("move") || clean.contains("glide")) {
                var target = clean
                    .replace("မောက်စ်ကို", "").replace("မောက်စ်နဲ့", "").replace("မောက်စ်", "")
                    .replace("မောက်ကွန်စာကို", "").replace("မောက်ကွန်စာနဲ့", "").replace("မောက်ကွန်စာ", "")
                    .replace("မောက်ကဆာကို", "").replace("မောက်ကဆာနဲ့", "").replace("မောက်ကဆာ", "")
                    .replace("move mouse to", "").replace("move mouse", "").replace("mouse move", "").replace("mouse", "")
                    .replace("ရွှေ့ပေးပါ", "").replace("ရွှေ့ပါ", "").replace("ရွှေ့", "")
                    .replace("move", "").replace("glide", "")
                    .replace("ကို", "").replace("မှာ", "").replace("သို့", "")
                    .trim()
                target = stripPoliteEnding(target)
                if (target.isNotBlank()) {
                    return DesktopCommand("mouse_move", target, null)
                }
            }

            // Check single click with target
            if (clean.contains("နှိပ်") || clean.contains("click") || clean.contains("ကလစ်")) {
                var target = clean
                    .replace("မောက်စ်နဲ့", "").replace("မောက်စ်ကို", "").replace("မောက်စ်", "")
                    .replace("မောက်ကွန်စာနဲ့", "").replace("မောက်ကွန်စာကို", "").replace("မောက်ကွန်စာ", "")
                    .replace("မောက်ကဆာနဲ့", "").replace("မောက်ကဆာကို", "").replace("မောက်ကဆာ", "")
                    .replace("mouse click", "").replace("click", "").replace("mouse", "")
                    .replace("နှိပ်ပေးပါ", "").replace("နှိပ်ပါ", "").replace("နှိပ်", "")
                    .replace("ကလစ်", "")
                    .replace("ကို", "").replace("မှာ", "").replace("သို့", "")
                    .trim()
                target = stripPoliteEnding(target)
                return DesktopCommand("mouse_click", target.ifBlank { null }, null)
            }
        }

        // 1. Dynamic Desktop Icon Toggle: "<app> ဖွင့်ပိတ်", "<app> အိုင်ကွန် ဖွင့်ပိတ်", "<app> icon ဖွင့်ပိတ်"
        if (clean.contains("ဖွင့်ပိတ်") || clean.contains("toggle")) {
            val app = clean.replace("အိုင်ကွန် နှိပ်ပြီး ဖွင့်ပိတ်", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ဖွင့်ပိတ်", "")
                .replace("အိုင်ကွန် ဖွင့်ပိတ်", "")
                .replace("icon ဖွင့်ပိတ်", "")
                .replace("အိုင်ကွန်", "")
                .replace("icon", "")
                .replace("ဖွင့်ပိတ်", "")
                .replace("toggle", "")
                .trim()
            if (app.isNotBlank()) {
                return DesktopCommand("toggle_app", app, null)
            }
        }

        // 2. Dynamic Desktop Icon Close: "<app> အိုင်ကွန် နှိပ်ပိတ်", "<app> icon နှိပ်ပိတ်", "<app> အိုင်ကွန် ပိတ်", "<app> icon ပိတ်"
        if ((clean.contains("icon") || clean.contains("အိုင်ကွန်")) && (clean.contains("ပိတ်") || clean.contains("close") || clean.contains("kill"))) {
            val app = clean.replace("အိုင်ကွန် နှိပ်ပြီး ပိတ်ပေးပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ပိတ်ပေးပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပြီး ပိတ်ပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ပိတ်ပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပြီး ပိတ်", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ပိတ်", "")
                .replace("အိုင်ကွန် နှိပ်ပိတ်", "")
                .replace("အိုင်ကွန် ပိတ်ပေးပါ", "")
                .replace("အိုင်ကွန် ပိတ်ပါ", "")
                .replace("အိုင်ကွန် ပိတ်", "")
                .replace("icon နှိပ်ပြီး ပိတ်", "")
                .replace("icon နှိပ်ပီး ပိတ်", "")
                .replace("icon နှိပ်ပိတ်", "")
                .replace("icon ပိတ်", "")
                .replace("အိုင်ကွန်", "")
                .replace("icon", "")
                .replace("ပိတ်", "")
                .replace("close", "")
                .replace("kill", "")
                .trim()
            if (app.isNotBlank()) {
                return DesktopCommand("close_desktop_icon", app, null)
            }
        }

        // 3. Dynamic Desktop Icon Open: "<app> အိုင်ကွန် နှိပ်ဖွင့်", "<app> icon နှိပ်ဖွင့်", "<app> အိုင်ကွန် ဖွင့်", "<app> icon ဖွင့်", "<app> အိုင်ကွန် နှိပ်"
        if (clean.contains("icon") || clean.contains("အိုင်ကွန်") || clean.contains("ဒက်စတော့ပေါ်က") || clean.contains("ဒက်စတော့က") || clean.contains("desktop ပေါ်က")) {
            val app = clean.replace("အိုင်ကွန် နှိပ်ပြီး ဖွင့်ပေးပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ဖွင့်ပေးပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပြီး ဖွင့်ပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ဖွင့်ပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပြီး ဖွင့်", "")
                .replace("အိုင်ကွန် နှိပ်ပီး ဖွင့်", "")
                .replace("အိုင်ကွန် နှိပ်ဖွင့်", "")
                .replace("အိုင်ကွန် ဖွင့်ပေးပါ", "")
                .replace("အိုင်ကွန် ဖွင့်ပါ", "")
                .replace("အိုင်ကွန် ဖွင့်", "")
                .replace("icon နှိပ်ပြီး ဖွင့်", "")
                .replace("icon နှိပ်ပီး ဖွင့်", "")
                .replace("icon နှိပ်ဖွင့်", "")
                .replace("icon နှိပ်ပေးပါ", "")
                .replace("icon နှိပ်ပါ", "")
                .replace("icon နှိပ်", "")
                .replace("icon ဖွင့်", "")
                .replace("အိုင်ကွန် နှိပ်ပေးပါ", "")
                .replace("အိုင်ကွန် နှိပ်ပါ", "")
                .replace("အိုင်ကွန် နှိပ်", "")
                .replace("ဒက်စတော့ပေါ်က", "")
                .replace("ဒက်စတော့က", "")
                .replace("desktop ပေါ်က", "")
                .replace("click", "")
                .replace("icon", "")
                .replace("အိုင်ကွန်", "")
                .replace("ဖွင့်ပေးပါ", "")
                .replace("ဖွင့်ပါ", "")
                .replace("ဖွင့်", "")
                .trim()
            if (app.isNotBlank()) {
                return DesktopCommand("click_desktop_icon", app, null)
            }
        }

        // 3.5. Dynamic Button & UI Element Clicking (e.g. "<target> ခလုတ် နှိပ်", "<target> ခလုတ်နှိပ်ပါ", "click <target> button", "<target> နှိပ်")
        if (clean.contains("ခလုတ်") || clean.contains("button") || clean.startsWith("click ") || clean.endsWith("နှိပ်") || clean.endsWith("နှိပ်ပါ") || clean.endsWith("နှိပ်ပေးပါ")) {
            if (!clean.contains("အိုင်ကွန်") && !clean.contains("icon") && !clean.contains("စက်") && !clean.contains("app") && !clean.contains("window") && !clean.contains("tab")) {
                var target = clean
                    .replace("ခလုတ်နှိပ်ပေးပါ", "").replace("ခလုတ်နှိပ်ပါ", "").replace("ခလုတ်နှိပ်", "")
                    .replace("ခလုတ်ကို နှိပ်ပေးပါ", "").replace("ခလုတ်ကို နှိပ်ပါ", "").replace("ခလုတ်ကို နှိပ်", "")
                    .replace("ခလုတ်", "")
                    .replace("button click", "").replace("click button", "").replace("button", "")
                    .replace("click", "")
                    .replace("နှိပ်ပေးပါ", "").replace("နှိပ်ပါ", "").replace("နှိပ်", "")
                    .replace("မောက်စ်နဲ့", "").replace("မောက်စ်ကို", "").replace("မောက်စ်", "")
                    .replace("မောက်ကွန်စာနဲ့", "").replace("မောက်ကွန်စာကို", "").replace("မောက်ကွန်စာ", "")
                    .replace("မောက်ကဆာနဲ့", "").replace("မောက်ကဆာကို", "").replace("မောက်ကဆာ", "")
                    .replace("ကို", "").replace("မှာ", "").replace("သို့", "")
                    .trim()
                target = stripPoliteEnding(target)
                if (target.isNotBlank()) {
                    return DesktopCommand("click_ui_element", target, null)
                }
            }
        }


        // Web Search with Dynamic Query: "google မှာ <query> ရှာပေးပါ", "<query> ရှာပေးပါ", "<query> ရှာပါ"
        val googleSearchRegex = Regex("^(?:google\\s+မှာ\\s+|search\\s+for\\s+|search\\s+)?(.+?)(?:\\s+ရှာပေးပါ|\\s+ရှာပါ|\\s+ကို\\s+ရှာပေးပါ)?$", RegexOption.IGNORE_CASE).find(clean)
        if (clean.contains("ရှာပေးပါ") || clean.contains("ရှာပါ") || clean.startsWith("search ")) {
            var query = clean.replace("google မှာ", "").replace("google", "").replace("ရှာပေးပါ", "").replace("ရှာပါ", "").replace("ကို", "").replace("search", "").trim()
            if (query.isNotBlank()) {
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
                if (candidate.isNotBlank() && !candidate.contains("စက်")) {
                    return DesktopCommand("open_app", candidate, null)
                }
            }
        }

        return null
    }

    private fun normalize(input: String): String = input
        .lowercase()
        .replace(Regex("""[၊။,!?]+|(?<=\s|^)\.+|\.+(?=\s|$)"""), " ")
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
