package com.example.voicebrainlive.desktop.platform

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Real Screen Vision & Eyes Engine:
 * Full-resolution and optimized screen capture, coordinate transformation from AI bounding boxes,
 * and desktop layout coordinate inference.
 */
class VisionEyesEngine {

    private val robot: Robot by lazy { Robot() }

    /**
     * Gets primary display screen bounds and dimensions.
     */
    fun getScreenDimensions(): Dimension {
        return Toolkit.getDefaultToolkit().screenSize
    }

    /**
     * Captures raw full-resolution screen as BufferedImage.
     */
    fun captureFullScreen(): BufferedImage {
        val size = getScreenDimensions()
        val screenRect = Rectangle(size)
        return robot.createScreenCapture(screenRect)
    }

    /**
     * Captures screen and encodes to Base64 JPEG for AI Vision perception.
     */
    fun captureScreenBase64(scaleDownWidth: Int = 1280): String {
        return runCatching {
            val size = getScreenDimensions()
            val screenRect = Rectangle(size)
            val capture = robot.createScreenCapture(screenRect)

            val targetWidth = scaleDownWidth.coerceAtMost(size.width)
            val targetHeight = (size.height.toDouble() / size.width.toDouble() * targetWidth).toInt()

            val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.drawImage(capture, 0, 0, targetWidth, targetHeight, null)
            g.dispose()

            val baos = ByteArrayOutputStream()
            ImageIO.write(resized, "jpg", baos)
            Base64.getEncoder().encodeToString(baos.toByteArray())
        }.getOrDefault("")
    }

    /**
     * Translates normalized coordinates (0..1000) from Gemini Vision to physical screen pixels (X, Y).
     */
    fun normalizedToScreen(normX: Int, normY: Int): Pair<Int, Int> {
        val dims = getScreenDimensions()
        val screenX = ((normX.coerceIn(0, 1000).toDouble() / 1000.0) * (dims.width - 1)).toInt()
        val screenY = ((normY.coerceIn(0, 1000).toDouble() / 1000.0) * (dims.height - 1)).toInt()
        return Pair(screenX, screenY)
    }

    /**
     * Infers screen coordinates of a desktop icon based on standard Windows desktop icon grid layout.
     * Starts from top-left (approx x=48, y=48) with vertical column stacking (~95px vertical, ~85px horizontal).
     */
    fun estimateDesktopIconPosition(iconIndex: Int, totalDesktopIcons: Int): Pair<Int, Int> {
        val screen = getScreenDimensions()
        val marginX = 48
        val marginY = 48
        val colWidth = 85
        val rowHeight = 98

        // Available vertical space excluding Windows taskbar (assume ~50px taskbar)
        val usableHeight = screen.height - 60 - marginY
        val rowsPerCol = (usableHeight / rowHeight).coerceAtLeast(1)

        val col = iconIndex / rowsPerCol
        val row = iconIndex % rowsPerCol

        val targetX = (marginX + col * colWidth).coerceIn(0, screen.width - 20)
        val targetY = (marginY + row * rowHeight).coerceIn(0, screen.height - 20)

        return Pair(targetX, targetY)
    }

    /**
     * Scans and returns a diagnostic overview of the visual screen state.
     */
    fun getScreenVisionOverview(): String {
        val dims = getScreenDimensions()
        val mousePos = java.awt.MouseInfo.getPointerInfo()?.location
        return "Screen Resolution: ${dims.width}x${dims.height} | Mouse Position: (${mousePos?.x ?: 0}, ${mousePos?.y ?: 0})"
    }
}
