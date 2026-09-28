package com.example.voicebrainlive.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.desktop.core.AssistantPhase
import com.example.voicebrainlive.desktop.core.ChatMessage
import com.example.voicebrainlive.desktop.core.MessageSender
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date

private const val GROUP_WINDOW_MS = 60_000L

/**
 * The conversation feed: a LazyColumn with smart auto-scroll (only when the
 * user is already near the end), same-sender grouping within 60 seconds,
 * a streaming caret on the final assistant message during THINKING/SPEAKING,
 * glass-soft bubbles with a 3dp phase-tinted left edge, hover-to-copy, and
 * inline sender + HH:mm headers with command badges (e.g. `⌁ open_app ✓`).
 *
 * Text selection stays on (SelectionContainer), so users can also select
 * across messages; the Copy chip is a convenience shortcut.
 */
@Composable
fun ConversationList(
    messages: List<ChatMessage>,
    phase: AssistantPhase,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Smart auto-scroll: only follow new messages when already near the end.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 3
            }
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && nearEnd) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NilarSpace.Xs),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                val prev = messages.getOrNull(index - 1)
                val grouped = prev != null &&
                    prev.sender == msg.sender &&
                    (msg.timestamp - prev.timestamp) < GROUP_WINDOW_MS
                val isStreaming = index == messages.lastIndex &&
                    msg.sender == MessageSender.ASSISTANT &&
                    (phase == AssistantPhase.THINKING || phase == AssistantPhase.SPEAKING)
                MessageBubble(
                    message = msg,
                    showHeader = !grouped,
                    streaming = isStreaming,
                    phase = phase,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    showHeader: Boolean,
    streaming: Boolean,
    phase: AssistantPhase,
) {
    val isUser = message.sender == MessageSender.USER
    val isSystem = message.sender == MessageSender.SYSTEM
    val accent = when {
        isUser -> NilarColors.Cyan
        isSystem -> NilarColors.Gold
        else -> NilarColors.Mint
    }
    var hovered by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when {
            isUser -> Arrangement.End
            isSystem -> Arrangement.Center
            else -> Arrangement.Start
        },
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Enter -> hovered = true
                                PointerEventType.Exit -> hovered = false
                            }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 3dp phase-tinted left edge.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.8f)),
            )
            Spacer(Modifier.width(NilarSpace.Xxs))
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(NilarRadii.Sm),
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    NilarColors.GlassSoft,
                                    NilarColors.GlassSoft.copy(alpha = 0.7f),
                                ),
                            ),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (showHeader) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Surface(
                                        color = accent.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(
                                            text = when {
                                                isUser -> "YOU"
                                                isSystem -> "SYSTEM"
                                                else -> "VOICEBRAIN"
                                            },
                                            style = NilarType.Caption.copy(
                                                color = accent,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text(
                                        text = SimpleDateFormat("HH:mm").format(Date(message.timestamp)),
                                        style = NilarType.Caption,
                                    )
                                    message.commandType?.let { cmd ->
                                        val mark = when (message.commandSuccess) {
                                            true -> "✓"
                                            false -> "✗"
                                            null -> ""
                                        }
                                        Surface(
                                            color = accent.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                text = "⌁ $cmd $mark".trimEnd(),
                                                style = NilarType.Mono.copy(color = accent, fontSize = 10.sp),
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                // Copy appears on hover.
                                if (hovered) {
                                    Surface(
                                        color = NilarColors.GlassSoft.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable {
                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                                StringSelection(message.text),
                                                null,
                                            )
                                            copied = true
                                        },
                                    ) {
                                        Text(
                                            text = if (copied) "✓ Copied" else "Copy",
                                            style = NilarType.Caption.copy(
                                                color = if (copied) NilarColors.Mint else NilarColors.TextSub,
                                            ),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Row {
                            Text(text = message.text, style = NilarType.Body)
                            if (streaming) {
                                StreamingCaret(color = accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Blinking block caret shown on the final assistant message while generating. */
@Composable
private fun StreamingCaret(color: Color) {
    val blink = rememberInfiniteTransition(label = "caret")
    val alpha by blink.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Text(
        text = "▍",
        color = color.copy(alpha = 0.35f + 0.65f * alpha),
        style = NilarType.Body,
    )
}
