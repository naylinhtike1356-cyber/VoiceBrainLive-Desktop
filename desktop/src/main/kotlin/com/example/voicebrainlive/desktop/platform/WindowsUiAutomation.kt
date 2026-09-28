package com.example.voicebrainlive.desktop.platform

import java.awt.Rectangle
import java.util.concurrent.TimeUnit

data class UiElementMatch(
    val name: String,
    val controlType: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: Rectangle? = null,
)

/**
 * Deep Windows UI Automation Engine:
 * Native accessibility inspection across active foreground windows using
 * Windows UIAutomation (System.Windows.Automation.AutomationElement).
 *
 * Provides pixel-accurate bounding box coordinates for arbitrary buttons,
 * tabs, menu items, edit fields, and links by name or common Burmese synonyms.
 */
class WindowsUiAutomation {

    /**
     * Common Burmese to English UI action / button synonyms
     */
    private val burmeseSynonyms = mapOf(
        "ရှာဖွေ" to listOf("search", "find"),
        "ရှာ" to listOf("search", "find"),
        "သိမ်း" to listOf("save"),
        "သိမ်းဆည်း" to listOf("save"),
        "ပိတ်" to listOf("close", "cancel", "exit"),
        "အပိတ်" to listOf("close"),
        "ဖျက်" to listOf("delete", "remove", "clear"),
        "အတည်ပြု" to listOf("ok", "confirm", "yes", "apply", "submit"),
        "သဘောတူ" to listOf("ok", "agree", "accept"),
        "မလုပ်တော့" to listOf("cancel", "no", "dismiss"),
        "အသစ်" to listOf("new", "add", "create"),
        "ဆက်လုပ်" to listOf("next", "continue", "proceed"),
        "နောက်သို့" to listOf("back", "previous"),
        "စတင်" to listOf("start", "run", "play"),
        "ရပ်" to listOf("stop", "pause"),
        "ဖွင့်" to listOf("open"),
        "ကူး" to listOf("copy"),
        "ထည့်" to listOf("paste"),
        "ရွေး" to listOf("select"),
        "ဆက်တင်" to listOf("settings", "options", "preferences"),
    )

    /**
     * Resolves Burmese query tokens into candidate search names
     */
    fun resolveQueryCandidates(rawQuery: String): List<String> {
        val clean = rawQuery.trim().lowercase()
        val candidates = mutableListOf<String>()
        candidates.add(clean)

        for ((burmeseKey, englishList) in burmeseSynonyms) {
            if (clean.contains(burmeseKey)) {
                candidates.addAll(englishList)
            }
        }
        return candidates.distinct()
    }

    /**
     * Finds an interactive UI element by name in the foreground window.
     */
    fun findElementInForeground(query: String): UiElementMatch? {
        val candidates = resolveQueryCandidates(query)
        for (cand in candidates) {
            val match = findElementDirect(cand)
            if (match != null) return match
        }
        return null
    }

