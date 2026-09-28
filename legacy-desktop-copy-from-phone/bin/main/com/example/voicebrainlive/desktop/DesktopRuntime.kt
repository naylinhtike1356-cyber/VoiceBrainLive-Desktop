package com.example.voicebrainlive.desktop

import com.example.voicebrainlive.desktop.core.AssistantController
import com.example.voicebrainlive.desktop.platform.GeminiLiveSession
import com.example.voicebrainlive.desktop.platform.GlobalHotkeyManager
import com.example.voicebrainlive.desktop.platform.WindowsAudioEngine
import com.example.voicebrainlive.desktop.platform.WindowsTrayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DesktopRuntime(
    private val onExitRequested: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = WindowsAudioEngine()
    private var listening = false

    private lateinit var controller: AssistantController
    private val session = GeminiLiveSession(
        apiKey = System.getenv("GEMINI_API_KEY")
            ?: System.getProperty("GEMINI_API_KEY", ""),
        onInputTranscript = { controller.updateTranscript(it) },
        onOutputTranscript = { controller.updateResponse(it) },
        onAudioResponse = { audio.playPcmBase64(it) },
        onStatus = { controller.updateStatus(it) },
    )

    val assistant: AssistantController
        get() = controller

    private val tray = WindowsTrayManager(
        onShow = { /* Compose window is already available; add focus handling here if needed. */ },
        onToggleListening = ::toggleListening,
        onExit = onExitRequested,
    )

    private val hotkey = GlobalHotkeyManager(::toggleListening)

    init {
        controller = AssistantController(session)
    }

    fun start() {
        tray.install()
        hotkey.register()
    }

    fun connect() {
        scope.launch { controller.connect() }
    }

    fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (!controller.state.value.isConnected) {
            connect()
            return
        }
        listening = true
        controller.updateListening(true)
        audio.startMicrophone(session::sendAudioChunk)
        controller.updateStatus("Listening — Ctrl+Alt+Space to stop")
    }

    private fun stopListening() {
        listening = false
        audio.stopMicrophone()
        session.stopAudioTurn()
        controller.updateListening(false)
        controller.updateStatus("Ready")
    }

    fun close() {
        stopListening()
        hotkey.unregister()
        tray.remove()
        audio.close()
        session.disconnect()
        scope.cancel()
    }
}
