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
        val intent = "Android ဖုန်းကို wireless ချိတ်ပြီး VoiceBrainLive project ကို build စစ်ပေး၊ Notion မှာ task update ပေးပါ"
        val goal = engine.decomposer.decompose(intent)

        assertTrue(goal.steps.size >= 3)
        assertEquals("check_adb_devices", goal.steps[0].command.type)
        assertEquals("run_android_build_test", goal.steps[1].command.type)
        assertEquals("sync_task_to_notion", goal.steps[2].command.type)
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
