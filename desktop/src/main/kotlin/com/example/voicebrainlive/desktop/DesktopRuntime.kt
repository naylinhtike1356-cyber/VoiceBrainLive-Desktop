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
import com.example.voicebrainlive.desktop.platform.SoundEffects
import com.example.voicebrainlive.desktop.platform.WindowsTrayManager
import com.example.voicebrainlive.desktop.core.UserMemoryStore
import com.example.voicebrainlive.desktop.core.ProfileManager
import com.example.voicebrainlive.desktop.core.VoiceRoutineEngine
import com.example.voicebrainlive.desktop.core.OfflineCommandMatcher
import com.example.voicebrainlive.desktop.core.AutonomousGoalEngine
import com.example.voicebrainlive.desktop.core.CompoundCommandHandler
import com.example.voicebrainlive.desktop.core.PlanDecomposer
import com.example.voicebrainlive.desktop.core.GoalDefinition
import com.example.voicebrainlive.desktop.core.GoalStep
import com.example.voicebrainlive.desktop.core.HybridOfflineFallbackEngine
import com.example.voicebrainlive.desktop.core.AssistantPhase
import com.example.voicebrainlive.desktop.platform.HealthWatchdog
import com.example.voicebrainlive.desktop.platform.LowPowerOptimizer
import com.example.voicebrainlive.desktop.platform.PowerMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DesktopRuntime(
    private val onExitRequested: () -> Unit,
    private val onMainWindowRequested: () -> Unit = {},
    private val onToggleMainWindowRequested: () -> Unit = onMainWindowRequested,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _liveVolumeLevel = MutableStateFlow(0f)
    val liveVolumeLevel: StateFlow<Float> = _liveVolumeLevel.asStateFlow()
    private val _voiceTypingMode = MutableStateFlow(false)
    val voiceTypingMode: StateFlow<Boolean> = _voiceTypingMode.asStateFlow()
    private val _commandModeRequest = MutableStateFlow(0L)
    val commandModeRequest: StateFlow<Long> = _commandModeRequest.asStateFlow()
    private val soundEffects = SoundEffects()
    private val commandExecutor = WindowsCommandExecutor()
    private val apiKeyStore = ApiKeyStore()
    val userMemoryStore = UserMemoryStore()
    val profileManager = ProfileManager()
    val routineEngine = VoiceRoutineEngine(commandExecutor)
    val offlineEngine = HybridOfflineFallbackEngine(commandExecutor)
    val powerOptimizer = LowPowerOptimizer(
        onThrottleStateChanged = { mode ->
            if (mode == PowerMode.THROTTLED_IDLE && !listening.get()) {
                audio.stopMicrophone()
            }
        }
    )
    val watchdog = HealthWatchdog(
        isOnlineProvider = { session.isLiveReady() },
        onAutoReconnect = {
            if (!reconnectInProgress && apiKeyStore.load().isNotBlank()) {
                scope.launch { connectGemini() }
            }
        },
        onSafeStateReset = { msg ->
            controller.updateStatus(msg)
            controller.updatePhase(AssistantPhase.READY)
        }
    )
    private val _showNeuralBrainRequest = MutableStateFlow(0L)
    val showNeuralBrainRequest: StateFlow<Long> = _showNeuralBrainRequest.asStateFlow()

    fun triggerNeuralBrainView() {
        _showNeuralBrainRequest.value = System.currentTimeMillis()
        onMainWindowRequested()
    }
    val compoundCommandHandler = CompoundCommandHandler(commandExecutor)
    val planDecomposer = PlanDecomposer(compoundCommandHandler)
    val goalEngine = AutonomousGoalEngine(commandExecutor, planDecomposer)
    private val listening = AtomicBoolean(false)
    private var liveConversationMode = false
    @Volatile private var reconnectInProgress = false
    private val pendingPowerCallLock = Any()
    private var pendingPowerCall: PendingPowerCall? = null
    private var robotVisible = apiKeyStore.loadRobotVisible()
    // Tool availability is no longer controlled by the old Settings toggle.
    // Each action is protected by fresh user-speech gating and confirmations.
    private var desktopAutomationEnabled = true
    @Volatile private var closed = false
    @Volatile private var liveAudioReceivedForTurn = false
    @Volatile private var userSpeechDetectedForTurn = false
    private val inputTranscriptBuffer = StringBuilder()
    private val preSetupAudioBuffer = mutableListOf<String>()

    private lateinit var session: GeminiLiveSession
    private lateinit var controller: AssistantController
    private lateinit var audio: WindowsAudioEngine

    val assistant: AssistantController
        get() = controller

    private val tray = WindowsTrayManager(
        onShow = onMainWindowRequested,
        onCommandMode = ::requestCommandMode,
        onShowRobot = { setRobotVisible(true) },
        onHideRobot = { setRobotVisible(false) },
        onToggleListening = ::toggleListening,
        onVisionScan = ::triggerVisionAnalysis,
        onReadClipboard = ::triggerClipboardRead,
        onExit = onExitRequested,
    )

    private val hotkey = GlobalHotkeyManager(
        onHotkey = ::toggleListening,
        onCommandHotkey = ::requestCommandMode,
        onToggleWindow = ::toggleMainWindow,
        onToggleRobot = ::toggleRobotVisible,
        onVisionHotkey = ::triggerVisionAnalysis,
        onClipboardHotkey = ::triggerClipboardRead,
    )

    init {
        val key = apiKeyStore.load()
        session = createSession(key)
        controller = AssistantController(session)
        scope.launch {
            goalEngine.activeGoal.collect { goal: GoalDefinition? ->
                controller.updateActiveGoal(goal)
            }
        }
        audio = WindowsAudioEngine(
            onSpeakingStateChanged = { isSpeaking ->
                controller.updateSpeaking(isSpeaking)
                if (isSpeaking) {
                    controller.updateStatus("🔊 ဖြေကြားနေပါတယ်…")
                } else if (listening.get()) {
                    controller.updateStatus("အသင့်ဖြစ်ပါပြီ — 🎤 နားထောင်နေပါသည်")
                } else {
                    controller.updateStatus("အသင့်ဖြစ်ပါပြီ")
                }
            },
            onAudioError = { message ->
                controller.updateStatus("အသံစနစ် အခက်အခဲ: $message")
            }
        )
        if (key.isNotBlank()) {
            scope.launch {
                controller.connect()
            }
        }
    }

    private fun createSession(savedKey: String): GeminiLiveSession {
        DesktopLogger.info("Creating Gemini session; keyConfigured=${savedKey.isNotBlank()}")
        val key = savedKey.ifBlank {
            System.getenv("GEMINI_API_KEY") ?: System.getProperty("GEMINI_API_KEY", "")
        }
        val memoryContext = userMemoryStore.toSystemInstructionContext()
        val activeWindowContext = commandExecutor.getActiveWindowContext().toPromptContext()
        val activeProfile = profileManager.getActiveProfile()
        val dynamicInstructions = """
            You are Nilar AI (နီလာ AI), an elite, friendly, warm, polite, and helpful Burmese-speaking Windows computer assistant and tech expert (နည်းပညာကျွမ်းကျင်သူ ကွန်ပျူတာ ပါတနာ) for user profile '$activeProfile'.
            
            LANGUAGE, PERSONALITY & SPOKEN CONVERSATION (မြန်မာစကားပြောနှင့် သွက်လက်စွာ ဆွေးနွေးခြင်း):
            - အသုံးပြုသူ မေးမြန်းသည်များ၊ ခိုင်းစေသည်များကို အမြဲတမ်း သဘာဝကျပြီး သွက်လက်ယဉ်ကျေးသော မြန်မာစကားပြောဖြင့် ရှင်းလင်းပြတ်သားစွာ အသံထွက် ဖြေကြားပေးပါ။
            - အသုံးပြုသူက ရှင်းလင်းစွာ မမေးဘဲ သို့မဟုတ် မခိုင်းဘဲနှင့် မလိုအပ်သော စကားများ လျှောက်ပြောခြင်း၊ မေးခွန်းများ လျှောက်မေးနေခြင်း လုံးဝ မပြုလုပ်ပါနှင့်။
            - အသုံးပြုသူနှင့် အပေးအယူ နားလည်မှု အပြည့်ရှိစွာဖြင့် လိုရင်းတိုရှင်း၊ သဘာဝကျကျ၊ တိကျပြတ်သားစွာ ဆောင်ရွက်ပြီး တုံ့ပြန်ပါ။
            - စကားရှည်ကြီးများ မပြောပါနှင့်။ အသုံးပြုသူ မခိုင်းပါက တိတ်ဆိတ်စွာ စောင့်ဆိုင်းပါ။
            - "I am an AI assistant" သို့မဟုတ် "<no speech detected>" စသည့် စကားလုံး/tags များကို ဘယ်သောအခါမှ ထုတ်မပြောပါနှင့်။
            - အသုံးပြုသူ၏ လက်ရှိမေးခွန်း/ပြောဆိုချက်ကို အဓိကထား၍ အကြောင်းအရာနှင့် ကိုက်ညီသော အဖြေကို သဘာဝကျကျ ဖြေကြားပါ။
            - အသုံးပြုသူ၏ အသံကို မြန်မာဘာသာစကားအဖြစ် ဦးစားပေးနားထောင်ပါ။ မြန်မာစကားဖြစ်နိုင်လျှင် English သို့မဟုတ် Spanish စကားလုံးအဖြစ် မခန့်မှန်းပါနှင့်။ မသေချာပါက command မလုပ်ဘဲ တိုတောင်းစွာ ပြန်မေးပါ။
            
            ACTIVE FOREGROUND APP CONTEXT (မျက်မှောက် ကွန်ပျူတာ အခြေအနေ):
            $activeWindowContext
            
            $memoryContext
            
            WINDOWS AUTOMATION & TECH EXPERT:
            - You may call execute_desktop_command for Windows actions:
            - open_app, close_app, search_web, search_youtube, search_files, find_file, open_file, open_url, open_folder, open_downloads, open_documents, open_desktop, open_recycle_bin, empty_recycle_bin, get_current_time, get_current_date, open_settings, open_network_settings, open_bluetooth_settings, open_display_settings, open_sound_settings, take_screenshot, volume_up, volume_down, mute, lock_computer, shutdown, restart, sleep, system_status, get_system_info, diagnose_network, get_battery_status, list_running_apps, copy_to_clipboard, run_powershell_safe, refresh_file_index, get_active_window.
            - Agentic memory & routines: get_active_window, remember_user_fact, get_user_memory, run_voice_routine, run_work_macro, show_neural_brain, switch_user_profile, analyze_screen, read_clipboard, media_play_pause, media_next, media_prev, minimize_all, maximize_window, minimize_window, close_window, close_tab, brightness_up, brightness_down.
            - Autonomous Goal Execution & Multi-Step Workflows (ပန်းတိုင်ရောက်သည်အထိ တဆင့်ချင်း ဆောင်ရွက်ခြင်း):
              * execute_goal: When user asks to achieve an objective that requires multiple sequential steps (e.g., "အလုပ်စဖို့ ပြင်ဆင်ပေးပါ", "စက်ကို သန့်ရှင်းရေးလုပ်ပေးပါ"), call execute_goal with target = user intention / goal name, value = optional project name.
              * chain_commands: When user gives multiple sequential actions in one sentence (e.g., "A ဖွင့်ပြီး B စစ်ပေးပါ"), call chain_commands with target = raw sentence or command list.
              * cancel_goal: When user says stop/cancel ongoing goal execution.
            - When user commands an action, execute the appropriate tool IMMEDIATELY and reply concisely with the action outcome in Burmese audio.
            - When asked technical, programming, Android, or IT questions, give clear, direct, step-by-step explanations in Burmese.
        """.trimIndent()

        val selectedModel = apiKeyStore.loadGeminiModel()
        return GeminiLiveSession(
            apiKey = key,
            modelName = selectedModel,
            allowDesktopTools = true,
            systemInstruction = dynamicInstructions,
            onInputTranscript = { text ->
                if (text.isNotBlank()) {
                    userSpeechDetectedForTurn = true
                    // Privacy: log that a transcript arrived, never its content.
                    DesktopLogger.info("Live input transcript received (${text.trim().length} chars)")
                }
                if (_voiceTypingMode.value && isVoiceTypingStopPhrase(text)) {
                    setVoiceTypingMode(false)
                } else {
                    val transcript = synchronized(inputTranscriptBuffer) {
                        mergeTranscriptPiece(inputTranscriptBuffer, text)
                    }
                    controller.updateTranscript(transcript)
                }
                handlePowerConfirmation(text)
            },
            onOutputTranscript = { text ->
                // Privacy: log that a transcript arrived, never its content.
                DesktopLogger.info("Live output transcript received (${text.trim().length} chars)")
                assistant.updateResponse(text)
            },
            onAudioResponse = { payload ->
                liveAudioReceivedForTurn = true
                audio.playPcmBase64(payload)
            },
            onTurnComplete = {
                val inputTranscript = synchronized(inputTranscriptBuffer) {
                    val result = inputTranscriptBuffer.toString().trim()
                    inputTranscriptBuffer.setLength(0)
                    result
                }
                if (_voiceTypingMode.value && inputTranscript.isNotBlank()) {
                    val result = commandExecutor.typeTextIntoActiveWindow(inputTranscript)
                    controller.updateResponse(result.message)
                    controller.updateStatus(if (result.success) "⌨️ စာသားကို ထည့်ပြီးပါပြီ" else result.message)
                } else if (inputTranscript.isNotBlank()) {
                    val sensitiveFacts = userMemoryStore.learnFromConversation(inputTranscript)
                    if (sensitiveFacts.isNotEmpty()) {
                        controller.updateStatus("⚠️ လျှို့ဝှက်အချက်အလက် (${sensitiveFacts.first().title}) အတွက် အတည်ပြုချက် လိုအပ်ပါသည်")
                    }
                }
                liveAudioReceivedForTurn = false
                userSpeechDetectedForTurn = false
                if (!audio.isSpeaking()) {
                    controller.updateStatus("အသင့်ဖြစ်ပါပြီ — 🎤 နားထောင်နေပါသည်")
                }
            },
            onSetupComplete = {
                // Flush pre-buffered audio captured while connecting
                val buffered = synchronized(preSetupAudioBuffer) {
                    val list = preSetupAudioBuffer.toList()
                    preSetupAudioBuffer.clear()
                    list
                }
                for (chunk in buffered) {
                    session.sendAudioChunk(chunk)
                }
                controller.updateStatus("အသင့်ဖြစ်ပါပြီ — 🎤 နားထောင်နေပါသည်")
            },
            onInterrupted = {
                audio.stopPlayback()
                controller.updateStatus("🎤 ဆက်လက် နားထောင်နေပါတယ်…")
            },
            onToolCall = { callId, commandType, target, value ->
                scope.launch {
                    if (!userSpeechDetectedForTurn) {
                        DesktopLogger.warn("Ignored unsolicited tool call type=$commandType without user speech")
                        session.sendToolResponse(callId, "လုပ်ဆောင်ချက်ကို မလုပ်ပါ။ အသုံးပြုသူ၏ ရှင်းလင်းသော ခိုင်းစေချက် မရရှိပါ။")
                        controller.updateStatus("ရှင်းလင်းသော ခိုင်းစေချက်ကို စောင့်နေပါတယ်")
                        return@launch
                    }
                    controller.updateStatus("လုပ်ဆောင်နေပါတယ်…")
                    val result = executeDesktopCommand(DesktopCommand(commandType, target, value))
                    controller.updateResponse(result.message)
                    if (result.requiresConfirmation) {
                        synchronized(pendingPowerCallLock) { pendingPowerCall = PendingPowerCall(callId, DesktopCommand(commandType, target, value)) }
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
                if (isLiveFailureStatus(it)) scheduleLiveReconnect()
            },
            onExecuteToolDirect = { cmd, target, value ->
                val res = executeDesktopCommand(DesktopCommand(cmd, target, value))
                controller.recordAction(cmd, res)
                res.message
            },
        )
    }

    fun storedApiKey(): String = apiKeyStore.load()

    fun storedGeminiModel(): String = apiKeyStore.loadGeminiModel()

    fun desktopAutomationEnabled(): Boolean = desktopAutomationEnabled

    /**
     * Swaps the live session while keeping the SAME AssistantController, so
     * Compose keeps observing the same StateFlow (H3). The controller
     * disconnects the old session internally.
     */
    private fun swapSession(newKey: String) {
        val newSession = createSession(newKey)
        controller.replaceSession(newSession)
        session = newSession
    }

    fun saveDesktopAutomationEnabled(enabled: Boolean) {
        desktopAutomationEnabled = enabled
        apiKeyStore.saveDesktopAutomationEnabled(enabled)
        swapSession(apiKeyStore.load())
        connectGemini()
    }

    fun saveGeminiModel(newModel: String) {
        apiKeyStore.saveGeminiModel(newModel)
        swapSession(apiKeyStore.load())
        connectGemini()
    }

    fun storedRobotVisible(): Boolean = robotVisible

    fun robotAlwaysOnTop(): Boolean = apiKeyStore.loadRobotAlwaysOnTop()

    fun setRobotAlwaysOnTop(value: Boolean) {
        apiKeyStore.saveRobotAlwaysOnTop(value)
    }

    fun storedRobotX(): Int = apiKeyStore.loadRobotX()

    fun storedRobotY(): Int = apiKeyStore.loadRobotY()

    fun saveRobotPosition(x: Int, y: Int) {
        apiKeyStore.saveRobotPosition(x, y)
    }

    fun setRobotVisible(value: Boolean) {
        robotVisible = value
        apiKeyStore.saveRobotVisible(value)
    }

    fun sendUserMessage(text: String) {
        scope.launch {
            val clean = text.trim()
            if (clean.isBlank()) return@launch
            val sensitivePending = userMemoryStore.learnFromConversation(clean)
            if (sensitivePending.isNotEmpty()) {
                controller.updateStatus("⚠️ လျှို့ဝှက်အချက်အလက် (${sensitivePending.first().title}) အတွက် အတည်ပြုချက် လိုအပ်ပါသည်")
            }
            powerOptimizer.onUserActivity()
            if (!session.isLiveReady()) {
                controller.updateTranscript(clean)
                controller.updateStatus("📡 Offline Mode — အော့ဖ်လိုင်း ဆောင်ရွက်နေပါသည်…")
                val res = offlineEngine.handleOfflineTurn(clean)
                controller.updateResponse(res.message)
                controller.recordAction("offline_command", res)
                return@launch
            }
            val matched = OfflineCommandMatcher.match(clean)
            if (matched != null) {
                controller.updateTranscript(clean)
                if (matched.type == "voice_typing_on") {
                    setVoiceTypingMode(true)
                } else if (matched.type == "voice_typing_off") {
                    setVoiceTypingMode(false)
                } else if (isPowerCommand(matched.type)) {
                    requestLocalPowerConfirmation(matched)
                } else {
                    val result = executeDesktopCommand(matched)
                    controller.updateResponse(result.message)
                    controller.recordAction(matched.type, result)
                    scope.launch {
                        session.sendText("လုပ်ဆောင်ချက်ရလဒ်ကို မြန်မာလို တိုတိုရှင်းရှင်း အသံဖြင့် ပြောပြပါ: ${result.message}")
                    }
                }
            } else {
                controller.submitText(clean)
            }
        }
    }

    fun setVoiceTypingMode(enabled: Boolean) {
        _voiceTypingMode.value = enabled
        synchronized(inputTranscriptBuffer) { inputTranscriptBuffer.setLength(0) }
        controller.updateStatus(if (enabled) "⌨️ Voice Typing ဖွင့်ထားပါပြီ — ပြောသောစာကို active window ထဲ ထည့်ပေးပါမယ်" else "Voice Typing ပိတ်ထားပါပြီ")
    }

    fun toggleVoiceTypingMode() {
        setVoiceTypingMode(!_voiceTypingMode.value)
    }

    private fun mergeTranscriptPiece(buffer: StringBuilder, piece: String): String {
        val clean = piece.trim()
        if (clean.isBlank()) return buffer.toString()
        val current = buffer.toString()
        when {
            current.isBlank() -> buffer.append(clean)
            current == clean || current.endsWith(clean) -> Unit
            clean.startsWith(current) -> {
                buffer.setLength(0)
                buffer.append(clean)
            }
            else -> {
                if (!buffer.endsWith(" ") && !clean.startsWith("။") && !clean.startsWith("၊")) buffer.append(' ')
                buffer.append(clean)
            }
        }
        return buffer.toString()
    }

    private fun isVoiceTypingStopPhrase(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean.contains("စာရိုက်တာရပ်") ||
            clean.contains("စာရိုက်တာ ပိတ်") ||
            clean.contains("voice typing off") ||
            clean.contains("stop typing")
    }

    fun toggleRobotVisible() {
        setRobotVisible(!robotVisible)
    }

    private suspend fun executeDesktopCommand(command: DesktopCommand): CommandResult {
        val targetVal = command.target ?: command.value.orEmpty()
        return when (command.type.lowercase()) {
            "execute_goal", "run_goal" -> {
                controller.updateStatus("ပန်းတိုင် အစီအစဉ် ချမှတ်နေပါတယ်…")
                val result = goalEngine.executeGoalFromIntent(
                    intent = targetVal,
                    optionalProject = command.value,
                    onStepMilestone = { stepIndex: Int, totalSteps: Int, step: GoalStep ->
                        controller.updateStatus("အဆင့် $stepIndex/$totalSteps: ${step.title}")
                    }
                )
                CommandResult(result.success, result.finalReport)
            }
            "chain_commands", "compound_command", "run_compound_command" -> {
                controller.updateStatus("အဆင့်များကို ဆက်တိုက် ဆောင်ရွက်နေပါတယ်…")
                val cmds = compoundCommandHandler.parseCommands(targetVal)
                compoundCommandHandler.executeChainedCommands(
                    commands = cmds,
                    onStepProgress = { stepIdx: Int, total: Int, current: DesktopCommand, res: CommandResult ->
                        controller.updateStatus("အဆင့် $stepIdx/$total ပြီးပါပြီ: ${current.type}")
                    }
                )
            }
            "cancel_goal", "stop_goal" -> {
                goalEngine.cancelGoal()
                CommandResult(true, "ပန်းတိုင် လုပ်ဆောင်ချက်ကို ရပ်တန့်လိုက်ပါပြီရှင်။")
            }
            "remember_user_fact" -> {
                userMemoryStore.rememberFact(command.target ?: "fact", command.value ?: "")
                CommandResult(true, "မှတ်မိပါပြီရှင်: ${command.target} = ${command.value}")
            }
            "get_user_memory" -> {
                val mem = userMemoryStore.getFact(targetVal) ?: "မှတ်မိသော အကြောင်းအရာ မရှိသေးပါရှင်။"
                CommandResult(true, mem)
            }
            "run_voice_routine", "voice_routine", "execute_routine", "run_work_macro" -> {
                routineEngine.executeRoutine(targetVal)
            }
            "show_neural_brain", "view_neural_brain", "open_brain_view" -> {
                triggerNeuralBrainView()
                CommandResult(true, "Neural Brain Memory Matrix ကို ဖွင့်လှစ်ပြသပေးလိုက်ပါပြီရှင်။")
            }
            "switch_user_profile" -> {
                val res = profileManager.switchProfile(targetVal)
                swapSession(apiKeyStore.load())
                controller.updateStatus(res)
                CommandResult(true, res)
            }
            "analyze_screen", "screen_vision", "capture_screen_vision" -> {
                val base64Jpg = commandExecutor.captureScreenImageBase64()
                if (base64Jpg.isBlank()) {
                    CommandResult(false, "စခရင် ပုံရိပ် ရယူ၍ မရပါရှင်။")
                } else {
                    val requestedQuestion = listOfNotNull(command.value, command.target)
                        .map { it.trim() }
                        .firstOrNull { it.isNotBlank() }
                        ?: "ဒီစခရင်ပုံကို အသုံးပြုသူ၏ လက်ရှိမေးခွန်းနှင့် ဆက်စပ်သည့်အပိုင်းကိုသာ ရှင်းပြပါ။ မေးခွန်းမရှိပါက မြင်ရသမျှကို ခန့်မှန်းမပြောဘဲ မေးခွန်းပြန်မေးပါ။"
                    scope.launch {
                        session.sendImageWithPrompt(base64Jpg, "အသုံးပြုသူ၏ လက်ရှိမေးခွန်း: $requestedQuestion\nဒီမေးခွန်းကို ဖြေဖို့လိုအပ်သော စခရင်အချက်အလက်ကိုသာ မြန်မာလို တိုတိုရှင်းရှင်း ဖြေပါ။ မေးခွန်းနှင့် မသက်ဆိုင်သော Taskbar/စခရင်စာရင်းကို မဖော်ပြပါနှင့်။")
                    }
                    CommandResult(true, "စခရင် ပုံရိပ်ကို Gemini ဖြင့် ဖတ်ရှု သုံးသပ်နေပါတယ်ရှင်…")
                }
            }
            "read_clipboard", "process_clipboard", "get_clipboard" -> {
                val clipText = commandExecutor.readClipboardText()
                if (clipText.isBlank()) {
                    CommandResult(false, "Clipboard ထဲတွင် စာသား မရှိပါရှင်။")
                } else {
                    scope.launch {
                        session.sendText("ဒီ Clipboard စာသားကို သုံးသပ် သို့မဟုတ် ရှင်းပြပေးပါ:\n$clipText")
                    }
                    CommandResult(true, "Clipboard စာသားကို Gemini သို့ ပို့ပေးလိုက်ပါပြီရှင်။")
                }
            }
            "optimize_ram", "clean_ram", "trim_memory", "clear_ram" -> {
                val reclaimed = powerOptimizer.trimMemoryWorkingSet()
                CommandResult(true, "RAM Memory ကို ရှင်းလင်းပြီးပါပြီရှင် (Reclaimed: ~${reclaimed}MB)")
            }
            "health_check", "system_health_watchdog", "watchdog_status" -> {
                val report = watchdog.getHealthReport(audio.isCaptureActive() || audio.isSpeaking())
                CommandResult(true, report.toSummary())
            }
            else -> commandExecutor.execute(command)
        }
    }

    fun onWindowVisibilityChanged(visible: Boolean) {
        powerOptimizer.onWindowVisibilityChanged(visible)
    }

    fun start() {
        watchdog.install()
        hotkey.register()
        tray.install()
        connectGemini()
    }

    fun cancelActiveGoal() {
        goalEngine.cancelGoal()
        controller.clearActiveGoal()
    }

    fun clearActiveGoal() {
        goalEngine.clearActiveGoal()
        controller.clearActiveGoal()
    }

    fun clearApiKey() {
        apiKeyStore.save("")
        swapSession("")
    }

    suspend fun testGeminiKey(key: String): Result<String> {
        return GeminiLiveSession.testApiKey(key)
    }

    fun saveApiKey(newKey: String) {
        apiKeyStore.save(newKey)
        swapSession(newKey)
        connectGemini()
    }

    private fun connectGemini() {
        scope.launch {
            var lastError = "Connection failed"
            repeat(CONNECT_ATTEMPTS) { attempt ->
                controller.updateStatus("Gemini ချိတ်ဆက်နေပါတယ်… (${attempt + 1}/$CONNECT_ATTEMPTS)", AssistantPhase.CONNECTING)
                val result = controller.connect()
                if (result.isSuccess) return@launch
                lastError = result.exceptionOrNull()?.message ?: lastError
                DesktopLogger.warn("Gemini connect attempt ${attempt + 1} failed: $lastError")
                if (attempt < CONNECT_ATTEMPTS - 1) delay(RECONNECT_DELAY_MS * (attempt + 1))
            }
            controller.updateStatus("မချိတ်ဆက်နိုင်ပါ — $lastError", AssistantPhase.ERROR)
        }
    }

    private fun isLiveFailureStatus(status: String): Boolean {
        val normalized = status.lowercase()
        return normalized.contains("timeout") ||
            normalized.contains("disconnected") ||
            normalized.contains("မအောင်မြင်") ||
            normalized.contains("မအသင့်") ||
            normalized.contains("မရနိုင်") ||
            normalized.contains("ပြန်လည်ချိတ်ဆက်")
    }

    private fun scheduleLiveReconnect() {
        if (closed || reconnectInProgress) return
        reconnectInProgress = true
        scope.launch {
            try {
                delay(1_500L)
                if (!closed && !session.isLiveReady()) connectGemini()
            } finally {
                reconnectInProgress = false
            }
        }
    }

    fun toggleListening() {
        if (listening.get()) {
            stopListening(userInitiated = true)
        } else {
            audio.stopPlayback()
            liveConversationMode = true
            scope.launch {
                if (!controller.state.value.isConnected || !session.isLiveReady()) {
                    // A stale controller flag must never allow microphone capture
                    // to start against a dead or half-setup WebSocket.
                    if (controller.state.value.isConnected && !session.isLiveReady()) {
                        controller.disconnect()
                    }
                    val connection = controller.connect()
                    if (connection.isFailure || !session.isLiveReady()) {
                        liveConversationMode = false
                        controller.updateStatus("Gemini Live native audio ချိတ်ဆက်မရသေးပါ — API key/network ကို စစ်ပါ")
                        return@launch
                    }
                } else if (!session.isLiveReady()) {
                    liveConversationMode = false
                    controller.updateStatus("Gemini Live native audio မအသင့်ဖြစ်သေးပါ")
                    return@launch
                }
                startListeningInternal()
                // Direct-input mode: do not speak an automatic greeting here.
                // The first microphone audio must belong to the user so the
                // initial turn cannot be blocked by greeting playback/echo.
            }
        }
    }

    private fun startListeningInternal() {
        // Atomic check-and-set: concurrent toggles cannot both enter.
        if (!listening.compareAndSet(false, true)) return
        audio.stopPlayback()
        userSpeechDetectedForTurn = false
        liveAudioReceivedForTurn = false
        synchronized(inputTranscriptBuffer) { inputTranscriptBuffer.setLength(0) }
        session.clearAudioBuffer()
        controller.updateListening(true)
        val microphoneStarted = audio.startMicrophone(
            onPcmChunk = { chunk ->
                if (session.isLiveReady()) {
                    session.sendAudioChunk(chunk)
                } else {
                    synchronized(preSetupAudioBuffer) {
                        if (preSetupAudioBuffer.size < 120) {
                            preSetupAudioBuffer.add(chunk)
                        }
                    }
                }
            },
            onVolumeLevel = { level -> _liveVolumeLevel.value = level },
            onSilenceDetected = {
                if (listening.get() && liveConversationMode && session.isLiveReady()) {
                    scope.launch {
                        val result = session.flushAudioTurn()
                        if (result.isFailure) {
                            val message = result.exceptionOrNull()?.message ?: "unknown error"
                            DesktopLogger.warn("Live audio turn flush failed: $message")
                            controller.updateStatus("အသံအလှည့် ပို့မရသေးပါ — Live ချိတ်ဆက်မှုကို စစ်နေပါတယ်")
                        }
                    }
                }
            },
            onSpeechStarted = {
                // This is only a local speech hint. In full-duplex mode the Live
                // server owns activity detection and sends `interrupted` for
                // genuine barge-in, which then stops playback in onInterrupted.
                userSpeechDetectedForTurn = true
            },
        )
        if (!microphoneStarted) {
            listening.set(false)
            liveConversationMode = false
            controller.updateListening(false)
            controller.updateStatus("Microphone မဖွင့်နိုင်ပါ — Windows input device/permission ကို စစ်ပါ")
            return
        }
        soundEffects.playListeningStarted(scope)
        controller.updateStatus("🎤 နားထောင်နေပါသည် — Live native audio အသင့်ဖြစ်ပါပြီ")
    }

    private fun stopListening(userInitiated: Boolean = false) {
        // Atomic take: exactly one caller performs the stop transition.
        if (!listening.getAndSet(false)) {
            if (userInitiated) liveConversationMode = false
            return
        }
        if (userInitiated) {
            liveConversationMode = false
        }
        _liveVolumeLevel.value = 0f
        audio.stopMicrophone()
        controller.updateListening(false)
        soundEffects.playListeningStopped(scope)
        if (userInitiated && session.isLiveReady()) {
            scope.launch { session.flushAudioTurn() }
        } else if (!userInitiated && !session.isLiveReady()) {
            scope.launch {
                val result = session.flushAudioTurn()
                if (result.isFailure) {
                    controller.updateStatus("Gemini Live native audio မရရှိသေးပါ — အသံအဖြေ မထုတ်နိုင်ပါ")
                }
            }
        } else {
            controller.updateStatus("အသင့်ဖြစ်ပါပြီ")
        }
    }

    private fun isPowerCommand(type: String): Boolean =
        type.lowercase() in setOf("shutdown", "restart", "sleep")

    private fun requestLocalPowerConfirmation(command: DesktopCommand) {
        synchronized(pendingPowerCallLock) {
            if (pendingPowerCall != null) {
                controller.updateStatus("အရင်တောင်းထားသော အတည်ပြုချက်ကို စောင့်နေပါတယ်။")
                return
            }
            pendingPowerCall = PendingPowerCall(null, command)
        }
        val actionText = when (command.type.lowercase()) {
            "shutdown" -> "ကွန်ပျူတာကို ပိတ်ပါမယ်"
            "restart" -> "ကွန်ပျူတာကို ပြန်စပါမယ်"
            else -> "ကွန်ပျူတာကို Sleep ဝင်ပါမယ်"
        }
        val confirmationText = "$actionText။ ဆက်လုပ်ရန် ဟုတ်ကဲ့ သို့မဟုတ် Confirm လို့ ပြောပါ။ မလုပ်လိုရင် Cancel လို့ ပြောပါ။"
        controller.updateResponse(confirmationText)
        controller.updateStatus("အသံဖြင့် အတည်ပြုချက်ကို စောင့်နေပါတယ်")
        scope.launch {
            session.sendText("အရေးကြီးပါတယ်။ $actionText။ အသံနဲ့ အတည်ပြုလိုပါသလား။ အတည်ပြုမယ်ဆိုရင် ဟုတ်ကဲ့ သို့မဟုတ် Confirm လို့ ပြောပါ။ မလုပ်လိုရင် မလုပ်ပါနဲ့ သို့မဟုတ် Cancel လို့ ပြောပါ။")
        }
    }

    private fun handlePowerConfirmation(text: String) {
        synchronized(pendingPowerCallLock) { if (pendingPowerCall == null) return }
        val normalized = text.trim().lowercase()
        val no = listOf("မလုပ်", "မလုပ်ပါ", "မလုပ်နဲ့", "မလုပ်ပါနဲ့", "မဟုတ်", "မဟုတ်ဘူး", "cancel", "no", "မလုပ်တော့")
        val yes = listOf("အတည်ပြု", "အတည်ပြုပါတယ်", "ဟုတ်", "ဟုတ်ကဲ့", "လုပ်ပါ", "လုပ်လို", "confirm", "yes", "ok")
        when {
            no.any { normalized.contains(it) } -> cancelPowerAction()
            yes.any { normalized.contains(it) } -> confirmPowerAction()
            else -> {
                val clarify = "အတည်ပြုချက် မရှင်းလင်းသေးပါ။ လုပ်ဆောင်မယ်ဆိုရင် ဟုတ်ကဲ့ သို့မဟုတ် Confirm၊ မလုပ်လိုရင် Cancel လို့ ပြောပါ။"
                scope.launch {
                    session.sendText(clarify)
                }
            }
        }
    }

    private fun confirmPowerAction() {
        val pending = synchronized(pendingPowerCallLock) {
            val p = pendingPowerCall
            pendingPowerCall = null
            p
        } ?: return
        scope.launch {
            val result = executeDesktopCommand(pending.command.copy(value = "confirmed"))
            controller.recordAction(pending.command.type, result)
            DesktopLogger.info("Confirmed power command ${pending.command.type} success=${result.success}")
            controller.updateResponse(result.message)
            session.sendText("အတည်ပြုပြီး လုပ်ဆောင်ထားသော ရလဒ်ကို မြန်မာလို တိုတိုရှင်းရှင်း အသံဖြင့် ပြောပြပါ: ${result.message}")
            controller.updateStatus(if (result.success) "အတည်ပြုပြီး လုပ်ဆောင်နေပါတယ်" else "မအောင်မြင်ပါ")
            pending.callId?.let { session.sendToolResponse(it, result.message) }
        }
    }

    private fun cancelPowerAction() {
        val pending = synchronized(pendingPowerCallLock) {
            val p = pendingPowerCall
            pendingPowerCall = null
            p
        } ?: return
        val cancelled = CommandResult(success = false, message = "စက်ပိတ်ခြင်းကို ပယ်ဖျက်လိုက်ပါပြီရှင်။")
        controller.recordAction(pending.command.type, cancelled)
        controller.updateStatus("မလုပ်တော့ပါ")
        scope.launch { session.sendText("ပယ်ဖျက်ထားကြောင်း မြန်မာလို တိုတိုရှင်းရှင်း အသံဖြင့် ပြောပြပါ: ${cancelled.message}") }
        pending.callId?.let { session.sendToolResponse(it, cancelled.message) }
    }

    fun requestMainWindow() = onMainWindowRequested()

    fun toggleMainWindow() = onToggleMainWindowRequested()

    fun showBackgroundNotification(title: String = "VoiceBrainLive", message: String) {
        tray.notify(title, message)
    }

    /** Opens the main window and signals the command field to receive focus. */
    fun requestCommandMode() {
        onMainWindowRequested()
        _commandModeRequest.value = System.currentTimeMillis()
    }

    fun triggerVisionAnalysis() {
        scope.launch {
            val base64Jpg = commandExecutor.captureScreenImageBase64()
            if (base64Jpg.isNotBlank()) {
                session.sendImageWithPrompt(base64Jpg, "ဒီစခရင်နှင့် အောက်ဘား (Taskbar) တွင် ပါရှိသော အက်ပ်များနှင့် အရာများကို အသေးစိတ် မြန်မာလို ရှင်းပြပေးပါ။")
            }
        }
    }

    fun triggerClipboardRead() {
        val text = commandExecutor.readClipboardText()
        if (text.isNotBlank()) {
            sendUserMessage("ဒီ Clipboard စာသားကို သုံးသပ် သို့မဟုတ် ရှင်းပြပေးပါ:\n$text")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        stopListening()
        watchdog.stop()
        hotkey.unregister()
        tray.remove()
        audio.close()
        session.disconnect()
        scope.cancel()
    }

    private data class PendingPowerCall(
        val callId: String?,
        val command: DesktopCommand,
    )

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
