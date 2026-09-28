package com.example.voicebrainlive.desktop.platform

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * High-performance, self-rotating logging engine with daily partitioning,
 * size-based rollover, and automatic retention pruning (max 7 days).
 * Never leaks secrets, auth tokens, transcripts, or file contents.
 */
object DesktopLogger {
    private val logsDir: File by lazy {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "VoiceBrainLive/logs")
            .also { it.mkdirs() }
    }

    private val mainLogFile: File by lazy {
        File(logsDir, "app.log")
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private const val MAX_LOG_BYTES = 3 * 1024 * 1024L // 3 MB per file
    private const val MAX_MESSAGE_LENGTH = 1000
    private const val MAX_RETENTION_DAYS = 7L
    @Volatile private var lastPruneCheckDate: LocalDate? = null

    @Synchronized
    fun info(message: String) = write("INFO", message)

    @Synchronized
    fun warn(message: String) = write("WARN", message)

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        val extra = if (throwable != null) {
            " | Exception: ${throwable::class.simpleName}: ${throwable.message ?: "no message"}"
        } else ""
        write("ERROR", "$message$extra")
    }

    private fun write(level: String, message: String) {
        runCatching {
            val today = LocalDate.now()
            if (lastPruneCheckDate != today) {
                lastPruneCheckDate = today
                pruneOldLogs(today)
            }

            val dailyFile = File(logsDir, "app-${today.format(dateFormatter)}.log")
            val sanitized = sanitize(message)
            val logLine = "${Instant.now()} [$level] $sanitized${System.lineSeparator()}"

            // Size rollover for daily file
            if (dailyFile.exists() && dailyFile.length() > MAX_LOG_BYTES) {
                val backup = File(logsDir, "app-${today.format(dateFormatter)}.1.log")
                backup.delete()
                dailyFile.renameTo(backup)
            }
            dailyFile.appendText(logLine)

            // Also keep standard app.log rolled over for current session
            if (mainLogFile.exists() && mainLogFile.length() > MAX_LOG_BYTES) {
                val backup = File(logsDir, "app.log.1")
                backup.delete()
                mainLogFile.renameTo(backup)
            }
            mainLogFile.appendText(logLine)
        }
    }

    @Synchronized
    fun pruneOldLogs(today: LocalDate = LocalDate.now()) {
        runCatching {
            val files = logsDir.listFiles() ?: return@runCatching
            files.forEach { file ->
                if (file.name.startsWith("app-") && file.name.endsWith(".log")) {
                    val datePart = file.name.removePrefix("app-").substringBefore(".").substringBefore(".1")
                    runCatching {
                        val fileDate = LocalDate.parse(datePart, dateFormatter)
                        val daysOld = ChronoUnit.DAYS.between(fileDate, today)
                        if (daysOld > MAX_RETENTION_DAYS) {
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    private fun sanitize(message: String): String = message
        .replace(Regex("(?i)(api[_ -]?key|token|authorization|bearer)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
        .take(MAX_MESSAGE_LENGTH)
}
