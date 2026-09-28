package com.example.voicebrainlive.desktop.platform

import java.io.File
import java.time.Instant

/** Minimal local diagnostics log. Secrets, transcripts, and file contents are never written. */
object DesktopLogger {
    private val logFile: File by lazy {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "VoiceBrainLive/logs/app.log")
            .also { it.parentFile.mkdirs() }
    }

    @Synchronized
    fun info(message: String) = write("INFO", message)

    @Synchronized
    fun warn(message: String) = write("WARN", message)

    private fun write(level: String, message: String) {
        runCatching {
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                val backup = File(logFile.parentFile, "app.log.1")
                backup.delete()
                logFile.renameTo(backup)
            }
            logFile.appendText("${Instant.now()} [$level] ${sanitize(message)}${System.lineSeparator()}")
        }
    }

    private fun sanitize(message: String): String = message
        .replace(Regex("(?i)(api[_ -]?key|token|authorization|bearer)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
        .take(MAX_MESSAGE_LENGTH)

    private const val MAX_LOG_BYTES = 2 * 1024 * 1024L
    private const val MAX_MESSAGE_LENGTH = 600
}
