package com.example.voicebrainlive.desktop.automation

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AdbDeviceInfo(
    val id: String,
    val isWireless: Boolean,
    val model: String? = null,
    val androidVersion: String? = null,
    val batteryPercent: String? = null,
    val wifiIp: String? = null
)

/**
 * Automates Android Wireless ADB pairing, connection, and device management.
 */
class WirelessAdbManager {

    /**
     * Enables TCP/IP wireless mode on a currently USB-connected Android device.
     */
    fun enableTcpip(port: Int = 5555): ProcessResult {
        return runCatching {
            val proc = ProcessBuilder("adb", "tcpip", port.toString()).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val error = proc.errorStream.bufferedReader().readText().trim()
            proc.waitFor(8, TimeUnit.SECONDS)

            val fullText = (output + "\n" + error).trim()
            if (fullText.contains("restarting in TCP mode", ignoreCase = true) || proc.exitValue() == 0) {
                ProcessResult(true, "Android Device ကို Wireless TCP/IP Mode (Port $port) သို့ ပြောင်းလဲလိုက်ပါပြီ။ USB ကြိုးဖြုတ်ပြီး 'adb connect <phone_ip>:$port' ဖြင့် ချိတ်ဆက်နိုင်ပါပြီရှင်။")
            } else {
                ProcessResult(false, "Wireless Mode ဖွင့်မရပါ: $fullText")
            }
        }.getOrElse {
            ProcessResult(false, "ADB command မအောင်မြင်ပါ: ${it.message}")
        }
    }

    /**
     * Connects to a wireless Android device using IP address and Port.
     */
    fun connectWireless(ipAndPort: String): ProcessResult {
        val clean = ipAndPort.trim()
        if (clean.isBlank()) {
            return ProcessResult(false, "ချိတ်ဆက်ရန် IP Address နှင့် Port လိုအပ်ပါသည် (ဥပမာ 192.168.1.100:5555)။")
        }
        val target = if (!clean.contains(":")) "$clean:5555" else clean

        return runCatching {
            val proc = ProcessBuilder("adb", "connect", target).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(10, TimeUnit.SECONDS)

            if (output.contains("connected to", ignoreCase = true) && !output.contains("unable", ignoreCase = true)) {
                ProcessResult(true, "Android Device '$target' သို့ Wi-Fi ဖြင့် အောင်မြင်စွာ ချိတ်ဆက်ပြီးပါပြီရှင် ($output)။")
            } else {
                ProcessResult(false, "ချိတ်ဆက်မှု မအောင်မြင်ပါ: $output")
            }
        }.getOrElse {
            ProcessResult(false, "Wireless ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${it.message}")
        }
    }

    /**
     * Pairs with Android 11+ Wireless Debugging using 6-digit pairing code.
     */
    fun pairWireless(ipAndPort: String, pairingCode: String): ProcessResult {
        val cleanTarget = ipAndPort.trim()
        val cleanCode = pairingCode.trim()
        if (cleanTarget.isBlank() || cleanCode.isBlank()) {
            return ProcessResult(false, "Pairing ပြုလုပ်ရန် IP:Port နှင့် 6-digit Pairing Code လိုအပ်ပါသည်ရှင်။")
        }

        return runCatching {
            val proc = ProcessBuilder("adb", "pair", cleanTarget, cleanCode).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val error = proc.errorStream.bufferedReader().readText().trim()
            proc.waitFor(12, TimeUnit.SECONDS)

            val fullText = (output + "\n" + error).trim()
            if (fullText.contains("Successfully paired", ignoreCase = true)) {
                ProcessResult(true, "Android Device '$cleanTarget' နှင့် အောင်မြင်စွာ Pair လုပ်ပြီးပါပြီရှင် ($fullText)။")
            } else {
                ProcessResult(false, "Pairing မအောင်မြင်ပါ: $fullText")
            }
        }.getOrElse {
            ProcessResult(false, "Pairing မအောင်မြင်ပါ: ${it.message}")
        }
    }