    private fun findElementDirect(query: String): UiElementMatch? {
        if (query.isBlank()) return null
        return runCatching {
            val script = """
                Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes -ErrorAction SilentlyContinue
                ${'$'}code = @'
                using System;
                using System.Windows.Automation;
                using System.Runtime.InteropServices;

                public class UiLocator {
                    [DllImport("user32.dll")]
                    public static extern IntPtr GetForegroundWindow();

                    public static string Locate(string query) {
                        IntPtr hwnd = GetForegroundWindow();
                        if (hwnd == IntPtr.Zero) return "";
                        AutomationElement root = AutomationElement.FromHandle(hwnd);
                        if (root == null) return "";

                        var condName = new PropertyCondition(AutomationElement.NameProperty, query, PropertyConditionFlags.IgnoreCase);
                        var condId = new PropertyCondition(AutomationElement.AutomationIdProperty, query, PropertyConditionFlags.IgnoreCase);
                        var condOr = new OrCondition(condName, condId);

                        AutomationElement target = root.FindFirst(TreeScope.Descendants, condOr);
                        if (target == null) {
                            var all = root.FindAll(TreeScope.Descendants, Condition.TrueCondition);
                            foreach (AutomationElement el in all) {
                                try {
                                    string n = el.Current.Name;
                                    if (!string.IsNullOrEmpty(n) && n.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0) {
                                        target = el;
                                        break;
                                    }
                                } catch {}
                            }
                        }

                        if (target != null) {
                            System.Windows.Rect r = target.Current.BoundingRectangle;
                            if (!r.IsEmpty && r.Width > 0 && r.Height > 0) {
                                int cx = (int)(r.X + r.Width / 2);
                                int cy = (int)(r.Y + r.Height / 2);
                                return cx + "|" + cy + "|" + (int)r.Width + "|" + (int)r.Height + "|" + target.Current.Name + "|" + target.Current.ControlType.ProgrammaticName;
                            }
                        }
                        return "";
                    }
                }
                '@
                Add-Type -TypeDefinition ${'$'}code -ReferencedAssemblies UIAutomationClient, UIAutomationTypes -ErrorAction SilentlyContinue
                [UiLocator]::Locate('$query')
            """.trimIndent()

            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }

            val line = proc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
            if (line.isBlank() || !line.contains("|")) return null

            val parts = line.split("|")
            if (parts.size >= 6) {
                val cx = parts[0].toIntOrNull() ?: return null
                val cy = parts[1].toIntOrNull() ?: return null
                val w = parts[2].toIntOrNull() ?: 0
                val h = parts[3].toIntOrNull() ?: 0
                val name = parts[4]
                val type = parts[5].removePrefix("ControlType.")
                UiElementMatch(
                    name = name,
                    controlType = type,
                    centerX = cx,
                    centerY = cy,
                    bounds = Rectangle(cx - w / 2, cy - h / 2, w, h)
                )
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Lists interactive UI elements in the foreground window (up to 25 items).
     */
    fun listInteractiveElements(): List<UiElementMatch> {
        return runCatching {
            val script = """
                Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes -ErrorAction SilentlyContinue
                ${'$'}code = @'
                using System;
                using System.Text;
                using System.Windows.Automation;
                using System.Runtime.InteropServices;

                public class UiScanner {
                    [DllImport("user32.dll")]
                    public static extern IntPtr GetForegroundWindow();

                    public static string Scan() {
                        IntPtr hwnd = GetForegroundWindow();
                        if (hwnd == IntPtr.Zero) return "";
                        AutomationElement root = AutomationElement.FromHandle(hwnd);
                        if (root == null) return "";

                        var all = root.FindAll(TreeScope.Descendants, Condition.TrueCondition);
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        foreach (AutomationElement el in all) {
                            try {
                                string n = el.Current.Name;
                                string ct = el.Current.ControlType.ProgrammaticName.Replace("ControlType.", "");
                                if (!string.IsNullOrWhiteSpace(n) && (ct == "Button" || ct == "TabItem" || ct == "MenuItem" || ct == "CheckBox" || ct == "Edit" || ct == "Hyperlink")) {
                                    System.Windows.Rect r = el.Current.BoundingRectangle;
                                    if (!r.IsEmpty && r.Width > 0 && r.Height > 0) {
                                        int cx = (int)(r.X + r.Width / 2);
                                        int cy = (int)(r.Y + r.Height / 2);
                                        sb.AppendLine(cx + "|" + cy + "|" + (int)r.Width + "|" + (int)r.Height + "|" + n.Trim() + "|" + ct);
                                        count++;
                                        if (count >= 25) break;
                                    }
                                }
                            } catch {}
                        }
                        return sb.ToString();
                    }
                }
                '@
                Add-Type -TypeDefinition ${'$'}code -ReferencedAssemblies UIAutomationClient, UIAutomationTypes -ErrorAction SilentlyContinue
                [UiScanner]::Scan()
            """.trimIndent()

            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return emptyList()
            }

            val lines = proc.inputStream.bufferedReader().readLines()
            lines.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 6) {
                    val cx = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val cy = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val w = parts[2].toIntOrNull() ?: 0
                    val h = parts[3].toIntOrNull() ?: 0
                    val name = parts[4]
                    val type = parts[5]
                    UiElementMatch(name, type, cx, cy, Rectangle(cx - w / 2, cy - h / 2, w, h))
                } else null
            }
        }.getOrDefault(emptyList())
    }
}
