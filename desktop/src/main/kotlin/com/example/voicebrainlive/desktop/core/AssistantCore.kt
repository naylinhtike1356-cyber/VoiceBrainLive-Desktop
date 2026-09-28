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
    /** Sends a prompt that should produce assistant audio without adding it to the user's transcript. */
    suspend fun sendAssistantPrompt(text: String): Result<Unit> = sendText(text)
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
    val activeGoal: GoalDefinition? = null,
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

    /**
     * Updates the status line. An explicit [phase] always wins; when omitted the
     * phase is inferred from the text as before (M3 keeps inference only as a
     * fallback so existing UI behavior is unchanged).
     *
     * Connection lifecycle callers must pass the phase explicitly instead of
     * relying on keyword inference — e.g. "Disconnected" contains "connect"
     * and must never resolve to CONNECTING.
     */
    fun updateStatus(text: String, phase: AssistantPhase? = null) {
        val normalized = text.lowercase()
        val connectionLost = normalized.contains("disconnect") ||
            normalized.contains("timeout") ||
            normalized.contains("error") ||
            normalized.contains("မအောင်မြင်") ||
            normalized.contains("မရနိုင်") ||
            normalized.contains("မအသင့်")
        _state.value = _state.value.copy(
            status = text,
            phase = phase ?: inferPhase(normalized),
            isConnected = if (connectionLost) false else _state.value.isConnected,
        )
    }

    private fun inferPhase(normalized: String): AssistantPhase {
        // Negative connection keywords take precedence: "Disconnected" contains
        // "connect" but is a loss, not a connection attempt; "မချိတ်ဆက်နိုင်ပါ"
        // (cannot connect) is a failure, not CONNECTING.
        val connectionNegative = normalized.contains("disconnect") ||
            normalized.contains("မချိတ်ဆက်")
        return when {
            connectionNegative -> AssistantPhase.ERROR
            normalized.contains("connect") || normalized.contains("ချိတ်ဆက်") -> AssistantPhase.CONNECTING
            normalized.contains("နားထောင်") || normalized.contains("listen") -> AssistantPhase.LISTENING
            normalized.contains("အတည်ပြု") || normalized.contains("confirm") -> AssistantPhase.CONFIRMING
            normalized.contains("error") || normalized.contains("မအောင်မြင်") -> AssistantPhase.ERROR
            normalized.contains("ပြော") || normalized.contains("speaking") || normalized.contains("ဖြေကြား") -> AssistantPhase.SPEAKING
            normalized.contains("sending") || normalized.contains("လုပ်ဆောင်") || normalized.contains("စဉ်းစား") -> AssistantPhase.THINKING
            else -> AssistantPhase.READY
        }
    }

    /**
     * Explicit phase mapping for GeminiLiveSession status strings. The
     * session-status path must not rely on free-text keyword inference
     * (e.g. thinking states are THINKING/purple, never CONNECTING/cyan);
     * unknown strings fall back to [inferPhase].
     */
    fun mapSessionStatusToPhase(status: String): AssistantPhase {
        val s = status.lowercase()
        return when {
            s.contains("live error") || s.contains("setup timeout") ||
                s.contains("မအောင်မြင်ပါ") || s.contains("မရနိုင်") ||
                s.contains("disconnected") || s.contains("gemini_api_key မထည့်ရသေးပါ") ||
                s.startsWith("error:") -> AssistantPhase.ERROR
            s.contains("ချိတ်ဆက်နေပါတယ်") || s.contains("setup ပို့နေပါတယ်") ||
                s.contains("ပြန်လည်ချိတ်ဆက်နေပါသည်") -> AssistantPhase.CONNECTING
            s.contains("စဉ်းစား") || s.contains("သုံးသပ်နေပါတယ်") ||
                s.contains("အဖြေထုတ်နေပါတယ်") || s.contains("ပို့နေပါတယ်") -> AssistantPhase.THINKING
            s.contains("နားထောင်နေပါသည်") || s.contains("နားထောင်နေပါတယ်") -> AssistantPhase.LISTENING
            s.contains("အသင့်ဖြစ်ပါပြီ") -> AssistantPhase.READY
            else -> inferPhase(s)
        }
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

    fun updatePhase(phase: AssistantPhase) {
        _state.value = _state.value.copy(phase = phase)
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

    fun updateActiveGoal(goal: GoalDefinition?) {
        _state.value = _state.value.copy(activeGoal = goal)
    }

    fun clearActiveGoal() {
        _state.value = _state.value.copy(activeGoal = null)
    }

    fun disconnect() {
        voiceSession.disconnect()
        _state.value = _state.value.copy(status = "Disconnected", isConnected = false)
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
