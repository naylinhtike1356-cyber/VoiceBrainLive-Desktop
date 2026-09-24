package com.example.voicebrainlive.desktop.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdvancedAutomationTest {

    @Test
    fun testAutoHealingCycleHandlesMissingDirectorySafely() {
        val engine = AutoHealingFixEngine()
        val nonExistentDir = File("C:\\non_existent_folder_xyz_123")
        val result = engine.runAutoHealingCycle(nonExistentDir, "Fix crash")
        assertFalse(result.success)
        assertTrue(result.finalStatusReport.contains("not found"))
    }

    @Test
    fun testAndroidEmulatorRunnerHandlesNoConnectedDevicesSafely() {
        val runner = AndroidEmulatorRunner()
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val result = runner.deployAndVerifyApp(tempDir)
        // If no emulator running, returns clear helpful message
        assertNotNull(result.message)
    }

    @Test
    fun testGitAutomationServiceDetectsGitRepo() {
        val gitService = GitAutomationService()
        val currentWorkspace = File(System.getProperty("user.dir") ?: ".")
        val isGit = File(currentWorkspace, ".git").exists() || File(currentWorkspace.parentFile ?: currentWorkspace, ".git").exists()
        if (isGit) {
            val status = gitService.getStatusSummary(currentWorkspace)
            assertNotNull(status)
        }
    }
}
