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
    /**
     * Honest playback meter from the audio engine (RMS of last written chunk).
     * Drives the speaking-state orb. 0f until the engine is initialized.
     */
    val playbackLevel: StateFlow<Float> get() = audio.playbackLevel
    /**
     * Session health for honest UI: updated from session.isLiveReady() on
     * every HealthWatchdog tick. While listening with an unhealthy session
     * the status pill is forced to the reconnecting state.
     */
    private val _sessionHealthy = MutableStateFlow(true)
    val sessionHealthy: StateFlow<Boolean> = _sessionHealthy.asStateFlow()
    /**
     * True when the mic level has stayed near zero for ~2.5s while listening.
     * Drives the flat-gray honest waveform + input-device hint.
     */
    private val _micSilentWhileListening = MutableStateFlow(false)
    val micSilentWhileListening: StateFlow<Boolean> = _micSilentWhileListening.asStateFlow()
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
        },
        onLivenessTick = { healthy -> _sessionHealthy.value = healthy },
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

    /** Throttles the periodic rolling audio-latency telemetry log (no audio contents). */
    @Volatile private var lastLatencyReportLogMs = 0L
    /** Consecutive sendAudioChunk() failures (not-ready or ws.send()==false). */
    @Volatile private var consecutiveAudioSendFailures = 0
    @Volatile private var audioSendStallWarned = false
    /** Last uplink audio timestamp; the liveness monitor reconnects when audio
     * flows but the server stays silent. */
    @Volatile private var lastUplinkAudioSentNanos = 0L
    /** Pre-setup audio chunks dropped because the buffer cap was hit. */
    @Volatile private var preSetupDroppedChunks = 0L
    /** When the mic level last rose above the near-zero floor (0 = currently above). */
    @Volatile private var micLowSinceNanos = 0L
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
                    controller.updateStatus("ဖြေကြားနေပါတယ်…")
                } else if (listening.get()) {
                    controller.updateStatus("အသင့်ဖြစ်ပါပြီ — နားထောင်နေပါသည်")
                } else {
                    controller.updateStatus("အသင့်ဖြစ်ပါပြီ")
                }
            },
            onAudioError = { message ->
                controller.updateStatus("အသံစနစ် အခက်အခဲ: $message", AssistantPhase.ERROR)
            },
            // Phase-2 turn-taking hooks: client-side VAD speech transitions.
            // bargeIn=true means the user interrupted assistant playback —
            // local playback is already stopped; the session drops the
            // interrupted turn's in-flight audio tail. Ordinary turn starts
            // need no signal: the server's own VAD owns turn-taking in
            // automatic-VAD mode (activityStart is not valid there).
            onUserSpeechStart = { bargeIn ->
                // "Heard you" pill the moment speech starts.
                controller.updateStatus("ကြားနေပါတယ်…", AssistantPhase.LISTENING)
                if (bargeIn) session.notifyClientBargeIn()
            },
            onUserSpeechEnd = {
                session.noteUserTurnEnd()
                // Turn-flush pill: the captured turn is being handed to Live.
                controller.updateStatus("ပို့နေပါတယ်…", AssistantPhase.THINKING)
            },
            onVadStallWarning = {
                controller.updateStatus(
                    "mic အသံကြားနေသော်လည်း စကားသံ မတွေ့ပါ — sensitivity ကို စစ်ပါ",
                    AssistantPhase.ERROR,
                )
            },
            selectedMixerName = apiKeyStore.loadAudioInputDevice().ifBlank { null },
            echoCancellerFactory = { isPlaying ->
                com.example.voicebrainlive.desktop.platform.audio.EchoCancellerFactory.create(
                    apiKeyStore.loadEchoCancellerKind(),
                    isPlaying,
                    onFallback = { message -> DesktopLogger.warn(message) },
                )
            },
            // Phase 2 — barge-in sensitivity from settings (default "high" gives
            // an 8 dB energy margin so quiet onsets are caught).
            vad = com.example.voicebrainlive.desktop.platform.audio.SpectralVad(
                sensitivity = com.example.voicebrainlive.desktop.platform.audio.SpectralVad.sensitivityForLevel(
                    apiKeyStore.loadBargeInSensitivity(),
                ),
            ),
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
            You are Nilar AI (နီလာ AI), a friendly Burmese-speaking voice companion on the user's Windows PC — warm, natural, and fast, like a phone call, never like reading an article. Active user profile: '$activeProfile'.
            SPOKEN STYLE: Default reply 1–2 short sentences, max ~35 words. Shorter is always better. Never use bullet lists, numbered steps, markdown, code, URLs, file paths, or tool names in speech. If detail is needed, give the single key point aloud, then ask "အသေးစိတ် ဆက်ပြောပေးရမလား။" Never say "I am an AI assistant"/"As an AI..." and never speak tags like <no speech detected>. Never narrate your own actions — just do it.
            GREETINGS & SMALL TALK: မင်္ဂလာပါ → one warm line back, e.g. "မင်္ဂလာပါ။ ဒီနေ့ ဘာကူညီပေးရမလဲ။" နေကောင်းလား → answer briefly and positively, then offer help in the same breath.
            INTERRUPTIONS: if interrupted, stop immediately and listen; never finish/restart the old answer; acknowledge briefly ("ဟုတ်ကဲ့၊ နားထောင်နေပါတယ်").
            CLARITY: vague request → ask ONE short clarifying question; never run a computer command on a guess.
            TOOL USE: act FIRST (call the tool immediately), then confirm in one short spoken sentence. Never call tools for greetings/small talk/questions.
            TECHNICAL QUESTIONS: ≤2 short sentences of plain spoken Burmese; offer more detail only if asked.
            LANGUAGE: default Burmese; if user clearly speaks English reply in natural English. Hear speech as Burmese first; if ambiguous never guess English/Spanish — ask briefly.
            
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
                // Phase 2: drop the interrupted turn's in-flight audio tail
                // instead of playing it over the user who just barged in.
                if (!session.shouldSuppressServerAudio()) {
                    audio.playPcmBase64(payload)
                }
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
                    controller.updateStatus("အသင့်ဖြစ်ပါပြီ — နားထောင်နေပါသည်")
                }
            },
            onSetupComplete = {
                // Flush pre-buffered audio captured while connecting
                val buffered = synchronized(preSetupAudioBuffer) {
                    val list = preSetupAudioBuffer.toList()
                    preSetupAudioBuffer.clear()
                    list
                }
                var drained = 0
                var drainFailed = 0
                for (chunk in buffered) {
                    if (session.sendAudioChunk(chunk)) drained++ else drainFailed++
                }
                if (drainFailed > 0) {
                    DesktopLogger.warn("Setup audio drain: $drained sent, $drainFailed failed")
                }
                controller.updateStatus("အသင့်ဖြစ်ပါပြီ — နားထောင်နေပါသည်")
            },
            onInterrupted = {
                audio.stopPlayback()
                controller.updateStatus("ဆက်လက် နားထောင်နေပါတယ်…")
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
                        session.sendText("${result.message}။ လုပ်ဆောင်မှာလား — ဟုတ်ကဲ့ ဒါမှမဟုတ် မလုပ်ပါ လို့ပြောပါ။")
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
                controller.updateStatus(it, controller.mapSessionStatusToPhase(it))
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

    fun storedAudioInputDevice(): String = apiKeyStore.loadAudioInputDevice()

    fun saveAudioInputDevice(name: String) {
        apiKeyStore.saveAudioInputDevice(name)
        // Takes effect on the next app start (the capture line opens once at engine construction).
    }

    fun storedBargeInSensitivity(): String = apiKeyStore.loadBargeInSensitivity()

    fun saveBargeInSensitivity(level: String) {
        val normalized = com.example.voicebrainlive.desktop.platform.audio.SpectralVad.normalizeLevel(level)
        apiKeyStore.saveBargeInSensitivity(normalized)
        audio.updateBargeInSensitivity(normalized)
    }

    fun storedEchoCancellerKind(): String = apiKeyStore.loadEchoCancellerKind()

    fun saveEchoCancellerKind(kind: String) {
        apiKeyStore.saveEchoCancellerKind(kind)
        // Takes effect on the next app start (engine wiring is built once).
    }

    /** Input mixer names for the Settings audio-device picker. */
    fun availableInputMixers(): List<String> = runCatching {
        javax.sound.sampled.AudioSystem.getMixerInfo()
            .filter { info ->
                runCatching { javax.sound.sampled.AudioSystem.getMixer(info).targetLineInfo.isNotEmpty() }
                    .getOrDefault(false)
            }
            .map { it.name }
            .distinct()
    }.getOrDefault(emptyList())

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
                } else if (isConfirmationGatedCommand(matched.type)) {
                    requestLocalConfirmation(matched)
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
            "latency_report", "audio_latency" -> {
                CommandResult(true, audio.getAudioLatencyReport())
            }
            "set_barge_in_sensitivity" -> {
                // Phase 2 — voice command: persists the level and applies it
                // to the running VAD immediately (no restart needed).
                val level = com.example.voicebrainlive.desktop.platform.audio.SpectralVad.normalizeLevel(
                    command.value ?: command.target,
                )
                apiKeyStore.saveBargeInSensitivity(level)
                audio.updateBargeInSensitivity(level)
                val levelText = when (level) {
                    "low" -> "နိမ့်"
                    "high" -> "မြင့်"
                    else -> "ပုံမှန်"
                }
                CommandResult(true, "ဖြတ်ပြောမှု ထိခိုက်လွယ်တာ (barge-in sensitivity) ကို ‘$levelText’ သတ်မှတ်လိုက်ပါပြီရှင်။")
            }
            else -> commandExecutor.execute(command)
        }
    }

    fun onWindowVisibilityChanged(visible: Boolean) {
        powerOptimizer.onWindowVisibilityChanged(visible)
    }

    /**
     * A sendAudioChunk() false means not-ready or a dying socket. One failure
     * is normal (reconnect buffering); a sustained stall (~5s of speech with
     * no delivery) surfaces on the pill and triggers a reconnect.
     */
    private fun onAudioSendFailed() {
        consecutiveAudioSendFailures++
        if (!audioSendStallWarned && consecutiveAudioSendFailures >= AUDIO_SEND_STALL_CHUNKS) {
            audioSendStallWarned = true
            DesktopLogger.warn("Audio send stalled: $consecutiveAudioSendFailures consecutive chunks not delivered")
            controller.updateStatus("အသံပို့မရသေးပါ — ပြန်လည်ချိတ်ဆက်နေပါသည်…", AssistantPhase.CONNECTING)
            scheduleLiveReconnect()
        }
    }

    /**
     * Honest waveform support: if the mic level stays near zero for ~2.5s
     * while listening, flag it so the UI shows flat gray bars + an
     * input-device hint instead of fake ambience.
     */
    private fun updateMicSilentState(level: Float) {
        if (!listening.get()) {
            micLowSinceNanos = 0L
            if (_micSilentWhileListening.value) _micSilentWhileListening.value = false
            return
        }
        val now = System.nanoTime()
        if (level < MIC_SILENT_LEVEL) {
            if (micLowSinceNanos == 0L) micLowSinceNanos = now
            if (!_micSilentWhileListening.value && now - micLowSinceNanos > MIC_SILENT_TIMEOUT_NANOS) {
                _micSilentWhileListening.value = true
                DesktopLogger.warn("Mic silent for >2.5s while listening (level=$level)")
                controller.updateStatus("mic က ဘာမှမကြားရပါ — input device ကို စစ်ပါ", AssistantPhase.ERROR)
            }
        } else {
            micLowSinceNanos = 0L
            if (_micSilentWhileListening.value) {
                _micSilentWhileListening.value = false
                controller.updateStatus("နားထောင်နေပါသည် — Live native audio အသင့်ဖြစ်ပါပြီ", AssistantPhase.LISTENING)
            }
        }
    }

    /**
     * App-level heartbeat: the shared OkHttpClient intentionally disables WS
     * pings, so a half-open socket (uplink audio flowing, server silent) is
     * detected here. When the user has been speaking but nothing has arrived
     * from the server for ~10s, reconnect.
     */
    private fun installLivenessMonitor() {
        scope.launch {
            while (!closed) {
                delay(5_000L)
                if (closed || !listening.get() || reconnectInProgress) continue
                val uplinkRecent = lastUplinkAudioSentNanos != 0L &&
                    (System.nanoTime() - lastUplinkAudioSentNanos) / 1_000_000L < 10_000L
                if (uplinkRecent && !session.probeLiveness(10_000L)) {
                    DesktopLogger.warn(
                        "Liveness probe failed: uplink audio flowing but no server activity for " +
                            "${session.lastServerActivityElapsedMs()}ms — reconnecting",
                    )
                    controller.updateStatus("ပြန်လည်ချိတ်ဆက်နေပါသည်…", AssistantPhase.CONNECTING)
                    scheduleLiveReconnect()
                }
            }
        }
    }

    fun start() {
        watchdog.install()
        installLivenessMonitor()
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
        lastUplinkAudioSentNanos = 0L
        consecutiveAudioSendFailures = 0
        audioSendStallWarned = false
        micLowSinceNanos = 0L
        _micSilentWhileListening.value = false
        synchronized(inputTranscriptBuffer) { inputTranscriptBuffer.setLength(0) }
        session.clearAudioBuffer()
        controller.updateListening(true)
        val microphoneStarted = audio.startMicrophone(
            onPcmChunk = { chunk ->
                audio.noteUplinkAudioSent()
                lastUplinkAudioSentNanos = System.nanoTime()
                val sendStart = System.nanoTime()
                if (session.isLiveReady()) {
                    if (session.sendAudioChunk(chunk)) {
                        consecutiveAudioSendFailures = 0
                        audioSendStallWarned = false
                    } else {
                        onAudioSendFailed()
                    }
                } else {
                    synchronized(preSetupAudioBuffer) {
                        if (preSetupAudioBuffer.size < 120) {
                            preSetupAudioBuffer.add(chunk)
                        } else {
                            // Bounded: drops are counted and logged, never silent.
                            preSetupDroppedChunks++
                            if (preSetupDroppedChunks % 50L == 0L) {
                                DesktopLogger.warn("preSetupAudioBuffer full; dropping pre-setup audio (total dropped=$preSetupDroppedChunks)")
                            }
                        }
                    }
                }
                audio.latencyTracker.record(
                    com.example.voicebrainlive.desktop.platform.audio.AudioStage.SESSION_SEND,
                    System.nanoTime() - sendStart,
                )
                // Bounded periodic telemetry (<= 1/min): rolling p50/p95 per
                // stage. No audio or transcript contents are ever logged.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastLatencyReportLogMs >= 60_000L) {
                    lastLatencyReportLogMs = nowMs
                    DesktopLogger.info("Audio latency rolling p50/p95 (ms):\n${audio.getAudioLatencyReport()}")
                }
            },
            onVolumeLevel = { level ->
                _liveVolumeLevel.value = level
                updateMicSilentState(level)
            },
            onSilenceDetected = {
                // S2S automatic-VAD mode: the server owns end-of-turn via its
                // own VAD (silenceDurationMs). The client must NOT send
                // audioStreamEnd here — that signal means "mic turned off" and
                // the mic stays open. The client hangover remains for
                // UI/telemetry only.
                if (listening.get() && liveConversationMode && session.isLiveReady()) {
                    DesktopLogger.info("Audio telemetry: client VAD hangover elapsed (turn end owned by server VAD)")
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
            controller.updateStatus(
                "Microphone မဖွင့်နိုင်ပါ — Windows input device/permission ကို စစ်ပါ",
                AssistantPhase.ERROR,
            )
            return
        }
        // Re-check: the socket may have died between the toggle check and mic
        // start. Never capture into a dead session — reconnect instead.
        if (!session.isLiveReady()) {
            DesktopLogger.warn("Session lost between toggle and mic start; reconnecting instead of capturing into a dead socket")
            liveConversationMode = false
            stopListening()
            controller.updateStatus("ပြန်လည်ချိတ်ဆက်နေပါသည်…", AssistantPhase.CONNECTING)
            scheduleLiveReconnect()
            return
        }
        soundEffects.playListeningStarted(scope)
        controller.updateStatus("နားထောင်နေပါသည် — Live native audio အသင့်ဖြစ်ပါပြီ")
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
        _micSilentWhileListening.value = false
        micLowSinceNanos = 0L
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

    /**
     * Phase 2 — destructive actions gated behind voice confirmation in the
     * offline path: power actions plus recycle-bin deletion and forced app
     * termination. (In the Live tool-call path the same gate is enforced via
     * CommandResult.requiresConfirmation from the executor.)
     */
    private fun isConfirmationGatedCommand(type: String): Boolean =
        type.lowercase() in setOf("shutdown", "restart", "sleep", "empty_recycle_bin", "close_app", "stop_app")

    private fun requestLocalConfirmation(command: DesktopCommand) {
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
            "sleep" -> "ကွန်ပျူတာကို Sleep ဝင်ပါမယ်"
            "empty_recycle_bin" -> "Recycle Bin ထဲက အရာအားလုံးကို အပြီးတိုင် ဖျက်ပါမယ်"
            else -> "‘${command.target ?: command.value.orEmpty()}’ app ကို အတင်းပိတ်ပါမယ် (မသိမ်းရသေးသော အလုပ်များ ဆုံးရှုံးနိုင်ပါသည်)"
        }
        val confirmationText = "$actionText။ လုပ်ဆောင်မှာလား — ဟုတ်ကဲ့ ဒါမှမဟုတ် မလုပ်ပါ။"
        controller.updateResponse(confirmationText)
        controller.updateStatus("အသံဖြင့် အတည်ပြုချက်ကို စောင့်နေပါတယ်")
        scope.launch {
            session.sendText("$actionText။ လုပ်ဆောင်မှာလား — ဟုတ်ကဲ့ ဒါမှမဟုတ် မလုပ်ပါ လို့ပြောပါ။")
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
                val clarify = "မရှင်းလင်းပါ။ လုပ်ဆောင်မယ်ဆို ဟုတ်ကဲ့၊ မလုပ်လိုရင် မလုပ်ပါ လို့ပြောပါ။"
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
            // Stamp the confirmation marker, keeping the action's name in
            // target so the executor never mistakes "confirmed" for it.
            val confirmedCommand = pending.command.copy(
                target = pending.command.target ?: pending.command.value,
                value = "confirmed",
            )
            val result = executeDesktopCommand(confirmedCommand)
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
        /** 32ms chunks; 150 ≈ 4.8s of speech with zero delivery = stalled socket. */
        const val AUDIO_SEND_STALL_CHUNKS = 150
        /** Below this raw RMS the mic counts as "hearing nothing". */
        const val MIC_SILENT_LEVEL = 0.005f
        /** Near-zero mic level this long while listening => mic-silent flag. */
        const val MIC_SILENT_TIMEOUT_NANOS = 2_500_000_000L
    }
}
