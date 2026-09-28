package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.DesktopCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EyesHandsOmniTest {

    @Test
    fun visionEyesCalculatesCoordinatesCorrectly() {
        val eyes = VisionEyesEngine()
        val dim = eyes.getScreenDimensions()
        val width = dim.width
        val height = dim.height
        assertTrue("Width should be positive", width > 0)
        assertTrue("Height should be positive", height > 0)

        // Coordinate normalization check
        val (originX, originY) = eyes.normalizedToScreen(0, 0)
        assertEquals(0, originX)
        assertEquals(0, originY)

        val (maxX, maxY) = eyes.normalizedToScreen(1000, 1000)
        assertEquals(width - 1, maxX)
        assertEquals(height - 1, maxY)

        val (midX, midY) = eyes.normalizedToScreen(500, 500)
        assertEquals((width - 1) / 2, midX)
        assertEquals((height - 1) / 2, midY)

        val overview = eyes.getScreenVisionOverview()
        assertTrue(overview.contains("Resolution:"))
        assertTrue(overview.contains("Mouse Position:"))
    }

    @Test
    fun visionEyesEstimatesDesktopIcons() {
        val eyes = VisionEyesEngine()
        val (x0, y0) = eyes.estimateDesktopIconPosition(0, 10)
        val (x1, y1) = eyes.estimateDesktopIconPosition(1, 10)

        assertTrue(x0 > 0)
        assertTrue(y0 > 0)
        assertEquals("Icons in same column should have same X", x0, x1)
        assertTrue("Second icon should be below the first", y1 > y0)
    }

    @Test
    fun osHandsCoordinatesAndActions() {
        val hands = OSHandsController()
        val curPos = hands.getCursorPosition()
        val curX = curPos.x
        val curY = curPos.y
        assertTrue(curX >= 0)
        assertTrue(curY >= 0)

        // Mouse smooth move within safe bounds
        val moveResult = hands.mouseMoveSmooth(100, 100, durationMs = 20)
        assertTrue(moveResult.success)
    }

    @Test
    fun omniAppCatalogInitializesAndTracks() {
        val catalog = OmniAppCatalog()
        assertNotNull(catalog.getCatalogStats())
        val searchBlank = catalog.findAndLaunchApp("   ")
        assertTrue(!searchBlank.success)
    }

    @Test
    fun windowsCommandExecutorRoutesHandsAndEyes() = runBlocking {
        val executor = WindowsCommandExecutor()

        // Screen eyes
        val eyeRes = executor.execute(DesktopCommand("screen_eyes"))
        assertTrue(eyeRes.success)
        assertTrue(eyeRes.message.contains("Resolution:"))

        // Mouse move
        val moveRes = executor.execute(DesktopCommand("mouse_move", "200,200"))
        assertTrue(moveRes.success)

        // Show desktop
        val desktopRes = executor.execute(DesktopCommand("show_desktop"))
        assertTrue(desktopRes.success)
    }

    @Test
    fun omniAppCatalogHandlesProcessStateAndToggle() {
        val catalog = OmniAppCatalog()
        val blankToggle = catalog.toggleAppByIcon("   ")
        assertTrue(!blankToggle.success)

        val blankClose = catalog.closeAppByIcon("   ")
        assertTrue(!blankClose.success)

        val notRunning = catalog.isAppRunning("non_existent_app_12345")
        assertTrue(!notRunning)
    }

    @Test
    fun windowsCommandExecutorResolvesSpatialLandmarkClicks() = runBlocking {
        val executor = WindowsCommandExecutor()

        // Center click
        val centerClick = executor.execute(DesktopCommand("mouse_click", "အလယ်"))
        assertTrue(centerClick.success)

        // Top right click
        val topRightClick = executor.execute(DesktopCommand("mouse_click", "ညာဘက်အပေါ်"))
        assertTrue(topRightClick.success)

        // Mouse move to spatial location
        val moveCenter = executor.execute(DesktopCommand("mouse_move", "center"))
        assertTrue(moveCenter.success)
    }
}
