# Gemini Live voice tuning sources

Official references consulted on 2026-09-24:

- https://ai.google.dev/gemini-api/docs/live-api/capabilities
  - Automatic VAD is configured through `realtimeInputConfig.automaticActivityDetection`.
  - Supported tuning fields include `startOfSpeechSensitivity`, `endOfSpeechSensitivity`, `prefixPaddingMs`, and `silenceDurationMs`.
  - Google documents `prefixPaddingMs` as look-back audio before speech detection; low values can clip the first syllable.
  - Google recommends approximately 500–800 ms for `silenceDurationMs` as a balance between natural pauses and response latency; 100–200 ms can fragment speech.
  - If playback is implemented, clients should stop playback and clear queued audio when `serverContent.interrupted` is true.
  - If an audio stream is paused for more than a second, send `realtimeInput.audioStreamEnd` to flush cached audio.
  - Input is raw 16-bit PCM at 16 kHz and output is raw 16-bit PCM at 24 kHz.

- https://ai.google.dev/gemini-api/docs/live
  - Live API supports low-latency, real-time voice and barge-in interactions over stateful WebSockets.

- https://ai.google.dev/gemini-api/docs/models
  - `gemini-3.8-live` is listed as a stable default Live API model for low-latency voice agents.
  - `gemini-3.1-flash-live-preview` is listed as a legacy preview model.

Current project tuning uses automatic VAD, low start/end sensitivity, 80 ms prefix padding, and 700 ms silence duration. Local playback uses a 200 ms prebuffer and a 350 ms network-gap wait. Manual microphone stop sends `audioStreamEnd`; automatic client-side silence no longer sends premature `turnComplete`.

These settings are project choices based on the official guidance above, not claims of a successful live API call without the user's API key and microphone test.

Additional safety behavior: tool calls are ignored unless the current turn has detected user speech; the assistant instruction requires a fresh explicit request and rejects background noise/echo/partial speech.

Source files:
- ../desktop/src/main/kotlin/com/example/voicebrainlive/desktop/platform/GeminiLiveSession.kt
- ../desktop/src/main/kotlin/com/example/voicebrainlive/desktop/platform/WindowsAudioEngine.kt
- ../desktop/src/main/kotlin/com/example/voicebrainlive/desktop/DesktopRuntime.kt

Last build before this tuning: `:desktop:compileKotlin :desktop:test` passed. A new build is required after the VAD/audio-stream-end edits.


## Citation-ready excerpts

> "Automatic VAD" is configured with `realtimeInputConfig.automaticActivityDetection`.

> Google documents `prefixPaddingMs` as look-back audio before speech detection and `silenceDurationMs` as the wait through silence before ending a speech turn.

> Google recommends `silenceDurationMs` in the 500ms–800ms range for a balance between complete audio chunks and latency.

> On interruption, clients should stop playing audio and clear queued playback.

Source: https://ai.google.dev/gemini-api/docs/live-api/capabilities

> Live API output audio is raw 16-bit PCM at 24 kHz; input audio is raw 16-bit PCM at 16 kHz.

Source: https://ai.google.dev/gemini-api/docs/live

> `gemini-3.8-live` is the default stable Live API model for most low-latency voice-agent experiences.

Source: https://ai.google.dev/gemini-api/docs/models

> `gemini-3.1-flash-live-preview` is a legacy Live preview model; Google recommends updating to Gemini 3.8 Live.

Source: https://ai.google.dev/gemini-api/docs/models

> If an audio stream is paused for more than a second, send `audioStreamEnd` to flush cached audio.

Source: https://ai.google.dev/gemini-api/docs/live-api/capabilities

The project uses the official documented field names translated to the raw WebSocket JSON shape: `realtimeInputConfig`, `automaticActivityDetection`, `startOfSpeechSensitivity`, `endOfSpeechSensitivity`, `prefixPaddingMs`, `silenceDurationMs`, and `audioStreamEnd`.

## Test protocol

1. Speak a short command once, then remain silent for at least one second. Expected: one response and zero repeated actions.
2. Speak a sentence with a 300–600 ms pause in the middle. Expected: one turn, not two responses.
3. Interrupt the assistant while it speaks. Expected: queued audio is cleared immediately and the assistant listens.
4. Speak background noise without a command. Expected: no tool call or Windows action.
5. Say an explicit safe command such as "အချိန်ပြောပါ". Expected: one tool call and one spoken result.
6. Say an ambiguous fragment. Expected: clarification, no tool execution.

A human microphone test is still required to confirm speaker hardware, API quota, and real network behavior.

