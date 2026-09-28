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
import java.util.concurrent.TimeUnit

/**
 * Full-featured Notion API Integration Client.
 * Automatically supports both Notion Databases and Pages for CRUD, search, and reading.
 */
class NotionClient(
    private val token: String,
    private val parentPageId: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val apiVersion = "2022-06-28"

    fun isConfigured(): Boolean = token.isNotBlank()

    fun testConnection(): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။ Settings ထဲတွင် ထည့်သွင်းပေးပါ။")
        val response = request("GET", "/users/me")
        if (!response.first) {
            return CommandResult(false, "Notion ချိတ်ဆက်မရပါ: Token မှားယွင်းနေနိုင်ပါသည် (${response.second})")
        }
        val botName = runCatching { JSONObject(response.second).optString("name", "User") }.getOrDefault("User")
        
        if (parentPageId.isNotBlank()) {
            val targetId = normalizeId(parentPageId)
            
            // Check if it is a Database
            val dbResp = request("GET", "/databases/$targetId")
            if (dbResp.first) {
                val dbTitle = runCatching { extractTitle(JSONObject(dbResp.second)) }.getOrDefault("Windows Assistant Database")
                return CommandResult(true, "Notion ချိတ်ဆက်မှု အပြည့်အဝ အောင်မြင်ပါသည်!\n• Bot: $botName\n• Database: ‘$dbTitle’ (ချိတ်ဆက်ပြီး)")
            }

            // Check if it is a Page
            val pageResp = request("GET", "/pages/$targetId")
            if (pageResp.first) {
                val pageTitle = runCatching { extractTitle(JSONObject(pageResp.second)) }.getOrDefault("Parent Page")
                return CommandResult(true, "Notion ချိတ်ဆက်မှု အပြည့်အဝ အောင်မြင်ပါသည်!\n• Bot: $botName\n• Parent Page: ‘$pageTitle’ (ချိတ်ဆက်ပြီး)")
            }

            // If neither worked
            return CommandResult(
                false,
                "Token မှန်ကန်ပါသည် ($botName)။ သို့သော် Parent ID ($parentPageId) ကို Notion ထဲတွင် ‘$botName’ integration နှင့် Share မလုပ်ရသေးပါ (သို့မဟုတ် ID မှားယွင်းနေပါသည်)။\n\n📌 ဖြေရှင်းနည်း: Notion တွင် ထို Page/Database ကိုဖွင့်ပါ -> ညာဘက်အပေါ်ဒေါင့်ရှိ '...' ကိုနှိပ်ပါ -> 'Connect to' မှ '$botName' ကို ရွေးပေးပါ။"
            )
        }

        return CommandResult(true, "Notion Token ချိတ်ဆက်မှု အောင်မြင်ပါသည် ($botName)။")
    }

    /**
     * Search Notion pages and databases by title or query string.
     */
    fun search(query: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val body = JSONObject().apply {
            if (query.isNotBlank()) put("query", query.trim())
            put("page_size", 10)
            put("sort", JSONObject().put("direction", "descending").put("timestamp", "last_edited_time"))
        }
        val response = request("POST", "/search", body)
        if (!response.first) return CommandResult(false, "Notion search မအောင်မြင်ပါ: ${response.second}")

        val results = runCatching { JSONObject(response.second).optJSONArray("results") ?: JSONArray() }.getOrDefault(JSONArray())
        if (results.length() == 0) {
            return CommandResult(true, "Notion ထဲတွင် ‘$query’ နှင့် ကိုက်ညီသော မှတ်တမ်း မတွေ့ပါ။")
        }

        val lines = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val url = item.optString("url")
            val title = extractTitle(item)
            val objType = item.optString("object", "page")
            val lastEdited = item.optString("last_edited_time").take(10)
            "• [$objType] $title (ID: $id, ရက်စွဲ: $lastEdited)\n  Link: $url"
        }
        return CommandResult(true, "Notion မှတ်တမ်း ရှာဖွေတွေ့ရှိချက် (${lines.size} ခု):\n${lines.joinToString("\n")}")
    }

    /**
     * Read full page or database content so Assistant can inspect and reason.
     */
    fun getPageContent(rawPageId: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val targetId = normalizeId(rawPageId.ifBlank { parentPageId })
        if (targetId.isBlank()) return CommandResult(false, "Page ID သို့မဟုတ် URL လိုအပ်ပါသည်။")

        // First check if it is a Database
        val dbResp = request("GET", "/databases/$targetId")
        if (dbResp.first) {
            val dbObj = JSONObject(dbResp.second)
            val dbTitle = extractTitle(dbObj)
            val queryResp = request("POST", "/databases/$targetId/query", JSONObject().put("page_size", 20))
            if (queryResp.first) {
                val entries = runCatching { JSONObject(queryResp.second).optJSONArray("results") ?: JSONArray() }.getOrDefault(JSONArray())
                val sb = StringBuilder()
                sb.appendLine("📊 Notion Database: ‘$dbTitle’ (ID: $targetId)")
                sb.appendLine("စုစုပေါင်း မှတ်တမ်း: ${entries.length()} ခု")
                sb.appendLine("----------------------------------------")
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val entryTitle = extractTitle(entry)
                    val entryId = entry.optString("id")
                    val entryUrl = entry.optString("url")
                    sb.appendLine("${i + 1}. $entryTitle (ID: $entryId)")
                    sb.appendLine("   Link: $entryUrl")
                }
                return CommandResult(true, sb.toString().trim())
            }
        }

        // Otherwise fetch as Page
        val pageMetaResp = request("GET", "/pages/$targetId")
        val pageTitle = if (pageMetaResp.first) {
            runCatching { extractTitle(JSONObject(pageMetaResp.second)) }.getOrDefault("Untitled Page")
        } else "Notion Page"

        // Fetch children blocks
        val blocksResp = request("GET", "/blocks/$targetId/children?page_size=100")
        if (!blocksResp.first) {
            return CommandResult(false, "Page content ဖတ်မရပါ: ${blocksResp.second}")
        }

        val results = runCatching { JSONObject(blocksResp.second).optJSONArray("results") ?: JSONArray() }.getOrDefault(JSONArray())
        if (results.length() == 0) {
            return CommandResult(true, "📄 Notion Page: ‘$pageTitle’ (ID: $targetId)\n(ဤစာမျက်နှာထဲတွင် စာသား block များ မရှိသေးပါ)")
        }

        val sb = StringBuilder()
        sb.appendLine("📄 Notion Page: ‘$pageTitle’ (ID: $targetId)")
        sb.appendLine("----------------------------------------")

        for (i in 0 until results.length()) {
            val block = results.optJSONObject(i) ?: continue
            val type = block.optString("type")
            val contentObj = block.optJSONObject(type)
            val richText = contentObj?.optJSONArray("rich_text")
            val plainText = extractPlainText(richText)

            when (type) {
                "paragraph" -> if (plainText.isNotBlank()) sb.appendLine(plainText)
                "heading_1" -> sb.appendLine("\n# $plainText")
                "heading_2" -> sb.appendLine("\n## $plainText")
                "heading_3" -> sb.appendLine("\n### $plainText")
                "bulleted_list_item" -> sb.appendLine("• $plainText")
                "numbered_list_item" -> sb.appendLine("1. $plainText")
                "to_do" -> {
                    val checked = contentObj?.optBoolean("checked", false) ?: false
                    val mark = if (checked) "[x]" else "[ ]"
                    sb.appendLine("$mark $plainText")
                }
                "code" -> {
                    val lang = contentObj?.optString("language", "") ?: ""
                    sb.appendLine("```$lang\n$plainText\n```")
                }
                "quote" -> sb.appendLine("> $plainText")
                "callout" -> sb.appendLine("💡 $plainText")
                "divider" -> sb.appendLine("---")
                "child_page" -> {
                    val title = contentObj?.optString("title", "Child Page")
                    sb.appendLine("📁 Subpage: $title (ID: ${block.optString("id")})")
                }
                else -> if (plainText.isNotBlank()) sb.appendLine(plainText)
            }
        }

        return CommandResult(true, sb.toString().trim())
    }

    /**
     * Create a new page under a Parent Database or Parent Page automatically.
     */
    fun createPage(title: String, content: String, targetParent: String? = null): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val effectiveParent = normalizeId(targetParent?.takeIf { it.isNotBlank() } ?: parentPageId)
        if (effectiveParent.isBlank()) {
            return CommandResult(false, "Notion Parent Page ID မထည့်ရသေးပါ။ Settings ထဲတွင် ထည့်သွင်းပေးပါ။")
        }

        val blocks = parseContentToBlocks(content)
        val cleanTitle = title.ifBlank { "Untitled Note" }

        // Check if effectiveParent is a Database
        val dbResp = request("GET", "/databases/$effectiveParent")
        val body = if (dbResp.first) {
            val dbObj = JSONObject(dbResp.second)
            val titlePropName = findTitlePropertyName(dbObj)
            val properties = JSONObject().apply {
                put(titlePropName, JSONObject().put("title", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", JSONObject().put("content", cleanTitle)))
                }))
                // If the database has a 'Content' property
                val dbProps = dbObj.optJSONObject("properties")
                if (dbProps != null && dbProps.has("Content") && content.isNotBlank()) {
                    put("Content", JSONObject().put("rich_text", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", JSONObject().put("content", content.take(2000))))
                    }))
                }
            }
            JSONObject().apply {
                put("parent", JSONObject().put("database_id", effectiveParent))
                put("properties", properties)
                if (blocks.length() > 0) put("children", blocks)
            }
        } else {
            // Standard Page Parent
            val properties = JSONObject().apply {
                put("title", JSONObject().put("title", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", JSONObject().put("content", cleanTitle)))
                }))
            }
            JSONObject().apply {
                put("parent", JSONObject().put("page_id", effectiveParent))
                put("properties", properties)
                if (blocks.length() > 0) put("children", blocks)
            }
        }

        val response = request("POST", "/pages", body)
        return if (response.first) {
            val respJson = runCatching { JSONObject(response.second) }.getOrNull()
            val newId = respJson?.optString("id").orEmpty()
            val url = respJson?.optString("url").orEmpty()
            CommandResult(true, "Notion စာမျက်နှာ ‘$cleanTitle’ အသစ် ဖန်တီးသိမ်းဆည်းပြီးပါပြီ။\nID: $newId\nURL: $url")
        } else {
            CommandResult(false, "Notion စာမျက်နှာ ဖန်တီးမရပါ: ${response.second}")
        }
    }

    /**
     * Create a task or todo item in Notion.
     */
    fun createTask(title: String, details: String, targetParent: String? = null): CommandResult {
        val taskTitle = if (title.startsWith("Task:", ignoreCase = true) || title.startsWith("Todo:", ignoreCase = true)) title else "Task: $title"
        val formattedContent = if (details.isNotBlank()) "☐ $details" else "☐ $title"
        return createPage(taskTitle, formattedContent, targetParent)
    }

    /**
     * Append text or checklist to an existing page or parent page.
     */
    fun appendToPage(targetPageIdOrUrl: String?, content: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val effectivePageId = normalizeId(targetPageIdOrUrl?.takeIf { it.isNotBlank() } ?: parentPageId)
        if (effectivePageId.isBlank()) {
            return CommandResult(false, "စာထည့်ရန် Notion Page ID မရှိပါ။ Settings တွင် Parent Page ID ထည့်ပါ သို့မဟုတ် Page ID ပေးပါ။")
        }

        val blocks = parseContentToBlocks(content)
        if (blocks.length() == 0) {
            return CommandResult(false, "ထည့်သွင်းရန် စာသား မရှိပါ။")
        }

        val body = JSONObject().put("children", blocks)
        val response = request("PATCH", "/blocks/$effectivePageId/children", body)
        return if (response.first) {
            CommandResult(true, "Notion စာမျက်နှာ (ID: $effectivePageId) ထဲသို့ မှတ်တမ်း အသစ် ဖြည့်စွက်ပြီးပါပြီ။")
        } else {
            CommandResult(false, "Notion စာမျက်နှာထဲ စာဖြည့်မရပါ: ${response.second}")
        }
    }

    /**
     * Update an existing page's title.
     */
    fun updatePageTitle(rawPageId: String, newTitle: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val targetId = normalizeId(rawPageId)
        if (targetId.isBlank()) return CommandResult(false, "ပြင်ဆင်လိုသော Page ID လိုအပ်ပါသည်။")
        if (newTitle.isBlank()) return CommandResult(false, "ခေါင်းစဉ်အသစ် လိုအပ်ပါသည်။")

        // Fetch page to check title property name
        val pageResp = request("GET", "/pages/$targetId")
        val titlePropName = if (pageResp.first) {
            findTitlePropertyName(JSONObject(pageResp.second))
        } else "title"

        val properties = JSONObject().apply {
            put(titlePropName, JSONObject().put("title", JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", JSONObject().put("content", newTitle)))
            }))
        }
        val body = JSONObject().put("properties", properties)

        val response = request("PATCH", "/pages/$targetId", body)
        return if (response.first) {
            CommandResult(true, "Notion စာမျက်နှာ (ID: $targetId) ၏ ခေါင်းစဉ်ကို ‘$newTitle’ သို့ အောင်မြင်စွာ ပြင်ဆင်ပြီးပါပြီ။")
        } else {
            CommandResult(false, "Notion ခေါင်းစဉ် ပြင်မရပါ: ${response.second}")
        }
    }

    /**
     * Archive/Delete a page in Notion (moves to Trash).
     */
    fun archivePage(rawPageId: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val targetId = normalizeId(rawPageId)
        if (targetId.isBlank()) return CommandResult(false, "ဖျက်ပစ်လိုသော Page ID လိုအပ်ပါသည်။")

        val body = JSONObject().put("archived", true)
        val response = request("PATCH", "/pages/$targetId", body)
        return if (response.first) {
            CommandResult(true, "Notion စာမျက်နှာ (ID: $targetId) ကို Trash သို့ ဖျက်/Archive ပြုလုပ်ပြီးပါပြီ။")
        } else {
            CommandResult(false, "Notion စာမျက်နှာ ဖျက်မရပါ: ${response.second}")
        }
    }

    /**
     * Delete a specific block in Notion.
     */
    fun deleteBlock(rawBlockId: String): CommandResult {
        if (!isConfigured()) return CommandResult(false, "Notion token မထည့်ရသေးပါ။")
        val targetId = normalizeId(rawBlockId)
        if (targetId.isBlank()) return CommandResult(false, "ဖျက်လိုသော Block ID လိုအပ်ပါသည်။")

        val response = request("DELETE", "/blocks/$targetId")
        return if (response.first) {
            CommandResult(true, "Notion Block (ID: $targetId) ကို ဖျက်လိုက်ပါပြီ။")
        } else {
            CommandResult(false, "Notion block ဖျက်မရပါ: ${response.second}")
        }
    }

    /**
     * Open a Notion page URL in the default browser.
     */
    fun openPage(url: String): CommandResult {
        val cleanUrl = url.trim()
        val isNotionUrl = cleanUrl.startsWith("https://www.notion.so/") ||
                cleanUrl.startsWith("https://notion.so/") ||
                cleanUrl.startsWith("https://app.notion.com/") ||
                cleanUrl.startsWith("https://www.notion.com/") ||
                cleanUrl.contains(".notion.site/")
        if (!isNotionUrl) {
            return CommandResult(false, "လုံခြုံရေးအရ Notion URL သာ ဖွင့်နိုင်ပါသည်။ ($cleanUrl)")
        }
        Desktop.getDesktop().browse(URI(cleanUrl))
        return CommandResult(true, "Notion page ကို ဖွင့်လိုက်ပါပြီ: $cleanUrl")
    }

    private fun findTitlePropertyName(obj: JSONObject): String {
        val properties = obj.optJSONObject("properties") ?: return "title"
        val keys = properties.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val prop = properties.optJSONObject(key) ?: continue
            if (prop.optString("type") == "title" || prop.has("title")) {
                return key
            }
        }
        return "Name"
    }

    private fun parseContentToBlocks(content: String): JSONArray {
        val blocks = JSONArray()
        if (content.isBlank()) return blocks

        val lines = content.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("☐ ") || trimmed.startsWith("[ ] ") || trimmed.startsWith("- [ ] ") -> {
                    val text = trimmed.removePrefix("☐ ").removePrefix("[ ] ").removePrefix("- [ ] ")
                    blocks.put(createBlockJson("to_do", text, checked = false))
                }
                trimmed.startsWith("☑ ") || trimmed.startsWith("[x] ") || trimmed.startsWith("- [x] ") -> {
                    val text = trimmed.removePrefix("☑ ").removePrefix("[x] ").removePrefix("- [x] ")
                    blocks.put(createBlockJson("to_do", text, checked = true))
                }
                trimmed.startsWith("• ") || trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val text = trimmed.removePrefix("• ").removePrefix("- ").removePrefix("* ")
                    blocks.put(createBlockJson("bulleted_list_item", text))
                }
                trimmed.startsWith("### ") -> {
                    blocks.put(createBlockJson("heading_3", trimmed.removePrefix("### ")))
                }
                trimmed.startsWith("## ") -> {
                    blocks.put(createBlockJson("heading_2", trimmed.removePrefix("## ")))
                }
                trimmed.startsWith("# ") -> {
                    blocks.put(createBlockJson("heading_1", trimmed.removePrefix("# ")))
                }
                trimmed.startsWith("> ") -> {
                    blocks.put(createBlockJson("quote", trimmed.removePrefix("> ")))
                }
                else -> {
                    blocks.put(createBlockJson("paragraph", trimmed))
                }
            }
        }
        return blocks
    }

    private fun createBlockJson(type: String, text: String, checked: Boolean? = null): JSONObject {
        val richTextArray = JSONArray().put(
            JSONObject().put("type", "text").put("text", JSONObject().put("content", text))
        )
        val typeObject = JSONObject().put("rich_text", richTextArray)
        if (checked != null) {
            typeObject.put("checked", checked)
        }
        return JSONObject().apply {
            put("object", "block")
            put("type", type)
            put(type, typeObject)
        }
    }

    private fun extractPlainText(richTextArray: JSONArray?): String {
        if (richTextArray == null || richTextArray.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until richTextArray.length()) {
            val item = richTextArray.optJSONObject(i) ?: continue
            val text = item.optJSONObject("text")?.optString("content") ?: item.optString("plain_text", "")
            sb.append(text)
        }
        return sb.toString()
    }

    private fun extractTitle(item: JSONObject): String {
        // Check title in database root
        val rootTitleArray = item.optJSONArray("title")
        if (rootTitleArray != null && rootTitleArray.length() > 0) {
            val extracted = extractPlainText(rootTitleArray)
            if (extracted.isNotBlank()) return extracted
        }

        // Check properties
        val properties = item.optJSONObject("properties")
        if (properties != null) {
            val keys = properties.keys()
            while (keys.hasNext()) {
                val property = properties.optJSONObject(keys.next()) ?: continue
                val titleArray = property.optJSONArray("title") ?: property.optJSONArray("rich_text") ?: continue
                val extracted = extractPlainText(titleArray)
                if (extracted.isNotBlank()) return extracted
            }
        }
        return item.optString("id", "Untitled Note")
    }

    private fun normalizeId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            // Extract the last 32 characters or uuid from notion URL
            val cleanPath = trimmed.substringAfterLast("/").substringAfterLast("-")
            val hexOnly = cleanPath.replace("-", "").takeLast(32)
            if (hexOnly.length == 32) return hexOnly
        }
        return trimmed.replace("-", "").trim()
    }

    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Boolean, String> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://api.notion.com/v1$path")
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", apiVersion)
            .header("Content-Type", "application/json")
        val request = when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            "PATCH" -> builder.patch((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            "DELETE" -> builder.delete((body ?: JSONObject()).toString().toRequestBody(jsonType)).build()
            else -> builder.get().build()
        }
        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                response.isSuccessful to text
            }
        }.getOrElse { false to (it.message ?: "network error") }
    }
}
