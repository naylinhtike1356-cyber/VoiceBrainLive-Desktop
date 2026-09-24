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
            add(MenuItem("💻 Open VoiceBrainLive  (Ctrl+Alt+W)").also { it.addActionListener { onShow() } })
            add(MenuItem("⚡ Quick Command  (Ctrl+Alt+Enter)").also { it.addActionListener { onCommandMode() } })
            add(MenuItem("🎤 Start/Stop Voice  (Ctrl+Alt+Space)").also { it.addActionListener { onToggleListening() } })
            add(MenuItem("🖼️ Screen Vision  (Ctrl+Alt+V)").also { it.addActionListener { onVisionScan() } })
            add(MenuItem("📋 Read Clipboard  (Ctrl+Alt+C)").also { it.addActionListener { onReadClipboard() } })
            addSeparator()
            add(MenuItem("🤖 Show Desktop Robot").also { it.addActionListener { onShowRobot() } })
            add(MenuItem("🙈 Hide Desktop Robot").also { it.addActionListener { onHideRobot() } })
            addSeparator()
            add(MenuItem("✕ Exit VoiceBrainLive").also { it.addActionListener { remove(); onExit() } })
        }

        trayIcon = TrayIcon(createIcon(), "VoiceBrainLive (Running in Background)", menu).also { icon ->
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
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        // Dark circular badge
        g.color = Color(16, 24, 39)
        g.fillRoundRect(2, 2, size - 4, size - 4, 10, 10)

        // Outer cyan border
        g.color = Color(124, 156, 255)
        g.drawRoundRect(2, 2, size - 5, size - 5, 10, 10)

        // Glowing Mint Core
        g.color = Color(85, 230, 193)
        g.fillOval(10, 10, 12, 12)

        g.dispose()
        return image
    }
}

