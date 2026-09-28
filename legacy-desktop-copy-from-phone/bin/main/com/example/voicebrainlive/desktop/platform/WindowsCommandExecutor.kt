package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.CommandResult
import com.example.voicebrainlive.desktop.core.DesktopCommand
import com.example.voicebrainlive.desktop.core.PlatformCommandExecutor
import java.awt.Desktop
import java.net.URI

/**
 * Safe first version of the Windows command adapter.
 * Add microphone, tray, media, clipboard, and Windows UI Automation adapters here later.
 */
class WindowsCommandExecutor : PlatformCommandExecutor {
    override suspend fun execute(command: DesktopCommand): CommandResult {
        return runCatching {
            when (command.type.lowercase()) {
                "open_url", "open_browser" -> {
                    val url = command.target ?: command.value
                        ?: error("A URL is required")
                    Desktop.getDesktop().browse(URI(url))
                    CommandResult(true, "Opened $url")
                }

                "open_app" -> {
                    val app = command.target ?: command.value
                        ?: error("An application name is required")
                    ProcessBuilder("cmd", "/c", "start", "", app)
                        .start()
                    CommandResult(true, "Opening $app")
                }

                "save_note" -> {
                    // TODO: Replace with a shared NoteRepository/SQLite adapter.
                    CommandResult(true, "Note template received: ${command.target.orEmpty()}")
                }

                else -> CommandResult(
                    success = false,
                    message = "Windows command not implemented yet: ${command.type}",
                )
            }
        }.getOrElse { error ->
            CommandResult(false, error.message ?: "Windows command failed")
        }
    }
}
