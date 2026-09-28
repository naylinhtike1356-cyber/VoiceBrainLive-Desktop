package com.example.voicebrainlive.desktop.automation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectAutomationTest {

    @Test
    fun testProjectResolverFindsCurrentProject() {
        val resolver = ProjectResolver()
        val resolved = resolver.resolveProjectDirectory("VoiceBrainLive-Desktop")
        assertNotNull("VoiceBrainLive-Desktop directory should be resolved", resolved)
        assertTrue("Resolved directory should exist", resolved?.exists() == true)
    }

    @Test
    fun testCodingTaskFormatterGeneratesPrompt() {
        val formatter = CodingTaskFormatter()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "TestProject")
        val prompt = formatter.formatTaskPrompt(
            projectName = "TestProject",
            projectDirectory = tempDir,
            problemDescription = "Fix websocket timeout and retry logic"
        )
        assertTrue(prompt.contains("Autonomous Bug Fix & Task Request"))
        assertTrue(prompt.contains("Fix websocket timeout and retry logic"))
        assertTrue(prompt.contains("compileKotlin"))
    }

    @Test
    fun testAntigravityBridgeDispatchesTaskFile() {
        val bridge = AntigravityBridge()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "TestTaskProj_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val taskFile = bridge.dispatchTaskToWorkspace(tempDir, "# Sample Task Prompt")
            assertTrue(taskFile.exists())
            assertTrue(taskFile.readText().contains("# Sample Task Prompt"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
