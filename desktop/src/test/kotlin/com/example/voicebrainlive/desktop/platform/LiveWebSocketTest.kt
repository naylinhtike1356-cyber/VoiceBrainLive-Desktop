package com.example.voicebrainlive.desktop.platform

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.TimeUnit

class LiveWebSocketTest {

    @Test
    fun testHttp11FastModels() {
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        val apiKeyStore = ApiKeyStore()
        val key = apiKeyStore.load()
        if (key.isBlank()) return

        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val candidateModels = listOf(
            "gemini-2.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-flash-latest",
            "gemma-4-26b-a4b-it"
        )

        val testPromptJson = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("text", "မင်္ဂလာပါ") })
                    })
                })
            })
        }
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        for (m in candidateModels) {
            val genReq = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key")
                .post(testPromptJson.toString().toRequestBody(jsonMediaType))
                .build()
            try {
                val genResp = client.newCall(genReq).execute()
                val genBody = genResp.body?.string().orEmpty()
                println("Model $m => HTTP ${genResp.code}")
                if (genResp.isSuccessful) {
                    val text = JSONObject(genBody).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    println("  -> SUCCESS: ${text?.take(80)}")
                } else {
                    val errMsg = JSONObject(genBody).optJSONObject("error")?.optString("message") ?: genBody.take(100)
                    println("  -> FAILED: $errMsg")
                }
            } catch (e: Exception) {
                println("Model $m EXCEPTION: ${e.message}")
            }
        }
    }
}
