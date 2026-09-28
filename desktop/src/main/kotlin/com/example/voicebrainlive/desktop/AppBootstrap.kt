package com.example.voicebrainlive.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.example.voicebrainlive.desktop.platform.DesktopLogger

/**
 * Owns the [DesktopRuntime] lifecycle: creation, [start], and [close].
 * Extracted from main() so the entry point stays thin; behavior is identical
 * to the previous inline wiring.
 */
class AppBootstrap(
    onExitRequested: () -> Unit,
    onMainWindowRequested: () -> Unit,
    onToggleMainWindowRequested: () -> Unit = onMainWindowRequested,
) {
    val runtime: DesktopRuntime by lazy {
        DesktopRuntime(
            onExitRequested = onExitRequested,
            onMainWindowRequested = onMainWindowRequested,
            onToggleMainWindowRequested = onToggleMainWindowRequested,
        )
    }

    fun start() {
        DesktopLogger.info("AppBootstrap: starting runtime")
        runtime.start()
    }

    fun close() {
        DesktopLogger.info("AppBootstrap: closing runtime")
        runtime.close()
    }
}

/**
 * Remembers a single [AppBootstrap] for the composition, starts the runtime
 * when entering the composition and closes it on dispose.
 */
@Composable
fun rememberAppBootstrap(
    onExitRequested: () -> Unit,
    onMainWindowRequested: () -> Unit,
    onToggleMainWindowRequested: () -> Unit = onMainWindowRequested,
): AppBootstrap {
    val bootstrap = remember {
        AppBootstrap(
            onExitRequested = onExitRequested,
            onMainWindowRequested = onMainWindowRequested,
            onToggleMainWindowRequested = onToggleMainWindowRequested,
        )
    }
    DisposableEffect(bootstrap) {
        bootstrap.start()
        onDispose { bootstrap.close() }
    }
    return bootstrap
}
