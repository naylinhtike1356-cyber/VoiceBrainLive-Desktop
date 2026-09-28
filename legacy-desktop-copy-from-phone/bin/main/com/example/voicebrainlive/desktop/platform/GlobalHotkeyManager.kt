package com.example.voicebrainlive.desktop.platform

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener

/** Default shortcut: Ctrl + Alt + Space. */
class GlobalHotkeyManager(
    private val onHotkey: () -> Unit,
) : NativeKeyListener {
    private var registered = false

    fun register() {
        if (registered) return
        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
            registered = true
        } catch (_: NativeHookException) {
            // The application can continue with the tray button if the hook is unavailable.
        }
    }

    fun unregister() {
        if (!registered) return
        GlobalScreen.removeNativeKeyListener(this)
        try {
            GlobalScreen.unregisterNativeHook()
        } catch (_: NativeHookException) {
            // Already unregistered by the native hook library.
        }
        registered = false
    }

    override fun nativeKeyPressed(event: NativeKeyEvent) {
        val ctrl = event.modifiers and NativeKeyEvent.CTRL_MASK != 0
        val alt = event.modifiers and NativeKeyEvent.ALT_MASK != 0
        if (ctrl && alt && event.keyCode == NativeKeyEvent.VC_SPACE) {
            onHotkey()
        }
    }

    override fun nativeKeyReleased(event: NativeKeyEvent) = Unit

    override fun nativeKeyTyped(event: NativeKeyEvent) = Unit
}
