package com.example.voicebrainlive.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.voicebrainlive.desktop.core.AssistantPhase
import kotlin.math.roundToInt

/**
 * Fake-glass container: translucent fill + hairline border + 1px top-edge
 * highlight. Compose Desktop has no backdrop blur, so the glass illusion
 * comes from the highlight/border pair over the aurora background.
 *
 * Never uses Modifier.shadow() — the depth cue is the highlight, not a
 * drop shadow.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = NilarRadii.Md,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(NilarColors.Glass, shape)
            .border(1.dp, NilarColors.BorderGlow.copy(alpha = 0.6f), shape),
    ) {
        Column { content() }
        // 1px top-edge highlight overlay; drawn on top so it never shifts layout.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(NilarColors.topEdgeHighlight),
        )
    }
}

/**
 * Ambient background: two large, heavily-blurred blobs that drift slowly
 * behind the abyss gradient, tinted with the current phase color at ~6%
 * alpha. All motion is ambient and slow — it never competes with the orb.
 */
@Composable
fun AuroraBackground(
    phase: AssistantPhase,
    modifier: Modifier = Modifier,
) {
    val animatedColor by animateColorAsState(
        targetValue = NilarColors.phaseColor(phase),
        animationSpec = tween(600),
        label = "aurora-color",
    )
    val drift = rememberInfiniteTransition(label = "aurora-drift")
    val x1 by drift.animateFloat(
        initialValue = -60f, targetValue = 60f,
        animationSpec = infiniteRepeatable(tween(11000), RepeatMode.Reverse),
        label = "aurora-x1",
    )
    val y1 by drift.animateFloat(
        initialValue = -40f, targetValue = 50f,
        animationSpec = infiniteRepeatable(tween(13000), RepeatMode.Reverse),
        label = "aurora-y1",
    )
    val x2 by drift.animateFloat(
        initialValue = 50f, targetValue = -50f,
        animationSpec = infiniteRepeatable(tween(15000), RepeatMode.Reverse),
        label = "aurora-x2",
    )
    val y2 by drift.animateFloat(
        initialValue = 40f, targetValue = -40f,
        animationSpec = infiniteRepeatable(tween(12000), RepeatMode.Reverse),
        label = "aurora-y2",
    )
    val blobColor = animatedColor.copy(alpha = 0.06f)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(x1.roundToInt(), y1.roundToInt()) }
                .size(460.dp)
                .blur(48.dp)
                .background(blobColor, CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(x2.roundToInt(), y2.roundToInt()) }
                .size(380.dp)
                .blur(48.dp)
                .background(blobColor, CircleShape),
        )
    }
}
