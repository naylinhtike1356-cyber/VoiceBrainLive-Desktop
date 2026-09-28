package com.example.voicebrainlive.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.desktop.DesktopRuntime
import com.example.voicebrainlive.desktop.core.MemoryCategory
import com.example.voicebrainlive.desktop.core.UnifiedMemoryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Cyberpunk / Neural Palette
private val BrainBg = Color(0xFF070B19)
private val CoreCyan = Color(0xFF00E5FF)
private val CoreBlue = Color(0xFF2979FF)
private val AccentPurple = Color(0xFFBD00FF)
private val CardBgGlass = Color(0xEE111827)
private val BorderGlass = Color(0x4438BDF8)

data class VisualBrainNode(
    val item: UnifiedMemoryItem,
    val baseAngle: Float,
    val distance: Float,
    var currentX: Float = 0f,
    var currentY: Float = 0f,
    var radius: Float = 22f,
    val color: Color,
    val glowColor: Color,
    var isSelected: Boolean = false
)

data class SynapticPulse(
    val fromNodeIndex: Int,
    val toCore: Boolean,
    var progress: Float = 0f,
    val speed: Float,
    val color: Color
)

@Composable
fun NeuralBrainScreen(
    runtime: DesktopRuntime,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var selectedCategoryFilter by remember { mutableStateOf<MemoryCategory?>(null) }
    var selectedNodeItem by remember { mutableStateOf<UnifiedMemoryItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var memoriesVersion by remember { mutableStateOf(0) }

    // Navigation & Pan/Zoom Offsets
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(1.0f) }
    var manualRotation by remember { mutableStateOf(0f) }

    // Load memories
    val allMemories = remember(memoriesVersion) {
        runtime.userMemoryStore.getAllUnifiedMemories()
    }
    val filteredMemories = remember(allMemories, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) allMemories else allMemories.filter { it.category == selectedCategoryFilter }
    }

    // Animation Transitions
    val infiniteTransition = rememberInfiniteTransition()
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val autoRotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // Animated Pulses State
    val pulses = remember { mutableStateListOf<SynapticPulse>() }
    LaunchedEffect(filteredMemories) {
        pulses.clear()
        while (isActive) {
            if (filteredMemories.isNotEmpty() && pulses.size < 7) {
                val randIdx = (filteredMemories.indices).random()
                val nodeColor = parseCategoryColor(filteredMemories[randIdx].category)
                pulses.add(
                    SynapticPulse(
                        fromNodeIndex = randIdx,
                        toCore = Math.random() < 0.5,
                        progress = 0f,
                        speed = (0.012f + (Math.random() * 0.015f)).toFloat(),
                        color = nodeColor
                    )
                )
            }
            val iter = pulses.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                p.progress += p.speed
                if (p.progress >= 1.0f) {
                    iter.remove()
                }
            }
            delay(32) // ~30 FPS pulse updates
        }
    }

    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BrainBg)
    ) {
        // 1. Neural Brain Interactive Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        panX += dragAmount.x
                        panY += dragAmount.y
                        manualRotation += dragAmount.x * 0.0015f
                    }
                }
                .pointerInput(filteredMemories, panX, panY, zoomScale, manualRotation, autoRotationAngle) {
                    detectTapGestures { tapOffset ->
                        val centerX = size.width / 2f + panX
                        val centerY = size.height / 2f + panY
                        val currentRotation = autoRotationAngle + manualRotation

                        // Check hit testing for nodes
                        var closestNode: UnifiedMemoryItem? = null
                        var minDistance = Float.MAX_VALUE

                        filteredMemories.forEachIndexed { i, item ->
                            val angleStep = (2 * PI / filteredMemories.size.coerceAtLeast(1)).toFloat()
                            val angle = i * angleStep + currentRotation
                            val distLayer = getCategoryDistance(item.category, i) * zoomScale
                            val nx = centerX + cos(angle) * distLayer
                            val ny = centerY + sin(angle) * distLayer

                            val dx = tapOffset.x - nx
                            val dy = tapOffset.y - ny
                            val dist = sqrt(dx * dx + dy * dy)
                            if (dist < 36f * zoomScale && dist < minDistance) {
                                minDistance = dist
                                closestNode = item
                            }
                        }

                        selectedNodeItem = closestNode
                    }
                }
        ) {
            val centerX = size.width / 2f + panX
            val centerY = size.height / 2f + panY
            val currentRotation = autoRotationAngle + manualRotation

            // Compute current node coordinates
            val nodes = filteredMemories.mapIndexed { i, item ->
                val angleStep = (2 * PI / filteredMemories.size.coerceAtLeast(1)).toFloat()
                val angle = i * angleStep + currentRotation
                val breath = sin(pulsePhase + i * 0.8f) * 10f * zoomScale
                val currentDist = (getCategoryDistance(item.category, i) + breath) * zoomScale
                val nx = centerX + cos(angle) * currentDist
                val ny = centerY + sin(angle) * currentDist
                val nodeColor = parseCategoryColor(item.category)
                val glowColor = nodeColor.copy(alpha = 0.25f)
                VisualBrainNode(
                    item = item,
                    baseAngle = angle,
                    distance = currentDist,
                    currentX = nx,
                    currentY = ny,
                    radius = (if (item.category == MemoryCategory.ROUTINE || item.category == MemoryCategory.PROJECT) 24f else 20f) * zoomScale,
                    color = nodeColor,
                    glowColor = glowColor,
                    isSelected = (selectedNodeItem?.id == item.id)
                )
            }

            // Draw Background Atmospheric Grid / Orbital Concentric Rings
            drawOrbitalRings(centerX, centerY, pulsePhase, zoomScale)

            // Draw Inter-node Synaptic Mesh
            for (i in nodes.indices) {
                val nodeA = nodes[i]
                for (j in i + 1 until nodes.size) {
                    val nodeB = nodes[j]
                    val dx = nodeA.currentX - nodeB.currentX
                    val dy = nodeA.currentY - nodeB.currentY
                    val dist = sqrt(dx * dx + dy * dy)
                    val maxMeshDist = 240f * zoomScale
                    if (dist < maxMeshDist) {
                        val alpha = ((1f - dist / maxMeshDist) * 0.35f).coerceIn(0.04f, 0.35f)
                        drawLine(
                            color = nodeA.color.copy(alpha = alpha),
                            start = Offset(nodeA.currentX, nodeA.currentY),
                            end = Offset(nodeB.currentX, nodeB.currentY),
                            strokeWidth = 1.2f * zoomScale
                        )
                    }
                }
            }

            // Draw Core-to-Node Synaptic Lines
            nodes.forEach { node ->
                drawLine(
                    color = node.color.copy(alpha = 0.28f),
                    start = Offset(centerX, centerY),
                    end = Offset(node.currentX, node.currentY),
                    strokeWidth = 1.6f * zoomScale
                )
            }

            // Draw Synaptic Action Potential Pulses
            pulses.forEach { pulse ->
                if (pulse.fromNodeIndex in nodes.indices) {
                    val node = nodes[pulse.fromNodeIndex]
                    val startX = if (pulse.toCore) node.currentX else centerX
                    val startY = if (pulse.toCore) node.currentY else centerY
                    val endX = if (pulse.toCore) centerX else node.currentX
                    val endY = if (pulse.toCore) centerY else node.currentY

                    val px = startX + (endX - startX) * pulse.progress
                    val py = startY + (endY - startY) * pulse.progress

                    // Pulse outer halo
                    drawCircle(
                        color = pulse.color.copy(alpha = 0.35f),
                        radius = 9f * zoomScale,
                        center = Offset(px, py)
                    )
                    // Pulse core
                    drawCircle(
                        color = Color.White,
                        radius = 4f * zoomScale,
                        center = Offset(px, py)
                    )
                }
            }

            // Draw Central AI Brain Core
            val coreBaseRadius = 38f * zoomScale
            val coreGlowRadius = coreBaseRadius + (16f + sin(pulsePhase) * 8f) * zoomScale

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CoreCyan.copy(alpha = 0.6f), CoreBlue.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(centerX, centerY),
                    radius = coreGlowRadius
                ),
                radius = coreGlowRadius,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = CoreBlue,
                radius = coreBaseRadius,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = CoreCyan.copy(alpha = 0.8f),
                radius = coreBaseRadius + 6f * zoomScale,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.2f * zoomScale)
            )

            // Core Nucleus Text
            val coreText = textMeasurer.measure("🧠 AI CORE", style = TextStyle(color = Color.White, fontSize = (12 * zoomScale).sp, fontWeight = FontWeight.Bold))
            drawText(
                textLayoutResult = coreText,
                topLeft = Offset(centerX - coreText.size.width / 2f, centerY - coreText.size.height / 2f)
            )

            // Draw Memory Synaptic Nodes
            nodes.forEach { node ->
                // Outer glow aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(node.color.copy(alpha = 0.8f), node.glowColor, Color.Transparent),
                        center = Offset(node.currentX, node.currentY),
                        radius = node.radius * 2.2f
                    ),
                    radius = node.radius * 2.2f,
                    center = Offset(node.currentX, node.currentY)
                )

                // Inner solid node
                drawCircle(
                    color = node.color,
                    radius = node.radius,
                    center = Offset(node.currentX, node.currentY)
                )

                // Selection animated halo
                if (node.isSelected) {
                    val selPulse = (6f + sin(pulsePhase * 2f) * 3f) * zoomScale
                    drawCircle(
                        color = Color.White,
                        radius = node.radius + selPulse,
                        center = Offset(node.currentX, node.currentY),
                        style = Stroke(width = 2.5f * zoomScale)
                    )
                }

                // Node Label & Icon
                val label = "${node.item.category.icon} ${node.item.title.take(12)}"
                val labelResult = textMeasurer.measure(
                    text = label,
                    style = TextStyle(
                        color = if (node.isSelected) Color.White else Color(0xFFE2E8F0),
                        fontSize = (11 * zoomScale).sp,
                        fontWeight = if (node.isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                )
                drawText(
                    textLayoutResult = labelResult,
                    topLeft = Offset(node.currentX - labelResult.size.width / 2f, node.currentY + node.radius + 6f * zoomScale)
                )
            }
        }

        // 2. Header Control Overlay
        NeuralBrainHeaderBar(
            totalCount = allMemories.size,
            selectedCategory = selectedCategoryFilter,
            onSelectCategory = { selectedCategoryFilter = it },
            onAddMemory = { showAddDialog = true },
            onResetView = {
                panX = 0f
                panY = 0f
                zoomScale = 1.0f
                manualRotation = 0f
            },
            onZoomIn = { zoomScale = (zoomScale * 1.15f).coerceAtMost(2.5f) },
            onZoomOut = { zoomScale = (zoomScale / 1.15f).coerceAtLeast(0.5f) },
            onClose = onClose
        )

        // 3. Selected Node Detail Floating Card (Bottom-Right or Bottom Center)
        AnimatedVisibility(
            visible = selectedNodeItem != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            selectedNodeItem?.let { item ->
                SelectedNodeCard(
                    item = item,
                    onClose = { selectedNodeItem = null },
                    onDelete = {
                        runtime.userMemoryStore.deleteMemoryItem(item.id)
                        selectedNodeItem = null
                        memoriesVersion++
                    },
                    onRunRoutine = {
                        scope.launch {
                            val res = runtime.routineEngine.executeRoutine(item.title)
                            runtime.assistant.updateResponse(res.message)
                            runtime.assistant.updateStatus(if (res.success) "Routine ပြီးပါပြီ" else "Routine အခက်အခဲ")
                        }
                    }
                )
            }
        }

        // 4. Add Memory Dialog
        if (showAddDialog) {
            AddMemoryDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { category, title, content ->
                    runtime.userMemoryStore.rememberItem(
                        UnifiedMemoryItem(
                            category = category,
                            title = title,
                            content = content,
                            confidencePercent = 100,
                            source = "manual"
                        )
                    )
                    showAddDialog = false
                    memoriesVersion++
                }
            )
        }
    }
}

