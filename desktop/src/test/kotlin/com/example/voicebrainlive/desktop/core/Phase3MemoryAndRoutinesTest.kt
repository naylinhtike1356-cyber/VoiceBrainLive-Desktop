package com.example.voicebrainlive.desktop.core

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase3MemoryAndRoutinesTest {

    @Test
    fun testConversationFactExtractorBurmesePreferences() {
        // 1. Browser preference
        val browserFacts = ConversationFactExtractor.extractFacts("ငါ Chrome ပဲ အဓိက သုံးတယ်")
        assertTrue(browserFacts.isNotEmpty(), "Browser fact should be extracted")
        val browser = browserFacts.find { it.key == "preferred_browser" }
        assertNotNull(browser)
        assertEquals("Chrome", browser.value)
        assertEquals(MemoryCategory.PREFERENCE, browser.category)

        // 2. Code Editor / IDE
        val ideFacts = ConversationFactExtractor.extractFacts("ငါ code ရေးရင် VS Code သုံးတယ်")
        assertTrue(ideFacts.isNotEmpty(), "IDE fact should be extracted")
        val ide = ideFacts.find { it.key == "preferred_ide" }
        assertNotNull(ide)
        assertEquals("VS Code", ide.value)

        // 3. Working habit (Night Owl)
        val habitFacts = ConversationFactExtractor.extractFacts("ငါ ညဘက်မှ code ရေးလေ့ရှိတယ်")
        assertTrue(habitFacts.isNotEmpty(), "Working hours habit should be extracted")
        val habit = habitFacts.find { it.key == "working_hours" }
        assertNotNull(habit)
        assertEquals(MemoryCategory.HABIT, habit.category)

        // 4. User Name
        val nameFacts = ConversationFactExtractor.extractFacts("ငါ့နာမည် မိုးမြတ် လို့ ခေါ်ပါတယ်")
        assertTrue(nameFacts.isNotEmpty(), "Name fact should be extracted")
        val name = nameFacts.find { it.key == "user_name" }
        assertNotNull(name)
        assertTrue(name.value.contains("မိုးမြတ်"))
    }

    @Test
    fun testConversationFactExtractorSensitiveProtection() {
        val sensitiveFacts = ConversationFactExtractor.extractFacts("ငါ့ password က secret123 ဖြစ်ပါတယ်")
        assertTrue(sensitiveFacts.all { it.requiresConfirmation && it.isSensitive }, "Sensitive facts must require explicit confirmation")
    }

    @Test
    fun testUserMemoryStoreAutoLearning() {
        val tempMemFile = File.createTempFile("test_mem_", ".json")
        val tempUniFile = File.createTempFile("test_uni_", ".json")
        tempMemFile.deleteOnExit()
        tempUniFile.deleteOnExit()

        val store = UserMemoryStore(tempMemFile, tempUniFile)
        
        // Auto learn from conversation
        val pending = store.learnFromConversation("ငါ Chrome ပဲ အဓိက သုံးတယ်")
        assertTrue(pending.isEmpty(), "Non-sensitive facts should be learned automatically")
        assertEquals("Chrome", store.getFact("preferred_browser"))

        val allUnified = store.getAllUnifiedMemories()
        assertTrue(allUnified.any { it.title.contains("Chrome") })

        val sysContext = store.toSystemInstructionContext()
        assertTrue(sysContext.contains("Chrome"))
        assertTrue(sysContext.contains("USER PERSONAL MEMORY"))
    }

    @Test
    fun testVoiceRoutineEngineMatching() {
        val tempRoutines = File.createTempFile("test_routines_", ".json")
        tempRoutines.deleteOnExit()

        val engine = VoiceRoutineEngine(routinesFile = tempRoutines)
        
        // 1. Match Burmese trigger
        val workRoutine = engine.findMatchingRoutine("အလုပ်စမယ်")
        assertNotNull(workRoutine)
        assertTrue(workRoutine.name.contains("Work Mode"))
        assertEquals(30, workRoutine.volumePercent)
        assertTrue(workRoutine.actions.isNotEmpty())

        // 2. Match English alias
        val studyRoutine = engine.findMatchingRoutine("study mode")
        assertNotNull(studyRoutine)
        assertTrue(studyRoutine.name.contains("Study Mode"))

        // 3. Match Rest Mode
        val restRoutine = engine.findMatchingRoutine("အနားယူမယ်")
        assertNotNull(restRoutine)
        assertTrue(restRoutine.name.contains("Rest Mode"))
    }

    @Test
    fun testOfflineCommandMatcherPhase3VoiceRoutines() {
        val workCmd = OfflineCommandMatcher.match("အလုပ်စမယ်")
        assertEquals("run_voice_routine", workCmd?.type)
        assertEquals("Work Mode (အလုပ်စမယ်)", workCmd?.target)

        val studyCmd = OfflineCommandMatcher.match("study mode")
        assertEquals("run_voice_routine", studyCmd?.type)

        val cleanCmd = OfflineCommandMatcher.match("clean desktop")
        assertEquals("run_voice_routine", cleanCmd?.type)

        val brainCmd = OfflineCommandMatcher.match("ဦးနှောက်မှတ်ဉာဏ်")
        assertEquals("show_neural_brain", brainCmd?.type)
    }
}
