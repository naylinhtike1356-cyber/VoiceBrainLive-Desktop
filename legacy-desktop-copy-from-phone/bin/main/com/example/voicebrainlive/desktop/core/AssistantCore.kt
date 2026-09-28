package com.example.voicebrainlive.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform-independent actions that the Gemini tool layer can request. */
interface PlatformCommandExecutor {
    suspend fun execute(command: DesktopCommand): CommandResult
}

data class DesktopCommand(
    val type: String,
    val target: String? = null,
    val value: String? = null,
)

data class CommandResult(
    val success: Boolean,
    val message: String,
)

interface VoiceSession {
    suspend fun connect(): Result<Unit>
    suspend fun sendText(text: String): Result<Unit>
    fun disconnect()
}

data class AssistantUiState(
    val status: String = "Ready",
    val transcript: String = "",
    val response: String = "",
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
)

class AssistantController(
    private val voiceSession: VoiceSession,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    suspend fun connect() {
        _state.value = _state.value.copy(status = "Connecting…")
        val result = voiceSession.connect()
        _state.value = if (result.isSuccess) {
            _state.value.copy(status = "Connected", isConnected = true)
        } else {
            _state.value.copy(
                status = result.exceptionOrNull()?.message ?: "Connection failed",
                isConnected = false,
            )
        }
    }

    suspend fun submitText(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        _state.value = _state.value.copy(
            transcript = cleanText,
            status = "Sending…",
        )
        val result = voiceSession.sendText(cleanText)
        if (result.isFailure) {
            _state.value = _state.value.copy(
                status = result.exceptionOrNull()?.message ?: "Request failed",
            )
        }
    }

    fun updateResponse(text: String) {
        _state.value = _state.value.copy(response = text, status = "Ready")
    }

    fun updateTranscript(text: String) {
        _state.value = _state.value.copy(transcript = text)
    }

    fun updateStatus(text: String) {
        _state.value = _state.value.copy(status = text)
    }

    fun updateListening(value: Boolean) {
        _state.value = _state.value.copy(isListening = value)
    }

    fun disconnect() {
        voiceSession.disconnect()
        _state.value = _state.value.copy(status = "Disconnected", isConnected = false)
    }
}
