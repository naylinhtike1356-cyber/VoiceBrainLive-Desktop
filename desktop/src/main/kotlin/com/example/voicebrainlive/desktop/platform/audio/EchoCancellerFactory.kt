package com.example.voicebrainlive.desktop.platform.audio

/**
 * Selects the [EchoCanceller] from the `audio.echoCanceller` setting.
 *
 * - `"suppression"` (default): [SuppressionEchoCanceller] — half-duplex with
 *   VAD-based barge-in. Always available, pure JVM.
 * - `"webrtc_aec3"`: [WebRtcAec3EchoCanceller] — true full-duplex. Requires
 *   `webrtc_aec3.dll` on `java.library.path` (see `native/BUILD_WINDOWS.md`).
 *   When the library is missing it logs once and falls back to suppression
 *   rather than failing startup.
 *
 * The setting is read once at engine construction; changing it takes effect
 * on the next app start.
 */
object EchoCancellerFactory {
    const val KIND_SUPPRESSION = "suppression"
    const val KIND_WEBRTC_AEC3 = "webrtc_aec3"

    fun normalizeKind(raw: String?): String =
        if (raw?.trim()?.lowercase() == KIND_WEBRTC_AEC3) KIND_WEBRTC_AEC3 else KIND_SUPPRESSION

    fun create(
        kind: String,
        isPlaying: () -> Boolean,
        onFallback: (message: String) -> Unit = {},
    ): EchoCanceller {
        if (normalizeKind(kind) == KIND_WEBRTC_AEC3) {
            val canceller = runCatching { WebRtcAec3EchoCanceller(isPlaying) }.getOrNull()
            if (canceller != null) return canceller
            onFallback(
                "audio.echoCanceller=webrtc_aec3 requested but the native library is unavailable; " +
                    "falling back to suppression. Build the DLL per native/BUILD_WINDOWS.md."
            )
        }
        return SuppressionEchoCanceller(isPlaying)
    }
}
