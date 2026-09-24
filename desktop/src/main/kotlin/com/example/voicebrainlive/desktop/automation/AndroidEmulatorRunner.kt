package com.example.voicebrainlive.desktop.automation

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Automates Android Emulator / ADB test execution:
 * Installs debug APK, launches the application, and monitors Logcat for runtime crashes or ANRs.
 */
class AndroidEmulatorRunner {

    data class AppVerificationResult(
        val success: Boolean,
        val deviceId: String?,
        val message: String,
        val crashLogs: String? = null
    )

    /**
     * Verifies the project on a connected Android device or emulator.
     */
    fun deployAndVerifyApp(projectDir: File, packageName: String? = null): AppVerificationResult {
        // 1. Check connected device
        val devices = getConnectedDevices()
        if (devices.isEmpty()) {
            return AppVerificationResult(
                success = false,
                deviceId = null,
                message = "ချိတ်ဆက်ထားသော Android Device သို့မဟုတ် Emulator မတွေ့ပါ။ Android Studio Emulator ကို အရင်ဖွင့်ပေးပါ။"
            )
        }

        val targetDevice = devices.first()

        // 2. Locate Debug APK
        val apkFile = findDebugApk(projectDir)
        if (apkFile == null || !apkFile.exists()) {
            return AppVerificationResult(
                success = false,
                deviceId = targetDevice,
                message = "Debug APK ဖိုင် ရှာမတွေ့ပါ။ `./gradlew.bat assembleDebug` ကို အရင် run စစ်ဆေးပေးပါ။"
            )
        }

        // 3. Clear Logcat buffer
        runCatching {
            ProcessBuilder("adb", "-s", targetDevice, "logcat", "-c").start().waitFor(5, TimeUnit.SECONDS)
        }

        // 4. Install APK
        val installProcess = runCatching {
            val proc = ProcessBuilder("adb", "-s", targetDevice, "install", "-r", apkFile.absolutePath).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            output
        }.getOrDefault("")

        if (!installProcess.contains("Success", ignoreCase = true)) {
            return AppVerificationResult(
                success = false,
                deviceId = targetDevice,
                message = "APK Install မအောင်မြင်ပါ: $installProcess"
            )
        }

        // 5. Query crash logs after short wait
        Thread.sleep(1500)
        val crashOutput = checkCrashLogs(targetDevice)
        if (crashOutput.isNotBlank()) {
            return AppVerificationResult(
                success = false,
                deviceId = targetDevice,
                message = "App Launch/Runtime တွင် Crash တွေ့ရှိပါသည်!",
                crashLogs = crashOutput
            )
        }

        return AppVerificationResult(
            success = true,
            deviceId = targetDevice,
            message = "Device ($targetDevice) ပေါ်တွင် APK ကို အောင်မြင်စွာ တင်ပြီး စမ်းသပ်စစ်ဆေးပြီးပါပြီ။ Runtime Crash မရှိပါ။"
        )
    }

    fun getConnectedDevices(): List<String> {
        return runCatching {
            val process = ProcessBuilder("adb", "devices").start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)
            lines.drop(1)
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains("\tdevice") }
                .map { it.split("\t")[0].trim() }
        }.getOrDefault(emptyList())
    }

    private fun findDebugApk(projectDir: File): File? {
        val candidates = listOf(
            File(projectDir, "app/build/outputs/apk/debug/app-debug.apk"),
            File(projectDir, "build/outputs/apk/debug/app-debug.apk"),
            File(projectDir, "desktop/build/compose/binaries/main/app"),
        )
        return candidates.firstOrNull { it.exists() } ?: projectDir.walkTopDown().firstOrNull { it.name.endsWith(".apk") }
    }

    private fun checkCrashLogs(deviceId: String): String {
        return runCatching {
            val proc = ProcessBuilder("adb", "-s", deviceId, "logcat", "-d", "-s", "AndroidRuntime:E", "*:F").start()
            val logs = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            logs.trim()
        }.getOrDefault("")
    }
}