    /**
     * Lists all connected devices and fetches their specs.
     */
    fun getDetailedDeviceList(): List<AdbDeviceInfo> {
        val rawDevices = runCatching {
            val process = ProcessBuilder("adb", "devices", "-l").start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)
            lines.drop(1)
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains("device") }
        }.getOrDefault(emptyList())

        return rawDevices.map { line ->
            val id = line.split(Regex("\\s+")).firstOrNull().orEmpty()
            val isWireless = id.contains(":") || id.startsWith("192.") || id.startsWith("10.") || id.startsWith("172.")
            val modelMatch = Regex("model:(\\S+)").find(line)?.groupValues?.getOrNull(1)

            val model = modelMatch ?: runCatching {
                val proc = ProcessBuilder("adb", "-s", id, "shell", "getprop", "ro.product.model").start()
                proc.inputStream.bufferedReader().readLine()?.trim()
            }.getOrNull()

            val version = runCatching {
                val proc = ProcessBuilder("adb", "-s", id, "shell", "getprop", "ro.build.version.release").start()
                proc.inputStream.bufferedReader().readLine()?.trim()
            }.getOrNull()

            val battery = runCatching {
                val proc = ProcessBuilder("adb", "-s", id, "shell", "dumpsys", "battery").start()
                val out = proc.inputStream.bufferedReader().readText()
                val level = Regex("level:\\s*(\\d+)").find(out)?.groupValues?.getOrNull(1)
                level?.let { "$it%" }
            }.getOrNull()

            val wifiIp = runCatching {
                val proc = ProcessBuilder("adb", "-s", id, "shell", "ip", "route").start()
                val out = proc.inputStream.bufferedReader().readText()
                val ipMatch = Regex("src\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(out)?.groupValues?.getOrNull(1)
                ipMatch
            }.getOrNull()

            AdbDeviceInfo(
                id = id,
                isWireless = isWireless,
                model = model,
                androidVersion = version,
                batteryPercent = battery,
                wifiIp = wifiIp
            )
        }
    }

    /**
     * Formats device list as human-readable Burmese summary.
     */
    fun formatDevicesSummary(): String {
        val devices = getDetailedDeviceList()
        if (devices.isEmpty()) {
            return "ချိတ်ဆက်ထားသော Android Device သို့မဟုတ် Emulator မရှိသေးပါရှင်။ (USB သို့မဟုတ် Wireless ADB ဖြင့် ချိတ်ဆက်နိုင်ပါသည်)"
        }

        return buildString {
            appendLine("ချိတ်ဆက်ထားသော Android Devices (${devices.size} ခု):")
            for (d in devices) {
                val typeTag = if (d.isWireless) "📶 Wireless" else "🔌 USB / Emulator"
                appendLine("• Device ID: ${d.id} ($typeTag)")
                if (!d.model.isNullOrBlank()) appendLine("  - Model: ${d.model}")
                if (!d.androidVersion.isNullOrBlank()) appendLine("  - Android Version: ${d.androidVersion}")
                if (!d.batteryPercent.isNullOrBlank()) appendLine("  - Battery: ${d.batteryPercent}")
                if (!d.wifiIp.isNullOrBlank()) appendLine("  - Wi-Fi IP: ${d.wifiIp}")
            }
        }.trim()
    }

    /**
     * Inspects recent Android crash logs, fatal exceptions, or system errors from connected device.
     */
    fun getRecentLogcatErrors(deviceId: String? = null, maxLines: Int = 25): ProcessResult {
        return runCatching {
            val cmd = mutableListOf("adb")
            if (!deviceId.isNullOrBlank()) {
                cmd.addAll(listOf("-s", deviceId.trim()))
            }
            // 1. Try crash buffer first
            val crashCmd = cmd.toMutableList().apply { addAll(listOf("logcat", "-d", "-b", "crash")) }
            val procCrash = ProcessBuilder(crashCmd).redirectErrorStream(true).start()
            val crashOut = procCrash.inputStream.bufferedReader().readText().trim()
            procCrash.waitFor(5, TimeUnit.SECONDS)

            if (crashOut.isNotBlank() && !crashOut.contains("--------- beginning of crash") || crashOut.length > 50) {
                val lines = crashOut.lines().takeLast(maxLines).joinToString("\n")
                return ProcessResult(true, "📱 Android Crash Log (မကြာမီဖြစ်ပွားခဲ့သော Error များ):\n```\n$lines\n```")
            }

            // 2. Fallback to recent Error logs (*:E)
            val errorCmd = cmd.toMutableList().apply { addAll(listOf("logcat", "-d", "-t", "80", "*:E")) }
            val procErr = ProcessBuilder(errorCmd).redirectErrorStream(true).start()
            val errOut = procErr.inputStream.bufferedReader().readText().trim()
            procErr.waitFor(5, TimeUnit.SECONDS)

            if (errOut.isNotBlank()) {
                val filtered = errOut.lines()
                    .filter { it.contains("FATAL", ignoreCase = true) || it.contains("Exception", ignoreCase = true) || it.contains("Error", ignoreCase = true) }
                    .takeLast(maxLines)
                    .joinToString("\n")

                if (filtered.isNotBlank()) {
                    return ProcessResult(true, "📱 Android Error Logs:\n```\n$filtered\n```")
                }
            }

            ProcessResult(true, "Device တွင် မကြာသေးမီက ဖြစ်ပွားခဲ့သော Crash သို့မဟုတ် Fatal Exception မတွေ့ရှိပါရှင် (System ပုံမှန် အလုပ်လုပ်နေပါသည်)။")
        }.getOrElse {
            ProcessResult(false, "Logcat ရယူရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Captures a screenshot from the active Android device and saves it locally.
     */
    fun captureDeviceScreenshot(outputFile: File? = null): ProcessResult {
        return runCatching {
            val targetFile = outputFile ?: File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "VoiceBrainLive\\screenshots\\phone_screen.png")
            targetFile.parentFile?.mkdirs()

            val proc = ProcessBuilder("adb", "exec-out", "screencap", "-p").start()
            val bytes = proc.inputStream.readBytes()
            proc.waitFor(8, TimeUnit.SECONDS)

            if (bytes.size > 1024) {
                FileOutputStream(targetFile).use { it.write(bytes) }
                ProcessResult(true, "Android Screen Screenshot ကို '${targetFile.name}' သို့ သိမ်းဆည်းလိုက်ပါပြီရှင် (${targetFile.absolutePath})။")
            } else {
                ProcessResult(false, "Screenshot ဖမ်းယူ၍ မရပါ (Device မချိတ်ဆက်ထားပါ သို့မဟုတ် Screen Off ဖြစ်နေပါသည်)။")
            }
        }.getOrElse {
            ProcessResult(false, "Android Screenshot ဖမ်းယူရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }

    /**
     * Installs an APK file onto the connected Android device.
     */
    fun installApk(apkPath: String): ProcessResult {
        val file = File(apkPath.trim())
        if (!file.exists() || !file.name.endsWith(".apk", ignoreCase = true)) {
            return ProcessResult(false, "မှန်ကန်သော APK ဖိုင် ရှာမတွေ့ပါ: ${file.absolutePath}")
        }

        return runCatching {
            val proc = ProcessBuilder("adb", "install", "-r", file.absolutePath).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(60, TimeUnit.SECONDS)

            if (out.contains("Success", ignoreCase = true)) {
                ProcessResult(true, "APK '${file.name}' ကို Android Device ပေါ်သို့ အောင်မြင်စွာ Install ပြုလုပ်ပြီးပါပြီရှင်။")
            } else {
                ProcessResult(false, "APK Install မအောင်မြင်ပါ: $out")
            }
        }.getOrElse {
            ProcessResult(false, "APK Install ပြုလုပ်ရာတွင် အခက်အခဲရှိပါသည်: ${it.message}")
        }
    }
}
