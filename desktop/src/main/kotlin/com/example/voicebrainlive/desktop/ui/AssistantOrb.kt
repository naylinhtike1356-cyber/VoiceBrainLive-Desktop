package com.example.voicebrainlive.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.voicebrainlive.desktop.core.AssistantPhase
import kotlin.math.cos
import kotlin.math.sin

/**
 * The voice orb — the app's living centerpiece, and the mic button itself.
 *
 * A single Canvas draws everything: 3-layer halo, radial-gradient core,
 * rotating membrane arc, and one motion language per [AssistantPhase]:
 *
 * - READY: cyan, slow 3.2s breathing ring
 * - CONNECTING: rotating dashed orbit
 * - LISTENING: mint expanding rings driven by the mic level; when the mic
 *   hears nothing ([micSilent]) the rings freeze and dim so a dead mic is
 *   visible at a glance
 * - THINKING: purple — 12 orbiting particles + two counter-rotating swirls
 * - SPEAKING: gold radiating waves driven by [playbackLevel]
 * - CONFIRMING: orange triple-blink
 * - ERROR: red 900ms pulse with jitter
 *
 * Total particle count stays at or under 16 per frame. All colors come from
 * [NilarColors.phaseColor] and transition over 600ms. The orb has no emoji,
 * no shadow — depth comes from the halo layers and the core gradient.
 *
 * @param size rendered diameter; defaults to 200dp (216dp when active).
 */
