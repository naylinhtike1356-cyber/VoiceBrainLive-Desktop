package com.example.voicebrainlive.desktop.core

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Verifies crash-safe persistence: atomic writes never leave half-written
 * files, and corrupt JSON is backed up instead of silently discarded.
 */
class AtomicFileIOTest {

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "atomic-io-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun testWriteTextAtomic_roundTrip() {
        val dir = tempDir()
        val target = File(dir, "data.json")
        AtomicFileIO.writeTextAtomic(target, """{"a":1}""")
        assertEquals("""{"a":1}""", target.readText(Charsets.UTF_8))
        // Temp file must not linger after a successful write.
        assertFalse(File(dir, "data.json.tmp").exists())
    }

    @Test
    fun testWriteTextAtomic_overwritesExistingAtomically() {
        val dir = tempDir()
        val target = File(dir, "data.json")
        target.writeText("old-content")
        AtomicFileIO.writeTextAtomic(target, "new-content")
        assertEquals("new-content", target.readText(Charsets.UTF_8))
        assertFalse(File(dir, "data.json.tmp").exists())
    }

    @Test
    fun testBackupCorruptFile_movesAsideWithTimestamp() {
        val dir = tempDir()
        val corrupt = File(dir, "routines.json")
        corrupt.writeText("{not valid json")
        AtomicFileIO.backupCorruptFile(corrupt, "parse error")
        assertFalse("corrupt file should be moved aside", corrupt.exists())
        val backups = dir.listFiles { f -> f.name.startsWith("routines.json.corrupt-") }
            ?: emptyArray()
        assertEquals(1, backups.size)
        assertEquals("{not valid json", backups[0].readText(Charsets.UTF_8))
    }

    @Test
    fun testBackupCorruptFile_missingFileIsNoOp() {
        val dir = tempDir()
        val missing = File(dir, "does-not-exist.json")
        // Must not throw.
        AtomicFileIO.backupCorruptFile(missing, "parse error")
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
