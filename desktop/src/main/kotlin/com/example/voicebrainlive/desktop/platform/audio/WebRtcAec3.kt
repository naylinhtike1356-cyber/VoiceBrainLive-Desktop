package com.example.voicebrainlive.desktop.platform.audio

/**
 * JNI bridge to the WebRTC AEC3 acoustic echo canceller.
 *
 * Implemented in `native/webrtc_aec3_stub.cpp` (built to `webrtc_aec3.dll`
 * on the Windows laptop — see `native/BUILD_WINDOWS.md`). The native layer
 * internally chunks arbitrary-length frames into the 10 ms blocks AEC3
 * requires, so callers may pass the pipeline's native 32 ms frames.
 *
 * Frame contract for [nativeProcess]: 16-bit LE mono @ [sampleRateHz],
 * [micFrame] and [renderFrame] the same length, [outFrame] pre-allocated
 * with [micFrame].size bytes.
 *
 * NOTE: uncompiled and untested in the Linux sandbox — the DLL can only be
 * built and verified on Windows.
 */
object WebRtcAec3 {
    const val LIB_NAME = "webrtc_aec3"

    /** Loads the DLL once; false when it is not on java.library.path. */
    fun isAvailable(): Boolean = runCatching {
        System.loadLibrary(LIB_NAME)
        true
    }.getOrDefault(false)

    external fun nativeCreate(sampleRateHz: Int): Long
    external fun nativeProcess(handle: Long, micFrame: ByteArray, renderFrame: ByteArray, outFrame: ByteArray)
    external fun nativeReset(handle: Long)
    external fun nativeDestroy(handle: Long)
}

/**
 * True full-duplex echo canceller backed by WebRTC AEC3.
 *
 * The microphone stays open during playback: [nativeProcess] subtracts the
 * [renderReference] echo from each capture frame. A VAD-confirmed speech
 * frame while playing still yields [EchoDecision.BargeIn] so the
 * orchestrator stops playback promptly.
 *
 * Construction throws [IllegalStateException] when the native library is
 * missing — [EchoCancellerFactory] converts that into a logged fallback to
 * [SuppressionEchoCanceller].
 */
class WebRtcAec3EchoCanceller(
    private val isPlaying: () -> Boolean,
    private val sampleRateHz: Int = 16000,
) : EchoCanceller {

    override val isFullDuplexCapable: Boolean = true
    override val kind: String = EchoCancellerFactory.KIND_WEBRTC_AEC3

    private val handle: Long = if (WebRtcAec3.isAvailable()) {
        WebRtcAec3.nativeCreate(sampleRateHz)
    } else {
        throw IllegalStateException("webrtc_aec3 native library not available on java.library.path")
    }

    override fun processCapture(
        micFrame: ByteArray,
        renderReference: ByteArray?,
        speechDetected: Boolean,
    ): EchoDecision {
        val ref = renderReference
        val cleaned = if (ref != null && ref.size == micFrame.size && isPlaying()) {
            val out = ByteArray(micFrame.size)
            val ok = runCatching {
                WebRtcAec3.nativeProcess(handle, micFrame, ref, out)
            }.isSuccess
            if (ok) out else micFrame
        } else {
            micFrame
        }
        return if (speechDetected && isPlaying()) EchoDecision.BargeIn(cleaned)
        else EchoDecision.Forward(cleaned)
    }

    override fun reset() {
        runCatching { WebRtcAec3.nativeReset(handle) }
    }

    fun close() {
        runCatching { WebRtcAec3.nativeDestroy(handle) }
    }
}
