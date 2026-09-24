package com.example.voicebrainlive.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    fun sendAudioChunk(base64Pcm: String): Boolean
    suspend fun flushAudioTurn(): Result<Unit>
    fun clearAudioBuffer()
    fun isLiveReady(): Boolean = false
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

enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val commandType: String? = null,
    val commandSuccess: Boolean? = null,
)

data class AssistantUiState(
    val status: String = "အသင့်ဖြစ်ပါပြီ",
    val phase: AssistantPhase = AssistantPhase.READY,
    val transcript: String = "",
    val response: String = "",
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val confirmationMessage: String? = null,
    val history: List<ActionHistoryEntry> = emptyList(),
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "မင်္ဂလာပါရှင်! ကျွန်မက VoiceBrainLive နည်းပညာကျွမ်းကျင် ကွန်ပျူတာလက်ထောက် ဖြစ်ပါတယ်။ ကွန်ပျူတာ အသုံးပြုခြင်း၊ Apps ဖွင့်/ပိတ်ခြင်း၊ စက်အခြေအနေစစ်ဆေးခြင်း၊ စခရင်ဖတ်ရှုခြင်းနှင့် Programming/IT နည်းပညာဆိုင်ရာ မည်သည့်အကြောင်းအရာမဆို မေးမြန်းတိုင်ပင် ခိုင်းစေနိုင်ပါတယ်ရှင်။",
        )
    ),
)

class AssistantController(
    private var voiceSession: VoiceSession,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    private val connectionMutex = Mutex()
    private val requestMutex = Mutex()
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun replaceSession(newSession: VoiceSession) {
        voiceSession.disconnect()
        voiceSession = newSession
        _state.value = _state.value.copy(status = "API key သိမ်းပြီးပါပြီ။ Robot ကိုနှိပ်ပါ သို့မဟုတ် စာပို့ပါ။", isConnected = false)
    }

    suspend fun connect(): Result<Unit> = connectionMutex.withLock {
        if (_state.value.isConnected) return@withLock Result.success(Unit)
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
        result
    }

    suspend fun submitText(text: String) = requestMutex.withLock {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withLock

        _state.value = _state.value.copy(
            transcript = cleanText,
            status = "အဖြေကို စဉ်းစားနေပါတယ်…",
            phase = AssistantPhase.THINKING,
        )
        val result = voiceSession.sendText(cleanText)
        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message ?: "လုပ်ဆောင်မှု မအောင်မြင်ပါ"
            _state.value = _state.value.copy(
                status = err,
                phase = AssistantPhase.ERROR,
            )
        }
    }

    fun addChatMessage(sender: MessageSender, text: String, commandType: String? = null, commandSuccess: Boolean? = null) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val newMsg = ChatMessage(
            sender = sender,
            text = clean,
            commandType = commandType,
            commandSuccess = commandSuccess,
        )
        _state.value = _state.value.copy(
            messages = (_state.value.messages + newMsg).takeLast(100)
        )
    }

    fun updateResponse(text: String) {
        val clean = text.trim()
        if (clean.isNotBlank()) {
            val msgs = _state.value.messages
            val last = msgs.lastOrNull()
            val isCurrentTurn = last != null && last.sender == MessageSender.ASSISTANT && (_state.value.phase == AssistantPhase.SPEAKING || _state.value.phase == AssistantPhase.THINKING || last.timestamp > System.currentTimeMillis() - 6000L)
            val updated = if (isCurrentTurn) {
                msgs.dropLast(1) + (last?.copy(text = clean) ?: ChatMessage(sender = MessageSender.ASSISTANT, text = clean))
            } else {
                msgs + ChatMessage(sender = MessageSender.ASSISTANT, text = clean)
            }
            _state.value = _state.value.copy(
                response = clean,
                status = "အဖြေပေးနေပါတယ်…",
                phase = AssistantPhase.SPEAKING,
                messages = updated.takeLast(100)
            )
        } else {
            _state.value = _state.value.copy(response = text, status = "Speaking", phase = AssistantPhase.SPEAKING)
        }
    }

    fun updateTranscript(text: String) {
        val clean = text.trim()
        if (clean.isNotBlank()) {
            val msgs = _state.value.messages
            val last = msgs.lastOrNull()
            val updated = if (last != null && last.sender == MessageSender.USER && last.text == clean) {
                msgs
            } else {
                msgs + ChatMessage(sender = MessageSender.USER, text = clean)
            }
            _state.value = _state.value.copy(transcript = clean, messages = updated.takeLast(100))
        } else {
            _state.value = _state.value.copy(transcript = text)
        }
    }

    fun updateStatus(text: String) {
        val normalized = text.lowercase()
        val phase = when {
            normalized.contains("connect") || normalized.contains("ချိတ်ဆက်") -> AssistantPhase.CONNECTING
            normalized.contains("နားထောင်") || normalized.contains("listen") -> AssistantPhase.LISTENING
            normalized.contains("အတည်ပြု") || normalized.contains("confirm") -> AssistantPhase.CONFIRMING
            normalized.contains("error") || normalized.contains("မအောင်မြင်") || normalized.contains("မချိတ်ဆက်") -> AssistantPhase.ERROR
            normalized.contains("ပြော") || normalized.contains("speaking") || normalized.contains("ဖြေကြား") -> AssistantPhase.SPEAKING
            normalized.contains("sending") || normalized.contains("လုပ်ဆောင်") || normalized.contains("စဉ်းစား") -> AssistantPhase.THINKING
            else -> AssistantPhase.READY
        }
        _state.value = _state.value.copy(
            status = text,
            phase = phase,
            isConnected = if (text.startsWith("Disconnected") || text.startsWith("Gemini error") || text.contains("socket closed", ignoreCase = true)) false else _state.value.isConnected,
        )
    }

    fun updateListening(value: Boolean) {
        _state.value = _state.value.copy(isListening = value, phase = if (value) AssistantPhase.LISTENING else AssistantPhase.READY)
    }

    fun updateSpeaking(speaking: Boolean) {
        _state.value = _state.value.copy(
            phase = if (speaking) AssistantPhase.SPEAKING else if (_state.value.isListening) AssistantPhase.LISTENING else AssistantPhase.READY
        )
    }

    fun requestConfirmation(message: String) {
        _state.value = _state.value.copy(confirmationMessage = message, phase = AssistantPhase.CONFIRMING)
    }

    fun clearConfirmation() {
        _state.value = _state.value.copy(confirmationMessage = null)
    }

    fun recordAction(command: String, result: CommandResult) {
        val entry = ActionHistoryEntry(command, result.message, result.success)
        val msgs = _state.value.messages
        val systemMsg = ChatMessage(
            sender = MessageSender.SYSTEM,
            text = "⚡ $command: ${result.message}",
            commandType = command,
            commandSuccess = result.success
        )
        _state.value = _state.value.copy(
            history = (_state.value.history + entry).takeLast(MAX_HISTORY),
            messages = (msgs + systemMsg).takeLast(100)
        )
    }

    fun disconnect() {
        voiceSession.disconnect()
        _state.value = _state.value.copy(status = "Disconnected", isConnected = false)
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
