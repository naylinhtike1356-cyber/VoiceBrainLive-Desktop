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
    private val onToggleListening: () -> Unit,
    private val onExit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (!SystemTray.isSupported() || trayIcon != null) return

        val menu = PopupMenu().apply {
            add(MenuItem("Show VoiceBrainLive").also { it.addActionListener { onShow() } })
            add(MenuItem("Start/Stop listening").also { it.addActionListener { onToggleListening() } })
            addSeparator()
            add(MenuItem("Exit").also { it.addActionListener { remove(); onExit() } })
        }

        trayIcon = TrayIcon(createIcon(), "VoiceBrainLive", menu).also { icon ->
            icon.isImageAutoSize = true
            icon.addActionListener { onShow() }
            try {
                SystemTray.getSystemTray().add(icon)
            } catch (_: AWTException) {
                trayIcon = null
            }
        }
    }

    fun notify(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    fun remove() {
        trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        trayIcon = null
    }

    private fun createIcon(): BufferedImage {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(24, 24, 28)
        graphics.fillRect(0, 0, 16, 16)
        graphics.color = Color(73, 190, 150)
        graphics.fillOval(3, 3, 10, 10)
        graphics.dispose()
        return image
    }
}
