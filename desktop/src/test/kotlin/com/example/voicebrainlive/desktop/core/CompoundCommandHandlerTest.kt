package com.example.voicebrainlive.desktop.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundCommandHandlerTest {

    private val dummyExecutor = object : PlatformCommandExecutor {
        val executedCommands = mutableListOf<DesktopCommand>()
        override suspend fun execute(command: DesktopCommand): CommandResult {
            executedCommands.add(command)
            return CommandResult(true, "Executed ${command.type}")
        }
    }

    private val handler = CompoundCommandHandler(dummyExecutor)

    @Test
    fun testSplitCompoundBurmeseConnectors() {
        val input = "VS Code ဖွင့် ပြီးရင် active app စစ်ပြီးတော့ Notion ဖွင့်ပါ"
        val parts = handler.splitCompoundInstruction(input)
        assertEquals(3, parts.size)
        assertTrue(parts[0].contains("VS Code"))
        assertTrue(parts[1].contains("active app"))
        assertTrue(parts[2].contains("Notion"))
    }

    @Test
    fun testSplitEnglishConnectors() {
        val input = "open chrome and then open spotify then check battery"
        val parts = handler.splitCompoundInstruction(input)
        assertEquals(3, parts.size)
    }

    @Test
    fun testParseJsonCommands() {
        val json = """[{"command_type": "open_app", "target": "VS Code"}, {"command_type": "system_status"}]"""
        val cmds = handler.parseCommands(json)
        assertEquals(2, cmds.size)
        assertEquals("open_app", cmds[0].type)
        assertEquals("VS Code", cmds[0].target)
        assertEquals("system_status", cmds[1].type)
    }

    @Test
    fun testExecuteChainedCommandsSequentially() = runBlocking {
        val commands = listOf(
            DesktopCommand("open_app", "VS Code"),
            DesktopCommand("system_status"),
            DesktopCommand("volume_up")
        )
        val result = handler.executeChainedCommands(commands)
        assertTrue(result.success)
        assertEquals(3, dummyExecutor.executedCommands.size)
        assertEquals("open_app", dummyExecutor.executedCommands[0].type)
        assertEquals("system_status", dummyExecutor.executedCommands[1].type)
        assertEquals("volume_up", dummyExecutor.executedCommands[2].type)
    }
}
