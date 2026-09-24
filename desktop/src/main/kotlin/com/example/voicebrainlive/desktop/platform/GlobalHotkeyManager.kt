package com.example.voicebrainlive.desktop.platform

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Global hotkey manager providing system-wide keyboard shortcuts:
 * - Ctrl + Alt + Space : Toggle Voice Listening
 * - Ctrl + Alt + Enter : Quick Command Bar & Restore Window
 * - Ctrl + Alt + W     : Toggle Main Window (Show/Hide)
 * - Ctrl + Alt + R     : Toggle 3D Floating Robot Mascot
 * - Ctrl + Alt + V     : Instant Screen Vision
 * - Ctrl + Alt + C     : Read & Process Clipboard
 */
class GlobalHotkeyManager(
    private val onHotkey: () -> Unit,
    private val onCommandHotkey: () -> Unit = {},
    private val onToggleWindow: () -> Unit = {},
    private val onToggleRobot: () -> Unit = {},
    private val onVisionHotkey: () -> Unit = {},
    private val onClipboardHotkey: () -> Unit = {},
) : NativeKeyListener {
    private var registered = false
    private var spaceShortcutDown = false
    private var enterShortcutDown = false
    private var wShortcutDown = false
    private var rShortcutDown = false
    private var vShortcutDown = false
    private var cShortcutDown = false

    init {
        // Suppress noisy JNativeHook logging
        runCatching {
            val hookLogger = Logger.getLogger(GlobalScreen::class.java.`package`.name)
            hookLogger.level = Level.WARNING
            hookLogger.useParentHandlers = false
        }
    }

    fun register() {
        if (registered) return
        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
            registered = true
            DesktopLogger.info("GlobalHotkeyManager registered successfully.")
        } catch (e: NativeHookException) {
            DesktopLogger.warn("Failed to register GlobalHotkey: ${e.message}")
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            GlobalScreen.removeNativeKeyListener(this)
            GlobalScreen.unregisterNativeHook()
        } catch (_: NativeHookException) {
            // Already unregistered
        }
        registered = false
    }

    override fun nativeKeyPressed(event: NativeKeyEvent) {
        val ctrl = event.modifiers and NativeKeyEvent.CTRL_MASK != 0
        val alt = event.modifiers and NativeKeyEvent.ALT_MASK != 0
        if (!ctrl || !alt) return

        when (event.keyCode) {
            NativeKeyEvent.VC_SPACE -> {
                if (!spaceShortcutDown) {
                    spaceShortcutDown = true
                    onHotkey()
                }
            }
            NativeKeyEvent.VC_ENTER -> {
                if (!enterShortcutDown) {
                    enterShortcutDown = true
                    onCommandHotkey()
                }
            }
            NativeKeyEvent.VC_W -> {
                if (!wShortcutDown) {
                    wShortcutDown = true
                    onToggleWindow()
                }
            }
            NativeKeyEvent.VC_R -> {
                if (!rShortcutDown) {
                    rShortcutDown = true
                    onToggleRobot()
                }
            }
            NativeKeyEvent.VC_V -> {
                if (!vShortcutDown) {
                    vShortcutDown = true
                    onVisionHotkey()
                }
            }
            NativeKeyEvent.VC_C -> {
                if (!cShortcutDown) {
                    cShortcutDown = true
                    onClipboardHotkey()
                }
            }
        }
    }

    override fun nativeKeyReleased(event: NativeKeyEvent) {
        when (event.keyCode) {
            NativeKeyEvent.VC_SPACE -> spaceShortcutDown = false
            NativeKeyEvent.VC_ENTER -> enterShortcutDown = false
            NativeKeyEvent.VC_W -> wShortcutDown = false
            NativeKeyEvent.VC_R -> rShortcutDown = false
            NativeKeyEvent.VC_V -> vShortcutDown = false
            NativeKeyEvent.VC_C -> cShortcutDown = false
        }
    }

    override fun nativeKeyTyped(event: NativeKeyEvent) = Unit
}
