package com.example.voicebrainlive.desktop.platform.phonemic

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the phone-as-mic feature:
 * - owns the [PhoneMicServer] (HTTPS+WSS) and [PhoneMicCapture] (jitter buffer)
 * - routes phone audio chunks into the capture
 * - fails over to the local mic when the phone disconnects
 * - exposes UI-observable state
 *
 * Lifecycle: [enable] starts the server; [disable] stops it and restores the
 * local mic. The phone mic becomes the active source only while a phone is
 * actually streaming; on disconnect we fail back to local automatically.
 */
class PhoneMicManager(
    /** Provides the live routing capture; set once the audio engine is built. */
    private val routingCaptureProvider: () -> RoutingAudioCapture?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    sealed interface State {
        data object Disabled : State
        data class WaitingForPhone(val pin: String, val lanIp: String, val port: Int) : State
        data class Streaming(val clientIp: String, val stats: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: PhoneMicServer? = null
    private var phoneCapture: PhoneMicCapture? = null

    @Volatile private var enabled = false

    /** QR payload for the UI: (url, pixels as ARGB ints). */
    @Volatile private var lastQr: Pair<String, Array<IntArray>>? = null
    fun lastQrPayload(): Pair<String, Array<IntArray>>? = lastQr

    fun isEnabled(): Boolean = enabled

    fun currentPin(): String? = server?.pairingPin

    fun serverAddress(): String? {
        val s = server ?: return null
        return "https://${s.lanIp}:${s.port}"
    }

    /**
     * Enables the phone-mic server. The local mic stays active until a phone
     * actually connects and starts streaming.
     */
    fun enable() {
        if (enabled) return
        enabled = true
        val capture = PhoneMicCapture()
        phoneCapture = capture
        val srv = PhoneMicServer(
            onAudioChunk = { seq, pcm -> capture.onChunk(seq, pcm) },
            onStreamerConnected = { ip -> onPhoneConnected(ip) },
            onStreamerDisconnected = { onPhoneDisconnected() },
        )
        server = srv
        scope.launch {
            try {
                srv.start()
                lastQr = srv.generatePairingQr()
                _state.value = State.WaitingForPhone(srv.pairingPin, srv.lanIp, srv.port)
                DesktopLogger.info("PhoneMicManager enabled: ${srv.lanIp}:${srv.port}")
            } catch (e: Exception) {
                DesktopLogger.warn("PhoneMicManager failed to start server: ${e.message}")
                _state.value = State.Disabled
                enabled = false
            }
        }
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        scope.launch {
            // Fail back to local mic first so capture keeps working.
            (routingCaptureProvider() ?: return@launch).let { it.switchTo(it.localSource) }
            phoneCapture?.stop()
            phoneCapture = null
            server?.stop()
            server = null
            lastQr = null
            _state.value = State.Disabled
            DesktopLogger.info("PhoneMicManager disabled")
        }
    }

    /** Regenerates the pairing PIN + QR (e.g. after sharing expired). */
    fun regeneratePairing() {
        val srv = server ?: return
        scope.launch {
            srv.regeneratePin()
            lastQr = srv.generatePairingQr()
            if (_state.value is State.WaitingForPhone) {
                _state.value = State.WaitingForPhone(srv.pairingPin, srv.lanIp, srv.port)
            }
        }
    }

    private fun onPhoneConnected(ip: String) {
        scope.launch {
            val routing = routingCaptureProvider() ?: return@launch
            val capture = phoneCapture ?: return@launch
            // Switch the live pipeline to the phone mic.
            val ok = routing.switchTo(capture)
            if (ok) {
                _state.value = State.Streaming(ip, capture.stats())
                DesktopLogger.info("PhoneMicManager: phone mic active ($ip)")
            } else {
                DesktopLogger.warn("PhoneMicManager: failed to switch to phone mic")
            }
        }
    }

    private fun onPhoneDisconnected() {
        scope.launch {
            if (!enabled) return@launch
            val routing = routingCaptureProvider() ?: return@launch
            // Fail over to local mic immediately so the assistant keeps hearing.
            routing.switchTo(routing.localSource)
            val srv = server
            _state.value = if (srv != null) {
                State.WaitingForPhone(srv.pairingPin, srv.lanIp, srv.port)
            } else {
                State.Disabled
            }
            DesktopLogger.info("PhoneMicManager: phone disconnected, failed over to local mic")
        }
    }

    /** Periodic stats refresh for the UI while streaming. */
    fun refreshStats() {
        val capture = phoneCapture ?: return
        val cur = _state.value
        if (cur is State.Streaming) {
            _state.value = cur.copy(stats = capture.stats())
        }
    }

}
