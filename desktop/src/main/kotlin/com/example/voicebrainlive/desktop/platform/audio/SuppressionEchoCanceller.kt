package com.example.voicebrainlive.desktop.platform.audio

/**
 * Fallback echo "cancellation" — the behavior the app has always had.
 *
 * While the assistant is speaking ([isPlaying]), microphone frames are
 * *dropped* ([EchoDecision.Suppress]) so speaker echo cannot loop back into
 * the Live session. A deliberate user interruption is detected via the
 * [VoiceActivityDetector] verdict: [bargeInConfirmFrames] consecutive speech
 * frames confirm the barge-in and produce [EchoDecision.BargeIn].
 *
 * This is NOT true echo cancellation — it is half-duplex with barge-in.
 * It stays the default until the WebRTC AEC3 native library is built and
 * enabled (see `native/BUILD_WINDOWS.md`), at which point
 * [WebRtcAec3EchoCanceller] takes over and the mic stays fully open.
 */
class SuppressionEchoCanceller(
    private val isPlaying: () -> Boolean,
    private val bargeInConfirmFrames: Int = 2,
) : EchoCanceller {

    override val isFullDuplexCapable: Boolean = false
    override val kind: String = EchoCancellerFactory.KIND_SUPPRESSION

    private var consecutiveSpeechFrames = 0

    override fun processCapture(
        micFrame: ByteArray,
        renderReference: ByteArray?,
        speechDetected: Boolean,
    ): EchoDecision {
        if (!isPlaying()) {
            consecutiveSpeechFrames = 0
            return EchoDecision.Forward(micFrame)
        }
        return if (speechDetected) {
            consecutiveSpeechFrames++
            if (consecutiveSpeechFrames >= bargeInConfirmFrames) {
                consecutiveSpeechFrames = 0
                EchoDecision.BargeIn(micFrame)
            } else {
                EchoDecision.Suppress
            }
        } else {
            consecutiveSpeechFrames = 0
            EchoDecision.Suppress
        }
    }

    override fun reset() {
        consecutiveSpeechFrames = 0
    }
}
