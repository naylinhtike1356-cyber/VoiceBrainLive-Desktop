# VoiceBrainLive Desktop

This module is the first Windows Desktop scaffold for VoiceBrainLive. It is intentionally independent from Android classes so that the desktop app can compile and evolve without breaking the existing `app` and `wear` modules.

## Folder structure

```text
desktop/
├── build.gradle.kts
├── README.md
└── src/main/
    ├── kotlin/com/example/voicebrainlive/desktop/
    │   ├── Main.kt                         # Compose Desktop entry point and starter UI
    │   ├── core/AssistantCore.kt            # Shared state, commands, and platform interfaces
    │   ├── platform/WindowsCommandExecutor.kt
    │   ├── audio/                            # Add microphone and PCM playback adapters here
    │   ├── gemini/                           # Add desktop Gemini Live adapter here
    │   ├── storage/                          # Add notes/history/memory persistence here
    │   └── automation/                       # Add hotkey/tray/Windows UI Automation here
    └── resources/                            # Icons and desktop resources
```

## Run on Windows

Install a compatible JDK and set `JAVA_HOME`, then run:

```powershell
$env:JAVA_HOME = "C:\Path\To\JDK"
.\gradlew.bat :desktop:run
```

To create an installer:

```powershell
.\gradlew.bat :desktop:createDistributable
```

## Features included in this template

The current desktop template now includes a system-tray icon, a `Ctrl + Alt + Space` global hotkey, microphone capture at 16 kHz mono PCM, streamed PCM response playback, Gemini Live WebSocket setup, text input, input/output transcription callbacks, and response audio playback. The first version deliberately does not execute Gemini tool calls yet; add those calls through `PlatformCommandExecutor` after the core voice loop is stable.

## Gemini API key

The desktop runtime reads the key from the `GEMINI_API_KEY` environment variable when no saved key exists. In PowerShell, set it for the current terminal session:

```powershell
$env:GEMINI_API_KEY = "your-key-here"
.\gradlew.bat :desktop:run
```

Do not commit the key to Git, place it in `build.gradle.kts`, or write it directly into Kotlin source. For a persistent Windows user-level variable, use Windows Environment Variables or:

```powershell
[Environment]::SetEnvironmentVariable("GEMINI_API_KEY", "your-key-here", "User")
```

The desktop app uses the Gemini Live WebSocket endpoint and sends microphone data as raw PCM 16-bit, 16 kHz, little-endian audio. Use a headset when testing to reduce feedback. The `Ctrl + Alt + Space` shortcut starts or stops microphone capture. If the WebSocket closes, the status explains that the key/model/internet should be checked, and the next text command or microphone start automatically attempts to reconnect. The tray menu provides the same control and an Exit action.

## Build a Windows executable or installer

