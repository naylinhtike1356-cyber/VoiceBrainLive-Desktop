package com.example.voicebrainlive.desktop.automation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase3AutomationTest {

    @Test
    fun testIdeBridgeServiceStatusCheck() {
        val service = IdeBridgeService()
        val status = service.getIdeStatus(activeProcessName = "code", activeWindowTitle = "Main.kt - VoiceBrainLive-Desktop - Visual Studio Code")
        assertNotNull(status)
        assertTrue(status.isVsCodeRunning)
        val summary = status.toBurmeseSummary()
        assertTrue(summary.contains("IDE / Code Editor အခြေအနေ:"))
        assertTrue(summary.contains("VS Code"))
    }

    @Test
    fun testIdeBridgeServiceHandlesMissingFileSafely() {
        val service = IdeBridgeService()
        val res = service.openFileInEditor("C:\\non_existent_path_file_xyz_123.kt")
        assertTrue(!res.success)
        assertTrue(res.message.contains("ရှာမတွေ့ပါ") || res.message.contains("not found"))
    }

    @Test
    fun testWirelessAdbManagerSummaryFormatting() {
        val adbManager = WirelessAdbManager()
        val summary = adbManager.formatDevicesSummary()
        assertNotNull(summary)
        assertTrue(summary.isNotBlank())
    }

    @Test
    fun testMultiRepoManagerDiscoversRepositoriesSafely() {
        val repoManager = MultiRepoManager()
        val report = repoManager.generateMultiRepoReport()
        assertNotNull(report)
        assertTrue(report.isNotBlank())
    }

    @Test
    fun testMultiRepoInspectCurrentWorkspace() {
        val repoManager = MultiRepoManager()
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val isGit = File(currentDir, ".git").exists() || File(currentDir.parentFile ?: currentDir, ".git").exists()
        if (isGit) {
            val target = if (File(currentDir, ".git").exists()) currentDir else currentDir.parentFile
            val info = repoManager.inspectRepository(target!!)
            if (info != null) {
                assertNotNull(info.currentBranch)
                assertTrue(info.name.isNotBlank())
            }
        }
    }
}
