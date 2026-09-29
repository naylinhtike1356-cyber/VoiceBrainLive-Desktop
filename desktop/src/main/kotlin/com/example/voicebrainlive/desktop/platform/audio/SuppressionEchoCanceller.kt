package com.example.voicebrainlive.desktop.platform.audio

import com.example.voicebrainlive.desktop.platform.DesktopLogger

/**
 * Fallback echo "cancellation" — the behavior the app has always had.
 *
 * While the assistant is speaking ([isPlaying]), microphone frames are
 * *dropped* ([EchoDecision.Suppress]) so speaker echo cannot loop back into
 * the Live session. A deliberate user interruption is detected via the
 * [VoiceActivityDetector] verdict: [bargeInConfirmFrames] consecutive speech
 * frames confirm the barge-in and produce [EchoDecision.BargeIn].
 *
 * Echo gate: while the speaker is actually emitting, the speaker output
 * reaches the mic attenuated by the room, so a VAD verdict alone cannot tell
 * the assistant's own voice from the user's. A barge-in candidate must
 * therefore ALSO sit clearly above an adaptive echo floor — the mic RMS
 * level observed while the speaker is active, learned per playback burst
 * (rooms and volumes differ). Without this gate the assistant interrupts
 * *itself*: its own voice trips the VAD, playback stops, and the response is
 * never heard. The floor adapts only to echo-like levels so a sudden user
 * voice can never drag it upward. In speaker-silent gaps the VAD verdict
 * alone is trustworthy and the pre-gate behavior applies.
 *
 * This is NOT true echo cancellation — it is half-duplex with barge-in.
 * It stays the default until the WebRTC AEC3 native library is built and
 * enabled (see `native/BUILD_WINDOWS.md`), at which point
 * [WebRtcAec3EchoCanceller] takes over and the mic stays fully open.
 *
 * The first speech frame is *held* (not dropped silently): on confirmation
 * it is returned inside [EchoDecision.BargeIn.firstFrame] so the speech
 * onset — the first syllable — is preserved, matching the pre-rewire
 * behavior that buffered the onset frame.
 */
