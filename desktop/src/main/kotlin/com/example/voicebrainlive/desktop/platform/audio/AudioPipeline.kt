package com.example.voicebrainlive.desktop.platform.audio

/**
 * Phase 1 — Full-duplex audio pipeline contracts.
 *
 * The pipeline is split into small interfaces so each stage can be unit-tested
 * and swapped without touching the orchestrator ([com.example.voicebrainlive.desktop.platform.WindowsAudioEngine]):
 *
 * ```
 * mic frames → AudioCapture → [AGC] → VoiceActivityDetector ─┐
 *                                                             ├→ EchoCanceller → forward / suppress / barge-in
 * render ref → AudioRenderer ─────────────────────────────────┘
 * ```
 *
 * Frame contract everywhere: 16-bit signed little-endian PCM, mono.
 * Capture runs at 16 kHz; render runs at 24 kHz (Gemini Live output) and is
 * resampled to 16 kHz only when a real echo canceller needs a reference
 * (see [AudioResampler]).
 */
interface AudioCapture {
    /**
     * Starts capture. Invokes [onFrame] with one raw PCM frame per read
     * (16 kHz mono 16-bit LE, typically 1024 bytes = 32 ms).
     * Returns false when no input device could be acquired.
     */
    fun start(onFrame: (ByteArray) -> Unit): Boolean
    fun stop()
    val isActive: Boolean
}

/** Playback side of the pipeline (assistant voice out). */
interface AudioRenderer {
    /**
     * Enqueues 16-bit LE mono PCM for playback.
     * Returns false when the internal queue was full and the chunk was dropped.
     */
    fun enqueuePcm16(data: ByteArray): Boolean

    /** Barge-in / intentional stop: drops queued audio and halts the line. */
    fun stopPlayback()

    /** True while audio is queued or the line is actively rendering. */
    val isPlaying: Boolean

    val droppedChunkCount: Long
    fun close()
}

/** Observer for render-side events needed by the orchestrator and telemetry. */
interface PlaybackListener {
    fun onBurstStateChanged(playing: Boolean)
}

/**
 * Voice activity detector. Implementations must be pure-JVM and fast enough
 * to run on every capture frame (32 ms budget is generous).
 */
interface VoiceActivityDetector {
    /**
     * Analyzes one frame (16 kHz mono 16-bit LE PCM) and returns true when
     * speech is present. Implementations apply their own hangover, so callers
     * must feed *every* frame in order — including silence — for the
     * adaptive noise floor and hangover to behave correctly.
     */
    fun isSpeech(frame: ByteArray): Boolean
    fun reset()
}

/** Verdict of the echo canceller for one capture frame. */
sealed interface EchoDecision {
    /** Forward the (possibly echo-cleaned) frame upstream. */
    data class Forward(val frame: ByteArray) : EchoDecision

    /** Drop the frame (echo/suppression — do not forward). */
    data object Suppress : EchoDecision

    /**
     * Confirmed user interruption while the assistant was speaking.
     * The orchestrator must stop playback immediately, then forward [frame].
     */
    data class BargeIn(val frame: ByteArray) : EchoDecision
}

/**
 * Echo cancellation stage.
 *
 * - [isFullDuplexCapable] = true: the canceller removes echo from the signal
 *   itself (e.g. WebRTC AEC3), so the microphone may stay fully open during
 *   playback — true full-duplex.
 * - false: the canceller only *suppresses* (drops mic frames while the
 *   assistant speaks, with barge-in) — today's half-duplex fallback.
 */
interface EchoCanceller {
    val isFullDuplexCapable: Boolean
    val kind: String

    /**
     * Processes one capture frame.
     *
     * @param micFrame raw (AGC'd) capture frame, 16 kHz mono 16-bit LE.
     * @param renderReference the audio currently being rendered, resampled to
     *   16 kHz mono 16-bit LE and length-matched to [micFrame] as closely as
     *   possible; null when nothing is playing (or when the canceller does
     *   not need a reference, e.g. pure suppression).
     * @param speechDetected the [VoiceActivityDetector] verdict for this frame.
     */
    fun processCapture(
        micFrame: ByteArray,
        renderReference: ByteArray?,
        speechDetected: Boolean,
    ): EchoDecision

    fun reset()
}