private fun DrawScope.drawOrbitalRings(centerX: Float, centerY: Float, pulsePhase: Float, zoomScale: Float) {
    val ringDistances = listOf(140f, 210f, 280f, 350f, 430f)
    ringDistances.forEachIndexed { i, dist ->
        val animatedDist = dist * zoomScale + sin(pulsePhase + i) * 3f
        drawCircle(
            color = Color(0xFF1E293B).copy(alpha = 0.35f),
            radius = animatedDist,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f))
        )
    }
}

private fun getCategoryDistance(cat: MemoryCategory, index: Int): Float {
    val base = when (cat) {
        MemoryCategory.FACT -> 140f
        MemoryCategory.PREFERENCE -> 210f
        MemoryCategory.HABIT -> 280f
        MemoryCategory.PROJECT -> 350f
        MemoryCategory.ROUTINE -> 430f
    }
    // Stagger distances slightly for organic neural constellation look
    val stagger = if (index % 2 == 0) 18f else -18f
    return base + stagger
}

private fun parseCategoryColor(cat: MemoryCategory): Color {
    return when (cat) {
        MemoryCategory.FACT -> Color(0xFF00E5FF)       // Neon Cyan
        MemoryCategory.PREFERENCE -> Color(0xFFFFB300) // Electric Amber
        MemoryCategory.HABIT -> Color(0xFFE040FB)      // Vivid Magenta
        MemoryCategory.PROJECT -> Color(0xFF00E676)    // Emerald Green
        MemoryCategory.ROUTINE -> Color(0xFF2979FF)    // Sky Blue
    }
}

