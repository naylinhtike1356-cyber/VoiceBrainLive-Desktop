package com.example.voicebrainlive.desktop.core

/** Documents the native-audio invariant used by the Live voice loop. */
object VoiceResponsePolicy {
    fun shouldSpeakTextFallback(liveAudioReceived: Boolean, responseText: String): Boolean =
        !liveAudioReceived && responseText.trim().isNotBlank()
}