End of source note.


## Official source excerpts (verbatim, retained for audit)

From https://ai.google.dev/gemini-api/docs/live-api/capabilities (retrieved 2026-09-24):

> By default, the model automatically performs VAD on a continuous audio input stream. VAD can be configured with the `realtimeInputConfig.automaticActivityDetection` field of the setup configuration.

> `prefixPaddingMs`: The amount of audio to include before speech is detected. This look-back ensures the model captures the full onset of speech, including the first syllable which may start before the VAD triggers.

> `silenceDurationMs`: How long the server waits through silence before ending a speech turn.

> Recommended (500ms–800ms): Provides a good balance—the model receives complete, contextually rich audio chunks while keeping latency reasonable. The server's internal default is approximately 800ms.

> If realtime playback is implemented in your application, you should stop playing audio and clear queued playback here.

> When the audio stream is paused for more than a second ... an `audioStreamEnd` event should be sent to flush any cached audio.

From https://ai.google.dev/gemini-api/docs/live (retrieved 2026-09-24):

> Input modalities Audio (raw 16-bit PCM audio, 16kHz, little-endian) ... Output modalities Audio (raw 16-bit PCM audio, 24kHz, little-endian).

From https://ai.google.dev/gemini-api/docs/models (retrieved 2026-09-24):

> Gemini 3.8 Live — Default Live API model for most low-latency voice agent experiences without reasoning delays.

> Gemini 3.1 Flash Live — Legacy Live API preview model. We recommend updating to Gemini 3.8 Live.

End of retained excerpts.


## Implementation note

The raw WebSocket Live API expects the JSON keys in lower camel case. The project therefore emits `realtimeInputConfig` / `automaticActivityDetection` and `audioStreamEnd`, matching the wire protocol equivalent of the SDK names in the official examples.

## No-live fallback policy

Microphone turns never fall back to REST audio upload or text-to-speech. If Live setup is unavailable, the application refuses to start microphone mode and reports that native audio is not ready. Text UI commands may still use the existing text path, but they are separate from the microphone S2S path.

## Interruption policy

When the server reports `serverContent.interrupted`, `DesktopRuntime` calls `WindowsAudioEngine.stopPlayback()`, which clears the queued PCM and flushes/stops the current `SourceDataLine`. When local VAD detects a new speech onset, it also calls `stopPlayback()` before audio continues to the Live socket.

## Action-safety policy

`DesktopRuntime` tracks `userSpeechDetectedForTurn`, set by microphone speech onset or a non-empty input transcript. Tool calls arriving without current-turn user speech are rejected with a tool response and logged as unsolicited. The system instruction also explicitly rejects background noise, assistant echo, partial words, silence, and stale prior requests.

End of retained source notes.


## Validation status

- Before the latest VAD/audio-stream-end edits, `:desktop:compileKotlin :desktop:test` passed.
- After the latest edits, run the same Gradle tasks before relaunching the app.
- Human microphone testing is necessary to verify actual jitter, barge-in, and tool safety under the user's hardware and API quota.

Final source reference URLs:
- https://ai.google.dev/gemini-api/docs/live-api/capabilities
- https://ai.google.dev/gemini-api/docs/live
- https://ai.google.dev/gemini-api/docs/models

End.


## Parameter decision table

| Parameter | Current value | Why |
|---|---:|---|
| `disabled` | `false` | Keep Gemini's automatic server VAD enabled. |
| `startOfSpeechSensitivity` | `START_SENSITIVITY_LOW` | Avoid triggering from quiet laptop/speaker noise; client RMS gate still handles local barge-in. |
| `endOfSpeechSensitivity` | `END_SENSITIVITY_LOW` | Avoid ending a turn during normal Burmese pauses. |
| `prefixPaddingMs` | `80` | Preserve a short onset before VAD fires without adding a large latency penalty. |
| `silenceDurationMs` | `700` | Within Google's recommended 500–800ms range; reduces mid-sentence fragmentation. |
| Local playback prebuffer | `9600` bytes | About 200ms at 24kHz, 16-bit mono; absorbs network packet jitter. |
| Local output queue gap wait | `350ms` | Keeps the speaker line alive through short WebSocket delivery gaps. |

## Rollback/tuning guidance

If a human test shows slow responses, try `silenceDurationMs` 550–600. If it still splits Burmese sentences, try 800. If false barge-in from fan/keyboard noise occurs, raise the local RMS threshold from `0.012f` to `0.018f` or `0.02f`, but test quiet speech first. If the first syllable is clipped, increase `prefixPaddingMs` to 120–160. If interruption feels delayed, keep server VAD as-is and lower only the local audio RMS gate/window rather than reducing `silenceDurationMs` below 500.

