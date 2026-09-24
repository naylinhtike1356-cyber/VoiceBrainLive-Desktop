package com.example.voicebrainlive.desktop.automation

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Automates Gradle builds, test runs, and Android Studio/ADB verification.
 */
class AndroidBuildPipeline {

    /**
     * Executes Gradle verification (compileKotlin / assembleDebug / test) in the target project.
     */
    fun runGradleCheck(
        projectDir: File,
        tasks: List<String> = listOf("compileKotlin", "test"),
        timeoutMinutes: Long = 5
    ): ProcessResult {
        if (!projectDir.exists()) {
            return ProcessResult(false, "Project directory does not exist: ${projectDir.absolutePath}")
        }

        val gradlewBat = File(projectDir, "gradlew.bat")
        val gradleCommand = if (gradlewBat.exists()) {
            gradlewBat.absolutePath
        } else {
            "gradle"
        }

        val fullCommand = mutableListOf(gradleCommand)
        fullCommand.addAll(tasks)
        fullCommand.add("--offline") // Fast local compilation first

        return try {
            val processBuilder = ProcessBuilder(fullCommand)
                .directory(projectDir)
                .redirectErrorStream(true)

            val process = processBuilder.start()
            val output = StringBuilder()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }

            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return ProcessResult(false, "Gradle task timed out after $timeoutMinutes minutes.")
            }

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ProcessResult(true, "Build and verification succeeded for tasks: ${tasks.joinToString(", ")}", output.toString())
            } else {
                // If offline failed, retry without --offline flag
                if (output.contains("offline") || output.contains("No cached version")) {
                    return retryOnlineGradle(projectDir, gradleCommand, tasks, timeoutMinutes)
                }
                ProcessResult(false, "Build failed with exit code $exitCode:\n${output.takeLast(1000)}", output.toString())
            }
        } catch (e: Exception) {
            ProcessResult(false, "Failed to execute Gradle check: ${e.message}")
        }
    }

    private fun retryOnlineGradle(
        projectDir: File,
        gradleCommand: String,
        tasks: List<String>,
        timeoutMinutes: Long
    ): ProcessResult {
        val fullCommand = mutableListOf(gradleCommand)
        fullCommand.addAll(tasks)

        return try {
            val processBuilder = ProcessBuilder(fullCommand)
                .directory(projectDir)
                .redirectErrorStream(true)

            val process = processBuilder.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ProcessResult(true, "Build succeeded on online retry for tasks: ${tasks.joinToString(", ")}", output.toString())
            } else {
                ProcessResult(false, "Build failed with exit code $exitCode:\n${output.takeLast(1000)}", output.toString())
            }
        } catch (e: Exception) {
            ProcessResult(false, "Online build execution failed: ${e.message}")
        }
    }

    /**
     * Checks if ADB is connected to any device or emulator.
     */
    fun checkAdbDevices(): ProcessResult {
        return try {
            val process = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            ProcessResult(true, "ADB Devices query completed", output)
        } catch (e: Exception) {
            ProcessResult(false, "ADB not found or unreachable: ${e.message}")
        }
    }
}
