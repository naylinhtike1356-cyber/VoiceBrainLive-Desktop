package com.example.voicebrainlive.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import com.example.voicebrainlive.desktop.core.AssistantPhase
import com.example.voicebrainlive.desktop.core.GoalDefinition
import com.example.voicebrainlive.desktop.core.GoalStatus
import com.example.voicebrainlive.desktop.core.StepStatus
import kotlinx.coroutines.launch
import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.example.voicebrainlive.desktop.ui.AssistantOrb
import com.example.voicebrainlive.desktop.ui.AuroraBackground
import com.example.voicebrainlive.desktop.ui.ConversationList
import com.example.voicebrainlive.desktop.ui.NeuralBrainScreen
import com.example.voicebrainlive.desktop.ui.NilarColors
import com.example.voicebrainlive.desktop.ui.NilarRadii
import com.example.voicebrainlive.desktop.ui.NilarType

// Modern Glassmorphic Dark Theme Palette
private val DarkBg = Color(0xFF070B14)
private val DarkBgSecondary = Color(0xFF0F172A)
private val CardBg = Color(0xEE0E1626)
private val CardSoft = Color(0xEE162238)
private val CardHighlight = Color(0xEE1E2E4A)
private val AccentMint = Color(0xFF00F5D4)
private val AccentCyan = Color(0xFF38BDF8)
private val AccentPurple = Color(0xFFA78BFA)
private val AccentGold = Color(0xFFFBBF24)
private val AccentRose = Color(0xFFFB7185)
private val TextMain = Color(0xFFF8FAFC)
private val TextSub = Color(0xFF94A3B8)
private val BorderColor = Color(0xFF1E293B)
private val BorderGlow = Color(0xFF334155)

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        DesktopLogger.warn("FATAL UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable.stackTraceToString()}")
    }
    DesktopLogger.info("NilarAI main() started. PID=${ProcessHandle.current().pid()}")
    val instanceGuard = SingleInstanceGuard.acquire()
    if (instanceGuard == null) {
        DesktopLogger.info("SingleInstanceGuard returned null -> secondary process exiting.")
        return
    }
    try {
        application {
            DisposableEffect(Unit) {
                onDispose {
                    DesktopLogger.info("Application disposed -> closing instanceGuard")
                    instanceGuard.close()
                }
            }
            val windowState = rememberWindowState(width = 980.dp, height = 700.dp)
            var mainWindowVisible by remember { mutableStateOf(true) }
            var bringToFrontTrigger by remember { mutableStateOf(0L) }

            val bringToFront: () -> Unit = {
                DesktopLogger.info("bringToFront called: making main window visible and restoring")
                mainWindowVisible = true
                windowState.isMinimized = false
                bringToFrontTrigger = System.currentTimeMillis()
            }

            val bootstrap = rememberAppBootstrap(
                onExitRequested = {
                    DesktopLogger.info("Runtime requested exitApplication()")
                    exitApplication()
                },
                onMainWindowRequested = bringToFront,
                onToggleMainWindowRequested = {
                    if (mainWindowVisible && !windowState.isMinimized) {
                        DesktopLogger.info("Toggle main window -> hiding to background")
                        mainWindowVisible = false
                    } else {
                        DesktopLogger.info("Toggle main window -> bringing to front")
                        bringToFront()
                    }
                },
            )
            val runtime = bootstrap.runtime
            var robotVisible by remember { mutableStateOf(runtime.storedRobotVisible()) }

            // File-based IPC listener: Detect when user clicks Desktop shortcut while app is already running
            LaunchedEffect(Unit) {
                awaitShowWindowTriggers(bringToFront)
            }

        FloatingRobotWindow(
            runtime = runtime,
            visible = robotVisible,
            onHide = {
                robotVisible = false
                runtime.setRobotVisible(false)
            },
        )

        LaunchedEffect(mainWindowVisible, windowState.isMinimized) {
            val effectiveVisible = mainWindowVisible && !windowState.isMinimized
            runtime.onWindowVisibilityChanged(effectiveVisible)
        }

        Window(
            visible = mainWindowVisible,
            onCloseRequest = {
                runtime.close()
                exitApplication()
            },
            title = "Nilar AI — မြန်မာ AI အသံလက်ထောက်",
            icon = androidx.compose.ui.res.painterResource("nilar_ai_logo.png"),
            state = windowState,
        ) {
            LaunchedEffect(bringToFrontTrigger) {
                if (bringToFrontTrigger > 0L) {
                    runCatching {
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.requestFocus()
                        window.isAlwaysOnTop = false
                    }
                }
            }

            VoiceBrainDesktopApp(
                runtime = runtime,
                commandModeRequest = runtime.commandModeRequest,
                robotVisible = robotVisible,
                onRobotVisibilityChange = { robotVisible = it; runtime.setRobotVisible(it) },
                onMinimizeToBackground = {
                    mainWindowVisible = false
                    runtime.showBackgroundNotification(
                        "Nilar AI",
                        "Nilar AI သည် နောက်ခံတွင် ဆက်လက်အလုပ်လုပ်နေပါသည်။ Global Shortcut (Ctrl + Alt + Space) ဖြင့် အသံသုံးနိုင်ပါသည်။"
                    )
                },
                onExitApp = {
                    runtime.close()
                    exitApplication()
                },
            )
        }
    }
} catch (t: Throwable) {
    DesktopLogger.warn("Fatal application error: ${t.stackTraceToString()}")
}
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun VoiceBrainDesktopApp(
    runtime: DesktopRuntime,
    commandModeRequest: kotlinx.coroutines.flow.StateFlow<Long>,
    robotVisible: Boolean,
    onRobotVisibilityChange: (Boolean) -> Unit,
    onMinimizeToBackground: () -> Unit = {},
    onExitApp: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(runtime.storedApiKey()) }
    var geminiModel by remember { mutableStateOf(runtime.storedGeminiModel()) }
    var desktopAutomationEnabled by remember { mutableStateOf(runtime.desktopAutomationEnabled()) }
    var showApiKey by remember { mutableStateOf(false) }
    var showNeuralBrain by remember { mutableStateOf(false) }
    val state by runtime.assistant.state.collectAsState()
    val voiceTyping by runtime.voiceTypingMode.collectAsState()
    val sessionHealthy by runtime.sessionHealthy.collectAsState()
    val micSilent by runtime.micSilentWhileListening.collectAsState()
    var audioInputDevice by remember { mutableStateOf(runtime.storedAudioInputDevice()) }
    var bargeInSensitivity by remember { mutableStateOf(runtime.storedBargeInSensitivity()) }
    var echoCancellerKind by remember { mutableStateOf(runtime.storedEchoCancellerKind()) }
    val commandRequest by commandModeRequest.collectAsState()
    val neuralBrainRequest by runtime.showNeuralBrainRequest.collectAsState()
    val commandFocusRequester = remember { FocusRequester() }
    LaunchedEffect(commandRequest) {
        if (commandRequest > 0L) commandFocusRequester.requestFocus()
    }
    LaunchedEffect(neuralBrainRequest) {
        if (neuralBrainRequest > 0L) {
            showNeuralBrain = true
            showSettings = false
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        runtime.sendUserMessage(text)
    }

    val quickActions = listOf(
        "🧠 Neural Brain" to "ဦးနှောက်မှတ်ဉာဏ် ကြည့်မယ်",
        "⚡ Work Mode" to "အလုပ်စမယ်",
        "☕ Rest Mode" to "အနားယူမယ်",
        "🎯 Workspace Setup" to "work mode ဖွင့်ပေးပါ",
        "💻 System Health" to "ကွန်ပျူတာ အခြေအနေ စစ်ဆေးပေးပါ",
        "🖥️ Active Window" to "လက်ရှိ ဘာ app သုံးနေလဲ စစ်ပေးပါ",
        "🌐 Network Check" to "အင်တာနက် ချိတ်ဆက်မှု စစ်ပေးပါ",
        "🔋 Battery Status" to "ဘက်ထရီ အခြေအနေ စစ်ပေးပါ",
        "🖼️ Screen Read" to "စခရင်မှာဘာပြလဲ",
        "📋 Clipboard Text" to "Clipboard ဖတ်ပေးပါ",
        "📂 Downloads Folder" to "Downloads ဖွင့်ပါ",
        "📝 Notepad App" to "Notepad ဖွင့်ပါ",
        "🌐 Google Chrome" to "Chrome ဖွင့်ပါ",
    )

    val devChips = listOf(
        "⚡ Auto-Fix" to "VoiceBrainLive ပရောဂျက်မှာ bug ရှာပြင်ပေးပါ",
        "🔄 Auto-Heal" to "VoiceBrainLive project ကို self heal လုပ်ပြီး compile စမ်းပေးပါ",
        "📱 Emulator" to "VoiceBrainLive app ကို emulator ပေါ်တင်ပြီး crash စစ်ပေးပါ",
        "🌿 Git Commit" to "VoiceBrainLive ပြင်ဆင်ထားတာတွေကို git commit ထိုးပေးပါ",
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentMint,
            secondary = AccentCyan,
            tertiary = AccentPurple,
            background = DarkBg,
            surface = CardBg,
            onBackground = TextMain,
            onSurface = TextMain,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NilarColors.abyssGradient)
        ) {
            AuroraBackground(phase = state.phase)
            val screenKey = when {
                showSettings -> 2
                showNeuralBrain -> 1
                else -> 0
            }
            AnimatedContent(
                targetState = screenKey,
                transitionSpec = {
                    (slideInHorizontally(animationSpec = tween(350)) { it / 4 } + fadeIn(animationSpec = tween(350))) with
                        (slideOutHorizontally(animationSpec = tween(350)) { -it / 4 } + fadeOut(animationSpec = tween(350)))
                },
                label = "screen-slide",
            ) { key ->
                when (key) {
                    2 ->
                SettingsPanel(
                    apiKey = apiKey,
                    showApiKey = showApiKey,
                    geminiModel = geminiModel,
                    desktopAutomationEnabled = desktopAutomationEnabled,
                    robotVisible = robotVisible,
                    status = state.status,
                    audioInputDevice = audioInputDevice,
                    bargeInSensitivity = bargeInSensitivity,
                    echoCancellerKind = echoCancellerKind,
                    inputMixerNames = remember { runtime.availableInputMixers() },
                    onAudioInputDeviceChange = { audioInputDevice = it },
                    onBargeInSensitivityChange = { bargeInSensitivity = it },
                    onEchoCancellerChange = { echoCancellerKind = it },
                    onApiKeyChange = { apiKey = it },
                    onGeminiModelChange = { geminiModel = it },
                    onDesktopAutomationChange = { desktopAutomationEnabled = it },
                    onToggleVisibility = { showApiKey = !showApiKey },
                    onRobotVisibilityChange = onRobotVisibilityChange,
                    onSave = {
                        runtime.saveApiKey(apiKey)
                        runtime.saveGeminiModel(geminiModel)
                        runtime.saveDesktopAutomationEnabled(desktopAutomationEnabled)
                        runtime.saveAudioInputDevice(audioInputDevice)
                        runtime.saveBargeInSensitivity(bargeInSensitivity)
                        runtime.saveEchoCancellerKind(echoCancellerKind)
                        showSettings = false
                    },
                    onClear = {
                        apiKey = ""
                        runtime.clearApiKey()
                    },
                    onTestGeminiKey = {
                        scope.launch {
                            val result = runtime.testGeminiKey(apiKey)
                            runtime.assistant.updateResponse(result.getOrElse { it.message ?: "Gemini test failed" })
                            runtime.assistant.updateStatus(if (result.isSuccess) "Gemini active" else "Gemini error")
                        }
                    },
                    onBack = { showSettings = false },
                    onMinimizeToBackground = {
                        showSettings = false
                        onMinimizeToBackground()
                    },
                    onExitApp = onExitApp,
                )
                1 ->
                        NeuralBrainScreen(
                            runtime = runtime,
                            onClose = { showNeuralBrain = false }
                        )
                    else ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Honest status: while listening against a dead session, force
                    // the reconnecting pill instead of a stale "listening" state.
                    val pillStatus = if (state.isListening && !sessionHealthy) {
                        "ပြန်လည်ချိတ်ဆက်နေပါသည်…"
                    } else {
                        state.status
                    }
                    val pillPhase = if (state.isListening && !sessionHealthy) {
                        AssistantPhase.CONNECTING
                    } else {
                        state.phase
                    }
                    // Header Bar with Glowing Accents
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // 40dp mini orb: the same living glyph as the main
                            // voice orb, kept still in the header.
                            AssistantOrb(
                                phase = state.phase,
                                micLevel = 0f,
                                playbackLevel = 0f,
                                micSilent = false,
                                onClick = {},
                                size = 40.dp,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Nilar AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextMain)
                                    Surface(
                                        color = AccentMint.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, AccentMint.copy(alpha = 0.4f))
                                    ) {
                                        Text("VOICE", color = AccentMint, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                    Surface(
                                        color = AccentCyan.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, AccentCyan.copy(alpha = 0.35f))
                                    ) {
                                        Text(geminiModel.removePrefix("gemini-").removePrefix("models/"), color = AccentCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                }
                                Text("မြန်မာ AI စကားပြော ကွန်ပျူတာ လက်ထောက်", color = TextSub, fontSize = 10.sp)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Neural Brain Matrix Button
                            Surface(
                                color = if (showNeuralBrain) CardBg else CardSoft,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (showNeuralBrain) AccentCyan else BorderGlow
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable { showNeuralBrain = !showNeuralBrain }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("🧠", fontSize = 16.sp)
                                }
                            }


                            StatusPill(pillStatus, state.isConnected, state.isListening, pillPhase)

                            Surface(
                                color = CardSoft,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlow),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable { showSettings = true }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("⚙️", fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    // Main Center Section: Voice Companion Card + Conversation Card
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Left: Voice Companion Card with Radial Aura
                        Card(
                            modifier = Modifier.weight(0.32f),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        if (state.isListening) AccentMint.copy(alpha = 0.5f) else BorderGlow,
                                        BorderColor
                                    )
                                )
                            ),
                        ) {
                            val liveLevel by runtime.liveVolumeLevel.collectAsState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                if (state.isListening) Color(0xFF0D252D) else CardBg,
                                                CardBg
                                            )
                                        )
                                    )
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("VOICE COMPANION", style = NilarType.Eyebrow)
                                    Text(
                                        if (state.isListening) "အသံကို နားထောင်နေပါသည်" else "ပြောရန် အဆင်သင့်ဖြစ်ပါပြီ",
                                        style = NilarType.Status.copy(fontSize = 13.sp),
                                    )
                                }

                                val playbackLevel by runtime.playbackLevel.collectAsState()
                                val orbSize by animateDpAsState(
                                    targetValue = if (state.isListening || state.phase == AssistantPhase.SPEAKING) 216.dp else 200.dp,
                                    animationSpec = tween(600),
                                    label = "orb-size",
                                )
                                // The orb IS the mic button: tap it to toggle listening.
                                AssistantOrb(
                                    phase = state.phase,
                                    micLevel = liveLevel,
                                    playbackLevel = playbackLevel,
                                    micSilent = micSilent,
                                    onClick = runtime::toggleListening,
                                    size = orbSize,
                                )
                                // Orb-integrated status line: AnimatedContent over the live
                                // status text so transitions read smoothly.
                                AnimatedContent(
                                    targetState = pillStatus,
                                    transitionSpec = { fadeIn(tween(250)) with fadeOut(tween(250)) },
                                    label = "orb-status",
                                ) { statusText ->
                                    Text(
                                        text = statusText,
                                        style = NilarType.Status.copy(
                                            color = NilarColors.phaseColor(pillPhase, state.isConnected),
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (micSilent) {
                                    Text(
                                        "mic က ဘာမှမကြားရပါ — input device ကို စစ်ပါ",
                                        color = Color(0xFFFF6B6B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }

                                Button(
                                    onClick = { runtime.toggleVoiceTypingMode() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceTyping) AccentGold.copy(alpha = 0.9f) else CardSoft,
                                        contentColor = if (voiceTyping) Color(0xFF241A00) else TextSub,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                ) {
                                    Text(if (voiceTyping) "⌨️ Voice Typing ON" else "⌨️ Voice Typing", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }

                                Surface(
                                    color = CardSoft.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderGlow)
                                ) {
                                    Text(
                                        when {
                                            voiceTyping && state.isListening -> "⌨️ ပြောသောစာကို active window ထဲ ထည့်နေပါသည်"
                                            state.isListening -> "Listening… • Press Ctrl+Alt+Space to stop"
                                            else -> "Tap Mic or Ctrl+Alt+Space"
                                        },
                                        color = if (state.isListening) AccentMint else TextSub,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Right: Conversation Card with Glassmorphic Aesthetic
                        Card(
                            modifier = Modifier.weight(0.68f),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlow),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("💬", fontSize = 13.sp)
                                        Text("Conversation Log", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text("Burmese AI Assistant", color = TextSub, fontSize = 11.sp)
                                }
                                state.activeGoal?.let { activeGoal ->
                                    GoalProgressCard(
                                        goal = activeGoal,
                                        onCancel = { runtime.cancelActiveGoal() },
                                        onClear = { runtime.clearActiveGoal() }
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                ConversationList(
                                    messages = state.messages,
                                    phase = state.phase,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }

                    // Quick-action chip rail (single, collapsible).
                    var chipsExpanded by remember { mutableStateOf(true) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("QUICK ACTIONS", style = NilarType.Eyebrow)
                            Surface(
                                color = CardSoft,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlow),
                                modifier = Modifier.clickable { chipsExpanded = !chipsExpanded },
                            ) {
                                Text(
                                    if (chipsExpanded) "\u25B4 Hide" else "\u25BE Show",
                                    color = TextSub,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        AnimatedVisibility(visible = chipsExpanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                (devChips + quickActions).forEach { (label, prompt) ->
                                    Surface(
                                        color = CardSoft,
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlow),
                                        modifier = Modifier.clickable { send(prompt) },
                                    ) {
                                        Text(
                                            label,
                                            color = TextMain,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Text Input Bar — glass container, circular mint send button.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(NilarRadii.Lg))
                            .background(NilarColors.Glass, RoundedCornerShape(NilarRadii.Lg))
                            .border(1.dp, NilarColors.BorderGlow.copy(alpha = 0.6f), RoundedCornerShape(NilarRadii.Lg))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(commandFocusRequester)
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter && !keyEvent.isShiftPressed) {
                                        if (input.isNotBlank()) {
                                            val textToSend = input
                                            input = ""
                                            send(textToSend)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            placeholder = { Text(if (voiceTyping) "Voice Typing ဖွင့်ရန် Mic ကိုနှိပ်ပြီး active app ကို focus ထားပါ" else "Quick command…  Ctrl + Alt + Enter to focus  •  Enter to send", color = TextSub, fontSize = 12.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain,
                            ),
                        )

                        // Send Button (Vibrant Icon Button)
                        Button(
                            enabled = input.isNotBlank(),
                            onClick = {
                                val message = input
                                input = ""
                                send(message)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentMint,
                                contentColor = Color(0xFF041E1A),
                                disabledContainerColor = CardSoft,
                                disabledContentColor = TextSub,
                            ),
                            shape = CircleShape,
                            modifier = Modifier.size(44.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("➤", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String, connected: Boolean, listening: Boolean, phase: AssistantPhase) {
    // Slim status pill: 8dp radius, no emoji, phase color from the single
    // shared source with a 600ms transition. The dead-session override is
    // applied by the caller (pillPhase = CONNECTING while listening on a
    // dead session).
    val animatedColor by animateColorAsState(
        targetValue = NilarColors.phaseColor(phase, connected),
        animationSpec = tween(600),
        label = "status-color",
    )
    val transition = rememberInfiniteTransition(label = "status-dot-pulse")
    val dotPulse by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "status-dot-scale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(animatedColor.copy(alpha = 0.14f), RoundedCornerShape(NilarRadii.Sm))
            .border(1.dp, animatedColor.copy(alpha = 0.45f), RoundedCornerShape(NilarRadii.Sm))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size((7 * dotPulse).dp)
                .clip(CircleShape)
                .background(animatedColor)
        )
        Text(status, style = NilarType.Status, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsPanel(
    apiKey: String,
    showApiKey: Boolean,
    geminiModel: String,
    desktopAutomationEnabled: Boolean,
    robotVisible: Boolean,
    status: String,
    audioInputDevice: String,
    bargeInSensitivity: String,
    echoCancellerKind: String,
    inputMixerNames: List<String>,
    onAudioInputDeviceChange: (String) -> Unit,
    onBargeInSensitivityChange: (String) -> Unit,
    onEchoCancellerChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onGeminiModelChange: (String) -> Unit,
    onDesktopAutomationChange: (Boolean) -> Unit,
    onToggleVisibility: () -> Unit,
    onRobotVisibilityChange: (Boolean) -> Unit,
    onTestGeminiKey: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onMinimizeToBackground: () -> Unit = {},
    onExitApp: () -> Unit = {},
) {
    val settingsScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(settingsScroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextMain)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onRobotVisibilityChange(!robotVisible) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (robotVisible) AccentMint else CardSoft,
                        contentColor = if (robotVisible) Color.Black else TextMain,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) { Text(if (robotVisible) "Hide Robot" else "Show Robot", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onMinimizeToBackground,
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Hide to Tray", fontSize = 12.sp, color = AccentMint) }
                TextButton(onClick = onBack) { Text("Back", color = AccentCyan) }
            }
        }

        // Gemini Model Selection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🤖 Gemini Intelligence Model", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AccentMint)
                    Surface(
                        color = AccentCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, AccentCyan.copy(alpha = 0.4f))
                    ) {
                        Text(geminiModel, color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }

                Text(
                    "Native Voice-to-Voice Live model ကိုသာ ရွေးချယ်နိုင်ပါသည်။ Text-only သို့မဟုတ် TTS model များကို မသုံးပါ။",
                    color = TextSub,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                val modelPresets = listOf(
                    Triple("gemini-3.8-live", "🎙️ 3.8 Live (Stable)", "အကြံပြုထားသော low-latency native audio-to-audio Live model"),
                    Triple("gemini-3.8-live-extended-thinking", "🧠 3.8 Live Extended", "ပိုမိုနက်ရှိုင်းသော reasoning; တုံ့ပြန်ချိန် ပိုကြာနိုင်သည်"),
                    Triple("gemini-3.1-flash-live-preview", "⚡ 3.1 Live (Legacy)", "အဟောင်း preview Live model; 3.8 Live ကို အကြံပြုသည်"),
                    Triple("gemini-2.5-flash-native-audio-preview-12-2025", "🎙️ 2.5 Native Audio", "ယခင် native-audio Live preview model"),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    modelPresets.forEach { (mId, label, desc) ->
                        val isSelected = geminiModel.equals(mId, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) CardHighlight else CardSoft)
                                .border(1.dp, if (isSelected) AccentMint else BorderColor, RoundedCornerShape(8.dp))
                                .clickable { onGeminiModelChange(mId) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, color = if (isSelected) AccentMint else TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(desc, color = TextSub, fontSize = 10.sp)
                            }
                            if (isSelected) {
                                Text("✓ Active", color = AccentMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = onGeminiModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Live model name (Live-only)") },
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }

        // Audio Devices Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🎤 Audio Input Device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AccentMint)
                Text(
                    "မိုက်ခရိုဖုန်း input device ရွေးချယ်ပါ။ ပြောင်းလဲမှုသည် အက်ပ် ပြန်ဖွင့်မှ သက်ရောက်ပါမည်။",
                    color = TextSub,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                val deviceOptions = listOf("" to "System Default") + inputMixerNames.map { it to it }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    deviceOptions.forEach { (id, label) ->
                        val isSelected = audioInputDevice == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) CardHighlight else CardSoft)
                                .border(1.dp, if (isSelected) AccentMint else BorderColor, RoundedCornerShape(8.dp))
                                .clickable { onAudioInputDeviceChange(id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                color = if (isSelected) AccentMint else TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Text("✓ Active", color = AccentMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Text("ဖြတ်ပြောမှု ထိခိုက်လွယ်တာ (Barge-in Sensitivity)", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("low" to "နိမ့်", "normal" to "ပုံမှန်", "high" to "မြင့်").forEach { (id, label) ->
                        val isSelected = bargeInSensitivity == id
                        Surface(
                            color = if (isSelected) AccentMint.copy(alpha = 0.2f) else CardSoft,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AccentMint else BorderColor),
                            modifier = Modifier.clickable { onBargeInSensitivityChange(id) }
                        ) {
                            Text(
                                label,
                                color = if (isSelected) AccentMint else TextMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Text("Echo Canceller", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("suppression" to "Suppression", "webrtc_aec3" to "WebRTC AEC3").forEach { (id, label) ->
                        val isSelected = echoCancellerKind == id
                        Surface(
                            color = if (isSelected) AccentCyan.copy(alpha = 0.2f) else CardSoft,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AccentCyan else BorderColor),
                            modifier = Modifier.clickable { onEchoCancellerChange(id) }
                        ) {
                            Text(
                                label,
                                color = if (isSelected) AccentCyan else TextMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Text(
                    "WebRTC AEC3 ကို ရွေးပါက webrtc_aec3.dll လိုအပ်ပြီး အက်ပ် ပြန်ဖွင့်မှ သက်ရောက်ပါမည် (အသေးစိတ် native/BUILD_WINDOWS.md)။",
                    color = TextSub,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        // Global Shortcuts Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⌨️ Global Shortcuts (Active Everywhere)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AccentMint)
                    Text("Background Service Active", color = AccentCyan, fontSize = 11.sp)
                }

                Text(
                    "အက်ပ်ကို Background (System Tray) တွင် အမြဲ Run ထားနိုင်ပြီး အောက်ပါ Shortcut များဖြင့် မည်သည့်နေရာမှမဆို အသုံးပြုနိုင်ပါသည်:",
                    color = TextSub,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                val shortcuts = listOf(
                    "Ctrl + Alt + Space" to "အသံ ဖွင့်/ပိတ် (Start/Stop Live Voice)",
                    "Ctrl + Alt + Enter" to "Command Bar သို့ တိုက်ရိုက် ရောက်ရှိရန်",
                    "Ctrl + Alt + W"     to "Window ဖွင့်/ပိတ် (Show/Hide VoiceBrain)",
                    "Ctrl + Alt + R"     to "3D Robot Mascot ဖွင့်/ပိတ် (Show/Hide Robot)",
                    "Ctrl + Alt + V"     to "စခရင် ဖတ်ရှု သုံးသပ်ရန် (Screen Vision)",
                    "Ctrl + Alt + C"     to "Clipboard ဖတ်ရှု သုံးသပ်ရန် (Read Clipboard)",
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shortcuts.forEach { (key, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardSoft, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(key, color = AccentGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(desc, color = TextMain, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Gemini API Key Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gemini API Key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextMain)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API Key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    shape = RoundedCornerShape(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onToggleVisibility, shape = RoundedCornerShape(8.dp)) { Text(if (showApiKey) "Hide" else "Show") }
                    OutlinedButton(onClick = onClear, shape = RoundedCornerShape(8.dp)) { Text("Clear") }
                    OutlinedButton(onClick = onTestGeminiKey, shape = RoundedCornerShape(8.dp)) { Text("Test") }
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = Color.Black), shape = RoundedCornerShape(8.dp)) { Text("Save") }
                }
                Text(status, color = TextSub, fontSize = 11.sp)
            }
        }

        // Application Power / Exit Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Exit Application", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Close VoiceBrainLive and stop all background services", color = TextSub, fontSize = 11.sp)
                }
                Button(
                    onClick = onExitApp,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1E1E), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Exit App", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun GoalProgressCard(
    goal: GoalDefinition,
    onCancel: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardSoft),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (goal.status) {
                GoalStatus.COMPLETED -> AccentMint
                GoalStatus.FAILED -> AccentRose
                GoalStatus.CANCELLED -> AccentGold
                else -> AccentCyan
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        when (goal.status) {
                            GoalStatus.COMPLETED -> "🎉"
                            GoalStatus.FAILED -> "⚠️"
                            GoalStatus.CANCELLED -> "⏹️"
                            else -> "🎯"
                        },
                        fontSize = 14.sp
                    )
                    Text(
                        goal.title,
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    color = when (goal.status) {
                        GoalStatus.COMPLETED -> AccentMint.copy(alpha = 0.2f)
                        GoalStatus.FAILED -> AccentRose.copy(alpha = 0.2f)
                        GoalStatus.CANCELLED -> AccentGold.copy(alpha = 0.2f)
                        else -> AccentCyan.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        when (goal.status) {
                            GoalStatus.COMPLETED -> "ပြီးစီးပါပြီ"
                            GoalStatus.FAILED -> "မအောင်မြင်ပါ"
                            GoalStatus.CANCELLED -> "ရပ်တန့်ထားသည်"
                            GoalStatus.EXECUTING -> "ဆောင်ရွက်နေပါသည် (${goal.completedStepsCount}/${goal.totalSteps})"
                            GoalStatus.PLANNING -> "စီစဉ်နေပါသည်…"
                            GoalStatus.PENDING -> "စောင့်ဆိုင်းနေပါသည်"
                        },
                        color = when (goal.status) {
                            GoalStatus.COMPLETED -> AccentMint
                            GoalStatus.FAILED -> AccentRose
                            GoalStatus.CANCELLED -> AccentGold
                            else -> AccentCyan
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { goal.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = when (goal.status) {
                    GoalStatus.COMPLETED -> AccentMint
                    GoalStatus.FAILED -> AccentRose
                    else -> AccentCyan
                },
                trackColor = BorderColor
            )

            // Step list
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                goal.steps.forEach { step ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when (step.status) {
                                    StepStatus.COMPLETED -> "✅"
                                    StepStatus.RUNNING -> "🔄"
                                    StepStatus.FAILED -> "❌"
                                    StepStatus.SKIPPED -> "⏭️"
                                    StepStatus.PENDING -> "⏳"
                                },
                                fontSize = 11.sp
                            )
                            Text(
                                step.title,
                                color = if (step.status == StepStatus.RUNNING) AccentMint else if (step.status == StepStatus.COMPLETED) TextMain else TextSub,
                                fontSize = 11.sp,
                                fontWeight = if (step.status == StepStatus.RUNNING) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!step.outputMessage.isNullOrBlank()) {
                            Text(
                                step.outputMessage.take(28),
                                color = TextSub,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Controls (Cancel / Clear)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (goal.status == GoalStatus.EXECUTING || goal.status == GoalStatus.PLANNING) {
                    Surface(
                        color = AccentRose.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onCancel() }
                    ) {
                        Text(
                            "🛑 ရပ်တန့်မည်",
                            color = AccentRose,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Surface(
                        color = BorderGlow,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onClear() }
                    ) {
                        Text(
                            "ရှင်းလင်းမည်",
                            color = TextSub,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
