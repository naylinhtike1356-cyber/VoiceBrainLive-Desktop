# Changelog

## 0.9.1 — Live Connection Stability

The native audio stream now uses the current `realtimeInput.audio` field instead of the deprecated `mediaChunks[]` field. The WebSocket client also uses a longer keep-alive interval, no artificial read timeout, and a 20-second setup grace period. After rebuilding the packaged app, the latest log reached **Gemini Live Mode ready** successfully.

## 0.9.0 — Stable Voice Runtime and Clean Exit

- Replaced the Gradle-based `Start-VoiceBrainLive.vbs` launcher with direct launch of the packaged `VoiceBrainLive.exe`.
- Prevented Gradle and Java wrapper processes from being left behind after closing the application.
- Kept single-instance protection and idempotent runtime shutdown.
- Improved native Live audio playback gap tolerance for WebSocket packet jitter.
- Removed the obsolete Desktop Automation toggle and stale permission rejection path.
- Kept explicit fresh-user-speech gating and confirmation for sensitive power actions.
- Verified the desktop module with `compileKotlin` and the complete test suite.
