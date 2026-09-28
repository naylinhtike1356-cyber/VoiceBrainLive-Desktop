# Automation Plugin (detached)

**Status:** Source archive only — NOT part of the core build.

This directory holds the former `desktop.automation` subsystem, extracted from the
core app during the Phase 0 "cut the bloat" pass (see `../PLAN-full-duplex.md` §3):

- Android build pipeline / emulator runner / wireless ADB manager
- Git automation / multi-repo manager
- IDE bridge / Antigravity bridge / project resolver
- Auto-healing fix engine / coding task formatter
- Associated unit tests (`*Test.kt`)

## Why it was detached

VoiceBrainLive-Desktop is a **voice-first Windows assistant**. Its core loop is
listen → understand → act → speak. A full AI coding-agent toolchain (Android
builds, ADB, repo management, IDE bridges) does not belong in that loop: it
inflates the command surface, the review burden, and the risk of the voice
command dispatcher.

## Not compiled

`plugins/` is intentionally **not** added to `settings.gradle(.kts)`. These files
are kept for reference and for a possible future **opt-in plugin**, not compiled
into the app. They retain their original
`com.example.voicebrainlive.desktop.automation` package declarations.

## Re-attaching (future)

If this ever becomes an opt-in plugin:

1. Move the sources into a dedicated Gradle module (e.g. `:plugin-automation`).
2. Expose a narrow, allow-listed command interface to `WindowsCommandExecutor`
   instead of the previous deep wiring.
3. Gate it behind the existing `desktopAutomationEnabled` setting.