Open PowerShell in the project root, install JDK 17, and set `JAVA_HOME`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
```

Then run the portable app-image build:

```powershell
.\gradlew.bat :desktop:createDistributable
```

The portable application directory is created under:

```text
desktop\build\compose\binaries\main\app\VoiceBrainLive\
```

Run the generated application directly with:

```powershell
.\desktop\build\compose\binaries\main\app\VoiceBrainLive\VoiceBrainLive.exe
```

Create Windows installers with:

```powershell
.\gradlew.bat :desktop:packageExe
.\gradlew.bat :desktop:packageMsi
```

The installer files are normally placed under:

```text
desktop\build\compose\binaries\main\exe\
desktop\build\compose\binaries\main\msi\
```

A repeatable packaging script is also included:

```powershell
.\desktop\package-windows.ps1 -JavaHome $env:JAVA_HOME
```

The script creates `dist\windows\` and copies the portable app and installers there. After a successful portable build, it also creates `VoiceBrainLive.lnk` on the current Windows user's Desktop. Build the installer on Windows because the native Windows packaging tools and current-OS target are required.

To create the shortcut manually after a successful build, run:

```powershell
.\desktop\create-desktop-shortcut.ps1
```

The previous build log showed that `C:\Program Files\Java\jdk-17` did not exist. Microsoft OpenJDK 17 has now been installed and the desktop build completed successfully. The generated executable was verified, and `VoiceBrainLive.lnk` was created on the Windows Desktop.

For future builds, use the robust script below. It automatically finds Microsoft JDK 17, builds the app, and creates the Desktop shortcut:

```powershell
.\desktop\build-desktop.ps1
```

To verify the shortcut target:

```powershell
.\desktop\verify-shortcut.ps1
```

The verified shortcut target is the generated `desktop\build\compose\binaries\main\app\VoiceBrainLive\VoiceBrainLive.exe`.

## Windows assistant features now included

The desktop UI is now organized as a Burmese-friendly assistant dashboard with connection status, microphone status, transcript and response panels, a text command box, and quick actions for Chrome, File Explorer, Calculator, and Google search. Gemini tool calls are restricted to safe Windows actions: opening approved applications, closing approved executable applications, opening URLs/settings/folders, and web search. The Windows module does not include phone calls, SMS, contacts, Android accessibility services, Health Connect, geofencing, Wear OS, or phone-specific permissions.

The assistant can answer Windows usage questions conversationally in Burmese, such as how to take a screenshot, how to find a file, or how to use a keyboard shortcut. When the user asks it to operate the laptop, the model must request the desktop command tool and the Windows adapter performs only the allow-listed action.

The expanded command set includes opening and closing approved applications, opening Downloads/Documents/Desktop/Recycle Bin, opening Windows Network/Bluetooth/Display/Sound settings, opening Task Manager, searching Google, taking a screenshot with Snipping Tool, increasing/decreasing/muting volume, locking the computer, and reporting basic system status. Shutdown, Restart, and Sleep are now available through voice commands with voice-only confirmation. The assistant speaks a confirmation prompt; the user must say `ဟုတ်ကဲ့`, `အတည်ပြုပါတယ်`, `Confirm`, `Yes`, or `OK` to execute. Saying `မလုပ်ပါနဲ့`, `Cancel`, or `No` cancels the request. Shutdown and Restart use a 30-second Windows timer so the user has a short opportunity to cancel with `shutdown /a`; Sleep starts immediately after voice confirmation. The floating robot changes to its confirmation expression while waiting. Say `ဟုတ်ကဲ့`, `အတည်ပြုပါတယ်`, `Confirm`, `Yes`, or `OK` to proceed, and say `မလုပ်ပါနဲ့`, `Cancel`, or `No` to cancel. No UI Confirm/Cancel card is required.

Voice file search is also enabled. The assistant can search file names in Desktop, Documents, Downloads, Pictures, Videos, Music, and OneDrive, return up to 25 matching paths, and open a selected file only when the user explicitly asks. It does not read private file contents, delete files, or search outside the approved user folders. Example commands include `invoice pdf ရှာပါ`, `မနေ့က report file ရှာပါ`, and `တွေ့တဲ့ report ကို ဖွင့်ပါ။`

The current implementation does not copy Android `Context`, `Activity`, `Service`, `SpeechRecognizer`, `CameraX`, or telephony code into the desktop module. Add those capabilities through Windows-specific adapters rather than importing Android classes.

## Home screen and API key Settings

The Desktop home screen is now microphone-centered: click the large microphone button or the floating robot companion to start or stop listening, or use `Ctrl + Alt + Space`. The robot is a separate transparent, always-on-top Windows window, so it continues moving on the Desktop when the main app window is minimized or closed. Its command system explicitly supports voice requests for `shutdown`, `restart`, and `sleep`; each request enters the robot's confirmation expression and asks for voice confirmation before the Windows action is sent. It now has custom expressions for idle, listening, thinking, speaking, success, error, and confirmation states. Expanding circular voice-visualizer waves surround the robot while it listens or speaks, with slower low-amplitude motion in idle mode to keep CPU use low. It stays inside the primary screen boundary, uses a low-frequency movement loop, changes color while listening, and is directly clickable for voice conversation. The home screen shows connection status, the latest conversation, a clean dark theme, and a compact text fallback.

Open `Settings`, paste the Gemini API key into the masked field, and press `Test Gemini Key` to validate it through the Gemini REST API. If the test succeeds, press `Save key`; the Desktop runtime automatically creates a new session and connects. The main screen does not need a `Connect Gemini` button. The key is stored in the current Windows user's Java Preferences under the `VoiceBrainLive` node, not in source code or the project directory. After saving, return to Home; the app connects automatically. `Clear` removes the stored key. The field is masked by default; `Show key` is available when checking the pasted value.

In Settings, the `Desktop Robot` button shows or hides the separate floating robot and persists the choice for the current Windows user. The system-tray menu also provides `Show Desktop Robot` and `Hide Desktop Robot` actions. Closing the robot window hides it rather than exiting the assistant; use the tray `Exit` action to stop the background assistant.

Notion integration is available in the same Settings panel. Enter the Notion internal integration token and the parent page ID, then press `Save key`; `Test Notion` checks `/v1/users/me`. Voice commands include `Notion မှာ meeting ရှာပါ`, `ဒီအကြောင်းအရာကို Notion note အဖြစ် သိမ်းပါ`, and `တွေ့တဲ့ Notion page ကို ဖွင့်ပါ`. The integration can search shared pages, create a child page under the configured parent page, and open a Notion URL. The Notion token and parent page ID are stored in the current Windows user's local Java Preferences.


## Completed staged upgrades

The Desktop build now includes explicit assistant phases for connecting, listening, thinking, speaking, voice confirmation, and errors. Recent Windows actions are retained in a bounded in-memory history panel, while shutdown, restart, and sleep remain voice-confirmed.

File search uses a bounded in-memory index of approved user folders with token ranking and a `refresh_file_index` command. Search returns names and paths only; it does not read or delete file contents.

Notion voice workflows include search, page creation, note creation, task creation, appending a note to the configured parent page, opening a Notion URL, and connection testing. Example commands include `Notion မှာ ဒီနေ့လုပ်စရာ task ဖန်တီးပါ` and `ဒီအကြောင်းအရာကို Notion note အဖြစ် သိမ်းပါ`.

Gemini connections use a 15-second connection timeout and up to three connection attempts with progressive delay. Local diagnostics are written to `%APPDATA%\\VoiceBrainLive\\logs\\app.log`; API keys, tokens, transcripts, and file contents are not written to the log. The current build script continues to create the portable application and refresh the Desktop shortcut.
