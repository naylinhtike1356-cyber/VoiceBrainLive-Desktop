package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.CommandResult
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

data class SoftwareEntry(
    val name: String,
    val command: String,
    val isUwp: Boolean = false,
    val appId: String? = null,
    val isShortcut: Boolean = false,
    val source: String = "Registry"
)

data class DesktopIconEntry(
    val name: String,
    val file: File,
    val index: Int
)

/**
 * Universal Software & File Omniscience Engine:
 * Indexes ALL installed software (Registry 32/64-bit, Start Menu, Desktop icons, UWP apps, AppData)
 * and deep filesystem workspaces so that any requested software or file can be accurately opened.
 */
class OmniAppCatalog(
    private val visionEyes: VisionEyesEngine = VisionEyesEngine(),
    private val osHands: OSHandsController = OSHandsController()
) {

    private val appsMap = ConcurrentHashMap<String, SoftwareEntry>()
    private val desktopIcons = mutableListOf<DesktopIconEntry>()
    @Volatile private var indexedFiles: List<File> = emptyList()
    @Volatile private var catalogBuiltAt: Long = 0L

    val allIndexedFiles: List<File> get() = indexedFiles
    val allApps: Map<String, SoftwareEntry> get() = appsMap
    val allDesktopIcons: List<DesktopIconEntry> get() = desktopIcons

    init {
        // Asynchronous background catalog crawl
        Thread {
            refreshCatalog()
        }.apply {
            isDaemon = true
            name = "omni-app-file-crawler"
            start()
        }
    }

    /**
     * Refreshes both installed software catalog and deep filesystem index.
     */
    fun refreshCatalog(): CommandResult {
        return runCatching {
            appsMap.clear()
            desktopIcons.clear()

            // 1. Scan Desktop Shortcuts & Icons
            crawlDesktopIcons()

            // 2. Scan Windows StartApps (UWP & packaged Win32)
            crawlStartApps()

            // 3. Scan Windows Registry (HKLM, HKLM WoW64, HKCU)
            crawlRegistryApps()

            // 4. Scan Start Menu Directories
            crawlStartMenuDirectories()

            // 5. Build Deep File Index
            indexedFiles = buildDeepFileIndex()
            catalogBuiltAt = System.currentTimeMillis()

            val msg = "ဆော့ဖ်ဝဲလ် စုစုပေါင်း ${appsMap.size} ခုနှင့် ဖိုင်ပေါင်း ${indexedFiles.size} ခုကို အောင်မြင်စွာ မှတ်တမ်းတင်သိရှိပြီးပါပြီရှင်။"
            DesktopLogger.info("OmniAppCatalog refreshed: ${appsMap.size} apps, ${desktopIcons.size} desktop icons, ${indexedFiles.size} files")
            CommandResult(true, msg)
        }.getOrElse {
            CommandResult(false, "App & File catalog ရေးဆွဲရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    private fun crawlDesktopIcons() {
        val desktopDirs = listOf(
            File(System.getProperty("user.home"), "Desktop"),
            File("C:\\Users\\Public\\Desktop"),
            File(System.getProperty("user.home"), "OneDrive\\Desktop")
        ).filter { it.exists() && it.isDirectory }

        val collectedFiles = mutableListOf<File>()
        for (dir in desktopDirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.name.startsWith(".")) continue
                val ext = f.extension.lowercase()
                if (ext == "lnk" || ext == "url" || ext == "exe" || f.isDirectory) {
                    collectedFiles.add(f)
                }
            }
        }

        val distinctFiles = collectedFiles
            .distinctBy { it.nameWithoutExtension.lowercase() }
            .sortedBy { it.nameWithoutExtension.lowercase() }

        for ((idx, f) in distinctFiles.withIndex()) {
            val nameWithoutExt = f.nameWithoutExtension
            val entry = DesktopIconEntry(nameWithoutExt, f, idx)
            desktopIcons.add(entry)

            val normalized = normalize(nameWithoutExt)
            appsMap[normalized] = SoftwareEntry(
                name = nameWithoutExt,
                command = f.absolutePath,
                isShortcut = true,
                source = "Desktop"
            )
        }
    }

    private fun crawlStartApps() {
        runCatching {
            val script = "Get-StartApps | ForEach-Object { \"\$(\$_.Name)|\$(\$_.AppID)\" }"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val lines = proc.inputStream.bufferedReader().readLines()
            for (line in lines) {
                val parts = line.split("|", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val id = parts[1].trim()
                    if (name.isNotBlank() && id.isNotBlank()) {
                        val norm = normalize(name)
                        appsMap.putIfAbsent(norm, SoftwareEntry(
                            name = name,
                            command = id,
                            isUwp = true,
                            appId = id,
                            source = "StartApps"
                        ))
                    }
                }
            }
        }
    }

    private fun crawlRegistryApps() {
        runCatching {
            val script = """
                Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
                Where-Object { ${'$'}_.DisplayName } |
                ForEach-Object {
                    ${'$'}name = ${'$'}_.DisplayName
                    ${'$'}icon = ${'$'}_.DisplayIcon
                    ${'$'}loc = ${'$'}_.InstallLocation
                    Write-Output "${'$'}name|${'$'}icon|${'$'}loc"
                }
            """.trimIndent()
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val lines = proc.inputStream.bufferedReader().readLines()
            for (line in lines) {
                val parts = line.split("|")
                if (parts.isNotEmpty()) {
                    val name = parts[0].trim()
                    if (name.isNotBlank()) {
                        val icon = parts.getOrNull(1)?.trim().orEmpty().replace(Regex(",\\d+$"), "").removeSurrounding("\"")
                        val loc = parts.getOrNull(2)?.trim().orEmpty().removeSurrounding("\"")

                        val exePath = when {
                            icon.endsWith(".exe", ignoreCase = true) && File(icon).exists() -> icon
                            loc.isNotBlank() && File(loc).isDirectory -> {
                                File(loc).listFiles { f -> f.extension.equals("exe", ignoreCase = true) }?.firstOrNull()?.absolutePath ?: loc
                            }
                            else -> ""
                        }

                        if (exePath.isNotBlank()) {
                            val norm = normalize(name)
                            appsMap.putIfAbsent(norm, SoftwareEntry(
                                name = name,
                                command = exePath,
                                source = "Registry"
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun crawlStartMenuDirectories() {
        val startMenuDirs = listOf(
            File("C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs"),
            File(System.getProperty("user.home"), "AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs"),
            File(System.getProperty("user.home"), "AppData\\Local\\Programs")
        ).filter { it.exists() && it.isDirectory }

        for (dir in startMenuDirs) {
            runCatching {
                Files.walk(dir.toPath(), 5).use { paths ->
                    paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".lnk", ignoreCase = true) || it.toString().endsWith(".exe", ignoreCase = true)) }
                        .forEach { path ->
                            val file = path.toFile()
                            val name = file.nameWithoutExtension
                            val norm = normalize(name)
                            appsMap.putIfAbsent(norm, SoftwareEntry(
                                name = name,
                                command = file.absolutePath,
                                isShortcut = file.extension.equals("lnk", ignoreCase = true),
                                source = "StartMenu"
                            ))
                        }
                }
            }
        }
    }

    private fun buildDeepFileIndex(): List<File> {
        val userHome = System.getProperty("user.home") ?: "C:\\Users\\nayli"
        val searchRoots = listOfNotNull(
            File(userHome, "Desktop"),
            File(userHome, "Documents"),
            File(userHome, "Downloads"),
            File(userHome, "Pictures"),
            File(userHome, "Videos"),
            File(userHome, "AndroidStudioProjects"),
            File(userHome, "Projects"),
            File(userHome, "IdeaProjects"),
            File(userHome, "source\\repos"),
            File("D:\\").takeIf { it.exists() && it.isDirectory }
        ).filter { it.exists() && it.isDirectory }

        val collected = mutableListOf<File>()
        for (root in searchRoots) {
            runCatching {
                Files.walk(root.toPath(), 4).use { stream ->
                    stream.filter { Files.isRegularFile(it) && !it.toFile().name.startsWith(".") }
                        .map { it.toFile() }
                        .forEach { collected.add(it) }
                }
            }
        }
        return collected
    }

    /**
     * Finds and opens any software. If the software has a desktop icon,
     * it physically moves the mouse and double-clicks it (Hands & Eyes),
     * while guaranteeing application launch!
     */
    fun findAndLaunchApp(rawName: String, handsOnly: Boolean = false): CommandResult {
        val clean = cleanAppName(rawName)
        if (clean.isBlank()) {
            return CommandResult(false, "ဖွင့်လိုသော ဆော့ဖ်ဝဲလ် အမည် မပါဝင်ပါရှင်။")
        }
        val normalized = normalize(clean)

        // 1. Check if matching icon exists on Desktop
        val matchedIcon = desktopIcons.firstOrNull {
            val normIcon = normalize(it.name)
            normIcon == normalized || normIcon.contains(normalized) || normalized.contains(normIcon)
        }

        if (matchedIcon != null) {
            if (handsOnly) {
                // User explicitly requested to physically click the desktop icon (Hands & Eyes)
                osHands.pressKeyCombo("win+d")
                Thread.sleep(180)
                val (iconX, iconY) = visionEyes.estimateDesktopIconPosition(matchedIcon.index, desktopIcons.size)
                osHands.mouseMoveSmooth(iconX, iconY, 180)
                osHands.doubleClick(iconX, iconY)
            }

            // Launch the application cleanly via Windows Explorer Shell
            var launched = runCatching {
                ProcessBuilder("explorer.exe", matchedIcon.file.absolutePath).start()
                true
            }.getOrDefault(false)

            if (!launched) {
                launched = runCatching {
                    Desktop.getDesktop().open(matchedIcon.file)
                    true
                }.getOrDefault(false)
            }

            if (!launched) {
                runCatching {
                    ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", "Start-Process -FilePath '${matchedIcon.file.absolutePath.replace("'", "''")}'").start()
                }
            }

            val msg = if (handsOnly) {
                "Desktop ပေါ်ရှိ ‘${matchedIcon.name}’ အိုင်ကွန်ကို မောက်စ်ဖြင့် နှိပ်ကာ ဖွင့်လိုက်ပါပြီရှင်။"
            } else {
                "‘${matchedIcon.name}’ ကို အောင်မြင်စွာ ဖွင့်လိုက်ပါပြီရှင်။"
            }
            return CommandResult(true, msg)
        }

        // 2. Search in Omni Software Map
        val matchedEntry = appsMap[normalized]
            ?: appsMap.entries.firstOrNull { it.key.contains(normalized) || (normalized.length >= 3 && it.key.contains(normalized.take(4))) }?.value
            ?: appsMap.entries.firstOrNull { normalized.contains(it.key) }?.value

        if (matchedEntry != null) {
            return launchSoftwareEntry(matchedEntry)
        }

        // 3. Fallback: try direct start
        val fallbackProc = runCatching {
            ProcessBuilder("cmd", "/c", "start", "", clean).start()
            true
        }.getOrDefault(false)

        if (fallbackProc) {
            return CommandResult(true, "‘$rawName’ ဆော့ဖ်ဝဲလ်ကို Windows Shell မှတဆင့် ဖွင့်လိုက်ပါပြီရှင်။")
        }

        return CommandResult(
            false,
            "‘$rawName’ ဆော့ဖ်ဝဲလ်ကို ကွန်ပျူတာထဲတွင် ရှာမတွေ့ပါ။ စုစုပေါင်း ဆော့ဖ်ဝဲလ် ${appsMap.size} ခု ရှာဖွေစစ်ဆေးခဲ့ပါသည်ရှင်။"
        )
    }

    private fun launchSoftwareEntry(entry: SoftwareEntry): CommandResult {
        return runCatching {
            if (entry.isUwp && entry.appId != null) {
                ProcessBuilder("explorer.exe", "shell:AppsFolder\\${entry.appId}").start()
            } else if (entry.isShortcut) {
                ProcessBuilder("explorer.exe", entry.command).start()
            } else {
                val targetFile = File(entry.command)
                val workingDir = if (targetFile.exists()) targetFile.parentFile else null
                val pb = ProcessBuilder("cmd", "/c", "start", "", entry.command)
                if (workingDir != null && workingDir.exists()) {
                    pb.directory(workingDir)
                }
                pb.start()
            }
            CommandResult(true, "${entry.name} ကို အောင်မြင်စွာ ဖွင့်လိုက်ပါပြီရှင် (${entry.source})။")
        }.getOrElse {
            CommandResult(false, "${entry.name} ကို ဖွင့်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Checks if an application or process is currently active/running.
     */
    fun isAppRunning(query: String): Boolean {
        val clean = cleanAppName(query).lowercase()
        val normalized = normalize(clean)

        val processHandles = ProcessHandle.allProcesses().toList()
        for (ph in processHandles) {
            val cmd = ph.info().command().orElse("").lowercase()
            if (cmd.contains(normalized) || (clean.length >= 3 && cmd.contains(clean))) return true
        }

        val psCheck = runCatching {
            val script = "Get-Process | Where-Object { \$_.ProcessName -like '*$clean*' -or \$_.MainWindowTitle -like '*$clean*' } | Select-Object -First 1 -ExpandProperty Id"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            out.isNotBlank()
        }.getOrDefault(false)

        return psCheck
    }

    /**
     * Closes an application with physical icon click interaction (Hands & Eyes).
     */
    fun closeAppByIcon(rawName: String): CommandResult {
        val clean = cleanAppName(rawName)
        if (clean.isBlank()) return CommandResult(false, "ပိတ်လိုသော ဆော့ဖ်ဝဲလ် အမည် မပါဝင်ပါရှင်။")
        val normalized = normalize(clean)

        val matchedIcon = desktopIcons.firstOrNull {
            val normIcon = normalize(it.name)
            normIcon == normalized || normIcon.contains(normalized) || normalized.contains(normIcon)
        }

        if (matchedIcon != null) {
            // Show desktop, move mouse to icon and click it
            osHands.pressKeyCombo("win+d")
            Thread.sleep(150)
            val (iconX, iconY) = visionEyes.estimateDesktopIconPosition(matchedIcon.index, desktopIcons.size)
            osHands.mouseMoveSmooth(iconX, iconY, 160)
            osHands.leftClick(iconX, iconY)
        }

        // Terminate the process
        val killed = terminateAppProcess(clean, normalized)
        val appLabel = matchedIcon?.name ?: clean
        return if (killed) {
            CommandResult(true, "Desktop ပေါ်ရှိ ‘$appLabel’ အိုင်ကွန်ကို နှိပ်ပြီး ဆော့ဖ်ဝဲလ်ကို ပိတ်လိုက်ပါပြီရှင်။")
        } else {
            CommandResult(false, "‘$appLabel’ ကို ပိတ်ရန် လက်ရှိ ဖွင့်ထားသော process များထဲတွင် မတွေ့ပါရှင်။")
        }
    }

    /**
     * Toggles an app: If open, closes it; if closed, opens it via Desktop Icon.
     */
    fun toggleAppByIcon(rawName: String): CommandResult {
        val clean = cleanAppName(rawName)
        if (clean.isBlank()) return CommandResult(false, "ဖွင့်/ပိတ်လိုသော ဆော့ဖ်ဝဲလ် အမည် မပါဝင်ပါရှင်။")

        val running = isAppRunning(clean)
        return if (running) {
            closeAppByIcon(clean)
        } else {
            findAndLaunchApp(clean)
        }
    }

    fun terminateAppProcess(clean: String, normalized: String): Boolean {
        // 1. Direct taskkill
        val directKill = runCatching {
            val proc = ProcessBuilder("taskkill", "/IM", "$clean.exe", "/T", "/F").start()
            proc.waitFor()
            proc.exitValue() == 0
        }.getOrDefault(false)

        if (directKill) return true

        // 2. Kill via PowerShell process search
        val psKill = runCatching {
            val script = "Get-Process | Where-Object { \$_.ProcessName -like '*$clean*' -or \$_.MainWindowTitle -like '*$clean*' } | Stop-Process -Force -PassThru | Select-Object -ExpandProperty ProcessName"
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start()
            val lines = proc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
            lines.isNotEmpty()
        }.getOrDefault(false)

        return psKill
    }

    /**
     * Deep search and open any file on the system.
     */
    fun searchAndOpenFile(query: String): CommandResult {
        val cleanQuery = query.trim().removeSurrounding("\"").lowercase()
        if (cleanQuery.isBlank()) return CommandResult(false, "ဖွင့်လိုသော ဖိုင်အမည်ကို ပြောပြပေးပါရှင်။")

        // 1. Direct path check
        val direct = File(query.trim())
        if (direct.exists()) {
            Desktop.getDesktop().open(direct)
            return CommandResult(true, "ဖိုင် '${direct.name}' ကို ဖွင့်လိုက်ပါပြီရှင်။")
        }

        // 2. Search in indexed files
        val files = if (indexedFiles.isEmpty()) buildDeepFileIndex() else indexedFiles
        val tokens = cleanQuery.split(Regex("\\s+"))

        val bestMatch = files.maxByOrNull { file ->
            val name = file.name.lowercase()
            tokens.sumOf { t ->
                when {
                    name == t -> 100
                    name.startsWith(t) -> 50
                    name.contains(t) -> 20
                    else -> 0
                }
            }
        }

        if (bestMatch != null && tokens.any { bestMatch.name.lowercase().contains(it) }) {
            Desktop.getDesktop().open(bestMatch)
            return CommandResult(true, "ဖိုင် '${bestMatch.name}' ကို ဖွင့်လိုက်ပါပြီရှင် (${bestMatch.parent})။")
        }

        return CommandResult(false, "‘$query’ အမည်ရှိ ဖိုင်ကို ရှာမတွေ့ပါရှင်။ (ဖိုင်ပေါင်း ${files.size} ခု စစ်ဆေးပြီး)")
    }

    fun getCatalogStats(): String {
        return "Apps: ${appsMap.size} | Desktop Icons: ${desktopIcons.size} | Files: ${indexedFiles.size}"
    }

    private fun cleanAppName(raw: String): String {
        val verbs = listOf(
            "အိုင်ကွန်နှိပ်ပြီး ဖွင့်ပေးပါ", "အိုင်ကွန်နှိပ်ပီး ဖွင့်ပေးပါ", "အိုင်ကွန်နှိပ်ပြီး ဖွင့်ပါ", "အိုင်ကွန်နှိပ်ပီး ဖွင့်ပါ",
            "အိုင်ကွန်နှိပ်ပြီး ဖွင့်", "အိုင်ကွန်နှိပ်ပီး ဖွင့်", "အိုင်ကွန်နှိပ်ဖွင့်", "အိုင်ကွန် ဖွင့်ပေးပါ", "အိုင်ကွန် ဖွင့်ပါ", "အိုင်ကွန် ဖွင့်",
            "အိုင်ကွန်နှိပ်ပြီး ပိတ်ပေးပါ", "အိုင်ကွန်နှိပ်ပီး ပိတ်ပေးပါ", "အိုင်ကွန်နှိပ်ပြီး ပိတ်ပါ", "အိုင်ကွန်နှိပ်ပီး ပိတ်ပါ",
            "အိုင်ကွန်နှိပ်ပြီး ပိတ်", "အိုင်ကွန်နှိပ်ပီး ပိတ်", "အိုင်ကွန်နှိပ်ပိတ်", "အိုင်ကွန် ပိတ်ပေးပါ", "အိုင်ကွန် ပိတ်ပါ", "အိုင်ကွန် ပိတ်",
            "အိုင်ကွန်နှိပ်ပြီး ဖွင့်ပိတ်", "အိုင်ကွန်နှိပ်ပီး ဖွင့်ပိတ်", "အိုင်ကွန် ဖွင့်ပိတ်", "ဖွင့်ပိတ်",
            "icon နှိပ်ပြီး ဖွင့်", "icon နှိပ်ပီး ဖွင့်", "icon နှိပ်ဖွင့်", "icon ဖွင့်",
            "icon နှိပ်ပြီး ပိတ်", "icon နှိပ်ပီး ပိတ်", "icon နှိပ်ပိတ်", "icon ပိတ်", "icon ဖွင့်ပိတ်",
            "အိုင်ကွန်နှိပ်ပေးပါ", "အိုင်ကွန်နှိပ်ပါ", "အိုင်ကွန်နှိပ်", "အိုင်ကွန်",
            "icon နှိပ်ပေးပါ", "icon နှိပ်ပါ", "icon နှိပ်", "icon",
            "ဖွင့်ပေးပါ", "ဖွင့်ပါ", "ဖွင့်ပေး", "ဖွင့်မယ်", "ဖွင့်စမ်းပါ", "ဖွင့်လိုက်ပါ", "ဖွင့်",
            "ပိတ်ပေးပါ", "ပိတ်ပါ", "ပိတ်ပေး", "ပိတ်မယ်", "ပိတ်လိုက်ပါ", "ပိတ်",
            "ဒက်စတော့ပေါ်က", "ဒက်စတော့က", "desktop ပေါ်က", "desktop က",
            "ဆော့ဖ်ဝဲလ်", "ဆော့ဖ်ဝဲ", "ဆော့ဝဲလ်", "ဆော့ဝဲ", "app", "application", "open", "launch", "close", "kill", "toggle"
        )
        var clean = raw.trim().lowercase()
        for (v in verbs) {
            clean = clean.replace(v, "").trim()
        }
        return if (clean.isBlank()) raw.trim().lowercase() else clean
    }

    private fun normalize(input: String): String = input
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .trim()
}
