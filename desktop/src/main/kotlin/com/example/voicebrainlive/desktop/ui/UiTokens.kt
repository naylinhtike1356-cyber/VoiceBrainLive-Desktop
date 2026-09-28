package com.example.voicebrainlive.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.desktop.core.AssistantPhase

/**
 * Single-source design tokens for the Nilar AI redesign.
 *
 * The phase-color language is intentionally identical to the pre-redesign
 * mapping (mint = listening, gold = speaking, purple = thinking,
 * cyan = connecting/ready, orange = confirming, red = error) so existing
 * user intuition carries over.
 */
object NilarColors {
    // Abyss gradient background stops.
    val AbyssTop = Color(0xFF111C38)
    val AbyssMid = Color(0xFF0A1022)
    val AbyssBottom = Color(0xFF050811)

    val abyssGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(AbyssTop, AbyssMid, AbyssBottom),
            center = Offset(300f, 150f),
            radius = 1200f,
        )

    // Fake-glass fills. Compose Desktop has no backdrop blur, so depth comes
    // from these translucent fills + the 1px top-edge highlight + hairline
    // border (see GlassCard).
    val Glass = Color(0xB30E1626)
    val GlassSoft = Color(0xB3162238)

    /** 1px top-edge highlight for the fake-glass effect. */
    val topEdgeHighlight: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.0f),
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.0f),
            ),
        )

    val Border = Color(0xFF1E293B)
    val BorderGlow = Color(0xFF334155)

    // Phase language.
    val Mint = Color(0xFF00F5D4)
    val Cyan = Color(0xFF38BDF8)
    val Purple = Color(0xFFA78BFA)
    val Gold = Color(0xFFFBBF24)
    val Orange = Color(0xFFFF9F68)
    val Red = Color(0xFFFF6B6B)
    val Slate = Color(0xFF475569)

    val TextMain = Color(0xFFF8FAFC)
    val TextSub = Color(0xFF94A3B8)

    /** Single-source phase → color mapping. */
    fun phaseColor(phase: AssistantPhase, connected: Boolean = true): Color = when (phase) {
        AssistantPhase.LISTENING -> Mint
        AssistantPhase.SPEAKING -> Gold
        AssistantPhase.THINKING -> Purple
        AssistantPhase.CONNECTING -> Cyan
        AssistantPhase.CONFIRMING -> Orange
        AssistantPhase.ERROR -> Red
        AssistantPhase.READY -> if (connected) Cyan else Slate
    }
}

/** Typography tokens. */
object NilarType {
    val Display = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = NilarColors.TextMain,
    )

    /**
     * Eyebrow: 10sp Bold with +1.6sp tracking.
     * Apply to Latin-caps labels only (e.g. "VOICE COMPANION").
     */
    val Eyebrow = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        color = NilarColors.Cyan,
    )

    val Status = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = NilarColors.TextMain,
    )

    val Body = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        color = NilarColors.TextMain,
    )

    val Caption = TextStyle(
        fontSize = 10.sp,
        color = NilarColors.TextSub,
    )

    val Mono = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = NilarColors.TextMain,
    )
}

/** Spacing scale. */
object NilarSpace {
    val Xxs: Dp = 4.dp
    val Xs: Dp = 8.dp
    val Sm: Dp = 12.dp
    val Md: Dp = 16.dp
    val Lg: Dp = 24.dp
}

/** Corner radii. */
object NilarRadii {
    val Sm: Dp = 8.dp
    val Md: Dp = 14.dp
    val Lg: Dp = 22.dp
}
