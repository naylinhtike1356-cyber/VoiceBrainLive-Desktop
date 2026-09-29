package com.example.voicebrainlive.desktop.platform.phonemic

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.example.voicebrainlive.desktop.platform.audio.AudioCapture
import java.util.concurrent.atomic.AtomicReference

/**
 * An [AudioCapture] that delegates to a swappable underlying source.
 *
 * This lets the assistant pipeline switch between the local microphone and
 * the phone mic at runtime without restarting the audio engine: the engine
 * holds this router, and the phone-mic feature swaps [activeSource] between
 * the local [AudioCapture] and a [PhoneMicCapture].
 *
 * Swap semantics: the old source is stopped, the new source is started with
 * the same [onFrame] callback. A swap while not started only changes which
 * source the next [start] will use.
 */
class RoutingAudioCapture(
    initialSource: AudioCapture,
) : AudioCapture {

    private val activeSource = AtomicReference<AudioCapture>(initialSource)

    @Volatile private var onFrame: ((ByteArray) -> Unit)? = null
    @Volatile private var started = false

    override val isActive: Boolean get() = started && (activeSource.get()?.isActive == true)

    /** The local-mic source (kept so we can fail back on phone disconnect). */
    @Volatile var localSource: AudioCapture = initialSource
        private set

    fun setLocalSource(source: AudioCapture) {
        localSource = source
        if (activeSource.get() !is PhoneMicCapture) {
            activeSource.set(source)
        }
    }

    /**
     * Switches the active source. If capture is currently started, the old
     * source is stopped and the new one started with the existing callback.
     */
    @Synchronized
    fun switchTo(source: AudioCapture): Boolean {
        val cb = onFrame
        val wasStarted = started
        if (wasStarted) {
            try { activeSource.get()?.stop() } catch (_: Exception) { }
        }
        activeSource.set(source)
        DesktopLogger.info("RoutingAudioCapture: switched to ${source.javaClass.simpleName}")
        if (wasStarted && cb != null) {
            return try {
                source.start(cb)
            } catch (e: Exception) {
                DesktopLogger.warn("RoutingAudioCapture: new source failed to start: ${e.message}")
                false
            }
        }
        return true
    }

    fun activeSourceName(): String = activeSource.get()?.javaClass?.simpleName ?: "none"

    fun isPhoneMicActive(): Boolean = activeSource.get() is PhoneMicCapture

    override fun start(onFrame: (ByteArray) -> Unit): Boolean {
        this.onFrame = onFrame
        val ok = activeSource.get()?.start(onFrame) == true
        started = ok
        return ok
    }

    override fun stop() {
        started = false
        onFrame = null
        try { activeSource.get()?.stop() } catch (_: Exception) { }
    }
}
