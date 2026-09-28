package com.example.voicebrainlive.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch

fun main() = application {
    val windowState = rememberWindowState(width = 920.dp, height = 680.dp)
    val runtime = remember { DesktopRuntime(::exitApplication) }

    DisposableEffect(runtime) {
        runtime.start()
        onDispose { runtime.close() }
    }

    Window(
        onCloseRequest = {
            runtime.close()
            exitApplication()
        },
        title = "VoiceBrainLive Desktop",
        state = windowState,
    ) {
        VoiceBrainDesktopApp(runtime)
    }
}

@Composable
private fun VoiceBrainDesktopApp(runtime: DesktopRuntime) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val state by runtime.assistant.state.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("VoiceBrainLive Desktop", style = MaterialTheme.typography.headlineMedium)
                Text("Status: ${state.status}")
                Text("Hotkey: Ctrl + Alt + Space")
                Text("You: ${state.transcript.ifBlank { "—" }}")
                Text("Assistant: ${state.response.ifBlank { "Connect to Gemini, then speak or type." }}")

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Type a message") },
                    singleLine = false,
                )
                Row {
                    Button(onClick = runtime::connect) {
                        Text("Connect")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = runtime::toggleListening) {
                        Text(if (state.isListening) "Stop mic" else "Start mic")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = input.isNotBlank(),
                        onClick = {
                            val message = input
                            input = ""
                            scope.launch { runtime.assistant.submitText(message) }
                        },
                    ) {
                        Text("Send")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = runtime.assistant::disconnect) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}