@Composable
fun AssistantOrb(
    phase: AssistantPhase,
    micLevel: Float,
    playbackLevel: Float,
    micSilent: Boolean,
    onClick: () -> Unit,
    size: Dp = 200.dp,
    modifier: Modifier = Modifier,
) {
    val baseColor = NilarColors.phaseColor(phase)
    val animatedColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(600),
        label = "orb-color",
    )
    // Smooth the level signals so the orb breathes with the audio instead of
    // jumping frame to frame.
    val smoothMic by animateFloatAsState(
        targetValue = micLevel.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "orb-mic",
    )
    val smoothPlay by animateFloatAsState(
        targetValue = playbackLevel.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "orb-play",
    )
    val anim = rememberInfiniteTransition(label = "orb")

    val breath by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Reverse),
        label = "orb-breath",
    )
    val orbitSpin by anim.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Restart),
        label = "orb-orbit",
    )
    val ringPhase by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "orb-rings",
    )
    val swirlA by anim.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "orb-swirl-a",
    )
    val swirlB by anim.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2300), RepeatMode.Restart),
        label = "orb-swirl-b",
    )
    val errPhase by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "orb-error",
    )
    val blinkPhase by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "orb-blink",
    )
    val wavePhase by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "orb-wave",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Canvas(modifier = Modifier.size(size)) {
            // NOTE: the composable's `size: Dp` param shadows DrawScope.size,
            // so reach the canvas pixel size via the explicit receiver.
            val pxSize = this.size
            val r = pxSize.minDimension / 2f
            val center = Offset(pxSize.width / 2f, pxSize.height / 2f)
            val c = animatedColor
            val silentListening = micSilent && phase == AssistantPhase.LISTENING

            // ---- Halo: 3 concentric circles, alpha .22 -> .06 ----
            val haloColor = if (silentListening) Color(0xFF64748B) else c
            drawCircle(color = haloColor.copy(alpha = 0.06f), radius = r * 0.98f, center = center)
            drawCircle(color = haloColor.copy(alpha = 0.12f), radius = r * 0.84f, center = center)
            drawCircle(color = haloColor.copy(alpha = 0.22f), radius = r * 0.70f, center = center)

            // ---- Core: radial-gradient disc ----
            val coreScale = when (phase) {
                AssistantPhase.LISTENING -> 1f + 0.25f * smoothMic
                AssistantPhase.READY -> 1f + 0.05f * breath
                AssistantPhase.ERROR -> 1f + 0.08f * errPhase
                else -> 1f
            }
            val coreR = r * 0.52f * coreScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.copy(alpha = 0.85f), Color(0xFF0B1220)),
                    center = center,
                    radius = coreR.coerceAtLeast(1f),
                ),
                radius = coreR,
                center = center,
            )
            // 1px top-edge highlight ring on the core.
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = coreR,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // ---- Per-state motion ----
            when (phase) {
                AssistantPhase.CONNECTING -> {
                    rotate(orbitSpin, center) {
                        drawArc(
                            color = c.copy(alpha = 0.8f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(center.x - r * 0.78f, center.y - r * 0.78f),
                            size = Size(r * 1.56f, r * 1.56f),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                            ),
                        )
                    }
                }
                AssistantPhase.LISTENING -> {
                    if (silentListening) {
                        // Frozen rings: the mic hears nothing.
                        repeat(3) { i ->
                            drawCircle(
                                color = c.copy(alpha = 0.08f),
                                radius = r * (0.60f + 0.10f * i),
                                center = center,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    } else {
                        repeat(3) { i ->
                            val frac = (ringPhase + i / 3f) % 1f
                            drawCircle(
                                color = c.copy(alpha = (1f - frac) * 0.55f),
                                radius = r * (0.55f + 0.35f * frac),
                                center = center,
                                style = Stroke(width = (2f + 6f * smoothMic).dp.toPx()),
                            )
                        }
                    }
                    // Rotating membrane arc around the core.
                    rotate(orbitSpin * 0.6f, center) {
                        drawArc(
                            color = c.copy(alpha = 0.5f),
                            startAngle = -40f,
                            sweepAngle = 120f,
                            useCenter = false,
                            topLeft = Offset(center.x - coreR, center.y - coreR),
                            size = Size(coreR * 2f, coreR * 2f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                AssistantPhase.THINKING -> {
                    // 12 orbiting particles (stays within the <=16 budget).
                    val pr = r * 0.72f
                    repeat(12) { i ->
                        val a = Math.toRadians((swirlA + i * 30f).toDouble())
                        drawCircle(
                            color = c.copy(alpha = 0.35f + 0.45f * ((i % 3) / 2f)),
                            radius = (2.2f + (i % 3)).dp.toPx(),
                            center = Offset(
                                center.x + pr * cos(a).toFloat(),
                                center.y + pr * sin(a).toFloat(),
                            ),
                        )
                    }
                    // Two counter-rotating swirls.
                    rotate(swirlA, center) {
                        drawArc(
                            color = c.copy(alpha = 0.7f),
                            startAngle = -30f, sweepAngle = 140f, useCenter = false,
                            topLeft = Offset(center.x - coreR, center.y - coreR),
                            size = Size(coreR * 2f, coreR * 2f),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    rotate(swirlB, center) {
                        drawArc(
                            color = c.copy(alpha = 0.45f),
                            startAngle = 20f, sweepAngle = 100f, useCenter = false,
                            topLeft = Offset(center.x - coreR * 0.8f, center.y - coreR * 0.8f),
                            size = Size(coreR * 1.6f, coreR * 1.6f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                AssistantPhase.SPEAKING -> {
                    // Gold radiating waves driven by the playback level.
                    repeat(3) { i ->
                        val frac = (wavePhase + i / 3f) % 1f
                        drawCircle(
                            color = c.copy(alpha = (1f - frac) * (0.25f + 0.5f * smoothPlay)),
                            radius = r * (0.55f + 0.35f * frac) * (1f + 0.25f * smoothPlay),
                            center = center,
                            style = Stroke(width = (2f + 5f * smoothPlay).dp.toPx()),
                        )
                    }
                }
                AssistantPhase.CONFIRMING -> {
                    // Orange triple-blink ring.
                    val on = (blinkPhase * 3f).toInt() % 2 == 0
                    drawCircle(
                        color = c.copy(alpha = if (on) 0.9f else 0.25f),
                        radius = coreR * 1.12f,
                        center = center,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                AssistantPhase.ERROR -> {
                    // 900ms pulse with jitter.
                    val jitter = sin(errPhase * Math.PI * 2f * 7f) * 2.dp.toPx()
                    drawCircle(
                        color = c.copy(alpha = 0.75f),
                        radius = r * (0.62f + 0.18f * errPhase) + jitter,
                        center = center,
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
                AssistantPhase.READY -> {
                    // Cyan breathing ring (3.2s cycle).
                    drawCircle(
                        color = c.copy(alpha = 0.25f + 0.25f * breath),
                        radius = r * (0.60f + 0.06f * breath),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            // ---- Mic glyph (center) ----
            drawMicGlyph(center, coreR * 0.62f, Color.White.copy(alpha = 0.92f))
        }
    }
}

/**
 * Vector mic icon drawn with the canvas (no emoji, no resource lookup):
 * rounded capsule + horseshoe arc + stand + base.
 */
private fun DrawScope.drawMicGlyph(center: Offset, s: Float, color: Color) {
    val w = s * 0.56f
    val h = s * 0.96f
    // Capsule.
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(w / 2f, w / 2f),
    )
    // Horseshoe arc under the capsule.
    drawArc(
        color = color,
        startAngle = 25f,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = Offset(center.x - s * 0.64f, center.y - s * 0.28f),
        size = Size(s * 1.28f, s * 1.12f),
        style = Stroke(width = (s * 0.12f).coerceAtLeast(2f), cap = StrokeCap.Round),
    )
    val stroke = (s * 0.12f).coerceAtLeast(2f)
    // Stand stem.
    drawLine(
        color = color,
        start = Offset(center.x, center.y + s * 0.54f),
        end = Offset(center.x, center.y + s * 0.84f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    // Base.
    drawLine(
        color = color,
        start = Offset(center.x - s * 0.36f, center.y + s * 0.90f),
        end = Offset(center.x + s * 0.36f, center.y + s * 0.90f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}
