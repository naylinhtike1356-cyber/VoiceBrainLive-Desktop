package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.VoiceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveSession(
    private val apiKey: String,
    private val model: String = "models/gemini-3.1-flash-live-preview",
    private val voice: String = "Aoede",
    private val systemInstruction: String = "You are VoiceBrainLive, a helpful Burmese voice assistant.",
    private val onInputTranscript: (String) -> Unit = {},
    private val onOutputTranscript: (String) -> Unit = {},
    private val onAudioResponse: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
) : VoiceSession {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var setupComplete = false
    private var connectWaiter: CompletableDeferred<Result<Unit>>? = null

    private val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not configured"))
        if (socket != null && setupComplete) return@withContext Result.success(Unit)

        val waiter = CompletableDeferred<Result<Unit>>()
        connectWaiter = waiter
        val request = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(request, listener)
        waiter.await()
    }

    override suspend fun sendText(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!setupComplete) return@withContext Result.failure(IllegalStateException("Gemini session is not connected"))
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("text", text),
        )
        if (socket?.send(message.toString()) == true) Result.success(Unit)
        else Result.failure(IllegalStateException("Gemini WebSocket is closed"))
    }

    fun sendAudioChunk(base64Pcm: String): Boolean {
        if (!setupComplete) return false
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("data", base64Pcm)
                    .put("mimeType", "audio/pcm;rate=16000"),
            ),
        )
        return socket?.send(message.toString()) == true
    }

    fun stopAudioTurn() {
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
    }

    override fun disconnect() {
        setupComplete = false
        connectWaiter = null
        socket?.close(1000, "User disconnected")
        socket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("WebSocket connected; configuring…")
            val setup = JSONObject().put(
                "setup",
                JSONObject()
                    .put("model", model)
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("systemInstruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", systemInstruction)),
                    ))
                    .put("speechConfig", JSONObject().put(
                        "voiceConfig", JSONObject().put(
                            "prebuiltVoiceConfig", JSONObject().put("voiceName", voice),
                        ),
                    ))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject()),
            )
            webSocket.send(setup.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            setupComplete = false
            onStatus("Gemini error: ${t.message ?: "connection failed"}")
            connectWaiter?.complete(Result.failure(t))
            connectWaiter = null
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            setupComplete = false
            onStatus("Disconnected: $reason")
        }
    }

    private fun handleMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return

        if (message.has("setupComplete")) {
            setupComplete = true
            onStatus("Connected")
            connectWaiter?.complete(Result.success(Unit))
            connectWaiter = null
            return
        }

        val content = message.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(onInputTranscript)
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(onOutputTranscript)

        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (index in 0 until parts.length()) {
            val inlineData = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
            if (inlineData.optString("mimeType").startsWith("audio/pcm")) {
                inlineData.optString("data").takeIf { it.isNotBlank() }?.let(onAudioResponse)
            }
        }
    }
}
