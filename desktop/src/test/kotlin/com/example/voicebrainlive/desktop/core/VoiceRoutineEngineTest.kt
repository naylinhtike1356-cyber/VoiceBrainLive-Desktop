package com.example.voicebrainlive.desktop.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M6: routine trigger matching must reject blank/very short phrases, and
 * executeRoutine must report partial failures instead of always succeeding.
 */
class VoiceRoutineEngineTest {

    private class FakeExecutor(
        private val failTypes: Set<String> = emptySet(),
    ) : PlatformCommandExecutor {
        val executed = mutableListOf<DesktopCommand>()
        override suspend fun execute(command: DesktopCommand): CommandResult {
            executed.add(command)
            return if (command.type in failTypes) {
                CommandResult(false, "simulated failure: ${command.type}")
            } else {
                CommandResult(true, "ok: ${command.type}")
            }
        }
    }

    private fun tempRoutinesFile(): File {
        val f = File.createTempFile("test_voice_routines_", ".json")
        f.deleteOnExit()
        return f
    }

    private fun testRoutine(
        id: String = "r1",
        triggers: List<String> = listOf("work mode start"),
        actions: List<DesktopCommand> = listOf(
            DesktopCommand("open_app", "Chrome"),
            DesktopCommand("volume_up"),
        ),
        volumePercent: Int? = null,
    ) = VoiceRoutine(
        id = id,
        name = "Test Routine",
        triggerPhrases = triggers,
        description = "test",
        actions = actions,
        volumePercent = volumePercent,
        responseBurmese = "စမ်းသပ်မှု ပြီးပါပြီ",
    )

    private fun engineWith(
        executor: PlatformCommandExecutor,
        routine: VoiceRoutine,
    ): VoiceRoutineEngine {
        val engine = VoiceRoutineEngine(executor = executor, routinesFile = tempRoutinesFile())
        // Start from a clean slate: the engine auto-registers default routines
        // when its file is empty, and those defaults would shadow the routine
        // under test in findMatchingRoutine().
        engine.getAllRoutines().forEach { engine.deleteRoutine(it.id) }
        engine.saveCustomRoutine(routine)
        return engine
    }

    @Test
    fun blankPhraseNeverMatches() {
        val engine = engineWith(FakeExecutor(), testRoutine())
        assertNull(engine.findMatchingRoutine(""))
        assertNull(engine.findMatchingRoutine("   "))
    }

    @Test
    fun veryShortPhraseNeverMatches() {
        val engine = engineWith(FakeExecutor(), testRoutine())
        assertNull(engine.findMatchingRoutine("a"))
        assertNull(engine.findMatchingRoutine("ab"))
        // A 3-char phrase may legitimately match; the point is 1-2 chars never do.
    }

    @Test
    fun routineWithBlankTriggerNeverMatches() {
        val engine = engineWith(FakeExecutor(), testRoutine(triggers = listOf("", "   ")))
        // Blank triggers previously matched EVERYTHING via contains("").
        assertNull(engine.findMatchingRoutine("work mode start"))
        assertNull(engine.findMatchingRoutine("anything at all here"))
    }

    @Test
    fun normalPhraseStillMatches() {
        val engine = engineWith(FakeExecutor(), testRoutine())
        val matched = engine.findMatchingRoutine("please work mode start now")
        assertTrue(matched != null)
        assertEquals("r1", matched!!.id)
    }

    @Test
    fun executeRoutineReportsPartialFailure() = runBlocking {
        val executor = FakeExecutor(failTypes = setOf("volume_up"))
        val engine = engineWith(executor, testRoutine())
        val result = engine.executeRoutine("work mode start")
        assertFalse(result.success)
        assertTrue(result.message.contains("volume_up"))
        assertEquals(2, executor.executed.size)
    }

    @Test
    fun executeRoutineCountsVolumeFailure() = runBlocking {
        val executor = FakeExecutor(failTypes = setOf("set_volume"))
        val engine = engineWith(executor, testRoutine(volumePercent = 30))
        val result = engine.executeRoutine("work mode start")
        assertFalse(result.success)
        assertTrue(result.message.contains("set_volume"))
        assertTrue(executor.executed.any { it.type == "set_volume" })
    }

    @Test
    fun executeRoutineSucceedsWhenAllActionsSucceed() = runBlocking {
        val executor = FakeExecutor()
        val engine = engineWith(executor, testRoutine(volumePercent = 30))
        val result = engine.executeRoutine("work mode start")
        assertTrue(result.success)
        assertEquals("စမ်းသပ်မှု ပြီးပါပြီ", result.message)
    }

    @Test
    fun executeRoutineUnknownRoutineFails() = runBlocking {
        val engine = engineWith(FakeExecutor(), testRoutine())
        val result = engine.executeRoutine("no such routine xyz")
        assertFalse(result.success)
    }
}