@Composable
private fun NeuralBrainHeaderBar(
    totalCount: Int,
    selectedCategory: MemoryCategory?,
    onSelectCategory: (MemoryCategory?) -> Unit,
    onAddMemory: () -> Unit,
    onResetView: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = Color(0xCC0B1120),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0x3338BDF8))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🧠", fontSize = 24.sp)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Neural Brain Memory Matrix",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Surface(
                                color = CoreCyan.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.5.dp, CoreCyan.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    "$totalCount Nodes Connected",
                                    color = CoreCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "မှတ်ဉာဏ်နှင့် အလေ့အထများကို ဦးနှောက်ကွန်ရက်ပုံစံဖြင့် မြင်တွေ့လေ့လာခြင်း",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Zoom In
                    Surface(
                        color = Color(0x2238BDF8),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, Color(0x4438BDF8)),
                        modifier = Modifier.size(32.dp).clickable { onZoomIn() }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("+", color = Color.White, fontSize = 16.sp) }
                    }
                    // Zoom Out
                    Surface(
                        color = Color(0x2238BDF8),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, Color(0x4438BDF8)),
                        modifier = Modifier.size(32.dp).clickable { onZoomOut() }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("-", color = Color.White, fontSize = 16.sp) }
                    }
                    // Reset View
                    Surface(
                        color = Color(0x2238BDF8),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, Color(0x4438BDF8)),
                        modifier = Modifier.size(32.dp).clickable { onResetView() }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("🎯", fontSize = 14.sp) }
                    }

                    // Add Memory Button
                    Button(
                        onClick = onAddMemory,
                        colors = ButtonDefaults.buttonColors(containerColor = CoreBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("+ မှတ်ဉာဏ်အသစ်", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Close View
                    Surface(
                        color = Color(0x33EF4444),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, Color(0x66EF4444)),
                        modifier = Modifier.size(32.dp).clickable { onClose() }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 13.sp) }
                    }
                }
            }

            // Filter Tabs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CategoryFilterPill(
                    title = "အားလုံး (All)",
                    isSelected = selectedCategory == null,
                    color = CoreCyan,
                    onClick = { onSelectCategory(null) }
                )
                MemoryCategory.values().forEach { cat ->
                    CategoryFilterPill(
                        title = "${cat.icon} ${cat.displayName.substringBefore(" ")}",
                        isSelected = selectedCategory == cat,
                        color = parseCategoryColor(cat),
                        onClick = { onSelectCategory(cat) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterPill(
    title: String,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) color.copy(alpha = 0.25f) else Color(0x111E293B),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (isSelected) color else Color(0x33475569)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SelectedNodeCard(
    item: UnifiedMemoryItem,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onRunRoutine: () -> Unit
) {
    val catColor = parseCategoryColor(item.category)
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBgGlass),
        border = BorderStroke(1.5.dp, catColor.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = catColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, catColor.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "${item.category.icon} ${item.category.displayName}",
                        color = catColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = Color(0x22FFFFFF),
                    shape = CircleShape,
                    modifier = Modifier.size(24.dp).clickable { onClose() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✕", color = Color.White, fontSize = 11.sp)
                    }
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Surface(
                color = Color(0x330F172A),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.5.dp, Color(0x3338BDF8)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.content,
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "🎯 Confidence: ${item.confidencePercent}%",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp
                )
                Text(
                    "🕒 ${dateFormat.format(Date(item.timestamp))}",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp
                )
            }

            HorizontalDivider(color = Color(0x22FFFFFF))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.category == MemoryCategory.ROUTINE) {
                    Button(
                        onClick = onRunRoutine,
                        colors = ButtonDefaults.buttonColors(containerColor = CoreBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(34.dp)
                    ) {
                        Text("▶️ Run Routine", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Text("🗑️ Delete", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (MemoryCategory, String, String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(MemoryCategory.FACT) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBB000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = CardBgGlass),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderGlass)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "🧠 မှတ်ဉာဏ် အသစ်ထည့်သွင်းခြင်း",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // Category selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MemoryCategory.values().forEach { cat ->
                        Surface(
                            color = if (selectedCategory == cat) parseCategoryColor(cat).copy(alpha = 0.3f) else Color(0x221E293B),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (selectedCategory == cat) parseCategoryColor(cat) else Color(0x33475569)),
                            modifier = Modifier.clickable { selectedCategory = cat }
                        ) {
                            Text(
                                text = "${cat.icon} ${cat.name.take(4)}",
                                color = if (selectedCategory == cat) Color.White else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("ခေါင်းစဉ် / အမည် (Title)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CoreCyan,
                        unfocusedBorderColor = Color(0x44475569)
                    )
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("မှတ်ဉာဏ် အသေးစိတ် (Content / Detail)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CoreCyan,
                        unfocusedBorderColor = Color(0x44475569)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("မလုပ်တော့ပါ", color = Color(0xFF94A3B8))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank() && content.isNotBlank()) {
                                onAdd(selectedCategory, title.trim(), content.trim())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CoreCyan),
                        enabled = title.isNotBlank() && content.isNotBlank()
                    ) {
                        Text("သိမ်းဆည်းမည်", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
