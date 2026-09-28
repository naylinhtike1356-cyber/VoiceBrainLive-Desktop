package com.example.voicebrainlive.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.delay

private object RobotAssets

@Composable
fun FloatingRobotWindow(
    runtime: DesktopRuntime,
    visible: Boolean,
    onHide: () -> Unit,
) {
    if (!visible) return

    val state by runtime.assistant.state.collectAsState()
    val windowState = rememberWindowState(
        width = 370.dp,
        height = 205.dp,
        position = WindowPosition.PlatformDefault,
    )
    var patrol by remember { mutableStateOf(false) }
    var patrolX by remember { mutableStateOf(80) }
    var direction by remember { mutableStateOf(1) }

    LaunchedEffect(patrol) {
        while (patrol) {
            val bounds = 1200
            patrolX += direction * 8
            if (patrolX >= bounds || patrolX <= 20) direction *= -1
            windowState.position = WindowPosition.Absolute(patrolX.dp, 34.dp)
            delay(55)
        }
    }

    Window(
        onCloseRequest = onHide,
        state = windowState,
        title = "VoiceBrainLive Companion",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
    ) {
        val nativeWindow = window
        Box(modifier = Modifier.pointerInput(nativeWindow) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                nativeWindow.setLocation(nativeWindow.x + dragAmount.x.toInt(), nativeWindow.y + dragAmount.y.toInt())
            }
        }) {
            CompanionSurface(
                status = state.status,
                listening = state.isListening,
                connected = state.isConnected,
                patrol = patrol,
                onTalk = { runtime.toggleListening() },
                onPatrol = { patrol = !patrol },
                onHide = onHide,
            )
        }
    }
}

@Composable
private fun CompanionSurface(
    status: String,
    listening: Boolean,
    connected: Boolean,
    patrol: Boolean,
    onTalk: () -> Unit,
    onPatrol: () -> Unit,
    onHide: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "companion")
    val pulse by transition.animateFloat(
        initialValue = .85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val accent = when { listening -> Color(0xFF55F6FF); connected -> Color(0xFF8DCEFF); else -> Color(0xFFFFB86B) }
    val idleRobot = remember { RobotAssets::class.java.getResourceAsStream("/robot_idle.png")!!.use { loadImageBitmap(it) } }
    val listeningRobot = remember { RobotAssets::class.java.getResourceAsStream("/robot_listening.png")!!.use { loadImageBitmap(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF0141B2D))
            .border(1.dp, accent.copy(alpha = .65f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("VoiceBrainLive", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("↔", color = if (patrol) accent else Color(0xFF93A4C4), modifier = Modifier.clickable(onClick = onPatrol))
            Text("×", color = Color(0xFFBFC9DE), modifier = Modifier.clickable(onClick = onHide))
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RobotAvatar(accent = accent, listening = listening, pulse = pulse, bitmap = if (listening) listeningRobot else idleRobot, onClick = onTalk)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        listening -> "နားထောင်နေပါတယ်…"
                        connected -> "မေးလို့ရပါတယ်"
                        else -> "Robot ကိုနှိပ်ပြီး စတင်ပါ"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                WaveVisualizer(accent = accent, active = listening || connected)
                Spacer(Modifier.height(5.dp))
                Text(status.take(52), color = Color(0xFFB9C7E2), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RobotAvatar(accent: Color, listening: Boolean, pulse: Float, bitmap: androidx.compose.ui.graphics.ImageBitmap, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E2B40))
            .border(2.dp, accent.copy(alpha = .75f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(bitmap = bitmap, contentDescription = "VoiceBrainLive robot", modifier = Modifier.size(106.dp))
        if (listening) {
            Box(Modifier.size(106.dp).border(2.dp, accent.copy(alpha = .55f), CircleShape))
        }
    }
}

@Composable
private fun WaveVisualizer(accent: Color, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(.55f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "wave-phase")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(18) { index ->
            val height = if (active) (8 + ((index * 7) % 18) * phase).dp else 5.dp
            Box(Modifier.size(3.dp, height).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = if (active) .9f else .35f)))
        }
    }
}
