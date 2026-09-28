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
    val requiresConfirmation: Boolean = false,
    val confirmationAction: String? = null,
)

interface VoiceSession {
    suspend fun connect(): Result<Unit>
    suspend fun sendText(text: String): Result<Unit>
    fun disconnect()
}

data class ActionHistoryEntry(
    val command: String,
    val result: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class AssistantPhase {
    READY,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    CONFIRMING,
    ERROR,
}

data class AssistantUiState(
    val status: String = "Ready",
    val phase: AssistantPhase = AssistantPhase.READY,
    val transcript: String = "",
    val response: String = "",
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val confirmationMessage: String? = null,
    val history: List<ActionHistoryEntry> = emptyList(),
)

class AssistantController(
    private var voiceSession: VoiceSession,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun replaceSession(newSession: VoiceSession) {
        voiceSession.disconnect()
        voiceSession = newSession
        _state.value = _state.value.copy(status = "API key သိမ်းပြီးပါပြီ။ Robot ကိုနှိပ်ပါ သို့မဟုတ် စာပို့ပါ။", isConnected = false)
    }

    suspend fun connect(): Result<Unit> {
        _state.value = _state.value.copy(status = "Connecting…", phase = AssistantPhase.CONNECTING, isConnected = false)
        val result = voiceSession.connect()
        _state.value = if (result.isSuccess) {
            _state.value.copy(status = "Connected", phase = AssistantPhase.READY, isConnected = true)
        } else {
            _state.value.copy(
                status = result.exceptionOrNull()?.message ?: "Connection failed",
                phase = AssistantPhase.ERROR,
                isConnected = false,
            )
        }
        return result
    }

    suspend fun submitText(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        if (!_state.value.isConnected) {
            val reconnect = connect()
            if (reconnect.isFailure) return
        }
        _state.value = _state.value.copy(
            transcript = cleanText,
            status = "Sending…",
            phase = AssistantPhase.THINKING,
        )
        val result = voiceSession.sendText(cleanText)
        if (result.isFailure) {
            _state.value = _state.value.copy(
                status = result.exceptionOrNull()?.message ?: "Request failed",
            )
        }
    }

    fun updateResponse(text: String) {
        _state.value = _state.value.copy(response = text, status = "Speaking", phase = AssistantPhase.SPEAKING)
    }

    fun updateTranscript(text: String) {
        _state.value = _state.value.copy(transcript = text)
    }

    fun updateStatus(text: String) {
        val normalized = text.lowercase()
        val phase = when {
            normalized.contains("connect") || normalized.contains("ချိတ်ဆက်") -> AssistantPhase.CONNECTING
            normalized.contains("နားထောင်") || normalized.contains("listen") -> AssistantPhase.LISTENING
            normalized.contains("အတည်ပြု") || normalized.contains("confirm") -> AssistantPhase.CONFIRMING
            normalized.contains("error") || normalized.contains("မအောင်မြင်") || normalized.contains("မချိတ်ဆက်") -> AssistantPhase.ERROR
            normalized.contains("ပြော") || normalized.contains("speaking") -> AssistantPhase.SPEAKING
            normalized.contains("sending") || normalized.contains("လုပ်ဆောင်") -> AssistantPhase.THINKING
            else -> AssistantPhase.READY
        }
        _state.value = _state.value.copy(
            status = text,
            phase = phase,
            isConnected = if (text.startsWith("Disconnected") || text.startsWith("Gemini error")) false else _state.value.isConnected,
        )
    }

    fun updateListening(value: Boolean) {
        _state.value = _state.value.copy(isListening = value, phase = if (value) AssistantPhase.LISTENING else AssistantPhase.READY)
    }

    fun requestConfirmation(message: String) {
        _state.value = _state.value.copy(confirmationMessage = message, phase = AssistantPhase.CONFIRMING)
    }

    fun clearConfirmation() {
        _state.value = _state.value.copy(confirmationMessage = null)
    }

    fun recordAction(command: String, result: CommandResult) {
        val entry = ActionHistoryEntry(command, result.message, result.success)
        _state.value = _state.value.copy(history = (_state.value.history + entry).takeLast(MAX_HISTORY))
    }

    fun disconnect() {
        voiceSession.disconnect()
        _state.value = _state.value.copy(status = "Disconnected", isConnected = false)
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
