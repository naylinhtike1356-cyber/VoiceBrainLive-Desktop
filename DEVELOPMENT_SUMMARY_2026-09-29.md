# VoiceBrainLive Desktop — Development Summary
**Date:** 2026-09-29  
**Branch:** `review/code-review-fixes`  
**Latest commit:** `a02995fd`

## Overview
This document summarizes the stability, UI, and diagnostic improvements made to the VoiceBrainLive Desktop voice assistant in the 2026-09-29 development session, based on web research of comparable open-source projects.

## Research Basis
Researched 6 open-source voice assistant projects (FLINT, LUNA, Kyros, py-xiaozhi, JAVI, voiceloop) and Gemini Live API best practices. Full report: `~/workspace/research_notes/voice-assistant-research-20260929-0306/report.md`

Key findings applied:
- Session resumption + context compression = highest-ROI reliability fix
- Interrupted-turn visualization (strike-through pattern from voiceloop)
- Turn latency telemetry (voiceloop's "two numbers that decide aliveness")

## Changes Made

### Phase 1: Stability — Session Resumption + Context Compression
**Commit:** `ecc2798`  
**Files:** `GeminiLiveSession.kt`, `SessionResumptionTest.kt`

- **Problem:** The app sent `"sessionResumption": {}` in setup but never stored the server's resumption handle, so reconnects always paid a full setup round-trip.
- **Fix:** Parse `sessionResumptionUpdate` messages, store the handle (2h validity), send it back on reconnect.
- **Context compression:** Enabled `contextWindowCompression` with sliding window. Without this, sessions die after ~15 min of audio (~25 tokens/sec).
- **Tests:** 3 new unit tests (store on resumable, ignore when not resumable, latest wins).

### Phase 2: UI — Interrupted-Turn Visualization
**Commit:** `161710b` (+ bugfix `d15e0b8`, formatting `1261695`)  
**Files:** `AssistantCore.kt`, `DesktopRuntime.kt`, `ConversationList.kt`

- **Problem:** When the user interrupted the assistant (barge-in), there was no visual indication of what was cancelled.
- **Fix:** 
  - `ChatMessage` gains `interrupted` flag
  - `markLastAssistantInterrupted()` marks the latest assistant message
  - UI renders interrupted turns dimmed with strike-through + "⛔ ဖြတ်တောက်ခံရသည်" label
  - Phase correctly resets to LISTENING on interruption (was stuck on SPEAKING)

### Phase 3: Diagnostics — Turn Latency Telemetry
**Commit:** `3bf7ce5`  
**Files:** `DesktopRuntime.kt`

- **Problem:** User reported "commands still slow" but no data on where the delay occurs.
- **Fix:** Track `turnSpeechStartNanos` when user speech first arrives; log `speech-to-first-audio` delay when server audio starts. If >700ms, users talk over the assistant.
- **Usage:** Check `%APPDATA%\VoiceBrainLive\logs\app.log` for "Turn latency:" entries.

## Pre-existing Features (Verified Present)
The following were already implemented and did not need changes:
- ✅ Persistent memory (`UserMemoryStore` with `toSystemInstructionContext`)
- ✅ Screen awareness (`sendImageWithPrompt` for screenshot Q&A)
- ✅ Global hotkeys (`GlobalHotkeyManager`)
- ✅ Echo-gated barge-in with 2s cooldown (`SuppressionEchoCanceller`)
- ✅ Voice-only replies for voice turns, text+voice for typed turns
- ✅ Bounded audio queues with `offer()` (non-blocking backpressure)

## Verification Status
- **Static review:** All changes reviewed for syntax, imports, logic correctness.
- **Unit tests:** 3 new tests added (SessionResumptionTest). Cannot run in Linux sandbox (no Kotlin toolchain).
- **Hardware testing:** REQUIRED on Windows laptop. The following cannot be verified without it:
  - Actual voice conversation quality and latency
  - Barge-in behavior with real microphone/speaker
  - Session resumption across network drops
  - UI rendering (Compose Desktop)

## Laptop Test Instructions
```powershell
cd C:\Users\nayli\VoiceBrainLive-Desktop
git pull origin review/code-review-fixes
git log --oneline -1   # Should show a02995fd
.\gradlew.bat:desktop:test --console=plain
.\gradlew.bat:desktop:run --console=plain
```

## Future Work (Requires Laptop Testing)
1. **Global hotkey refinements** — Already implemented, needs real-world testing
2. **Wake word** — Offline "Hey Nilar" via ONNX Runtime (complex, needs research)
3. **Screen watch mode** — FLINT-style "watch for changes" (needs throttling design)
4. **Latency HUD** — Visual overlay (currently logs only)

## Important Notes
- **Repository visibility:** Repo was temporarily made public for review. Should be restored to private.
- **API key:** Stored via Windows DPAPI in app Settings. Never in `local.properties`.
- **Correct laptop path:** `C:\Users\nayli\VoiceBrainLive-Desktop` (NOT the AndroidStudioProjects folder)
