package com.example.voicebrainlive.desktop.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsUiAutomationTest {

    @Test
    fun testResolveQueryCandidatesForBurmeseSynonyms() {
        val uia = WindowsUiAutomation()

        // 1. Search synonym
        val searchCandidates = uia.resolveQueryCandidates("ရှာဖွေရန်")
        assertTrue(searchCandidates.contains("search"))
        assertTrue(searchCandidates.contains("find"))
        assertTrue(searchCandidates.contains("ရှာဖွေရန်"))

        // 2. Save synonym
        val saveCandidates = uia.resolveQueryCandidates("သိမ်းဆည်းရန်")
        assertTrue(saveCandidates.contains("save"))
        assertTrue(saveCandidates.contains("သိမ်းဆည်းရန်"))

        // 3. Close synonym
        val closeCandidates = uia.resolveQueryCandidates("ပိတ်ရန်")
        assertTrue(closeCandidates.contains("close"))
        assertTrue(closeCandidates.contains("cancel"))

        // 4. Confirm / OK synonym
        val okCandidates = uia.resolveQueryCandidates("အတည်ပြုပါ")
        assertTrue(okCandidates.contains("ok"))
        assertTrue(okCandidates.contains("confirm"))

        // 5. English direct query
        val submitCandidates = uia.resolveQueryCandidates("Submit")
        assertEquals(listOf("submit"), submitCandidates)
    }

    @Test
    fun testDpiCoordinateConversionDoesNotThrow() {
        val hands = OSHandsController()

        // Test normal screen coordinates
        val robotPt = hands.toRobotCoordinates(1920, 1080)
        assertTrue(robotPt.x > 0)
        assertTrue(robotPt.y > 0)

        val physicalPt = hands.toPhysicalCoordinates(robotPt.x, robotPt.y)
        assertTrue(physicalPt.x > 0)
        assertTrue(physicalPt.y > 0)

        // Coordinates should be consistent and round-trip closely
        val roundTrip = hands.toRobotCoordinates(physicalPt.x, physicalPt.y)
        assertEquals(robotPt.x.toDouble(), roundTrip.x.toDouble(), 2.0)
        assertEquals(robotPt.y.toDouble(), roundTrip.y.toDouble(), 2.0)
    }
}
