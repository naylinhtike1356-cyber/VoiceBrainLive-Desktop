package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.CommandResult
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Real Physical OS Hands Controller:
 * Direct mouse manipulation (smooth human-like movement, clicks, double-clicks, drags, scrolls),
 * DPI-aware screen coordinate calibration, and physical keyboard interaction via java.awt.Robot
 * and Win32 interfaces.
 */
class OSHandsController {

    private val robot: Robot by lazy {
        Robot().apply {
            isAutoWaitForIdle = true
            autoDelay = 10
        }
    }

    /**
     * Primary display DPI scaling factors (e.g. 1.25 for 125%, 1.50 for 150%).
     */
    val dpiScaleX: Double by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .defaultTransform
                .scaleX
        }.getOrDefault(1.0).coerceAtLeast(0.5)
    }

    val dpiScaleY: Double by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .defaultTransform
                .scaleY
        }.getOrDefault(1.0).coerceAtLeast(0.5)
    }

    /**
     * Converts physical screen pixels (from Win32 / UIAutomation) into Java Robot logical coordinates.
     */
    fun toRobotCoordinates(physicalX: Int, physicalY: Int): Point {
        val rx = if (dpiScaleX != 1.0) (physicalX / dpiScaleX).toInt() else physicalX
        val ry = if (dpiScaleY != 1.0) (physicalY / dpiScaleY).toInt() else physicalY
        return Point(rx, ry)
    }

    /**
     * Converts Java Robot logical coordinates into physical screen pixels.
     */
    fun toPhysicalCoordinates(robotX: Int, robotY: Int): Point {
        val px = if (dpiScaleX != 1.0) (robotX * dpiScaleX).toInt() else robotX
        val py = if (dpiScaleY != 1.0) (robotY * dpiScaleY).toInt() else robotY
        return Point(px, py)
    }

    /**
     * Gets current mouse cursor position on screen.
     */
    fun getCursorPosition(): Point {
        return runCatching {
            MouseInfo.getPointerInfo()?.location ?: Point(0, 0)
        }.getOrDefault(Point(0, 0))
    }

    /**
     * Moves mouse smoothly with human-like cubic easing to (targetX, targetY).
     */
    fun mouseMoveSmooth(targetX: Int, targetY: Int, durationMs: Long = 180): CommandResult {
        return runCatching {
            val start = getCursorPosition()
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val clampedX = targetX.coerceIn(0, screenSize.width - 1)
            val clampedY = targetY.coerceIn(0, screenSize.height - 1)

            val steps = 24
            val stepDelay = (durationMs / steps).coerceAtLeast(3)

            for (i in 1..steps) {
                val t = i.toFloat() / steps.toFloat()
                // Ease-in-out cubic formula
                val ease = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
                val curX = (start.x + (clampedX - start.x) * ease).toInt()
                val curY = (start.y + (clampedY - start.y) * ease).toInt()
                robot.mouseMove(curX, curY)
                Thread.sleep(stepDelay)
            }
            robot.mouseMove(clampedX, clampedY)
            CommandResult(true, "မောက်စ်ကို X: $clampedX, Y: $clampedY သို့ ရွှေ့လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "မောက်စ်ရွှေ့ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Performs a left-click at (targetX, targetY) or current cursor location.
     */
    fun leftClick(targetX: Int? = null, targetY: Int? = null): CommandResult {
        return runCatching {
            if (targetX != null && targetY != null) {
                mouseMoveSmooth(targetX, targetY, 120)
            }
            Thread.sleep(30)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(45)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            val pos = getCursorPosition()
            CommandResult(true, "X: ${pos.x}, Y: ${pos.y} တွင် မောက်စ် Left Click နှိပ်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "Left Click နှိပ်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Performs a double-click (e.g. to open desktop icons or folders).
     */
    fun doubleClick(targetX: Int? = null, targetY: Int? = null): CommandResult {
        return runCatching {
            if (targetX != null && targetY != null) {
                mouseMoveSmooth(targetX, targetY, 150)
            }
            Thread.sleep(40)
            // Click 1
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(40)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(60)
            // Click 2
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(40)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            val pos = getCursorPosition()
            CommandResult(true, "X: ${pos.x}, Y: ${pos.y} တွင် Double Click နှိပ်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "Double Click နှိပ်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Performs a right-click to open context menus.
     */
    fun rightClick(targetX: Int? = null, targetY: Int? = null): CommandResult {
        return runCatching {
            if (targetX != null && targetY != null) {
                mouseMoveSmooth(targetX, targetY, 120)
            }
            Thread.sleep(30)
            robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
            Thread.sleep(45)
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
            val pos = getCursorPosition()
            CommandResult(true, "X: ${pos.x}, Y: ${pos.y} တွင် Right Click နှိပ်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "Right Click နှိပ်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Scrolls the mouse wheel up (negative) or down (positive).
     */
    fun mouseScroll(clicks: Int): CommandResult {
        return runCatching {
            robot.mouseWheel(clicks)
            val dir = if (clicks > 0) "အောက်သို့ $clicks ချက်" else "အထက်သို့ ${-clicks} ချက်"
            CommandResult(true, "မောက်စ်ဘီးကို $dir လှည့်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "မောက်စ်ဘီးလှည့်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Drags from (fromX, fromY) to (toX, toY).
     */
    fun dragAndDrop(fromX: Int, fromY: Int, toX: Int, toY: Int): CommandResult {
        return runCatching {
            mouseMoveSmooth(fromX, fromY, 120)
            Thread.sleep(50)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(60)
            mouseMoveSmooth(toX, toY, 250)
            Thread.sleep(50)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            CommandResult(true, "($fromX, $fromY) မှ ($toX, $toY) သို့ အောင်မြင်စွာ Drag & Drop ပြုလုပ်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "Drag & Drop မအောင်မြင်ပါ: ${it.message}")
        }
    }

    /**
     * Physical text typing using keyboard events or clipboard insertion.
     */
    fun typeText(text: String, pressEnter: Boolean = false): CommandResult {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val previous = if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null

            val selection = StringSelection(text)
            clipboard.setContents(selection, selection)

            Thread.sleep(30)
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            Thread.sleep(40)

            if (pressEnter) {
                robot.keyPress(KeyEvent.VK_ENTER)
                robot.keyRelease(KeyEvent.VK_ENTER)
            }

            if (previous != null) {
                Thread.sleep(50)
                val restore = StringSelection(previous)
                clipboard.setContents(restore, restore)
            }

            CommandResult(true, "စာသား '${text.take(30)}' ကို လက်ဖြင့် ရိုက်ထည့်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "စာရိုက်ထည့်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Executes standard Windows key combinations (e.g., Win+D, Alt+F4, Ctrl+S, Win+Arrows).
     */
    fun pressKeyCombo(combo: String): CommandResult {
        return runCatching {
            val lower = combo.lowercase().replace(" ", "")
            when {
                lower.contains("win+d") || lower == "show_desktop" -> {
                    robot.keyPress(KeyEvent.VK_WINDOWS)
                    Thread.sleep(30)
                    robot.keyPress(KeyEvent.VK_D)
                    robot.keyRelease(KeyEvent.VK_D)
                    Thread.sleep(30)
                    robot.keyRelease(KeyEvent.VK_WINDOWS)
                }
                lower.contains("win+left") || lower.contains("snap_left") -> {
                    robot.keyPress(KeyEvent.VK_WINDOWS)
                    Thread.sleep(30)
                    robot.keyPress(KeyEvent.VK_LEFT)
                    robot.keyRelease(KeyEvent.VK_LEFT)
                    Thread.sleep(30)
                    robot.keyRelease(KeyEvent.VK_WINDOWS)
                }
                lower.contains("win+right") || lower.contains("snap_right") -> {
                    robot.keyPress(KeyEvent.VK_WINDOWS)
                    Thread.sleep(30)
                    robot.keyPress(KeyEvent.VK_RIGHT)
                    robot.keyRelease(KeyEvent.VK_RIGHT)
                    Thread.sleep(30)
                    robot.keyRelease(KeyEvent.VK_WINDOWS)
                }
                lower.contains("win+up") || lower.contains("snap_up") || lower.contains("maximize") -> {
                    robot.keyPress(KeyEvent.VK_WINDOWS)
                    Thread.sleep(30)
                    robot.keyPress(KeyEvent.VK_UP)
                    robot.keyRelease(KeyEvent.VK_UP)
                    Thread.sleep(30)
                    robot.keyRelease(KeyEvent.VK_WINDOWS)
                }
                lower.contains("win+down") || lower.contains("snap_down") || lower.contains("minimize") -> {
                    robot.keyPress(KeyEvent.VK_WINDOWS)
                    Thread.sleep(30)
                    robot.keyPress(KeyEvent.VK_DOWN)
                    robot.keyRelease(KeyEvent.VK_DOWN)
                    Thread.sleep(30)
                    robot.keyRelease(KeyEvent.VK_WINDOWS)
                }
                lower.contains("alt+f4") || lower == "close" -> {
                    robot.keyPress(KeyEvent.VK_ALT)
                    robot.keyPress(KeyEvent.VK_F4)
                    robot.keyRelease(KeyEvent.VK_F4)
                    robot.keyRelease(KeyEvent.VK_ALT)
                }
                lower.contains("alt+tab") -> {
                    robot.keyPress(KeyEvent.VK_ALT)
                    robot.keyPress(KeyEvent.VK_TAB)
                    robot.keyRelease(KeyEvent.VK_TAB)
                    robot.keyRelease(KeyEvent.VK_ALT)
                }
                lower.contains("ctrl+s") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_S)
                    robot.keyRelease(KeyEvent.VK_S)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+t") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_T)
                    robot.keyRelease(KeyEvent.VK_T)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+w") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_W)
                    robot.keyRelease(KeyEvent.VK_W)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+tab") || lower.contains("next_tab") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    Thread.sleep(30)
                    robot.keyPress(KeyEvent.VK_TAB)
                    robot.keyRelease(KeyEvent.VK_TAB)
                    Thread.sleep(30)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+a") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_A)
                    robot.keyRelease(KeyEvent.VK_A)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+c") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_C)
                    robot.keyRelease(KeyEvent.VK_C)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+v") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_V)
                    robot.keyRelease(KeyEvent.VK_V)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+z") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_Z)
                    robot.keyRelease(KeyEvent.VK_Z)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("ctrl+f") -> {
                    robot.keyPress(KeyEvent.VK_CONTROL)
                    robot.keyPress(KeyEvent.VK_F)
                    robot.keyRelease(KeyEvent.VK_F)
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                lower.contains("enter") -> {
                    robot.keyPress(KeyEvent.VK_ENTER)
                    robot.keyRelease(KeyEvent.VK_ENTER)
                }
                lower.contains("esc") || lower.contains("escape") -> {
                    robot.keyPress(KeyEvent.VK_ESCAPE)
                    robot.keyRelease(KeyEvent.VK_ESCAPE)
                }
                else -> {
                    ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "\$ws = New-Object -ComObject WScript.Shell; \$ws.SendKeys('$combo')").start()
                }
            }
            CommandResult(true, "Key Combination '$combo' ကို နှိပ်လိုက်ပါပြီရှင်။")
        }.getOrElse {
            CommandResult(false, "Key Combo နှိပ်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }
}
