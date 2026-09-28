package com.example.voicebrainlive.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.voicebrainlive.desktop.core.AssistantPhase
import kotlinx.coroutines.launch

private val Accent = Color(0xFF63E6BE)
private val AccentBlue = Color(0xFF7AA7FF)
private val Background = Color(0xFF0B0F14)
private val Panel = Color(0xFF151B23)
private val PanelSoft = Color(0xFF1C2530)
private val Muted = Color(0xFF9BA8B7)

fun main() = application {
    val windowState = rememberWindowState(width = 980.dp, height = 720.dp)
    val runtime = remember { DesktopRuntime(::exitApplication) }
    var robotVisible by remember { mutableStateOf(runtime.storedRobotVisible()) }

    DisposableEffect(runtime) {
        runtime.start()
        onDispose { runtime.close() }
    }

    FloatingRobotWindow(
        runtime = runtime,
        visible = robotVisible,
        onHide = {
            robotVisible = false
            runtime.setRobotVisible(false)
        },
    )

    Window(
        onCloseRequest = {
            runtime.close()
            exitApplication()
        },
        title = "VoiceBrainLive — Windows Assistant",
        state = windowState,
    ) {
        VoiceBrainDesktopApp(
            runtime = runtime,
            robotVisible = robotVisible,
            onRobotVisibilityChange = { robotVisible = it; runtime.setRobotVisible(it) },
        )
    }
}

