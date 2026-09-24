package com.example.voicebrainlive.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.example.voicebrainlive.desktop.core.AssistantPhase
import java.awt.GraphicsEnvironment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FloatingRobotWindow(
    runtime: DesktopRuntime,
    visible: Boolean,
    onHide: () -> Unit,
) {
    if (!visible) return

    val state by runtime.assistant.state.collectAsState()
    val liveVolume by runtime.liveVolumeLevel.collectAsState()
    val screenBounds = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val robotWidth = 145
    val robotHeight = 145
    val robotVisualSize = 135
    val safeX = runtime.storedRobotX().coerceIn(
        screenBounds.x,
        (screenBounds.x + screenBounds.width - robotWidth).coerceAtLeast(screenBounds.x),
    )
    val safeY = runtime.storedRobotY().coerceIn(
        screenBounds.y,
        (screenBounds.y + screenBounds.height - robotHeight).coerceAtLeast(screenBounds.y),
    )
    val windowState = rememberWindowState(
        width = robotWidth.dp,
        height = robotHeight.dp,
        position = WindowPosition.Absolute(safeX.dp, safeY.dp),
    )

    val isAlwaysOnTop by remember { mutableStateOf(runtime.robotAlwaysOnTop()) }

    Window(
        onCloseRequest = onHide,
        state = windowState,
        title = "VoiceBrainLive Robot",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = isAlwaysOnTop,
        focusable = true,
    ) {
        val transparent = java.awt.Color(0, 0, 0, 0)
        window.background = transparent
        window.rootPane?.apply {
            isOpaque = false
            background = transparent
        }
        val nativeWindow = window
        val scope = rememberCoroutineScope()
        var inertiaJob by remember { mutableStateOf<Job?>(null) }
        val minX = screenBounds.x
        val minY = screenBounds.y
        val maxX = (screenBounds.x + screenBounds.width - robotWidth).coerceAtLeast(minX)
        val maxY = (screenBounds.y + screenBounds.height - robotHeight).coerceAtLeast(minY)
        fun clampX(value: Int) = value.coerceIn(minX, maxX)
        fun clampY(value: Int) = value.coerceIn(minY, maxY)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nativeWindow, minX, minY, maxX, maxY) {
                    var velocityX = 0f
                    var velocityY = 0f
                    var dragX = 0f
                    var dragY = 0f
                    var lastEventNanos = 0L
                    var totalDragDist = 0f
                    detectDragGestures(
                        onDragStart = {
                            inertiaJob?.cancel()
                            velocityX = 0f
                            velocityY = 0f
                            dragX = nativeWindow.x.toFloat()
                            dragY = nativeWindow.y.toFloat()
                            lastEventNanos = System.nanoTime()
                            totalDragDist = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragDist += abs(dragAmount.x) + abs(dragAmount.y)
                            val now = System.nanoTime()
                            val deltaSeconds = ((now - lastEventNanos) / 1_000_000_000f).coerceIn(.008f, .05f)
                            velocityX = velocityX * .65f + (dragAmount.x / deltaSeconds) * .35f
                            velocityY = velocityY * .65f + (dragAmount.y / deltaSeconds) * .35f
                            lastEventNanos = now
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                            val nextX = clampX(dragX.toInt())
                            val nextY = clampY(dragY.toInt())
                            if (nextX == minX || nextX == maxX) dragX = nextX.toFloat()
                            if (nextY == minY || nextY == maxY) dragY = nextY.toFloat()
                            nativeWindow.setLocation(nextX, nextY)
                        },
                        onDragEnd = {
                            if (totalDragDist < 8f) {
                                // Direct tap on robot -> Toggle voice listening
                                runtime.toggleListening()
                                return@detectDragGestures
                            }
                            val releaseVelocityX = velocityX.coerceIn(-1400f, 1400f)
                            val releaseVelocityY = velocityY.coerceIn(-1400f, 1400f)
                            runtime.saveRobotPosition(nativeWindow.x, nativeWindow.y)
                            if (abs(releaseVelocityX) > 10f || abs(releaseVelocityY) > 10f) {
                                inertiaJob = scope.launch {
                                    var vx = releaseVelocityX
                                    var vy = releaseVelocityY
                                    var x = nativeWindow.x.toFloat()
                                    var y = nativeWindow.y.toFloat()
                                    while (abs(vx) > 8f || abs(vy) > 8f) {
                                        delay(16)
                                        val nextX = clampX((x + vx * .016f).toInt())
                                        val nextY = clampY((y + vy * .016f).toInt())
                                        if (nextX == minX || nextX == maxX) vx = 0f
                                        if (nextY == minY || nextY == maxY) vy = 0f
                                        x = nextX.toFloat()
                                        y = nextY.toFloat()
                                        nativeWindow.setLocation(nextX, nextY)
                                        vx *= .88f
                                        vy *= .88f
                                    }
                                    // Smooth Edge Magnetic Snapping
                                    val finalX = nativeWindow.x
                                    val snapTargetX = when {
                                        finalX - minX < 40 -> minX
                                        maxX - finalX < 40 -> maxX
                                        else -> finalX
                                    }
                                    if (snapTargetX != finalX) {
                                        nativeWindow.setLocation(snapTargetX, nativeWindow.y)
                                    }
                                    runtime.saveRobotPosition(nativeWindow.x, nativeWindow.y)
                                    inertiaJob = null
                                }
                            }
                        },
                        onDragCancel = { inertiaJob?.cancel() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ThreeDRobot(
                phase = state.phase,
                listening = state.isListening,
                visualSize = robotVisualSize,
                liveVolume = liveVolume,
                onClick = runtime::toggleListening,
            )
        }
    }
}

@Composable
private fun ThreeDRobot(
    phase: AssistantPhase,
    listening: Boolean,
    visualSize: Int,
    liveVolume: Float,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "3d-robot-life")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Restart),
        label = "3d-robot-time",
    )
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(150, delayMillis = 2600), RepeatMode.Reverse),
        label = "3d-robot-blink",
    )
    val pulseRing by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "3d-robot-pulse",
    )

    val active = phase == AssistantPhase.SPEAKING || phase == AssistantPhase.LISTENING || listening
    val bob = sin(time * if (active) 1.8f else 0.8f) * if (active) 3.2f else 1.1f
    val armSwing = sin(time * if (phase == AssistantPhase.SPEAKING) 2.4f else 0.9f)
    val legSwing = sin(time * 1.25f)
    val bodyColor = when (phase) {
        AssistantPhase.SPEAKING -> Color(0xFFFBBF24)
        AssistantPhase.LISTENING -> Color(0xFF00F5D4)
        AssistantPhase.THINKING -> Color(0xFFA78BFA)
        AssistantPhase.CONFIRMING -> Color(0xFFFF9F68)
        AssistantPhase.ERROR -> Color(0xFFFB7185)
        AssistantPhase.CONNECTING -> Color(0xFF38BDF8)
        else -> Color(0xFF38BDF8)
    }

    Canvas(
        modifier = Modifier
            .size(visualSize.dp)
            .clickable(onClick = onClick),
    ) {
        // Audio reactive glowing aura ring
        if (active) {
            val dynamicRadius = size.minDimension * (0.42f + liveVolume * 0.25f) * pulseRing
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(bodyColor.copy(alpha = 0.40f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.44f),
                    radius = dynamicRadius
                )
            )
        }

        drawRobot(
            phase = phase,
            bodyColor = bodyColor,
            blink = blink,
            bob = bob,
            armSwing = armSwing,
            legSwing = legSwing,
        )
    }
}

