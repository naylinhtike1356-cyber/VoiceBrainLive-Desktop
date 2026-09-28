package com.example.voicebrainlive.desktop

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import kotlinx.coroutines.delay
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Ensures only one app instance runs. The first process holds an exclusive
 * file lock; a second process signals the running instance to show its window
 * (via the trigger file + COM AppActivate) and then exits.
 */
class SingleInstanceGuard private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val lockFile: Path,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(lockFile) }
    }

    companion object {
        fun acquire(): SingleInstanceGuard? = runCatching {
            val dir = Path.of(System.getenv("APPDATA"), "VoiceBrainLive")
            Files.createDirectories(dir)
            val lockFile = dir.resolve("instance.lock")
            val channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
            )
            val lock = channel.tryLock() ?: run {
                DesktopLogger.info("SingleInstanceGuard: another instance is running; signaling to show window and exiting secondary process.")
                channel.close()
                signalRunningInstance(dir)
                return null
            }
            DesktopLogger.info("SingleInstanceGuard: acquired lock successfully.")
            runCatching { Files.deleteIfExists(dir.resolve("show_window.trigger")) }
            SingleInstanceGuard(channel, lock, lockFile)
        }.getOrNull()

        private fun signalRunningInstance(dir: Path) {
            runCatching {
                val trigger = dir.resolve("show_window.trigger")
                // Content is a uniqueness token (millis + nano stamp), not just a
                // timestamp: two signals landing inside one filesystem timestamp
                // tick must still be seen as distinct by the watcher.
                val token = "${System.currentTimeMillis()}-${System.nanoTime()}"
                Files.writeString(
                    trigger,
                    token,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            runCatching {
                ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "(New-Object -ComObject WScript.Shell).AppActivate('Nilar AI')").start()
            }
        }
    }
}

/**
 * Watches the show_window.trigger file and invokes [onTrigger] once per
 * distinct signal. Dedupe is by file CONTENT (see [SingleInstanceGuard]),
 * so rapid double-clicks on the desktop shortcut are not coalesced away by
 * coarse filesystem timestamps.
 *
 * Runs until the calling coroutine is cancelled. The polling [delay] stays
 * outside the runCatching so cancellation propagates instead of being
 * swallowed.
 */
suspend fun awaitShowWindowTriggers(onTrigger: () -> Unit) {
    val dir = Path.of(System.getenv("APPDATA"), "VoiceBrainLive")
    val trigger = dir.resolve("show_window.trigger")
    var lastSeenToken: String? = null
    while (true) {
        delay(200)
        runCatching {
            if (Files.exists(trigger)) {
                val token = Files.readString(trigger)
                if (token != lastSeenToken) {
                    lastSeenToken = token
                    DesktopLogger.info("Detected show_window.trigger signal -> bringing window to front")
                    onTrigger()
                }
            }
        }
    }
}