@Composable
private fun VoiceBrainDesktopApp(
    runtime: DesktopRuntime,
    robotVisible: Boolean,
    onRobotVisibilityChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(runtime.storedApiKey()) }
    var notionToken by remember { mutableStateOf(runtime.storedNotionToken()) }
    var notionParentPageId by remember { mutableStateOf(runtime.storedNotionParentPageId()) }
    var showApiKey by remember { mutableStateOf(false) }
    var showNotionToken by remember { mutableStateOf(false) }
    val state by runtime.assistant.state.collectAsState()
    val scroll = rememberScrollState()

    fun send(text: String) {
        if (text.isBlank()) return
        scope.launch { runtime.assistant.submitText(text) }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            if (showSettings) {
                SettingsPanel(
                    apiKey = apiKey,
                    showApiKey = showApiKey,
                    notionToken = notionToken,
                    notionParentPageId = notionParentPageId,
                    showNotionToken = showNotionToken,
                    robotVisible = robotVisible,
                    status = state.status,
                    onApiKeyChange = { apiKey = it },
                    onNotionTokenChange = { notionToken = it },
                    onNotionParentPageChange = { notionParentPageId = it },
                    onToggleVisibility = { showApiKey = !showApiKey },
                    onToggleNotionVisibility = { showNotionToken = !showNotionToken },
                    onRobotVisibilityChange = onRobotVisibilityChange,
                    onSave = {
                        runtime.saveApiKey(apiKey)
                        runtime.saveNotionSettings(notionToken, notionParentPageId)
                        showSettings = false
                    },
                    onClear = {
                        apiKey = ""
                        runtime.clearApiKey()
                    },
                    onTestGeminiKey = {
                        scope.launch {
                            val result = runtime.testGeminiKey(apiKey)
                            runtime.assistant.updateResponse(result.getOrElse { it.message ?: "Gemini API key test မအောင်မြင်ပါ" })
                            runtime.assistant.updateStatus(if (result.isSuccess) "Gemini API key အလုပ်လုပ်ပါတယ်" else "Gemini API key မအလုပ်လုပ်ပါ")
                        }
                    },
                    onTestNotion = {
                        scope.launch {
                            val result = runtime.testNotionConnection()
                            runtime.assistant.updateResponse(result.message)
                            runtime.assistant.updateStatus(if (result.success) "Notion connected" else "Notion connection failed")
                        }
                    },
                    onBack = { showSettings = false },
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 34.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("VoiceBrainLive", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                            Text("Your calm Windows computer assistant", color = Muted)
                        }
                        OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
                    }

                    StatusPill(state.status, state.isConnected, state.isListening, state.phase)
                    Text(phaseLabel(state.phase), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    RobotCompanion(isActive = state.phase != AssistantPhase.READY && state.phase != AssistantPhase.ERROR, onClick = runtime::toggleListening)
                    MicrophoneButton(isActive = state.isListening, onClick = runtime::toggleListening)
                    Text("Ctrl + Alt + Space", color = Muted, fontSize = 12.sp)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Conversation", style = MaterialTheme.typography.titleMedium, color = Accent)
                                Text(if (state.isConnected) "Gemini connected" else "Not connected", color = Muted)
                            }
                            ConversationLine("You", state.transcript.ifBlank { "အသံနဲ့ မေးလိုတာကို ပြောပါ။" })
                            ConversationLine("VoiceBrainLive", state.response.ifBlank { "ကွန်ပျူတာအသုံးပြုနည်း မေးနိုင်ပါတယ်။" })
                        }
                    }

                    if (state.history.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Recent actions", color = Accent, style = MaterialTheme.typography.titleMedium)
                                state.history.takeLast(5).reversed().forEach { entry ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(entry.command, color = Color.White, modifier = Modifier.weight(1f))
                                        Text(if (entry.success) "OK" else "Cancelled/Failed", color = if (entry.success) Accent else Color(0xFFFF9F68), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { send("Computer status ကို ပြောပြပါ") }) { Text("Computer status") }
                    }

                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("စာနဲ့လည်း မေးနိုင်ပါတယ်") },
                        placeholder = { Text("ဥပမာ — Downloads ထဲမှာ report ရှာပါ") },
                        minLines = 2,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(enabled = input.isNotBlank(), onClick = { val message = input; input = ""; send(message) }) { Text("Send") }
                    }
                    Text("Phone calls, SMS နှင့် Android-only controls များ မပါဝင်ပါ။", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    apiKey: String,
    notionToken: String,
    notionParentPageId: String,
    showApiKey: Boolean,
    showNotionToken: Boolean,
    robotVisible: Boolean,
    status: String,
    onApiKeyChange: (String) -> Unit,
    onNotionTokenChange: (String) -> Unit,
    onNotionParentPageChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleNotionVisibility: () -> Unit,
    onRobotVisibilityChange: (Boolean) -> Unit,
    onTestGeminiKey: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onTestNotion: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(34.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Gemini API ချိတ်ဆက်မှုကို စီမံရန်", color = Muted)
            }
            TextButton(onClick = onBack) { Text("Back") }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Gemini API Key", style = MaterialTheme.typography.titleLarge, color = Accent)
                Text("API key ကို ဒီ Windows user အတွက် local settings ထဲမှာ သိမ်းပါမယ်။ Source code ထဲ မထည့်ပါ။", color = Muted)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Paste Gemini API key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onToggleVisibility) { Text(if (showApiKey) "Hide key" else "Show key") }
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                    OutlinedButton(onClick = onTestGeminiKey) { Text("Test Gemini Key") }
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) { Text("Save key") }
                }
                Text(status, color = Muted)
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Notion Integration", style = MaterialTheme.typography.titleLarge, color = Accent)
                Text("Notion internal integration token နဲ့ page ID ထည့်ပြီး voice နဲ့ note/search စီမံနိုင်ပါတယ်။", color = Muted)
                OutlinedTextField(
                    value = notionToken,
                    onValueChange = onNotionTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Notion integration token") },
                    visualTransformation = if (showNotionToken) VisualTransformation.None else PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = notionParentPageId,
                    onValueChange = onNotionParentPageChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Parent page ID") },
                    placeholder = { Text("Notion page ID for new notes") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onToggleNotionVisibility) { Text(if (showNotionToken) "Hide token" else "Show token") }
                    OutlinedButton(onClick = onTestNotion) { Text("Test Notion") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Desktop Robot", style = MaterialTheme.typography.titleLarge, color = Accent)
                    Text("App window ပိတ်ထားလည်း screen ပေါ်မှာ robot လှုပ်ရှားနေစေမည်။ Robot ကို click လုပ်ပြီး voice conversation စနိုင်သည်။", color = Muted)
                }
                Button(
                    onClick = { onRobotVisibilityChange(!robotVisible) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (robotVisible) Accent else PanelSoft,
                        contentColor = if (robotVisible) Color.Black else Color.White,
                    ),
                ) { Text(if (robotVisible) "Robot ပြနေသည်" else "Robot ဖျောက်ထားသည်") }
            }
        }

        Text("သိမ်းပြီးနောက် Home သို့ပြန်သွားပြီး Connect Gemini ကိုနှိပ်ပါ။ API key မရှိလျှင် voice session မချိတ်နိုင်ပါ။ Notion အတွက် token နဲ့ parent page ID ကို Save key နှိပ်ပြီး သိမ်းပါ။ Robot setting က Windows user အတွက် အမြဲသိမ်းထားပါမည်။", color = Muted)
    }
}

