package com.example.voicebrainlive.desktop

import com.example.voicebrainlive.desktop.core.AssistantController
import com.example.voicebrainlive.desktop.core.CommandResult
import com.example.voicebrainlive.desktop.core.DesktopCommand
import com.example.voicebrainlive.desktop.platform.ApiKeyStore
import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.example.voicebrainlive.desktop.platform.GeminiLiveSession
import com.example.voicebrainlive.desktop.platform.GlobalHotkeyManager
import com.example.voicebrainlive.desktop.platform.WindowsAudioEngine
import com.example.voicebrainlive.desktop.platform.WindowsCommandExecutor
import com.example.voicebrainlive.desktop.platform.WindowsTrayManager
import com.example.voicebrainlive.desktop.platform.NotionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DesktopRuntime(
    private val onExitRequested: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = WindowsAudioEngine()
    private val commandExecutor = WindowsCommandExecutor()
    private val apiKeyStore = ApiKeyStore()
    private var notionClient = NotionClient(apiKeyStore.loadNotionToken(), apiKeyStore.loadNotionParentPageId())
    private var listening = false
    private var pendingPowerCall: PendingPowerCall? = null
    private var robotVisible = apiKeyStore.loadRobotVisible()

    private lateinit var session: GeminiLiveSession
    private lateinit var controller: AssistantController

    val assistant: AssistantController
        get() = controller

    private val tray = WindowsTrayManager(
        onShow = { /* Main window focus can be added here later. */ },
        onShowRobot = { setRobotVisible(true) },
        onHideRobot = { setRobotVisible(false) },
        onToggleListening = ::toggleListening,
        onExit = onExitRequested,
    )

    private val hotkey = GlobalHotkeyManager(::toggleListening)

    init {
        session = createSession(apiKeyStore.load())
        controller = AssistantController(session)
    }

    private fun createSession(savedKey: String): GeminiLiveSession {
        DesktopLogger.info("Creating Gemini session; keyConfigured=${savedKey.isNotBlank()}")
        val key = savedKey.ifBlank {
            System.getenv("GEMINI_API_KEY") ?: System.getProperty("GEMINI_API_KEY", "")
        }
        return GeminiLiveSession(
            apiKey = key,
            onInputTranscript = { text ->
                controller.updateTranscript(text)
                handlePowerConfirmation(text)
            },
            onOutputTranscript = { controller.updateResponse(it) },
            onAudioResponse = { audio.playPcmBase64(it) },
            onToolCall = { callId, commandType, target, value ->
                scope.launch {
                    controller.updateStatus("လုပ်ဆောင်နေပါတယ်…")
                    val result = executeDesktopCommand(DesktopCommand(commandType, target, value))
                    controller.updateResponse(result.message)
                    if (result.requiresConfirmation) {
                        pendingPowerCall = PendingPowerCall(callId, DesktopCommand(commandType, target, value))
                        controller.updateStatus("အသံဖြင့် အတည်ပြုချက်ကို စောင့်နေပါတယ်")
                        session.sendText("အရေးကြီးပါတယ်။ ${result.message} အသံနဲ့ အတည်ပြုလိုပါသလား။ အတည်ပြုမယ်ဆိုရင် ဟုတ်ကဲ့ သို့မဟုတ် Confirm လို့ ပြောပါ။ မလုပ်လိုရင် မလုပ်ပါနဲ့ သို့မဟုတ် Cancel လို့ ပြောပါ။")
                    } else {
                    controller.recordAction(commandType, result)
                    DesktopLogger.info("Command $commandType success=${result.success}")
                    controller.updateStatus(if (result.success) "ပြီးပါပြီ" else "မအောင်မြင်ပါ")
                        session.sendToolResponse(callId, result.message)
                    }
                }
            },
            onStatus = {
                DesktopLogger.info("Gemini status: $it")
                controller.updateStatus(it)
            },
        )
    }

    fun storedApiKey(): String = apiKeyStore.load()

    fun storedNotionToken(): String = apiKeyStore.loadNotionToken()

    fun storedNotionParentPageId(): String = apiKeyStore.loadNotionParentPageId()

    fun storedRobotVisible(): Boolean = robotVisible

    fun setRobotVisible(value: Boolean) {
        robotVisible = value
        apiKeyStore.saveRobotVisible(value)
    }

    fun toggleRobotVisible() {
        setRobotVisible(!robotVisible)
    }

    fun saveNotionSettings(token: String, parentPageId: String) {
        apiKeyStore.saveNotionToken(token)
        apiKeyStore.saveNotionParentPageId(parentPageId)
        notionClient = NotionClient(apiKeyStore.loadNotionToken(), apiKeyStore.loadNotionParentPageId())
        controller.updateStatus("Notion settings သိမ်းပြီးပါပြီ။")
    }

    fun testNotionConnection(): CommandResult = notionClient.testConnection()

    private suspend fun executeDesktopCommand(command: DesktopCommand): CommandResult {
        return when (command.type.lowercase()) {
            "notion_test" -> notionClient.testConnection()
            "notion_search" -> notionClient.search(command.target ?: command.value.orEmpty())
            "notion_create_page", "notion_add_note" -> notionClient.createPage(command.target ?: "VoiceBrain Note", command.value.orEmpty())
            "notion_create_task" -> notionClient.createTask(command.target ?: "VoiceBrain Task", command.value.orEmpty())
            "notion_append_note" -> notionClient.appendToParent(command.value ?: command.target.orEmpty())
            "open_notion_page" -> notionClient.openPage(command.target ?: command.value.orEmpty())
            else -> commandExecutor.execute(command)
        }
    }

    fun saveApiKey(value: String) {
        apiKeyStore.save(value)
        session.disconnect()
        session = createSession(apiKeyStore.load())
        controller.replaceSession(session)
    }

    suspend fun testGeminiKey(value: String): Result<String> = GeminiLiveSession.testApiKey(value)

    fun clearApiKey() {
        apiKeyStore.clear()
        session.disconnect()
        session = createSession("")
        controller.replaceSession(session)
    }

    fun start() {
        tray.install()
        hotkey.register()
        // Lazy connection: do not open a Live socket until the user clicks the robot,
        // starts the microphone, or sends a text command.
    }

    fun connect() {
        scope.launch {
            var lastError = "Connection failed"
            repeat(CONNECT_ATTEMPTS) { attempt ->
                controller.updateStatus("Gemini ချိတ်ဆက်နေပါတယ်… (${attempt + 1}/$CONNECT_ATTEMPTS)")
                val result = controller.connect()
                if (result.isSuccess) return@launch
                lastError = result.exceptionOrNull()?.message ?: lastError
                DesktopLogger.warn("Gemini connect attempt ${attempt + 1} failed: $lastError")
                if (attempt < CONNECT_ATTEMPTS - 1) delay(RECONNECT_DELAY_MS * (attempt + 1))
            }
            controller.updateStatus("မချိတ်ဆက်နိုင်ပါ — $lastError")
        }
    }

    fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (!controller.state.value.isConnected) {
            controller.updateStatus("Gemini သို့ အလိုအလျောက်ချိတ်ဆက်နေပါတယ်…")
            scope.launch {
                if (controller.connect().isSuccess) startListening()
            }
            return
        }
        listening = true
        controller.updateListening(true)
        audio.startMicrophone(session::sendAudioChunk)
        controller.updateStatus("နားထောင်နေပါတယ် — Ctrl+Alt+Space ဖြင့် ရပ်နိုင်ပါတယ်")
    }

    private fun stopListening() {
        listening = false
        audio.stopMicrophone()
        session.stopAudioTurn()
        controller.updateListening(false)
        controller.updateStatus("အသင့်ဖြစ်ပါပြီ")
    }

    private fun handlePowerConfirmation(text: String) {
        if (pendingPowerCall == null) return
        val normalized = text.trim().lowercase()
        val no = listOf("မလုပ်", "မလုပ်ပါ", "မလုပ်နဲ့", "မလုပ်ပါနဲ့", "မဟုတ်", "မဟုတ်ဘူး", "cancel", "no", "မလုပ်တော့")
        val yes = listOf("အတည်ပြု", "အတည်ပြုပါတယ်", "ဟုတ်", "ဟုတ်ကဲ့", "လုပ်ပါ", "လုပ်လို", "confirm", "yes", "ok")
        when {
            no.any { normalized.contains(it) } -> cancelPowerAction()
            yes.any { normalized.contains(it) } -> confirmPowerAction()
            else -> scope.launch {
                session.sendText("အတည်ပြုချက် မရှင်းလင်းသေးပါ။ လုပ်ဆောင်မယ်ဆိုရင် ဟုတ်ကဲ့ သို့မဟုတ် Confirm၊ မလုပ်လိုရင် Cancel လို့ ပြောပါ။")
            }
        }
    }

    private fun confirmPowerAction() {
        val pending = pendingPowerCall ?: return
        pendingPowerCall = null
        scope.launch {
            val result = executeDesktopCommand(pending.command.copy(value = "confirmed"))
            controller.recordAction(pending.command.type, result)
            DesktopLogger.info("Confirmed power command ${pending.command.type} success=${result.success}")
            controller.updateResponse(result.message)
            controller.updateStatus(if (result.success) "အတည်ပြုပြီး လုပ်ဆောင်နေပါတယ်" else "မအောင်မြင်ပါ")
            session.sendToolResponse(pending.callId, result.message)
        }
    }

    private fun cancelPowerAction() {
        val pending = pendingPowerCall ?: return
        pendingPowerCall = null
        val cancelled = CommandResult(success = false, message = "User cancelled the power action.")
        controller.recordAction(pending.command.type, cancelled)
        controller.updateStatus("မလုပ်တော့ပါ")
        session.sendToolResponse(pending.callId, cancelled.message)
    }

    fun close() {
        stopListening()
        hotkey.unregister()
        tray.remove()
        audio.close()
        session.disconnect()
        scope.cancel()
    }

    private data class PendingPowerCall(
        val callId: String,
        val command: DesktopCommand,
    )

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
