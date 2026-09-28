package com.example.voicebrainlive.desktop.platform

import java.awt.AWTException
import java.awt.Color
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

class WindowsTrayManager(
    private val onShow: () -> Unit,
    private val onCommandMode: () -> Unit = {},
    private val onShowRobot: () -> Unit = {},
    private val onHideRobot: () -> Unit = {},
    private val onToggleListening: () -> Unit = {},
    private val onVisionScan: () -> Unit = {},
    private val onReadClipboard: () -> Unit = {},
    private val onExit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (!SystemTray.isSupported() || trayIcon != null) return

        val menu = PopupMenu().apply {
            add(MenuItem("💎 Open Nilar AI  (Ctrl+Alt+W)").also { it.addActionListener { onShow() } })
            add(MenuItem("⚡ Quick Command  (Ctrl+Alt+Enter)").also { it.addActionListener { onCommandMode() } })
            add(MenuItem("🎤 Start/Stop Voice  (Ctrl+Alt+Space)").also { it.addActionListener { onToggleListening() } })
            add(MenuItem("🖼️ Screen Vision  (Ctrl+Alt+V)").also { it.addActionListener { onVisionScan() } })
            add(MenuItem("📋 Read Clipboard  (Ctrl+Alt+C)").also { it.addActionListener { onReadClipboard() } })
            addSeparator()
            add(MenuItem("🤖 Show Desktop Robot").also { it.addActionListener { onShowRobot() } })
            add(MenuItem("🙈 Hide Desktop Robot").also { it.addActionListener { onHideRobot() } })
            addSeparator()
            add(MenuItem("✕ Exit Nilar AI").also { it.addActionListener { remove(); onExit() } })
        }

        trayIcon = TrayIcon(createIcon(), "Nilar AI (Running in Background)", menu).also { icon ->
            icon.isImageAutoSize = true
            icon.addActionListener { onShow() }
            try {
                SystemTray.getSystemTray().add(icon)
                DesktopLogger.info("System Tray icon installed.")
            } catch (e: AWTException) {
                DesktopLogger.warn("Failed to install System Tray icon: ${e.message}")
                trayIcon = null
            }
        }
    }

    fun notify(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    fun remove() {
        trayIcon?.let {
            try {
                SystemTray.getSystemTray().remove(it)
            } catch (_: Exception) {}
        }
        trayIcon = null
    }

    private fun createIcon(): BufferedImage {
        val size = 32
        return runCatching {
            val stream = javaClass.classLoader.getResourceAsStream("nilar_ai_logo.png")
            if (stream != null) {
                val raw = javax.imageio.ImageIO.read(stream)
                val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val g = scaled.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(raw, 0, 0, size, size, null)
                g.dispose()
                scaled
            } else null
        }.getOrNull() ?: run {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0, 180, 255)
            g.drawOval(4, 4, size - 8, size - 8)
            g.dispose()
            image
        }
    }
}