private fun DrawScope.drawRobot(
    phase: AssistantPhase,
    bodyColor: Color,
    blink: Float,
    bob: Float,
    armSwing: Float,
    legSwing: Float,
) {
    val cx = size.width / 2f
    val scale = (size.minDimension / 195f).coerceAtMost(1f)
    val baseY = size.height * 0.44f + bob * scale
    val head = Rect(cx - 42f * scale, baseY - 54f * scale, cx + 42f * scale, baseY + 14f * scale)
    val body = Rect(cx - 32f * scale, baseY + 18f * scale, cx + 32f * scale, baseY + 62f * scale)
    val shadow = Color.Black.copy(alpha = 0.22f)
    val dark = Color(0xFF141F33)
    val face = Color(0xFF071224)
    val white = Color(0xFFF4F8FF)

    // Ground Shadow under feet
    drawOval(
        color = shadow,
        topLeft = Offset(cx - 36f * scale, baseY + 78f * scale),
        size = androidx.compose.ui.geometry.Size(72f * scale, 10f * scale),
    )

    // Antennas
    drawLine(dark, Offset(cx - 20f * scale, baseY - 53f * scale), Offset(cx - 28f * scale, baseY - 70f * scale), 3.5f * scale, StrokeCap.Round)
    drawLine(dark, Offset(cx + 20f * scale, baseY - 53f * scale), Offset(cx + 28f * scale, baseY - 70f * scale), 3.5f * scale, StrokeCap.Round)
    drawCircle(bodyColor, 4.5f * scale, Offset(cx - 28f * scale, baseY - 72f * scale))
    drawCircle(bodyColor, 4.5f * scale, Offset(cx + 28f * scale, baseY - 72f * scale))
    drawCircle(Color.White.copy(alpha = 0.8f), 2f * scale, Offset(cx - 29f * scale, baseY - 73f * scale))
    drawCircle(Color.White.copy(alpha = 0.8f), 2f * scale, Offset(cx + 27f * scale, baseY - 73f * scale))

    // Head Shell (3D metallic gradient)
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD3E2F2), Color(0xFF8BA6C1)), head.topLeft, head.bottomRight),
        topLeft = head.topLeft,
        size = head.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f * scale, 22f * scale),
    )
    // Face Screen
    drawRoundRect(
        color = face,
        topLeft = Offset(head.left + 7f * scale, head.top + 8f * scale),
        size = androidx.compose.ui.geometry.Size(head.width - 14f * scale, head.height - 16f * scale),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f * scale, 16f * scale),
    )

    // Expressive Glowing Eyes
    val eyeHeight = 11f * scale * blink.coerceIn(.08f, 1f)
    val eyeWidth = 16f * scale
    drawRoundRect(bodyColor, Offset(cx - 25f * scale, baseY - 24f * scale - eyeHeight / 2), androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight), androidx.compose.ui.geometry.CornerRadius(8f * scale, 8f * scale))
    drawRoundRect(bodyColor, Offset(cx + 9f * scale, baseY - 24f * scale - eyeHeight / 2), androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight), androidx.compose.ui.geometry.CornerRadius(8f * scale, 8f * scale))
    if (blink > 0.4f) {
        drawCircle(Color.White, 2.5f * scale, Offset(cx - 21f * scale, baseY - 26f * scale))
        drawCircle(Color.White, 2.5f * scale, Offset(cx + 13f * scale, baseY - 26f * scale))
    }

    // Animated Mouth
    val mouthOpen = when (phase) {
        AssistantPhase.SPEAKING -> 6f * scale + kotlin.math.abs(armSwing) * 6f * scale
        AssistantPhase.LISTENING -> 4f * scale
        AssistantPhase.ERROR -> 4f * scale
        else -> 2.5f * scale
    }
    drawRoundRect(
        color = bodyColor.copy(alpha = .92f),
        topLeft = Offset(cx - 11f * scale, baseY - 5f * scale),
        size = androidx.compose.ui.geometry.Size(22f * scale, mouthOpen),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * scale, 8f * scale),
    )

    // Body Torso
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFEBF4FF), bodyColor.copy(alpha = .85f), Color(0xFF6E8CAE)), body.topLeft, body.bottomRight),
        topLeft = body.topLeft,
        size = body.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(15f * scale, 15f * scale),
    )
    // Glowing Power Core on Chest
    drawCircle(bodyColor, 7.5f * scale, Offset(cx, baseY + 38f * scale))
    drawCircle(Color.White.copy(alpha = .9f), 3f * scale, Offset(cx - 1.5f * scale, baseY + 36.5f * scale))

    // Articulated Arms
    val leftArmAngle = -22f + armSwing * if (phase == AssistantPhase.SPEAKING) 24f else 9f
    val rightArmAngle = 22f - armSwing * if (phase == AssistantPhase.SPEAKING) 24f else 9f
    drawLimb(Offset(cx - 32f * scale, baseY + 26f * scale), leftArmAngle, bodyColor, white, dark, scale = scale)
    drawLimb(Offset(cx + 32f * scale, baseY + 26f * scale), rightArmAngle, bodyColor, white, dark, scale = scale)

    // Articulated Legs & Feet
    drawLimb(Offset(cx - 16f * scale, baseY + 60f * scale), legSwing * 12f, bodyColor, white, dark, leg = true, scale = scale)
    drawLimb(Offset(cx + 16f * scale, baseY + 60f * scale), -legSwing * 12f, bodyColor, white, dark, leg = true, scale = scale)

    if (phase == AssistantPhase.ERROR) {
        drawArc(bodyColor, 205f, 130f, false, topLeft = Offset(cx - 52f * scale, baseY - 65f * scale), size = androidx.compose.ui.geometry.Size(104f * scale, 136f * scale), style = Stroke(2.5f * scale))
    }
}

private fun DrawScope.drawLimb(
    anchor: Offset,
    angle: Float,
    accent: Color,
    light: Color,
    dark: Color,
    leg: Boolean = false,
    scale: Float = 1f,
) {
    val radians = angle * PI.toFloat() / 180f
    val length = (if (leg) 19f else 22f) * scale
    val end = Offset(anchor.x + sin(radians) * length, anchor.y + kotlin.math.cos(radians) * length)
    drawLine(dark, anchor, end, 7f * scale, StrokeCap.Round)
    drawLine(light, anchor, end, 4.5f * scale, StrokeCap.Round)
    drawCircle(accent, 5.5f * scale, end)
    drawCircle(light.copy(alpha = .9f), 2.5f * scale, Offset(end.x - 1.2f * scale, end.y - 1.2f * scale))
}
