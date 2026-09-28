package com.example.voicebrainlive.desktop.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousGoalEngineTest {

    private val dummyExecutor = object : PlatformCommandExecutor {
        val executedList = mutableListOf<DesktopCommand>()
        override suspend fun execute(command: DesktopCommand): CommandResult {
            executedList.add(command)
            return CommandResult(true, "Completed ${command.type}")
        }
    }

    private val engine = AutonomousGoalEngine(dummyExecutor)

    @Test
    fun testDecomposeWirelessAndroidGoal() {
        // The Android wireless-deploy / Notion-sync templates were removed
        // along with the extracted automation and notion plugins. Decompose
        // must no longer emit those dead command types for this intent.
        val intent = "Android ဖုန်းကို wireless ချိတ်ပြီး VoiceBrainLive project ကို build စစ်ပေး၊ Notion မှာ task update ပေးပါ"
        val goal = engine.decomposer.decompose(intent)

        val deadTypes = setOf(
            "check_adb_devices", "run_android_build_test", "sync_task_to_notion",
            "auto_heal_project", "git_commit_fix", "git_repo_status_all"
        )
        assertTrue(goal.steps.none { it.command.type in deadTypes })
    }

    @Test
    fun testDecomposeWorkspaceGoal() {
        val intent = "work mode ဖွင့်ပေးပါ"
        val goal = engine.decomposer.decompose(intent)

        assertTrue(goal.steps.size >= 3)
        assertEquals("open_app", goal.steps[0].command.type)
        assertEquals("VS Code", goal.steps[0].command.target)
    }

    @Test
    fun testExecuteGoalToCompletion() = runBlocking {
        val intent = "Android ဖုန်းကို wireless ချိတ်ပြီး build စစ်ပေးပါ"
        val result = engine.executeGoalFromIntent(intent)

        assertTrue(result.success)
        assertEquals(result.totalSteps, result.completedSteps)
        assertTrue(result.finalReport.contains("အောင်မြင်စွာ ပြီးဆုံးပါပြီ"))
        assertEquals(result.totalSteps, dummyExecutor.executedList.size)

        val active = engine.activeGoal.value
        assertNotNull(active)
        assertEquals(GoalStatus.COMPLETED, active?.status)
        assertEquals(1.0f, active?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testGoalCancellation() = runBlocking {
        val plan = GoalDefinition(
            title = "Test Cancel Goal",
            rawIntent = "test cancel",
            steps = listOf(
                GoalStep(index = 1, title = "Step 1", command = DesktopCommand("open_app", "calc")),
                GoalStep(index = 2, title = "Step 2", command = DesktopCommand("system_status"))
            )
        )

        lateinit var cancelEngine: AutonomousGoalEngine
        val cancelExecutor = object : PlatformCommandExecutor {
            override suspend fun execute(command: DesktopCommand): CommandResult {
                cancelEngine.cancelGoal()
                return CommandResult(true, "Step executed")
            }
        }
        cancelEngine = AutonomousGoalEngine(cancelExecutor)
        val result = cancelEngine.executePlan(plan)

        assertTrue(result.finalReport.contains("ရပ်တန့်လိုက်ပါသည်"))
        assertEquals(GoalStatus.CANCELLED, cancelEngine.activeGoal.value?.status)
    }
}