class SuppressionEchoCanceller(
    private val isPlaying: () -> Boolean,
    private val bargeInConfirmFrames: Int = 2,
    private val burstStartGuardMs: Long = 500L,
) : EchoCanceller {

    override val isFullDuplexCapable: Boolean = false
    override val kind: String = EchoCancellerFactory.KIND_SUPPRESSION

    private var consecutiveSpeechFrames = 0
    private var pendingOnset: ByteArray? = null

    /**
     * Adaptive echo floor (mic-domain RMS). Seeded from the render reference
     * on the first speaker-active frame of each playback burst so the first
     * echo frames can never false-trigger; then tracks the observed echo.
     */
    private var echoFloorRms = ECHO_FLOOR_INIT
    private var floorInitializedForBurst = false
    /**
     * Burst-start guard: for the first 500ms of each playback burst, barge-in
     * is suppressed entirely. The echo floor needs a few frames to stabilize,
     * and the initial estimate can be far off (loud speakers, sensitive mic).
     * Without this, the first syllables of the assistant's own speech can
     * false-trigger barge-in before the floor adapts — the self-interruption
     * in the screenshot. A real user interruption 500ms into playback still
     * works; an interruption in the first 500ms is rare and the user can
     * simply speak again.
     */
    private var burstStartNanos = 0L

    /**
     * Barge-in cooldown: after a confirmed barge-in, ignore new barge-in
     * candidates for 2 seconds. Without this, a false barge-in (speaker
     * echo slipping through the gate) stops playback, the server keeps
     * sending audio, playback resumes, the echo triggers again — an
     * oscillation that permanently chops the response. The cooldown breaks
     * the cycle; a real user interruption is already captured by the first
     * barge-in, so delaying a second one by 2s is harmless.
     */
    private var lastBargeInNanos = 0L

    private var echoGatedFrames = 0L
    private var lastGateLogNanos = 0L

    override fun processCapture(
        micFrame: ByteArray,
        renderReference: ByteArray?,
        speechDetected: Boolean,
    ): EchoDecision {
        if (!isPlaying()) {
            consecutiveSpeechFrames = 0
            pendingOnset = null
            floorInitializedForBurst = false
            return EchoDecision.Forward(micFrame)
        }

        val micRms = rms16(micFrame)
        val renderRms = if (renderReference != null) rms16(renderReference) else 0f
        val speakerActive = renderRms > RENDER_ACTIVE_RMS

        if (speakerActive) {
            // Seed the floor from the render signal on the first speaker-active
            // frame of a burst: pure echo is the render attenuated by the room,
            // so render*PRIOR_COUPLING is a safe starting estimate.
            if (!floorInitializedForBurst) {
                echoFloorRms = maxOf(ECHO_FLOOR_INIT, renderRms * PRIOR_COUPLING)
                floorInitializedForBurst = true
            }
            // Track the echo level — UPWARD ONLY during a burst. The floor is
            // re-seeded at the start of each burst, so it never needs to drop:
            // downward adaptation into mic noise during speech pauses was
            // leaving the floor too low when the echo resumed, causing the
            // self-interruption that appears after a few minutes of use.
            // Only observations above the current floor (up to the ceiling)
            // raise it; anything louder may be the user and must not deafen
            // the barge-in detector.
            if (micRms >= echoFloorRms && micRms <= echoFloorRms * ADAPT_CEILING_RATIO) {
                echoFloorRms += (micRms - echoFloorRms) * ADAPT_UP_RATE
            }
        }

        if (!speechDetected) {
            consecutiveSpeechFrames = 0
            pendingOnset = null
            return EchoDecision.Suppress
        }

        // Burst-start guard: suppress barge-in for the first 500ms of playback
        // while the echo floor stabilizes. Prevents self-interruption from
        // the assistant's own opening syllables.
        if (speakerActive) {
            val now = System.nanoTime()
            if (burstStartNanos == 0L) burstStartNanos = now
            if ((now - burstStartNanos) / 1_000_000L < burstStartGuardMs) {
                consecutiveSpeechFrames = 0
                pendingOnset = null
                return EchoDecision.Suppress
            }
        } else {
            burstStartNanos = 0L
        }

        // Echo gate — only while the speaker is emitting: the frame must sit
        // clearly above the learned echo floor to count toward barge-in.
        // Speaker echo alone never passes, so the assistant can no longer
        // interrupt itself.
        if (speakerActive && micRms <= echoFloorRms * BARGE_IN_FLOOR_RATIO) {
            consecutiveSpeechFrames = 0
            pendingOnset = null
            echoGatedFrames++
            val now = System.nanoTime()
            if (now - lastGateLogNanos > GATE_LOG_INTERVAL_NANOS) {
                lastGateLogNanos = now
                DesktopLogger.info(
                    "Audio telemetry: echo gate rejected $echoGatedFrames barge-in candidate frame(s) " +
                        "(floor=${"%.4f".format(echoFloorRms)} mic=${"%.4f".format(micRms)})",
                )
            }
            return EchoDecision.Suppress
        }

        consecutiveSpeechFrames++
        return if (consecutiveSpeechFrames >= bargeInConfirmFrames) {
            consecutiveSpeechFrames = 0
            // Barge-in cooldown: break the false-barge-in oscillation cycle.
            val now = System.nanoTime()
            if (now - lastBargeInNanos < BARGE_IN_COOLDOWN_NANOS) {
                pendingOnset = null
                return EchoDecision.Suppress
            }
            lastBargeInNanos = now
            val first = pendingOnset
            pendingOnset = null
            EchoDecision.BargeIn(first, micFrame)
        } else {
            // Hold the onset frame (copied — the capture line may reuse buffers).
            if (pendingOnset == null) pendingOnset = micFrame.copyOf()
            EchoDecision.Suppress
        }
    }

    override fun reset() {
        consecutiveSpeechFrames = 0
        pendingOnset = null
        echoFloorRms = ECHO_FLOOR_INIT
        floorInitializedForBurst = false
        lastBargeInNanos = 0L
        burstStartNanos = 0L
    }

    /** RMS of 16-bit LE mono PCM, normalized 0..1. */
    private fun rms16(frame: ByteArray): Float {
        val samples = frame.size / 2
        if (samples == 0) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val s = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return (kotlin.math.sqrt(sum / samples) / 32768.0).toFloat()
    }

    companion object {
        /** Floor never tracks below this (mic noise floor territory). */
        private const val ECHO_FLOOR_INIT = 0.015f
        /** Render RMS below this counts as "speaker silent" — VAD-only path. */
        private const val RENDER_ACTIVE_RMS = 0.005f
        /**
         * Prior room coupling used to seed the floor: pure echo is assumed to
         * reach the mic at most this fraction of the digital render level.
         * 0.7 (-3 dB) is conservative for laptop speakers near the mic; the
         * old 0.5 underestimated loud setups, leaving the floor too low and
         * letting echo transients false-trigger barge-in.
         */
        private const val PRIOR_COUPLING = 0.7f
        /** Observations above floor*this never adapt the floor (may be user).
         * 2.0 lets the floor learn the true echo level even when the initial
         * estimate was low; the barge-in ratio (4.0) stays well above it so
         * real echo never triggers. */
        private const val ADAPT_CEILING_RATIO = 2.0f
        private const val ADAPT_UP_RATE = 0.10f
        /**
         * Barge-in needs the mic this far above the echo floor (~12 dB).
         * Loudspeaker echo alone stays under it; a live voice over the
         * speaker clears it. Raised from 3.0 after field reports of the
         * assistant's own voice false-triggering barge-in mid-response.
         */
        private const val BARGE_IN_FLOOR_RATIO = 4.0f
        private const val GATE_LOG_INTERVAL_NANOS = 5_000_000_000L
        /** Cooldown after a confirmed barge-in: breaks false-barge-in oscillation. */
        private const val BARGE_IN_COOLDOWN_NANOS = 2_000_000_000L
    }
}