@Composable
private fun RobotCompanion(isActive: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "robot-walk")
    val walkX by transition.animateFloat(
        initialValue = -68f,
        targetValue = 68f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "robot-walk-x",
    )
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "robot-bob",
    )
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(116.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .graphicsLayer { translationX = walkX; translationY = bob }
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF214D43) else PanelSoft, CircleShape)
                .border(1.dp, if (isActive) Accent else Color(0xFF334252), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(78.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val glow = if (isActive) Accent else AccentBlue
                drawRoundRect(
                    color = glow,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.25f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                )
                drawCircle(Color(0xFF0B0F14), radius = 5.dp.toPx(), center = Offset(c.x - 11.dp.toPx(), c.y - 3.dp.toPx()))
                drawCircle(Color(0xFF0B0F14), radius = 5.dp.toPx(), center = Offset(c.x + 11.dp.toPx(), c.y - 3.dp.toPx()))
                drawLine(Color(0xFF0B0F14), Offset(c.x - 9.dp.toPx(), c.y + 14.dp.toPx()), Offset(c.x + 9.dp.toPx(), c.y + 14.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(glow, Offset(c.x, size.height * 0.25f), Offset(c.x, size.height * 0.08f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(glow, radius = 4.dp.toPx(), center = Offset(c.x, size.height * 0.06f))
            }
        }
    }
}

@Composable
private fun MicrophoneButton(isActive: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = if (isActive) 1.08f else 0.98f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "mic-pulse-scale",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Box(
            modifier = Modifier.size((196 * pulse).dp).clip(CircleShape).background(if (isActive) Color(0xFF214D43) else PanelSoft).border(1.dp, if (isActive) Accent else Color(0xFF334252), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(98.dp).clickable(onClick = onClick)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val micWidth = size.width * 0.28f
                val micHeight = size.height * 0.48f
                drawRoundRect(if (isActive) Accent else AccentBlue, topLeft = Offset(center.x - micWidth / 2, center.y - micHeight / 2), size = androidx.compose.ui.geometry.Size(micWidth, micHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(micWidth / 2, micWidth / 2))
                drawArc(if (isActive) Accent else AccentBlue, 25f, 130f, false, topLeft = Offset(center.x - size.width * 0.32f, center.y - size.height * 0.14f), size = androidx.compose.ui.geometry.Size(size.width * 0.64f, size.height * 0.56f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                drawLine(if (isActive) Accent else AccentBlue, Offset(center.x, center.y + size.height * 0.42f), Offset(center.x, center.y + size.height * 0.27f), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(if (isActive) Accent else AccentBlue, Offset(center.x - size.width * 0.18f, center.y + size.height * 0.45f), Offset(center.x + size.width * 0.18f, center.y + size.height * 0.45f), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun StatusPill(status: String, connected: Boolean, listening: Boolean, phase: AssistantPhase) {
    val color = when (phase) {
        AssistantPhase.LISTENING -> Accent
        AssistantPhase.SPEAKING -> Color(0xFFFFC857)
        AssistantPhase.THINKING, AssistantPhase.CONNECTING -> AccentBlue
        AssistantPhase.CONFIRMING -> Color(0xFFFF9F68)
        AssistantPhase.ERROR -> Color(0xFFFF6B6B)
        AssistantPhase.READY -> if (connected) AccentBlue else Color(0xFF495564)
    }
    Text(status, color = Color.Black, modifier = Modifier.background(color, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
}

private fun phaseLabel(phase: AssistantPhase): String = when (phase) {
    AssistantPhase.READY -> "ပြောရန် robot သို့မဟုတ် microphone ကို နှိပ်ပါ"
    AssistantPhase.CONNECTING -> "Gemini သို့ ချိတ်ဆက်နေပါတယ်…"
    AssistantPhase.LISTENING -> "နားထောင်နေပါတယ် — ပြောနိုင်ပါပြီ"
    AssistantPhase.THINKING -> "စဉ်းစားပြီး လုပ်ဆောင်နေပါတယ်…"
    AssistantPhase.SPEAKING -> "ပြန်လည်ပြောဆိုနေပါတယ်…"
    AssistantPhase.CONFIRMING -> "အတည်ပြုချက်ကို အသံနဲ့ စောင့်နေပါတယ်…"
    AssistantPhase.ERROR -> "ပြဿနာရှိပါတယ် — status ကို စစ်ပါ"
}

@Composable
private fun ConversationLine(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Accent, style = MaterialTheme.typography.labelLarge)
        Text(text, color = Color.White)
    }
}
