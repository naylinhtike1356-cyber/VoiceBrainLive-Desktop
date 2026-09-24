# VoiceBrainLive Windows `.exe` Build Guide

## Important architecture note

VoiceBrainLive is a Kotlin/JVM Compose Desktop application. Therefore, the **native and recommended packaging path** is Compose Desktop + Gradle. PyInstaller is a Python packager, and Electron Builder is an Electron/Node packager; neither one compiles Kotlin source. In this project they are provided only as optional wrappers around the already-created Compose Desktop distribution.

| Workflow | Correct use | Output | Recommendation |
|---|---|---|---|
| Compose Desktop + Gradle | Builds the actual Kotlin app | Self-contained app folder, EXE, MSI | Recommended |
| PyInstaller | Wraps the complete Compose app folder with a Python launcher | One optional wrapper EXE | Use only if Python-based deployment is required |
| Electron Builder | Wraps the complete Compose app folder with an Electron launcher | NSIS installer and/or portable EXE | Use only if Electron distribution features are required |

## Prerequisites

For the native build, install JDK 17 and use the project Gradle wrapper. For the optional PyInstaller wrapper, install Python 3.10 or newer and run `py -m pip install pyinstaller`. For the optional Electron wrapper, install Node.js LTS and npm. Build on Windows when producing a Windows `.exe`; Windows-specific native packaging tools and icons are not portable across operating systems.

## Native Compose Desktop build — recommended

Run the following from the project root:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1 -Mode native
```

For a clean build:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1 -Mode native -Clean
```

The script first runs `:desktop:compileKotlin` and `:desktop:test`, then calls `:desktop:createDistributable` and `:desktop:runDistributable`. The complete distribution is written under:

```text
desktop\build\compose\binaries\main\app\VoiceBrainLive\
```

Do not copy only `VoiceBrainLive.exe`. The `.cfg`, runtime image, DLLs, resources, and other generated files must remain beside the executable. For an installer, run:

```powershell
.\gradlew.bat :desktop:packageMsi --console=plain
```

The packaging task disables Gradle configuration cache because the Compose/WiX packaging task may invoke project state at execution time. This is deliberate: configuration cache remains enabled for normal compile/test/startup work, while packaging uses the compatible mode.

## Optional PyInstaller wrapper

The files are under `packaging\pyinstaller\`:

```text
packaging\pyinstaller\launcher.py
packaging\pyinstaller\VoiceBrainLive.spec
```

Build it after the native Compose distribution exists:

```powershell
py -m pip install pyinstaller
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1 -Mode pyinstaller
```

The spec bundles the complete Compose app folder as PyInstaller data and the Python launcher starts `VoiceBrainLive.exe` with the correct working directory. This wrapper does not convert Kotlin into Python and does not remove the need for the Compose runtime files. It is useful only when a Python-controlled deployment wrapper is specifically needed.

## Optional Electron Builder wrapper

The files are under `packaging\electron\`:

```text
packaging\electron\package.json
packaging\electron\main.cjs
```

Build it with:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1 -Mode electron
```

The Electron main process launches the bundled Compose executable. Electron Builder produces an NSIS installer and a portable target according to `package.json`. This adds Electron runtime size and an extra process, so it is not faster or smaller than the native Compose distribution. Use it only when Electron-specific installer, update, or shell integration requirements justify the added layer.

## Verification checklist

After any packaging workflow, verify that the output contains `VoiceBrainLive.exe`, at least one `.cfg` file, the generated runtime/DLL files, and the resources directory. Launch from the generated app folder first. Then create the Desktop shortcut with the executable as **Target** and the app folder as **Start in**. Finally test Main App visibility, floating robot visibility, drag/damping, Always-on-top, microphone/listening, Gemini connection, a text command, and a voice command.

Never put the Gemini API key, keystore password, or signing secrets into these scripts. Keep secrets in local Windows settings or environment variables and sanitize logs before sharing them.

## Troubleshooting

If the shortcut reports that `VoiceBrainLive.cfg` is missing, the shortcut is pointing to an incomplete or moved distribution. Re-run `:desktop:createDistributable`, point the shortcut to the new `VoiceBrainLive.exe`, and set **Start in** to the directory containing that executable. If packaging fails while cleaning output files, close VoiceBrainLive, Gradle, Java, and Electron processes before retrying; do not delete files while the application is running.
