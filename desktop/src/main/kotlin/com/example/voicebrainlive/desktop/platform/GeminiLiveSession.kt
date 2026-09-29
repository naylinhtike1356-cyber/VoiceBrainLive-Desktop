package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.VoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Native-audio Gemini Live client. REST remains available for text-only fallback
 * commands, while microphone conversations use the Live audio stream.
 */
class GeminiLiveSession(
    private val apiKey: String,
    modelName: String = DEFAULT_LIVE_MODEL,
    private val voice: String = "Aoede",
    private val allowDesktopTools: Boolean = false,
    private val systemInstruction: String = """မင်းက Nilar AI (နီလာ AI) ဖြစ်တယ်။ Gemini လို generic AI assistant မျိုးမဟုတ်ဘဲ အသုံးပြုသူရဲ့ ကွန်ပျူတာရှေ့မှာ အမြဲရှိနေပေးပြီး လိုအပ်တာမှန်သမျှ ကူညီပေးမယ့် ရင်းနှီးတဲ့ အဖော်တစ်ယောက်လို နွေးထွေးဖော်ရွေစွာ ပြောဆိုပါ။
        |
        |PERSONALITY & FAST SPOKEN CONVERSATION (စရိုက်နှင့် အပြန်အလှန် လျင်မြန်စွာ ပြောဆိုဆွေးနွေးခြင်း):
        |- အမြဲတမ်း ဖော်ရွေနွေးထွေးပြီး အားပေးတတ်သူဖြစ်ပါစေ။
        |- အသုံးပြုသူက ရှင်းလင်းစွာ မမေးဘဲ သို့မဟုတ် မခိုင်းဘဲနှင့် မလိုအပ်ဘဲ စကားတွေ လျှောက်ပြောခြင်း၊ မေးခွန်းတွေ လျှောက်မေးနေခြင်း လုံးဝ မပြုလုပ်ပါနှင့်။
        |- အသုံးပြုသူနှင့် အပေးအယူ နားလည်မှုရှိစွာဖြင့် လိုရင်းတိုရှင်း၊ တိကျပြတ်သားစွာသာ တုံ့ပြန်ပါ။
        |- စကားပြောတဲ့အခါ စက်ရုပ်လို မဟုတ်ဘဲ လူသားတစ်ယောက်လို ရင်းရင်းနှီးနှီး ယဉ်ကျေးစွာ ပြောပါ။ စကားရှည်ကြီးများ မပြောပါနှင့်။
        |- "I am an AI assistant" သို့မဟုတ် "As an AI..." စတဲ့ စကားလုံးတွေကို လုံးဝမသုံးပါနှင့်။
        |- အသုံးပြုသူကို လေးစားရတဲ့ ပါတနာ/မိတ်ဆွေတစ်ယောက်လို ဆက်ဆံပါ။ မခိုင်းပါက တိတ်ဆိတ်စွာ စောင့်ဆိုင်းပါ။
        |- ဒီ conversation မှာ ရှင်းပြပြီးသားအချက်တွေကို မလိုအပ်ဘဲ ထပ်မပြောပါနှင့်။
        |- ခိုင်းစေချက်များရှိပါက tool ကို ချက်ချင်းခေါ်ယူပြီး ရလဒ်ကို လိုရင်းတိုရှင်း အစီရင်ခံပါ။
    """.trimMargin(),
    private val onInputTranscript: (String) -> Unit = {},
    private val onOutputTranscript: (String) -> Unit = {},
    private val onAudioResponse: (String) -> Unit = {},
    private val onTurnComplete: () -> Unit = {},
    private val onSetupComplete: () -> Unit = {},
    private val onInterrupted: () -> Unit = {},
    private val onToolCall: (callId: String, commandType: String, target: String?, value: String?) -> Unit = { _, _, _, _ -> },
    private val onStatus: (String) -> Unit = {},
    private val onExecuteToolDirect: (suspend (commandType: String, target: String?, value: String?) -> String)? = null,
    /**
     * Phase 2 — server audio arriving within this window after a client-side
     * barge-in belongs to the interrupted generation and is dropped instead
     * of being played over the user. Exposed for unit tests.
     */
    private val interruptedTailDropMs: Long = 900L,
) : VoiceSession {

    private val normalizedModel = modelName.trim().removePrefix("models").removePrefix("/").let {
        if (it.isBlank()) DEFAULT_LIVE_MODEL else it
    }
    // Written from OkHttp callback threads by checkAndTriggerModelFallback,
    // read on Dispatchers.IO in connect(). Must be volatile.
    @Volatile private var model: String = "models/$normalizedModel"

    // Session resumption handle from the server's sessionResumptionUpdate
    // messages. Sent back in setup on reconnect so the server resumes the
    // same Live session (2h validity) instead of starting a fresh one.
    @Volatile private var resumptionHandle: String? = null

    // Single shared OkHttpClient is intentional: its dispatcher threads and
    // connection pool are reused across reconnects by design (not a leak).
    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        // Google Gemini Live WebSocket does not support standard WS ping frames;
        // keeping pingInterval=0 prevents false pong timeouts.
        // readTimeout is a READ-IDLE timeout: if the server goes completely silent
        // for 90 seconds the socket is treated as half-open/dead, onFailure fires,
        // and the existing auto-reconnect path (DesktopRuntime.scheduleLiveReconnect)
        // can recover. Without this, a dead connection looks "connected" forever.
        // (An app-level probe — probeLiveness() — catches the subtler case where
        // uplink audio flows but the server never answers, well before this fires.)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var connected = false
    @Volatile private var userDisconnectRequested = false
    private var webSocket: WebSocket? = null
    @Volatile private var setupCompleteReceived = false
    @Volatile private var setupSignal = CompletableDeferred<Unit>()
    private val audioBuffer = ByteArrayOutputStream()
    // 16kHz 16-bit mono PCM = 32,000 bytes/sec; cap pre-setup buffering at ~5 seconds.
    private val MAX_BUFFERED_AUDIO_BYTES = 160_000
    private val BUFFER_DRAIN_CHUNK_BYTES = 4096 // ~= 128ms per message
    private val restChatHistory = mutableListOf<JSONObject>()
    private val liveTurnText = StringBuilder()
    private val sentAudioChunks = java.util.concurrent.atomic.AtomicLong(0)
    private val failedAudioSends = java.util.concurrent.atomic.AtomicLong(0)
    private val receivedAudioChunks = java.util.concurrent.atomic.AtomicLong(0)
    /**
     * Last time any bytes arrived from the server (any message type).
     * The app-level liveness probe uses this because the shared OkHttpClient
     * intentionally disables WS pings — pingInterval must stay 0.
     */
    @Volatile private var lastServerActivityNanos = 0L

    // ---- Phase 2: client-side interruption protocol ----
    //
    // In automatic-VAD mode (our setup: realtimeInputConfig.automaticActivityDetection
    // is enabled), the Live API does NOT accept realtimeInput.activityStart /
    // activityEnd from the client — those fields are only valid when server-side
    // activity detection is disabled. So the client deliberately sends NO
    // interruption message: the forwarded barge-in audio itself (onset frame
    // first, never clipped) is the interruption signal. The server's VAD hears
    // the user, stops generating, and confirms with serverContent.interrupted.
    //
    // What the client CAN do: local playback is already stopped instantly by
    // WindowsAudioEngine, but a few audio chunks of the interrupted turn are
    // still in flight from the server. Playing them would talk over the user,
    // so they are dropped (see shouldSuppressServerAudio) until the server
    // confirms the interruption or the drop window expires.
    @Volatile private var clientBargeInAtNanos = 0L
    private val clientBargeInCount = java.util.concurrent.atomic.AtomicLong(0)
    private val droppedInterruptedTailChunks = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastUserTurnEndNanos = 0L

    /**
     * Called by DesktopRuntime when client VAD confirms the user interrupted
     * assistant playback (barge-in). Local playback is already stopped; this
     * marks the turn so the interrupted generation's in-flight audio tail is
     * dropped rather than played over the user.
     */
    fun notifyClientBargeIn() {
        clientBargeInAtNanos = System.nanoTime()
        val n = clientBargeInCount.incrementAndGet()
        if (n == 1L || n % 25L == 0L) {
            // Telemetry: counts only, never transcript content.
            DesktopLogger.info("Turn-taking telemetry: client barge-in #$n (awaiting server interrupted=true)")
        }
    }

    /**
     * Client VAD speech-end marker: turn-timing telemetry only. In
     * automatic-VAD mode the server derives end-of-turn from its own VAD
     * (silenceDurationMs); the client must NOT send audioStreamEnd here —
     * that signal means "mic turned off" and the mic stays open.
     */
    fun noteUserTurnEnd() {
        lastUserTurnEndNanos = System.nanoTime()
    }

    /**
     * True when an incoming server audio chunk should be discarded because it
     * belongs to the generation the user just interrupted. Chunks arriving
     * after the drop window are the new turn's audio and play normally; the
     * marker is also cleared when the server confirms via interrupted=true.
     */
    fun shouldSuppressServerAudio(): Boolean {
        val bargeInAt = clientBargeInAtNanos
        if (bargeInAt == 0L) return false
        val elapsedMs = (System.nanoTime() - bargeInAt) / 1_000_000
        if (elapsedMs > interruptedTailDropMs) {
            clientBargeInAtNanos = 0L // window expired — new-turn audio plays
            return false
        }
        val dropped = droppedInterruptedTailChunks.incrementAndGet()
        if (dropped == 1L || dropped % 50L == 0L) {
            DesktopLogger.info("Turn-taking telemetry: dropped $dropped interrupted-turn audio tail chunk(s)")
        }
        return true
    }

    private val restFallbackModels = listOf(
        "gemini-3-flash-preview",
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-2.5-flash-lite",
        "gemini-3.1-flash-lite-preview",
        "gemini-flash-latest",
        "gemma-4-26b-a4b-it",
    ).distinct()
    @Volatile private var currentRestModel: String = "gemini-3-flash-preview"

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            onStatus("GEMINI_API_KEY မထည့်ရသေးပါ")
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is missing"))
        }

        onStatus("Gemini Live ချိတ်ဆက်နေပါတယ်…")
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        userDisconnectRequested = false
        setupCompleteReceived = false
        connected = false
        setupSignal = CompletableDeferred()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastServerActivityNanos = System.nanoTime()
                onStatus("Live WebSocket Connected - Setup ပို့နေပါတယ်…")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val rawBytes = bytes.toByteArray()
                try {
                    val text = bytes.utf8()
                    if (text.trimStart().startsWith("{") || text.contains("\"setupComplete\"") || text.contains("\"serverContent\"") || text.contains("\"toolCall\"")) {
                        handleServerMessage(text)
                        return
                    }
                } catch (e: Exception) { }
                if (rawBytes.size > 50) {
                    lastServerActivityNanos = System.nanoTime()
                    val base64 = Base64.getEncoder().encodeToString(rawBytes)
                    onAudioResponse(base64)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                this@GeminiLiveSession.webSocket = null
                setupCompleteReceived = false
                setupSignal.completeExceptionally(IllegalStateException("Live socket closed: $code $reason"))
                val raw = "Closed: $code - $reason"
                if (code != 1000 && !userDisconnectRequested) {
                    checkAndTriggerModelFallback(raw)
                    DesktopLogger.warn("Unexpected WebSocket close ($code: $reason), signaling auto-reconnect")
                    onStatus("အင်တာနက် အခြေအနေကြောင့် ပြန်လည်ချိတ်ဆက်နေပါသည်… ($code)")
                } else {
                    onStatus("Disconnected")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                this@GeminiLiveSession.webSocket = null
                setupCompleteReceived = false
                setupSignal.completeExceptionally(t)
                val raw = t.message ?: "Unknown error"
                checkAndTriggerModelFallback(raw)
                val httpDetail = response?.code?.let { " HTTP $it" }.orEmpty()
                DesktopLogger.warn("Gemini Live WebSocket failure model=$model$httpDetail: $raw")
                if (!userDisconnectRequested) {
                    onStatus("အင်တာနက် ပြန်လည်ချိတ်ဆက်နေပါသည်…")
                } else {
                    onStatus("Live ချိတ်ဆက်မှု မအောင်မြင်ပါ$httpDetail — native voice မရနိုင်သေးပါ")
                }
            }
        })

        // setupSignal may complete exceptionally (onFailure/onClosed fire before
        // setup). Await would then throw — convert that into Result.failure so
        // callers always get a Result, never an unexpected exception.
        val ready = try {
            withTimeoutOrNull(45_000L) { setupSignal.await() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DesktopLogger.warn("Gemini Live setup failed before timeout: ${e.message}")
            false
        }
        if (!ready) {
            webSocket?.cancel()
            webSocket = null
            connected = false
            setupCompleteReceived = false
            onStatus("Gemini Live setup timeout — native voice မရနိုင်သေးပါ")
            Result.failure(IllegalStateException("Gemini Live setup timeout"))
        } else {
            Result.success(Unit)
        }
    }

    private fun checkAndTriggerModelFallback(errorText: String) {
        val isModelIssue = errorText.contains("404") ||
                errorText.contains("429") ||
                errorText.contains("quota", ignoreCase = true) ||
                errorText.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                errorText.contains("rate limit", ignoreCase = true) ||
                (errorText.contains("not found", ignoreCase = true) && errorText.contains("model", ignoreCase = true)) ||
                (errorText.contains("not supported", ignoreCase = true) && errorText.contains("model", ignoreCase = true))

        if (isModelIssue) {
            val currentBaseModel = model.removePrefix("models/")
            val fallbackList = listOf(
                "gemini-3.8-live",
                "gemini-3.1-flash-live-preview",
            )
            val currentIndex = fallbackList.indexOf(currentBaseModel)
            val nextModel = if (currentIndex != -1 && currentIndex < fallbackList.lastIndex) {
                "models/" + fallbackList[currentIndex + 1]
            } else if (currentIndex == -1) {
                "models/gemini-3.8-live"
            } else {
                null
            }
            if (nextModel != null && nextModel != model) {
                DesktopLogger.warn("Model $model failed with error: $errorText. Falling back to $nextModel for the next connection attempt.")
                model = nextModel
            }
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    // The Live API accepts exactly ONE response modality per session
                    // (AUDIO or TEXT, never both) — sending ["AUDIO","TEXT"]
                    // makes the server reject the setup and the client loops in
                    // "reconnecting" forever. Text for the conversation list
                    // comes from input/outputAudioTranscription below, which is
                    // the supported mechanism alongside AUDIO.
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    // Keep spoken replies short: ~300 tokens caps a turn at
                    // roughly 1–2 minutes of fast speech, well beyond the
                    // 1–2 sentence conversational target in the instructions.
                    put("maxOutputTokens", 300)
                    // S2S speed: disable the model's internal thinking pass.
                    // For real-time voice, thinking adds seconds to first
                    // audio with no conversational benefit at 1–2 sentences.
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voice)
                            })
                        })})
                    })

                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        // S2S speed: HIGH sensitivity on both ends of speech so
                        // turn-taking reacts in ~400 ms instead of ~700 ms.
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        put("prefixPaddingMs", 80)
                        put("silenceDurationMs", 400)
                    })
                })
                // Enable server-side session resumption: the server returns
                // resumption handles in sessionResumptionUpdate messages, so a
                // dropped socket can resume the same Live session instead of
                // paying a full setup round-trip on reconnect.
                put("sessionResumption", JSONObject().apply {
                    // Send the last known handle when reconnecting; the server
                    // resumes the session if the handle is still valid (2h).
                    resumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
                })
                // Sliding-window context compression: audio consumes ~25 tokens/s,
                // so a long conversation would exhaust the context window and kill
                // the session. Compression preserves system instructions and keeps
                // the session alive indefinitely.
                put("contextWindowCompression", JSONObject().apply {
                    put("slidingWindow", JSONObject())
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                if (allowDesktopTools) put("tools", desktopTools())
            })
        }
        val setupStr = setup.toString()
        DesktopLogger.info("Gemini Live setup sent model=$model bytes=${setupStr.toByteArray(Charsets.UTF_8).size} tools=$allowDesktopTools")
        ws.send(setupStr)
    }

    override fun sendAudioChunk(base64Pcm: String): Boolean {
        val ws = webSocket
        if (ws != null && setupCompleteReceived) {
            val realtimeInput = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    // Current Gemini Live API field. mediaChunks[] is deprecated.
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            }
            val sent = ws.send(realtimeInput.toString())
            if (sent) {
                val sentCount = sentAudioChunks.incrementAndGet()
                if (sentCount == 1L || sentCount % 50L == 0L) {
                    DesktopLogger.info("Live audio telemetry: sent=$sentCount failed=${failedAudioSends.get()} ready=true")
                }
            } else {
                failedAudioSends.incrementAndGet()
                DesktopLogger.warn("Live audio telemetry: ws.send() returned false (buffer full or closing); buffering chunk for reconnect drain")
                // The socket is dying — don't silently drop this 32ms of speech.
                // It will be re-sent by drainAudioBuffer() after reconnect.
                bufferAudioChunk(base64Pcm)
            }
            return sent
        } else {
            bufferAudioChunk(base64Pcm)
            // Buffered, NOT sent: report false so callers never mistake this for delivery.
            return false
        }
    }

    /**
     * Holds raw PCM (decoded from base64) for later re-send, capped so a long
     * outage can't grow memory without bound. Newest speech is kept: when the
     * cap is hit the stale prefix is dropped, not the fresh audio.
     */
    private fun bufferAudioChunk(base64Pcm: String) {
        synchronized(audioBuffer) {
            runCatching {
                val bytes = Base64.getDecoder().decode(base64Pcm)
                if (audioBuffer.size() + bytes.size > MAX_BUFFERED_AUDIO_BYTES) {
                    DesktopLogger.warn("Audio buffer full (${audioBuffer.size()} bytes); dropping stale buffered audio")
                    audioBuffer.reset()
                }
                audioBuffer.write(bytes)
            }
        }
    }

    /**
     * Sends audio that was buffered while the socket was not ready.
     * Called once when setup completes.
     */
    private fun drainAudioBuffer() {
        val bytes: ByteArray = synchronized(audioBuffer) {
            if (audioBuffer.size() == 0) return
            audioBuffer.toByteArray().also { audioBuffer.reset() }
        }
        val encoder = Base64.getEncoder()
        var offset = 0
        var chunks = 0
        // 16kHz 16-bit mono = 32,000 bytes/sec; 4096 bytes ~= 128ms per message.
        while (offset < bytes.size) {
            val end = minOf(offset + BUFFER_DRAIN_CHUNK_BYTES, bytes.size)
            if (!sendAudioChunk(encoder.encodeToString(bytes.copyOfRange(offset, end)))) {
                // Socket dropped mid-drain. sendAudioChunk() already re-buffered
                // the failed chunk; preserve everything after it too, otherwise
                // the tail of the user's speech would be silently lost.
                val remaining = bytes.size - end
                if (remaining > 0) {
                    synchronized(audioBuffer) {
                        audioBuffer.write(bytes, end, remaining)
                    }
                }
                break
            }
            chunks++
            offset = end
        }
        DesktopLogger.info("Drained $chunks buffered audio chunks ($offset of ${bytes.size} bytes sent)")
    }

    override fun clearAudioBuffer() {
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
    }

    override suspend fun flushAudioTurn(): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            onStatus("GEMINI_API_KEY မထည့်ရသေးပါ")
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is missing"))
        }

        val ws = webSocket
        if (ws != null && setupCompleteReceived) {
            val audioStreamEnd = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            ws.send(audioStreamEnd.toString())
            return@withContext Result.success(Unit)
        }

        // Never convert microphone audio into a REST request. Voice mode is
        // intentionally native Live audio-to-audio only; callers can surface
        // this state and ask the user to reconnect instead of silently falling
        // back to transcription plus TTS.
        Result.failure(IllegalStateException("Gemini Live native audio is not ready"))
    }

    override suspend fun sendText(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            onStatus("GEMINI_API_KEY မထည့်ရသေးပါ")
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is missing"))
        }
        onInputTranscript(text)

        sendClientText(text)
    }

    override suspend fun sendAssistantPrompt(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            onStatus("GEMINI_API_KEY မထည့်ရသေးပါ")
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is missing"))
        }

        DesktopLogger.info("Sending assistant greeting prompt; liveReady=${isLiveReady()}")
        val result = sendClientText(text)
        if (result.isFailure) {
            DesktopLogger.warn("Assistant greeting prompt failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
        }
        result
    }

    private suspend fun sendClientText(text: String): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)

        val ws = webSocket
        if (ws != null && setupCompleteReceived) {
            onStatus("Gemini Live သို့ ပို့နေပါတယ်…")
            val clientContent = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", text) })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            val sent = ws.send(clientContent.toString())
            if (sent) return Result.success(Unit)
        }

        // Reliable Multi-Turn REST Fallback
        return executeRestChat(text)
    }

    private fun executeGenerateContent(requestBodyJson: JSONObject): Pair<Int, String> {
        val modelsToTry = buildList {
            add(currentRestModel)
            addAll(restFallbackModels.filter { it != currentRestModel })
        }
        var lastCode = 500
        var lastBody = ""
        for (m in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$apiKey"
            val req = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .build()
            try {
                val response = client.newCall(req).execute()
                val code = response.code
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    currentRestModel = m
                    return Pair(code, body)
                }
                lastCode = code
                lastBody = body
                val err = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                val isModelIssue = code == 404 || code == 429 || (code == 400 && (
                    err.contains("no longer available", ignoreCase = true) ||
                    err.contains("not found", ignoreCase = true) ||
                    err.contains("not supported", ignoreCase = true) ||
                    err.contains("is deprecated", ignoreCase = true)
                )) || err.contains("quota", ignoreCase = true) || err.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
                if (isModelIssue) {
                    DesktopLogger.warn("Model $m failed ($code: $err), falling back to next available model...")
                    continue
                } else {
                    return Pair(code, body)
                }
            } catch (e: Exception) {
                lastBody = e.message.orEmpty()
            }
        }
        return Pair(lastCode, lastBody)
    }

    suspend fun executeRestChat(userPrompt: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onStatus("အဖြေကို စဉ်းစားတွေးခေါ်နေပါတယ်…")
            val userTurn = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userPrompt) })
                })
            }
            restChatHistory.add(userTurn)
            if (restChatHistory.size > 20) {
                while (restChatHistory.size > 14) restChatHistory.removeAt(0)
            }

            val requestBodyJson = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", JSONArray(restChatHistory.map { JSONObject(it.toString()) }))
                put("generationConfig", JSONObject().apply { put("maxOutputTokens", 300) })
                if (allowDesktopTools) put("tools", desktopTools())
            }

            val (code, responseBody) = executeGenerateContent(requestBodyJson)
            if (code !in 200..299) {
                val err = runCatching { JSONObject(responseBody).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                onStatus("Error: $err")
                error(err)
            }

            val respJson = JSONObject(responseBody)
            val candidates = respJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                onOutputTranscript("တုံ့ပြန်မှု မရှိပါ။")
                onStatus("အသင့်ဖြစ်ပါပြီ")
                return@runCatching
            }

            var currentResponseCandidate = candidates.getJSONObject(0)
            var recursionCount = 0
            val maxRecursion = 6
            var assistantText = ""

            while (recursionCount < maxRecursion) {
                recursionCount++
                val content = currentResponseCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts") ?: break

                var hasFunctionCall = false
                val functionCalls = mutableListOf<JSONObject>()
                var turnText = ""

                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.has("text")) {
                        turnText += p.getString("text")
                    }
                    if (p.has("functionCall")) {
                        hasFunctionCall = true
                        functionCalls.add(p)
                    }
                }

                if (turnText.isNotBlank()) {
                    assistantText = turnText
                }

                if (!hasFunctionCall || onExecuteToolDirect == null) {
                    break
                }

                val modelTurn = JSONObject().apply {
                    put("role", "model")
                    put("parts", parts)
                }
                restChatHistory.add(modelTurn)

                val functionResponseParts = JSONArray()
                for (fcPart in functionCalls) {
                    val fc = fcPart.getJSONObject("functionCall")
                    val args = fc.optJSONObject("args")
                    val cmdType = args?.optString("command_type")?.takeIf { it.isNotBlank() } ?: fc.optString("name")
                    val target = args?.optString("target")?.takeIf { it.isNotBlank() }
                    val value = args?.optString("value")?.takeIf { it.isNotBlank() }

                    // REST fallback path: execute exactly once via onExecuteToolDirect.
                    // (onToolCall is the Live-WebSocket flow; invoking it here too
                    // would execute the same command a second time.)
                    val toolResult = onExecuteToolDirect.invoke(cmdType, target, value)

                    functionResponseParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", "execute_desktop_command")
                            put("response", JSONObject().apply {
                                put("result", toolResult)
                            })
                        })
                    })
                }

                val funcResponseTurn = JSONObject().apply {
                    put("role", "user")
                    put("parts", functionResponseParts)
                }
                restChatHistory.add(funcResponseTurn)

                val followUpReqJson = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
                    })
                    put("contents", JSONArray(restChatHistory.map { JSONObject(it.toString()) }))
                    put("generationConfig", JSONObject().apply { put("maxOutputTokens", 300) })
                    if (allowDesktopTools) put("tools", desktopTools())
                }

                val (fuCode, followUpBody) = executeGenerateContent(followUpReqJson)
                if (fuCode !in 200..299) {
                    DesktopLogger.warn("Chained tool follow-up generation failed: HTTP $fuCode")
                    break
                }

                val fuJson = JSONObject(followUpBody)
                val nextCandidate = fuJson.optJSONArray("candidates")?.optJSONObject(0)
                if (nextCandidate != null) {
                    currentResponseCandidate = nextCandidate
                } else {
                    break
                }
            }

            if (assistantText.isNotBlank()) {
                val modelResp = JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", assistantText) })
                    })
                }
                restChatHistory.add(modelResp)
                onOutputTranscript(assistantText)
            }
            onTurnComplete()
            onStatus("အသင့်ဖြစ်ပါပြီ")
        }
    }

    suspend fun executeRestAudio(base64Wav: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onStatus("အသံဖိုင်ကို နားထောင်ပြီး အဖြေထုတ်နေပါတယ်…")
            val audioPart = JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", base64Wav)
                })
            }
            val textPrompt = JSONObject().apply {
                put("text", "ကျေးဇူးပြု၍ ဤအသံကို နားထောင်ပြီး အသုံးပြုသူ ခိုင်းစေသော/မေးမြန်းသော အရာကို မြန်မာစကားပြော ကွန်ပျူတာ လက်ထောက် အနေဖြင့် တုံ့ပြန်/လုပ်ဆောင်ပေးပါ။ တိုတိုနှင့် ၁–၂ ကြောင်းသာ ဖြေပါ။")
            }

            val requestBodyJson = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(audioPart)
                            put(textPrompt)
                        })
                    })
                })
                put("generationConfig", JSONObject().apply { put("maxOutputTokens", 300) })
                if (allowDesktopTools) put("tools", desktopTools())
            }

            val (code, responseBody) = executeGenerateContent(requestBodyJson)
            if (code !in 200..299) {
                val err = runCatching { JSONObject(responseBody).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                onStatus("Error: $err")
                error(err)
            }

            val respJson = JSONObject(responseBody)
            val candidates = respJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")

                var assistantText = ""
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.has("text")) {
                            assistantText += p.getString("text")
                        }
                        if (p.has("functionCall")) {
                            val fc = p.getJSONObject("functionCall")
                            val args = fc.optJSONObject("args")
                            val cmdType = args?.optString("command_type")?.takeIf { it.isNotBlank() } ?: fc.optString("name")
                            val target = args?.optString("target")?.takeIf { it.isNotBlank() }
                            val value = args?.optString("value")?.takeIf { it.isNotBlank() }

                            // REST fallback path: execute exactly once via onExecuteToolDirect.
                            // (onToolCall is the Live-WebSocket flow; invoking it here too
                            // would execute the same command a second time.)
                            if (onExecuteToolDirect != null) {
                                val toolResult = onExecuteToolDirect.invoke(cmdType, target, value)
                                val fuJson = JSONObject().apply {
                                    put("systemInstruction", JSONObject().apply {
                                        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
                                    })
                                    put("generationConfig", JSONObject().apply { put("maxOutputTokens", 300) })
                                    put("contents", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("role", "user")
                                            put("parts", JSONArray().apply {
                                                put(audioPart)
                                                put(textPrompt)
                                            })
                                        })
                                        put(JSONObject().apply {
                                            put("role", "model")
                                            put("parts", JSONArray().apply { put(p) })
                                        })
                                        put(JSONObject().apply {
                                            put("role", "user")
                                            put("parts", JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("functionResponse", JSONObject().apply {
                                                        put("name", "execute_desktop_command")
                                                        put("response", JSONObject().apply { put("result", toolResult) })
                                                    })
                                                })
                                            })
                                        })
                                    })
                                }
                                val (fuCode, followUpBody) = executeGenerateContent(fuJson)
                                if (fuCode in 200..299) {
                                    val fuCandidate = JSONObject(followUpBody).optJSONArray("candidates")?.optJSONObject(0)
                                    val fuText = fuCandidate?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                                    if (!fuText.isNullOrBlank()) {
                                        assistantText = fuText
                                    }
                                }
                            }
                        }
                    }
                }
                if (assistantText.isNotBlank()) {
                    onOutputTranscript(assistantText)
                }
            }
            onTurnComplete()
            onStatus("အသင့်ဖြစ်ပါပြီ")
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        return header + pcmData
    }

    suspend fun sendImageWithPrompt(base64Jpg: String, prompt: String): Result<Unit> = withContext(Dispatchers.IO) {
        onStatus("စခရင် ပုံရိပ် သုံးသပ်နေပါတယ်…")
        onInputTranscript("🖼️ [စခရင် ဖတ်ရှုခြင်း]: $prompt")

        val ws = webSocket
        if (ws != null && setupCompleteReceived) {
            val imageInput = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpg)
                        })
                    })
                })
            }
            ws.send(imageInput.toString())

            val textInput = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            ws.send(textInput.toString())
            return@withContext Result.success(Unit)
        }

        // REST Vision Fallback
        return@withContext runCatching {
            val contentJson = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpg)
                        })
                    })
                    put(JSONObject().apply {
                        put("text", prompt)
                    })
                })
            }

            val requestBodyJson = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", JSONArray().apply { put(contentJson) })
                put("generationConfig", JSONObject().apply { put("maxOutputTokens", 300) })
            }

            val (code, responseBody) = executeGenerateContent(requestBodyJson)
            if (code in 200..299) {
                val respJson = JSONObject(responseBody)
                val textResp = respJson.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                if (textResp.isNotBlank()) {
                    onOutputTranscript(textResp)
                }
            } else {
                val err = runCatching { JSONObject(responseBody).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Vision Error $code"
                onOutputTranscript("စခရင် ဖတ်ရှုရာတွင် အခက်အခဲရှိပါသည်: $err")
            }
            onTurnComplete()
            onStatus("အသင့်ဖြစ်ပါပြီ")
        }
    }

    private fun handleServerMessage(text: String) {
        lastServerActivityNanos = System.nanoTime()
        // Parse once up front. A transcript that merely CONTAINS the word "error"
        // (e.g. the user saying "there was an error") must not kill the session,
        // so only treat the message as a server error when the JSON actually
        // carries an "error" key (or the payload is not JSON at all).
        val jsonOrNull = runCatching { JSONObject(text) }.getOrNull()
        val isServerError = jsonOrNull?.has("error") == true ||
            (jsonOrNull == null && text.contains("\"error\""))
        if (isServerError) {
            DesktopLogger.warn("Gemini Live server error: $text")
            checkAndTriggerModelFallback(text)
            val err = jsonOrNull?.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() } ?: text.take(200)
            setupSignal.completeExceptionally(IllegalStateException(err))
            onStatus("Live Error: $err")
            return
        }

        if (jsonOrNull?.has("setupComplete") == true || text.contains("\"setupComplete\"")) {
            setupCompleteReceived = true
            connected = true
            setupSignal.complete(Unit)
            // Flush any audio captured while the socket was not ready.
            drainAudioBuffer()
            onStatus("Gemini Live Mode အသင့်ဖြစ်ပါပြီ")
            onSetupComplete()
            return
        }

        // Session resumption: the server periodically sends a fresh handle
        // (when resumable=true). Store the latest so reconnect can resume
        // the same session instead of paying a full setup round-trip.
        // Note: no early return here — the server may combine this update
        // with other top-level content in one event.
        jsonOrNull?.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle", "").takeIf { it.isNotBlank() }
            if (update.optBoolean("resumable", false) && handle != null) {
                resumptionHandle = handle
                DesktopLogger.info("Gemini Live session resumption handle updated")
            }
        }

        runCatching {
            val json = jsonOrNull ?: JSONObject(text)

            if (json.has("toolCall")) {
                val toolCall = json.getJSONObject("toolCall")
                if (toolCall.has("functionCalls")) {
                    val functionCalls = toolCall.getJSONArray("functionCalls")
                    for (i in 0 until functionCalls.length()) {
                        val fc = functionCalls.getJSONObject(i)
                        val callId = fc.optString("id", "call_${System.currentTimeMillis()}_$i")
                        val args = fc.optJSONObject("args")
                        onToolCall(
                            callId,
                            args?.optString("command_type")?.takeIf { it.isNotBlank() } ?: fc.optString("name"),
                            args?.optString("target")?.takeIf { it.isNotBlank() },
                            args?.optString("value")?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }

            if (!json.has("serverContent")) return

            val serverContent = json.getJSONObject("serverContent")
            if (serverContent.optBoolean("interrupted", false)) {
                synchronized(liveTurnText) { liveTurnText.setLength(0) }
                // Server confirmed the interruption: stop dropping audio —
                // anything arriving now belongs to the new turn.
                clientBargeInAtNanos = 0L
                onInterrupted()
            }

            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    val clean = raw.trim()
                    if (!clean.contains("<no speech detected>", ignoreCase = true)) {
                        onInputTranscript(clean)
                    }
                }

            val outTranscript = serverContent.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
            if (outTranscript != null && !outTranscript.contains("<no speech detected>", ignoreCase = true)) {
                val fullText = synchronized(liveTurnText) {
                    if (!liveTurnText.contains(outTranscript)) {
                        if (liveTurnText.isNotEmpty() && !liveTurnText.endsWith(" ")) liveTurnText.append(" ")
                        liveTurnText.append(outTranscript)
                    }
                    liveTurnText.toString()
                }
                onOutputTranscript(fullText)
            }

            val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
            if (modelTurn != null && modelTurn.has("parts")) {
                val parts = modelTurn.getJSONArray("parts")
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    if (inlineData != null && inlineData.has("data")) {
                        val base64Audio = inlineData.getString("data")
                        if (base64Audio.isNotEmpty()) {
                            onAudioResponse(base64Audio)
                            val audioCount = receivedAudioChunks.incrementAndGet()
                            if (audioCount == 1L || audioCount % 20L == 0L) {
                                DesktopLogger.info("Live audio telemetry: modelAudioChunks=$audioCount")
                            }
                        }
                    }
                    if (part.has("text") && !part.optBoolean("thought", false)) {
                        val partText = part.optString("text", "").trim()
                        if (partText.isNotBlank() && !partText.contains("<no speech detected>", ignoreCase = true)) {
                            val fullText = synchronized(liveTurnText) {
                                if (!liveTurnText.contains(partText)) {
                                    if (liveTurnText.isNotEmpty() && !liveTurnText.endsWith(" ")) liveTurnText.append(" ")
                                    liveTurnText.append(partText)
                                }
                                liveTurnText.toString()
                            }
                            onOutputTranscript(fullText)
                        }
                    }
                    if (part.has("functionCall")) {
                        val fc = part.getJSONObject("functionCall")
                        val callId = fc.optString("id", "call_${System.currentTimeMillis()}_$i")
                        val args = fc.optJSONObject("args")
                        onToolCall(
                            callId,
                            args?.optString("command_type")?.takeIf { it.isNotBlank() } ?: fc.optString("name"),
                            args?.optString("target")?.takeIf { it.isNotBlank() },
                            args?.optString("value")?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }

            val isTurnComplete = serverContent.optBoolean("turnComplete", false) || serverContent.optBoolean("turn_complete", false)
            if (isTurnComplete) {
                DesktopLogger.info("Live audio telemetry: serverTurnComplete=true sent=$sentAudioChunks modelAudio=$receivedAudioChunks")
                synchronized(liveTurnText) { liveTurnText.setLength(0) }
                onTurnComplete()
            }
        }
    }

    fun sendToolResponse(callId: String, result: String) {
        val ws = webSocket ?: return
        val functionResponse = JSONObject().apply {
            if (callId.isNotBlank()) put("id", callId)
            put("name", "execute_desktop_command")
            put("response", JSONObject().apply {
                put("result", result)
            })
        }
        val toolResponse = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply { put(functionResponse) })
            })
        }
        ws.send(toolResponse.toString())
    }

    override fun isLiveReady(): Boolean = connected && setupCompleteReceived && webSocket != null

    /**
     * App-level liveness probe. The shared OkHttpClient intentionally keeps
     * pingInterval=0 (Gemini Live does not support WS ping frames), so a
     * half-open socket is detected here instead: the connection only counts
     * as live when the server has been heard from within
     * [serverIdleThresholdMs]. DesktopRuntime reconnects when uplink audio is
     * flowing but this probe fails.
     */
    fun probeLiveness(serverIdleThresholdMs: Long = 10_000L): Boolean =
        isLiveReady() && lastServerActivityElapsedMs() <= serverIdleThresholdMs

    fun lastServerActivityElapsedMs(): Long {
        val last = lastServerActivityNanos
        return if (last == 0L) Long.MAX_VALUE else (System.nanoTime() - last) / 1_000_000L
    }

    override fun disconnect() {
        userDisconnectRequested = true
        connected = false
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        setupCompleteReceived = false
        onStatus("Disconnected")
    }

    companion object {
        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"

        suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("Gemini API key မထည့်ရသေးပါ"))
            runCatching {
                val httpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
                val encodedKey = java.net.URLEncoder.encode(cleanKey, Charsets.UTF_8)
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$encodedKey")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = JSONObject(body).optJSONObject("error")?.optString("message")
                            ?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
                        error("Gemini API key မအလုပ်လုပ်ပါ: $detail")
                    }
                    "Gemini API key အလုပ်လုပ်ပါတယ်။ API access အောင်မြင်ပါပြီ။"
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private fun desktopTools(): JSONArray {
        val commandTypes = arrayOf(
            "mouse_click", "mouse_double_click", "mouse_right_click", "mouse_move", "mouse_scroll", "mouse_drag",
            "click_ui_element", "inspect_window_ui", "snap_window_left", "snap_window_right", "snap_window_up", "snap_window_down", "new_tab", "switch_tab",
            "click_desktop_icon", "close_desktop_icon", "toggle_app", "click_normalized", "show_desktop", "type_text_physical", "screen_eyes",
            "open_app", "close_app", "search_web", "search_youtube", "search_files", "find_file", "open_file", "open_url",
            "open_folder", "open_downloads", "open_documents", "open_desktop", "open_recycle_bin", "empty_recycle_bin",
            "get_current_time", "get_current_date", "open_settings", "open_network_settings", "open_bluetooth_settings",
            "open_display_settings", "open_sound_settings", "take_screenshot", "volume_up", "volume_down", "mute",
            "lock_computer", "shutdown", "restart", "sleep", "system_status", "get_system_info", "diagnose_network",
            "get_battery_status", "list_running_apps", "copy_to_clipboard", "run_powershell_safe", "refresh_file_index",
            "get_active_window",
            "remember_user_fact", "get_user_memory", "run_voice_routine", "run_work_macro", "switch_user_profile",
            "analyze_screen", "read_clipboard", "media_play_pause", "media_next", "media_prev", "minimize_all", "maximize_window",
            "minimize_window", "close_window", "close_tab", "brightness_up", "brightness_down",
            "execute_goal", "chain_commands", "cancel_goal"
        )
        val properties = JSONObject()
            .put("command_type", JSONObject().put("type", "string").put("enum", JSONArray(commandTypes.toList())).put("description", "Choose exactly one action from the enum. Do not call a tool for ordinary conversation."))
            .put("target", JSONObject().put("type", "string").put("description", "Target: app name, memory fact key/query, macro name, profile name, file query, URL, PowerShell script, path"))
            .put("value", JSONObject().put("type", "string").put("description", "Value: memory fact value, optional secondary value"))
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().put("command_type"))
        val declaration = JSONObject()
            .put("name", "execute_desktop_command")
            .put("description", "Execute a Windows desktop action, system status check, or macro.")
            .put("parameters", schema)
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(declaration)))
    }
}
