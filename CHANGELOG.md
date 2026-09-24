# Changelog

## 0.9.0 — Stable Voice Runtime and Clean Exit

- Replaced the Gradle-based `Start-VoiceBrainLive.vbs` launcher with direct launch of the packaged `VoiceBrainLive.exe`.
- Prevented Gradle and Java wrapper processes from being left behind after closing the application.
- Kept single-instance protection and idempotent runtime shutdown.
- Improved native Live audio playback gap tolerance for WebSocket packet jitter.
- Removed the obsolete Desktop Automation toggle and stale permission rejection path.
- Kept explicit fresh-user-speech gating and confirmation for sensitive power actions.
- Verified the desktop module with `compileKotlin` and the complete test suite.
