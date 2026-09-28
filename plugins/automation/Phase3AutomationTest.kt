package com.example.voicebrainlive.desktop.automation

import com.example.voicebrainlive.desktop.core.OfflineCommandMatcher
import org.junit.Assert.assertEquals
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
    fun testIdeBridgeServiceOpenProjectValidation() {
        val service = IdeBridgeService()
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val res = service.openProject(currentDir.absolutePath, "vscode")
        assertNotNull(res)
        assertTrue(res.message.contains("VS Code") || res.message.contains("Project"))

        val invalidRes = service.openProject("C:\\non_existent_folder_xyz_999", "vscode")
        assertTrue(!invalidRes.success)
    }

    @Test
    fun testWirelessAdbManagerSummaryFormatting() {
        val adbManager = WirelessAdbManager()
        val summary = adbManager.formatDevicesSummary()
        assertNotNull(summary)
        assertTrue(summary.isNotBlank())
    }

    @Test
    fun testWirelessAdbManagerLogcatSafeExecution() {
        val adbManager = WirelessAdbManager()
        val logcatRes = adbManager.getRecentLogcatErrors()
        assertNotNull(logcatRes)
        assertTrue(logcatRes.message.isNotBlank())
    }

    @Test
    fun testWirelessAdbManagerApkInstallMissingFile() {
        val adbManager = WirelessAdbManager()
        val res = adbManager.installApk("C:\\non_existent_test_app.apk")
        assertTrue(!res.success)
        assertTrue(res.message.contains("ရှာမတွေ့ပါ") || res.message.contains("not found"))
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

            val statusRes = repoManager.getRepoStatus(target.name)
            assertNotNull(statusRes)
            assertTrue(statusRes.message.contains("Git Status") || statusRes.message.contains("Branch"))
        }
    }

    @Test
    fun testOfflineCommandMatcherPhase3DeveloperShortcuts() {
        // 1. Static shortcuts
        assertEquals("adb_logcat_crash", OfflineCommandMatcher.match("ဖုန်း crash log စစ်")?.type)
        assertEquals("adb_logcat_crash", OfflineCommandMatcher.match("crash log စစ်")?.type)
        assertEquals("adb_logcat_crash", OfflineCommandMatcher.match("logcat စစ်")?.type)
        assertEquals("adb_device_screenshot", OfflineCommandMatcher.match("ဖုန်း စခရင်ရှော့")?.type)
        assertEquals("adb_device_screenshot", OfflineCommandMatcher.match("phone screenshot")?.type)
        assertEquals("git_status", OfflineCommandMatcher.match("git status စစ်")?.type)
        assertEquals("git_status", OfflineCommandMatcher.match("repo status")?.type)
        assertEquals("git_pull_repo", OfflineCommandMatcher.match("git pull")?.type)
        assertEquals("git_push", OfflineCommandMatcher.match("git push")?.type)
        assertEquals("build_project", OfflineCommandMatcher.match("project build လုပ်")?.type)
        assertEquals("build_project", OfflineCommandMatcher.match("run build")?.type)
        assertEquals("auto_heal_project", OfflineCommandMatcher.match("error ပြင်")?.type)
        assertEquals("open_in_vscode", OfflineCommandMatcher.match("vscode ဖွင့်")?.type)
        assertEquals("open_in_studio", OfflineCommandMatcher.match("android studio ဖွင့်")?.type)

        // 2. Dynamic wireless ADB connect
        val adbCmd = OfflineCommandMatcher.match("wireless adb 192.168.1.105:5555 ချိတ်")
        assertEquals("adb_connect_wireless", adbCmd?.type)
        assertEquals("192.168.1.105:5555", adbCmd?.target)

        // 3. Dynamic git commit
        val commitCmd = OfflineCommandMatcher.match("git commit fix audio jitter buffer")
        assertEquals("git_commit", commitCmd?.type)
        assertEquals("fix audio jitter buffer", commitCmd?.value)

        // 4. Dynamic git switch branch
        val branchCmd = OfflineCommandMatcher.match("branch feature/login-page ပြောင်း")
        assertEquals("git_switch_branch", branchCmd?.type)
        assertEquals("feature/login-page", branchCmd?.value)

        // 5. Dynamic open file in editor
        val fileCmd = OfflineCommandMatcher.match("code မှာ Main.kt ဖွင့်")
        assertEquals("ide_open_file", fileCmd?.type)
        assertEquals("main.kt", fileCmd?.target?.lowercase())
    }
}
