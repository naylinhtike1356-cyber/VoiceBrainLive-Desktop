# Building `webrtc_aec3.dll` on Windows

**Do this on the Windows laptop only.** The stub
(`native/webrtc_aec3_stub.cpp`) is deliberately *not* compiled in the Linux
sandbox — WebRTC's build toolchain (depot_tools + VS + ~30 GB) is
Windows-only in practice, and the JNI surface can only be validated against
real audio hardware.

The app works without the DLL: it falls back to the pure-JVM
`SuppressionEchoCanceller` (half-duplex barge-in) and logs a warning.
This DLL upgrades the pipeline to true acoustic echo cancellation
(WebRTC AEC3) so the user can interrupt naturally while the assistant
is speaking.

## 1. Prerequisites

- Windows 10/11 x64, ~40 GB free disk
- Visual Studio 2022 with "Desktop development with C++" (MSVC v143)
- JDK 17 (already required by the Gradle build) — needed for `jni.h`
- CMake ≥ 3.20
- Python 3.9+ (used by depot_tools)

## 2. Fetch WebRTC and pin a revision

```bat
mkdir C:\webrtc && cd C:\webrtc
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
set PATH=C:\webrtc\depot_tools;%PATH%
set DEPOT_TOOLS_WIN_TOOLCHAIN=0
fetch webrtc
cd src
```

Pin and record the exact revision you built (reproducibility):

```bat
git rev-parse HEAD > C:\webrtc\pinned-revision.txt
```

> ⚠️ **API-drift checkpoint (do not skip):** open
> `src\modules\audio_processing\audio_buffer.h` in the pinned checkout and
> verify the `AudioBuffer` constructor used by `webrtc_aec3_stub.cpp`
> (`MakeBuffer()`/`FillBuffer()`/`ReadBuffer()`). If the signature changed,
> adapt the three helpers — the rest of the stub does not depend on it.

## 3. Build WebRTC's audio_processing module (static libs)

In an **x64 Native Tools Command Prompt for VS 2022**:

```bat
cd C:\webrtc\src
gn gen out\aec3 --args="is_debug=false target_cpu=""x64"" rtc_include_tests=false rtc_build_examples=false rtc_use_h264=false"
ninja -C out\aec3 modules/audio_processing
```

This produces static `.lib` files under `out\aec3\obj\modules\audio_processing\`.

## 4. Build the JNI DLL

Still in the x64 Native Tools prompt, in the repo checkout:

```bat
cd C:\Users\nayli\AndroidStudioProjects\VoiceBrainLive-Desktop\native
cmake -S . -B build -DWEBRTC_ROOT=C:\webrtc\src
cmake --build build --config Release
```

Output: `build\Release\webrtc_aec3.dll` (also copied to `..\packaging\`).

## 5. Install and enable

1. Build the packaged app: `.\build-windows.ps1` (runs tests too).
2. Copy `native\build\Release\webrtc_aec3.dll` next to
   `VoiceBrainLive.exe` in
   `desktop\build\compose\binaries\main\app\VoiceBrainLive\`
   (same folder as the exe — it is on the JVM's library path).
3. Enable AEC3 (one of):
   - `setx VBL_ECHO_CANCELLER webrtc_aec3` (then restart the app), or
   - JVM flag `-Dvbl.echo.canceller=webrtc_aec3`, or
   - the app's settings store key `audio.echoCanceller = webrtc_aec3`.
4. Say **"latency စစ်"** after a voice session and check the log shows the
   AEC stage timing — that confirms the native path is active.

## 6. Echo-loop validation (the actual test)

1. Start the app, let the assistant speak a long answer.
2. While it is speaking, talk over it (deliberate barge-in).
3. Expected: the assistant stops promptly, your words are transcribed from
   the *first* syllable (pre-roll preserved), and the assistant's own voice
   does not leak into the transcript.
4. Counter-check with suppression mode (`VBL_ECHO_CANCELLER=suppression`):
   barge-in should still work but with a slightly later cut-in — AEC3
   should be noticeably cleaner.

If the DLL fails to load you will see
`[Audio] webrtc_aec3 native library not available ... falling back to
suppression mode` in the log and the app keeps working.