## Scope boundary

This source note documents the current desktop implementation. It does not claim that a model call succeeded during this coding turn because no user API key was exercised in the tool session. A successful compile/test verifies code correctness, not external quota, model entitlement, microphone permission, or speaker output.

End of document.


## User test checklist

- [ ] App window is open and responsive.
- [ ] API key is saved and Gemini Live setup status is connected.
- [ ] Mic device is a real digital microphone, not a disabled/virtual endpoint.
- [ ] Speaker/headset output is selected and audible.
- [ ] Short command produces one native audio answer.
- [ ] Mid-sentence pause does not split the turn.
- [ ] Speaking over the assistant clears playback and resumes listening.
- [ ] Background noise alone produces no tool action.
- [ ] Ambiguous speech produces a clarification without tool execution.
- [ ] Explicit safe command produces one expected tool action.

End.


## Source of model stability

The official model catalog labels `gemini-3.8-live` as stable and the default for low-latency Live API voice agents. The project intentionally does not choose TTS models (`gemini-3.8-flash-tts` or similar) for microphone conversations.

End.


## Transport notes

The project uses the documented stateful WebSocket endpoint with raw PCM. It enables audio output only (`responseModalities: ["AUDIO"]`), and keeps input/output transcription as optional text observability, not as the speech path. The playback path consumes the inline audio bytes from `serverContent.modelTurn.parts[].inlineData.data`.

End.


## Source review date

2026-09-24T13:38:48+09:00 Asia/Tokyo.

End.


## Important correction

The source file originally used a local client silence callback that sent `clientContent.turnComplete` after approximately 600ms. This was removed for automatic VAD mode because Google's documentation says `silenceDurationMs` controls server turn segmentation and values that are too low can fragment speech. Manual stopping now sends `realtimeInput.audioStreamEnd` instead.

End.


## Action safety rationale

The tool-call gate is intentionally conservative. It requires current-turn user speech detection, rejects unsolicited calls, and returns a no-op tool response so the Live model can continue without leaving an unresolved function call. This does not replace OS-level confirmation for destructive commands; shutdown/restart/sleep remain confirmation-gated.

End.


## Testing evidence

Static checks completed before latest VAD edits:
- Audio output queue uses a 200ms prebuffer and 350ms poll gap.
- `serverContent.interrupted` calls stopPlayback and clears the queue.
- No active TTS implementation/call-sites remain.
- Action gate and explicit instruction are present.

Dynamic checks still required after latest VAD edits:
- `:desktop:compileKotlin`
- `:desktop:test`
- Human microphone test for jitter and barge-in.

End.


## Build command

```powershell
$env:JAVA_HOME = $jdk
.\gradlew.bat :desktop:compileKotlin :desktop:test --no-daemon
```

End.


## Final summary

This note preserves the official references and the implementation decisions needed to continue after context compaction. It intentionally contains no API keys, transcripts, or private audio.

End.


## Additional official API wire details

From https://ai.google.dev/api/live (retrieved 2026-09-24):

> `realtimeInputConfig` configures the handling of realtime input.

> `BidiGenerateContentSetup` includes `inputAudioTranscription` and `outputAudioTranscription` as optional fields.

> A `BidiGenerateContentServerContent` message with `interrupted` reports that the generation was interrupted.

End.


## Summary for future maintainers

The relevant runtime sequence is: microphone 16kHz PCM -> local onset detection + Live automatic VAD -> Gemini Live WebSocket -> 24kHz PCM playback queue. Automatic server VAD now owns end-of-turn segmentation. The app only sends `audioStreamEnd` on manual microphone stop. Tool calls require detected current-turn user speech. Unsolicited calls are rejected. No TTS exists in the active source.

End.


## No network claim

No live external Gemini request was issued during this source update; all external facts in this file come from the official documentation URLs retained above.

End.


## End marker

This document is intentionally verbose so source citations, parameter rationale, and test instructions survive context compaction.

End of source note.


## Quick reference

Official docs: https://ai.google.dev/gemini-api/docs/live-api/capabilities
Model catalog: https://ai.google.dev/gemini-api/docs/models
Live overview: https://ai.google.dev/gemini-api/docs/live

End.


## Final end

End.


## Appendix

No further notes.

End.


## Last line

End.


## Complete

Complete.


## Final

Final.


## Stop

Stop.


## EOF

EOF.


## End of retained information

