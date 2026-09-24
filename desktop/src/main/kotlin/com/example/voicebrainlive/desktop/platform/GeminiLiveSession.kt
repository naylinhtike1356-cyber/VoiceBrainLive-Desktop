package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val systemInstruction: String = """မင်းက VoiceBrainLive Intelligence Partner (အသိဉာဏ်ရှိတဲ့ နည်းပညာ ပါတနာ) ဖြစ်တယ်။ Gemini လို generic AI assistant မျိုးမဟုတ်ဘဲ အသုံးပြုသူရဲ့ ကွန်ပျူတာရှေ့မှာ အမြဲရှိနေပေးပြီး လိုအပ်တာမှန်သမျှ ကူညီပေးမယ့် ရင်းနှီးတဲ့ အဖော်တစ်ယောက်လို နွေးထွေးဖော်ရွေစွာ ပြောဆိုပါ။
        |
        |PERSONALITY & FAST SPOKEN CONVERSATION (စရိုက်နှင့် အပြန်အလှန် လျင်မြန်စွာ ပြောဆိုဆွေးနွေးခြင်း):
        |- အမြဲတမ်း ဖော်ရွေနွေးထွေးပြီး အားပေးတတ်သူဖြစ်ပါစေ။
        |- စကားပြောတဲ့အခါ စက်ရုပ်လို မဟုတ်ဘဲ လူသားတစ်ယောက်လို ရင်းရင်းနှီးနှီး ယဉ်ကျေးစွာ ပြောပါ။
        |- စကားပြောဆို ဆွေးနွေးရာတွင် လိုရင်းတိုရှင်း၊ သဘာဝကျကျ၊ မြန်ဆန်သွက်လက်စွာ ဖြေကြားပါ။ စကားရှည်ကြီးများ မပြောပါနှင့်။
        |- "I am an AI assistant" သို့မဟုတ် "As an AI..." စတဲ့ စကားလုံးတွေကို လုံးဝမသုံးပါနှင့်။
        |- အသုံးပြုသူကို လေးစားရတဲ့ ပါတနာ/မိတ်ဆွေတစ်ယောက်လို ဆက်ဆံပါ။
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
) : VoiceSession {

    private val normalizedModel = modelName.trim().removePrefix("models").removePrefix("/").let {
        if (it.isBlank()) DEFAULT_LIVE_MODEL else it
    }
    private var model: String = "models/$normalizedModel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        // Give the Live server enough time to complete setup and avoid
        // false ping failures during a slow native-audio handshake.
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var connected = false
    private var webSocket: WebSocket? = null
    @Volatile private var setupCompleteReceived = false
    @Volatile private var setupSignal = CompletableDeferred<Unit>()
    private val audioBuffer = ByteArrayOutputStream()
    private val restChatHistory = mutableListOf<JSONObject>()
    private val liveTurnText = StringBuilder()

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

        setupCompleteReceived = false
        connected = false
        setupSignal = CompletableDeferred()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                if (code != 1000) {
                    checkAndTriggerModelFallback(raw)
                    onStatus("Disconnected ($code)")
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
                onStatus("Live ချိတ်ဆက်မှု မအောင်မြင်ပါ$httpDetail — native voice မရနိုင်သေးပါ")
            }
        })

        val ready = withTimeoutOrNull(20_000L) { setupSignal.await() } != null
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
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voice)
                            })
                        })
                    })
                })
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("prefixPaddingMs", 80)
                        put("silenceDurationMs", 700)
                    })
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
        DesktopLogger.info("Gemini Live Setup sent: $setupStr")
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
            return ws.send(realtimeInput.toString())
        } else {
            synchronized(audioBuffer) {
                runCatching {
                    val bytes = Base64.getDecoder().decode(base64Pcm)
                    audioBuffer.write(bytes)
                }
            }
        }
        return true
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
            onStatus("Live Audio မေးမြန်းနေပါတယ်…")
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
            if (sent) return@withContext Result.success(Unit)
        }

        // Reliable Multi-Turn REST Fallback
        return@withContext executeRestChat(text)
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
                        val callId = "call_${System.currentTimeMillis()}_$i"
                        val args = fc.optJSONObject("args")
                        val cmdType = args?.optString("command_type")?.takeIf { it.isNotBlank() } ?: fc.optString("name")
                        val target = args?.optString("target")?.takeIf { it.isNotBlank() }
                        val value = args?.optString("value")?.takeIf { it.isNotBlank() }

                        onToolCall(callId, cmdType, target, value)

                        if (onExecuteToolDirect != null) {
                            val toolResult = onExecuteToolDirect.invoke(cmdType, target, value)
                            val modelTurn = JSONObject().apply {
                                put("role", "model")
                                put("parts", JSONArray().apply { put(p) })
                            }
                            restChatHistory.add(modelTurn)

                            val funcResponseTurn = JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("functionResponse", JSONObject().apply {
                                            put("name", "execute_desktop_command")
                                            put("response", JSONObject().apply {
                                                put("result", toolResult)
                                            })
                                        })
                                    })
                                })
                            }
                            restChatHistory.add(funcResponseTurn)

                            val followUpReqJson = JSONObject().apply {
                                put("systemInstruction", JSONObject().apply {
                                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
                                })
                                put("contents", JSONArray(restChatHistory.map { JSONObject(it.toString()) }))
                            }
                            val (fuCode, followUpBody) = executeGenerateContent(followUpReqJson)
                            if (fuCode in 200..299) {
                                val fuJson = JSONObject(followUpBody)
                                val fuCandidate = fuJson.optJSONArray("candidates")?.optJSONObject(0)
                                val fuParts = fuCandidate?.optJSONObject("content")?.optJSONArray("parts")
                                val fuText = fuParts?.optJSONObject(0)?.optString("text")
                                if (!fuText.isNullOrBlank()) {
                                    assistantText = fuText
                                }
                            }
                        }
                    }
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
                put("text", "ကျေးဇူးပြု၍ ဤအသံကို နားထောင်ပြီး အသုံးပြုသူ ခိုင်းစေသော/မေးမြန်းသော အရာကို မြန်မာစကားပြော ကွန်ပျူတာ လက်ထောက် အနေဖြင့် တုံ့ပြန်/လုပ်ဆောင်ပေးပါ။")
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
                            val callId = "call_${System.currentTimeMillis()}_$i"
                            val args = fc.optJSONObject("args")
                            val cmdType = args?.optString("command_type")?.takeIf { it.isNotBlank() } ?: fc.optString("name")
                            val target = args?.optString("target")?.takeIf { it.isNotBlank() }
                            val value = args?.optString("value")?.takeIf { it.isNotBlank() }

                            onToolCall(callId, cmdType, target, value)

                            if (onExecuteToolDirect != null) {
                                val toolResult = onExecuteToolDirect.invoke(cmdType, target, value)
                                val fuJson = JSONObject().apply {
                                    put("systemInstruction", JSONObject().apply {
                                        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
                                    })
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
        if (text.contains("\"error\"")) {
            DesktopLogger.warn("Gemini Live server error: $text")
            checkAndTriggerModelFallback(text)
            val err = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: text.take(200)
            setupSignal.completeExceptionally(IllegalStateException(err))
            onStatus("Live Error: $err")
            return
        }

        if (text.contains("\"setupComplete\"")) {
            setupCompleteReceived = true
            connected = true
            setupSignal.complete(Unit)
            onStatus("Gemini Live Mode အသင့်ဖြစ်ပါပြီ")
            onSetupComplete()
            return
        }

        runCatching {
            val json = JSONObject(text)

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

    override fun disconnect() {
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
            "open_app", "close_app", "search_web", "search_youtube", "search_files", "find_file", "open_file", "open_url",
            "open_folder", "open_downloads", "open_documents", "open_desktop", "open_recycle_bin", "empty_recycle_bin",
            "get_current_time", "get_current_date", "open_settings", "open_network_settings", "open_bluetooth_settings",
            "open_display_settings", "open_sound_settings", "take_screenshot", "volume_up", "volume_down", "mute",
            "lock_computer", "shutdown", "restart", "sleep", "system_status", "get_system_info", "diagnose_network",
            "get_battery_status", "list_running_apps", "copy_to_clipboard", "run_powershell_safe", "refresh_file_index",
            "get_active_window", "solve_project_issue", "auto_heal_project", "launch_coding_agent", "run_android_build_test",
            "verify_app_on_emulator", "git_commit_fix", "check_adb_devices", "notion_test", "notion_search",
            "notion_get_page_content", "notion_create_page", "notion_add_note", "notion_create_task", "notion_append_note",
            "notion_append_to_page", "notion_update_page_title", "notion_archive_page", "notion_delete_page", "notion_delete_block",
            "open_notion_page", "remember_user_fact", "get_user_memory", "run_work_macro", "switch_user_profile", "analyze_screen",
            "read_clipboard", "media_play_pause", "media_next", "media_prev", "minimize_all", "maximize_window", "minimize_window",
            "close_window", "close_tab", "brightness_up", "brightness_down"
        )
        val properties = JSONObject()
            .put("command_type", JSONObject().put("type", "string").put("enum", JSONArray(commandTypes.toList())).put("description", "Choose exactly one action from the enum. Do not call a tool for ordinary conversation."))
            .put("target", JSONObject().put("type", "string").put("description", "Target: Project name, app name, memory fact key/query, macro name, profile name, file query, URL, Notion query/page ID/block ID/page title, PowerShell script, path"))
            .put("value", JSONObject().put("type", "string").put("description", "Value: Issue description for project, page content, new page title, task details, memory fact value, optional secondary value"))
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().put("command_type"))
        val declaration = JSONObject()
            .put("name", "execute_desktop_command")
            .put("description", "Execute a Windows desktop action, autonomous coding task for project, Android build/test verification, system status check, Notion notes management, or macros.")
            .put("parameters", schema)
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(declaration)))
    }
}
