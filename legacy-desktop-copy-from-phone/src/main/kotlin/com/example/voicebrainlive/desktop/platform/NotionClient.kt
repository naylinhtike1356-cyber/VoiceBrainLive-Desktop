package com.example.voicebrainlive.desktop.platform

import com.example.voicebrainlive.desktop.core.CommandResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.net.URI

class NotionClient(
    private val token: String,
    private val parentPageId: String,
) {
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()
    private val apiVersion = "2022-06-28"

    fun isConfigured() = token.isNotBlank()

    fun testConnection(): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။ Settings ထဲမှာ ထည့်ပါ။")
        val response = request("GET", "/users/me")
        return if (response.first) CommandResult(true, "Notion ချိတ်ဆက်မှု အောင်မြင်ပါတယ်။")
        else CommandResult(false, "Notion ချိတ်ဆက်မရပါ: ${response.second}")
    }

    fun search(query: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        if (query.isBlank()) return CommandResult(false, "Notion မှာ ရှာဖွေရန် စကားလုံးလိုအပ်ပါတယ်။")
        val body = JSONObject().put("query", query).put("page_size", 10)
        val response = request("POST", "/search", body)
        if (!response.first) return CommandResult(false, "Notion search မအောင်မြင်ပါ: ${response.second}")
        val results = runCatching { JSONObject(response.second).optJSONArray("results") ?: JSONArray() }.getOrDefault(JSONArray())
        if (results.length() == 0) return CommandResult(true, "Notion မှာ ‘$query’ နဲ့ ကိုက်ညီတာ မတွေ့ပါ။")
        val lines = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val url = item.optString("url")
            val title = extractTitle(item)
            "$title — $url (ID: $id)"
        }
        return CommandResult(true, "Notion ရလဒ် ${lines.size} ခု:\n${lines.joinToString("\n")}")
    }

    fun createPage(title: String, content: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        if (parentPageId.isBlank()) return CommandResult(false, "Notion parent page ID မထည့်ရသေးပါ။ Settings ထဲမှာ ထည့်ပါ။")
        val children = JSONArray().put(
            JSONObject().put(
                "object", "block",
            ).put(
                "type", "paragraph",
            ).put(
                "paragraph", JSONObject().put(
                    "rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", content))),
                ),
            ),
        )
        val properties = JSONObject().put(
            "title",
            JSONObject().put("title", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", title)))),
        )
        val body = JSONObject()
            .put("parent", JSONObject().put("page_id", parentPageId.replace("-", "")))
            .put("properties", properties)
            .put("children", children)
        val response = request("POST", "/pages", body)
        return if (response.first) {
            val url = runCatching { JSONObject(response.second).optString("url") }.getOrDefault("")
            CommandResult(true, "Notion page ‘$title’ ဖန်တီးပြီးပါပြီ။ $url")
        } else CommandResult(false, "Notion page ဖန်တီးမရပါ: ${response.second}")
    }

    fun createTask(title: String, details: String): CommandResult {
        val taskTitle = if (title.startsWith("Task:", ignoreCase = true)) title else "Task: $title"
        return createPage(taskTitle, "☐ $details")
    }

    fun appendToParent(content: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        if (parentPageId.isBlank()) return CommandResult(false, "Notion parent page ID မထည့်ရသေးပါ။ Settings ထဲမှာ ထည့်ပါ။")
        val block = JSONObject()
            .put("object", "block")
            .put("type", "paragraph")
            .put("paragraph", JSONObject().put("rich_text", JSONArray().put(
                JSONObject().put("type", "text").put("text", JSONObject().put("content", content)),
            )))
        val response = request("PATCH", "/blocks/${parentPageId.replace("-", "")}/children", JSONObject().put("children", JSONArray().put(block)))
        return if (response.first) CommandResult(true, "Notion page ထဲကို note ထည့်ပြီးပါပြီ။")
        else CommandResult(false, "Notion page ထဲ note ထည့်မရပါ: ${response.second}")
    }

    fun openPage(url: String): CommandResult {
        if (!url.startsWith("https://www.notion.so/") && !url.startsWith("https://notion.so/")) {
            return CommandResult(false, "လုံခြုံရေးအရ Notion URL သာ ဖွင့်နိုင်ပါတယ်။")
        }
        Desktop.getDesktop().browse(URI(url))
        return CommandResult(true, "Notion page ကို ဖွင့်လိုက်ပါပြီ။")
    }

    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Boolean, String> {
        val builder = Request.Builder()
            .url("https://api.notion.com/v1$path")
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", apiVersion)
            .header("Content-Type", "application/json")
        val request = when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            "PATCH" -> builder.patch((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            else -> builder.get().build()
        }
        return runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                response.isSuccessful to text
            }
        }.getOrElse { false to (it.message ?: "network error") }
    }

    private fun extractTitle(item: JSONObject): String {
        val properties = item.optJSONObject("properties") ?: return item.optString("id", "Untitled")
        val keys = properties.keys()
        while (keys.hasNext()) {
            val property = properties.optJSONObject(keys.next()) ?: continue
            val title = property.optJSONArray("title") ?: property.optJSONArray("rich_text") ?: continue
            if (title.length() > 0) return title.optJSONObject(0)?.optJSONObject("text")?.optString("content") ?: "Untitled"
        }
        return item.optString("id", "Untitled")
    }
}
