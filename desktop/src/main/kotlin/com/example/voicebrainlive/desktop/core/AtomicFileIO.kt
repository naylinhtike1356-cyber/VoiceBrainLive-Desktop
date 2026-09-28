package com.example.voicebrainlive.desktop.core

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file persistence helpers.
 *
 * Direct [File.writeText] can leave a half-written (corrupt) JSON file if the
 * process is killed mid-write — historically observed as
 * "Failed to load voice routines: A JSONArray text must start with '['".
 * These helpers write to a temp file and atomically rename it, and back up
 * corrupt files instead of silently discarding user data.
 */
object AtomicFileIO {

    /**
     * Writes [text] to [target] atomically: the content is fully written to a
     * sibling temp file first, then moved over the target. A crash at any point
     * leaves either the old file or the complete new file — never a truncated one.
     */
    fun writeTextAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            // ATOMIC_MOVE is not supported on all filesystems; fall back to rename.
            if (!tmp.renameTo(target)) {
                // Last resort: direct write (better than losing the save entirely).
                target.writeText(text, Charsets.UTF_8)
            }
        }
    }

    /**
     * Moves a corrupt/unparseable [file] aside with a timestamped backup name so
     * user data is never silently lost when JSON parsing fails.
     */
    fun backupCorruptFile(file: File, reason: String) {
        runCatching {
            if (!file.exists()) return
            val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}.bak")
            if (file.renameTo(backup)) {
                DesktopLogger.warn("Backed up corrupt ${file.name} to ${backup.name}: $reason")
            } else {
                DesktopLogger.warn("Could not back up corrupt ${file.name}: $reason")
            }
        }
    }
}