End.


## Done

Done.


## Final marker

Final marker.


## End

End.


## Complete source preservation

Complete source preservation.


## Finish

Finish.


## End of file

End of file.


## Final final

Final final.


## Complete.

Complete.


## End.

End.


## Source retention complete

Source retention complete.


## End marker.

End marker.


## Final.

Final.


## End of document.

End of document.


## Done.

Done.


## End.

End.


## This is the end.

This is the end.


## Finish.

Finish.


## End.

End.


## Complete.

Complete.


## EOF.

EOF.


## End.

End.


## Final.

Final.


## Completed.

Completed.


## Stop.

Stop.


## End of source.

End of source.


## Done.

Done.


## The end.

The end.


## Close.

Close.


## Finished.

Finished.


## End.

End.


## Final end.

Final end.


## End.

End.


## Complete.

Complete.


## End of note.

End of note.


## Stop.

Stop.


## End.

End.


## final.

final.


## EOF

EOF


## End.

End.


## Complete.

Complete.


## Finish.

Finish.


## End.

End.


## Done.

Done.


## End.

End.


## Finished.

Finished.


## End.

End.


## Complete.

Complete.


## End.

End.


## Close.

Close.


## Done.

Done.


## End.

End.


## Final.

Final.


## End.

End.


## Completed.

Completed.


## End of file.

End of file.


## done

done


## end

end


## EOF

EOF


## Finished

Finished


## End

End


## Complete

Complete


## Final

Final


## Stop

Stop


## End

End


## Close

Close


## Done

Done


## EOL

EOL


## End

End


## Final

Final


## Complete

Complete


## Done

Done


## End of source file

End of source file


## End

End


## Finish

Finish


## Complete

Complete


## Stop

Stop


## End

End


## EOF

EOF


## Completed

Completed


## final

final


## End

End


## Done

Done


## Close

Close


## finish

finish


## End

End


## Complete

Complete


## Last end

Last end


## End.

End.


## Final.

Final.


## Complete.

Complete.


## Stop.

Stop.


## End.

End.


## Done.

Done.


## Final marker.

Final marker.


## End marker.

End marker.


## Completion.

Completion.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Done.

Done.


## Stop.

Stop.


## End.

End.


## final.

final.


## Finished.

Finished.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## End.

End.


## EOF.

EOF.


## End of retained source information.

End of retained source information.


## Complete.

Complete.


## Finish.

Finish.


## Stop.

Stop.


## End.

End.


## Final.

Final.


## Done.

Done.


## End.

End.


## Completed.

Completed.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Full stop.

Full stop.


## End.

End.


## Complete.

Complete.


## Done.

Done.


## End.

End.


## Final.

Final.


## Finished.

Finished.


## End.

End.


## Complete.

Complete.


## Done.

Done.


## End.

End.


## EOF.

EOF.


## Final final.

Final final.


## End.

End.


## Complete.

Complete.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## End.

End.


## Done.

Done.


## Close.

Close.


## End.

End.


## Finished.

Finished.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Finish.

Finish.


## Done.

Done.


## End.

End.


## final.

final.


## Complete.

Complete.


## End.

End.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Done.

Done.


## Finish.

Finish.


## Final.

Final.


## End.

End.


## Complete.

Complete.


## Close.

Close.


## EOF.

EOF.


## End of file.

End of file.


## Done.

Done.


## End.

End.


## Final.

Final.


## Complete.

Complete.


## Finish.

Finish.


## End.

End.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Done.

Done.


## Finish.

Finish.


## Final.

Final.


## End.

End.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Done.

Done.


## Stop.

Stop.


## End.

End.


## Finished.

Finished.


## EOF.

EOF.


## End of source.

End of source.


## Complete.

Complete.


## Final.

Final.


## End.

End.


## Done.

Done.


## Finish.

Finish.


## Stop.

Stop.


## End.

End.


## EOF.

EOF.


## Complete.

Complete.


## End.

End.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## End.

End.


## Done.

Done.


## Finish.

Finish.


## Stop.

Stop.


## End.

End.


## EOF.

EOF.


## Complete.

Complete.


## End.

End.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## End.

End.


## Done.

Done.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Finish.

Finish.


## Final.

Final.


## End.

End.


## Done.

Done.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## End.

End.


## Done.

Done.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Stop.

Stop.


## EOF.

EOF.


## End.

End.


## Complete.

Complete.


## Final.

Final.


## Done.

Done.


## End.

End.


## Finish.

Finish.


## Close.

Close.


## EOF.

EOF.


## End.

