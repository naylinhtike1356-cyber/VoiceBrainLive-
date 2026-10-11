package com.example.voicebrainlive.notion

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NotionNote(
    val id: String,
    val name: String,
    val content: String,
    val category: String,
    val status: String
)

/**
 * Notion API client for VoiceBrainLive.
 *
 * Required database schema:
 * - **Name** (Title): Page title
 * - **Content** (Rich text): Note content
 * - **Category** (Select): e.g. "Quick Note", "Idea", "Korean", "Work"
 * - **Status** (Status or Select): e.g. "မလုပ်ရသေး", "မကျက်ရသေး"
 *
 * Create integration at notion.so/my-integrations and share database with it.
 */
class NotionClient(private val context: Context) {

    companion object {
        private const val TAG = "NotionClient"
        private const val NOTION_API_URL = "https://api.notion.com/v1/pages"
        private const val NOTION_VERSION = "2022-06-28"  // Valid per Notion API; Status type supported
    }

    /** Routine: Query items with Status=မလုပ်ရသေး or To Do for to-do list. Returns list of (name, content). */
    fun getTodoItems(limit: Int = 10): List<Pair<String, String>> {
        val token = getToken()
        val cleanDbId = getCleanDatabaseId()
        if (token.isNullOrBlank() || cleanDbId.isNullOrBlank()) return emptyList()
        val queryUrl = "https://api.notion.com/v1/databases/$cleanDbId/query"
        return try {
            val filter = org.json.JSONObject().apply {
                put("property", "Status")
                put("status", org.json.JSONObject().apply {
                    put("equals", "မလုပ်ရသေး")
                })
            }
            val body = org.json.JSONObject().apply {
                put("filter", filter)
                put("page_size", limit)
                put("sorts", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("timestamp", "last_edited_time")
                        put("direction", "descending")
                    })
                })
            }
            val request = Request.Builder()
                .url(queryUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string() ?: return emptyList()
            val json = org.json.JSONObject(responseBody)
            val results = json.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until results.length()) {
                val page = results.getJSONObject(i)
                val props = page.optJSONObject("properties") ?: continue
                val nameObj = props.optJSONObject("Name") ?: props.optJSONObject("name") ?: continue
                val titleArr = nameObj.optJSONArray("title") ?: continue
                val name = if (titleArr.length() > 0) {
                    val t = titleArr.getJSONObject(0)
                    t.optString("plain_text", t.optJSONObject("text")?.optString("content", "") ?: "")
                } else ""
                val contentObj = props.optJSONObject("Content") ?: props.optJSONObject("content") ?: continue
                val richArr = contentObj.optJSONArray("rich_text") ?: continue
                val content = if (richArr.length() > 0) {
                    val r = richArr.getJSONObject(0)
                    r.optString("plain_text", r.optJSONObject("text")?.optString("content", "") ?: "")
                } else ""
                if (name.isNotBlank() || content.isNotBlank()) list.add(Pair(name.ifBlank { "Task" }, content))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "getTodoItems: ${e.message}")
            emptyList()
        }
    }

    /** v2.0 SRS: Query items with Status=မကျက်ရသေး for quiz. Returns list of (name, content). */
    fun getItemsForQuiz(subject: String, limit: Int = 5): List<Pair<String, String>> {
        val token = getToken()
        val cleanDbId = getCleanDatabaseId()
        if (token.isNullOrBlank() || cleanDbId.isNullOrBlank()) return emptyList()
        val queryUrl = "https://api.notion.com/v1/databases/$cleanDbId/query"
        return try {
            val statusFilter = JSONObject().apply {
                put("property", "Status")
                put("status", JSONObject().apply {
                    put("equals", "မကျက်ရသေး")
                })
            }
            val filter = if (subject.isNotBlank()) {
                JSONObject().apply {
                    put("and", JSONArray().apply {
                        put(statusFilter)
                        put(JSONObject().apply {
                            put("property", "Category")
                            put("select", JSONObject().apply {
                                put("equals", subject.replaceFirstChar { it.uppercase() })
                            })
                        })
                    })
                }
            } else statusFilter
            val body = JSONObject().apply {
                put("filter", filter)
                put("page_size", limit)
                put("sorts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("timestamp", "last_edited_time")
                        put("direction", "ascending")
                    })
                })
            }
            val request = Request.Builder()
                .url(queryUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string() ?: return emptyList()
            val json = JSONObject(responseBody)
            val results = json.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until results.length()) {
                val page = results.getJSONObject(i)
                val props = page.optJSONObject("properties") ?: continue
                val nameObj = props.optJSONObject("Name") ?: props.optJSONObject("name") ?: continue
                val titleArr = nameObj.optJSONArray("title") ?: continue
                val name = if (titleArr.length() > 0) {
                    val t = titleArr.getJSONObject(0)
                    t.optString("plain_text", t.optJSONObject("text")?.optString("content", "") ?: "")
                } else ""
                val contentObj = props.optJSONObject("Content") ?: props.optJSONObject("content") ?: continue
                val richArr = contentObj.optJSONArray("rich_text") ?: continue
                val content = if (richArr.length() > 0) {
                    val r = richArr.getJSONObject(0)
                    r.optString("plain_text", r.optJSONObject("text")?.optString("content", "") ?: "")
                } else ""
                if (name.isNotBlank() || content.isNotBlank()) list.add(Pair(name.ifBlank { "Item" }, content))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "getItemsForQuiz: ${e.message}")
            emptyList()
        }
    }

    /**
     * Test Notion connection by retrieving database schema.
     * Shows property names, types, and any API errors for debugging.
     */
    fun testConnection(): String {
        val token = getToken()
        val cleanDbId = getCleanDatabaseId()
        if (token.isNullOrBlank()) return "Notion token မရှိပါ။ Settings မှာ ထည့်ပါ။"
        if (cleanDbId.isNullOrBlank()) return "Database ID မရှိပါ။ Settings မှာ ထည့်ပါ။"
        return try {
            val url = "https://api.notion.com/v1/databases/$cleanDbId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Notion-Version", NOTION_VERSION)
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            when {
                response.code == 401 -> "Token မမှန်ပါ။ notion.so/my-integrations မှာ ပြန်ကြည့်ပါ။"
                response.code == 404 -> "Database မတွေ့ပါ။ (1) Database ကို integration နဲ့ share ပါ။ (2) ID မှန်ပါသလား စစ်ပါ။"
                response.code == 400 -> "Request မမှန်: $body"
                !response.isSuccessful -> "Error ${response.code}: ${body.take(200)}"
                else -> {
                    val props = JSONObject(body).optJSONObject("properties") ?: JSONObject()
                    val sb = StringBuilder("✓ Notion ချိတ်ဆက်ပြီး။\n")
                    sb.append("Schema: ")
                    props.keys().forEach { key ->
                        val type = props.optJSONObject(key)?.optString("type", "?") ?: "?"
                        sb.append("$key($type) ")
                    }
                    sb.append("\nလိုအပ်ချက်: Name(title), Content(rich_text), Category(select), Status(status သို့ select)")
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            "ချိတ်ဆက်မရ: ${e.message}. အင်တာနက် စစ်ပါ။"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getToken(): String? = SettingsActivity.getNotionToken(context)?.trim()
    private fun getDatabaseId(): String? = SettingsActivity.getNotionDatabaseId(context)?.trim()

    /** Returns database ID with ALL hyphens/dashes removed. Notion API requires ID without hyphens. */
    private fun getCleanDatabaseId(): String? = getDatabaseId()?.let { id ->
        id.replace("-", "").replace("\u2013", "").replace("\u2014", "")  // hyphen, en-dash, em-dash
    }?.takeIf { it.isNotBlank() }

    fun isConfigured(): Boolean {
        val token = getToken()
        val dbId = getDatabaseId()
        return !token.isNullOrBlank() && !dbId.isNullOrBlank()
    }

    internal data class ExactCreate(
        val databaseId: String,
        val isCurrent: () -> Boolean,
        val submit: suspend () -> Boolean
    )

    /** Frozen destination/auth/body for M1; one POST, no redirects or schema fallback. */
    internal fun prepareExactCreate(name: String, content: String): ExactCreate? {
        val token = getToken()?.takeIf { it.isNotBlank() } ?: return null
        val databaseId = getCleanDatabaseId()?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) } ?: return null
        val json = exactCreateJson(databaseId, name, content) ?: return null
        val request = Request.Builder().url(NOTION_API_URL)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Notion-Version", NOTION_VERSION)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        val singleAttemptClient = client.newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()
        return ExactCreate(databaseId, { getToken() == token && getCleanDatabaseId() == databaseId }, {
            suspendCancellableCoroutine { continuation ->
                // The request captures the reviewed database and credentials, not mutable Settings.
                val call = singleAttemptClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use {
                            if (continuation.isActive) continuation.resumeWith(Result.success(it.isSuccessful))
                        }
                    }
                })
            }
        })
    }

    /** Reject truncation and malformed UTF-16 rather than silently changing reviewed content. */
    internal fun exactCreateJson(databaseId: String, name: String, content: String): String? {
        if (name.isBlank() || name.length > 2000 || content.isBlank() ||
            !validUnicode(name) || !validUnicode(content) || chunkString(content).size > 100) return null
        return buildNoteJson(databaseId, name, content, "Quick Note", "မလုပ်ရသေး")
    }

    private fun validUnicode(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) return false
                index++
            }
        }
        return true
    }

    suspend fun createNote(
        name: String,
        content: String,
        category: String = "Quick Note",
        status: String = "မလုပ်ရသေး"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = getToken()
            val cleanDbId = getCleanDatabaseId() ?: ""

            // Strictly use ID without hyphens for body; ensure no hyphens remain
            val sanitizedDbId = cleanDbId.replace("-", "")

            AppLog.d(TAG, "createNote: URL=$NOTION_API_URL")
            AppLog.d(TAG, "createNote: parent.database_id (no hyphens)=$sanitizedDbId")
            Log.d(TAG, "createNote: exact URL=$NOTION_API_URL | database_id=$sanitizedDbId")

            if (token.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Notion token is missing"))
            }
            if (sanitizedDbId.isBlank()) {
                return@withContext Result.failure(Exception("Notion database ID is missing"))
            }

            // Try Status type first; if 400, retry with Select (many DBs use Select for Status)
            var json = buildNoteJson(sanitizedDbId, name, content, category, status, useStatusAsSelect = false)
            AppLog.d(TAG, "Sending to Notion: $json")

            var request = Request.Builder()
                .url(NOTION_API_URL)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            AppLog.d(TAG, "Making request to Notion API... POST $NOTION_API_URL")
            var response = client.newCall(request).execute()
            var responseBody = response.body?.string() ?: ""

            AppLog.d(TAG, "Notion response code: ${response.code}")
            AppLog.d(TAG, "Notion response body: ${responseBody.take(500)}")

            if (!response.isSuccessful && response.code == 400) {
                if (responseBody.contains("Status", ignoreCase = true)) {
                    AppLog.d(TAG, "Retrying with Status as Select type...")
                    json = buildNoteJson(sanitizedDbId, name, content, category, status, useStatusAsSelect = true)
                    request = Request.Builder()
                        .url(NOTION_API_URL)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Notion-Version", NOTION_VERSION)
                        .post(json.toRequestBody("application/json".toMediaType()))
                        .build()
                    response = client.newCall(request).execute()
                    responseBody = response.body?.string() ?: ""
                    AppLog.d(TAG, "Retry Status response: ${response.code} ${responseBody.take(300)}")
                }

                if (!response.isSuccessful && response.code == 400) {
                    AppLog.d(TAG, "Retrying with Minimal Schema (Name + Blocks) due to error: $responseBody")
                    json = buildMinimalNoteJson(sanitizedDbId, name, content)
                    request = Request.Builder()
                        .url(NOTION_API_URL)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Notion-Version", NOTION_VERSION)
                        .post(json.toRequestBody("application/json".toMediaType()))
                        .build()
                    response = client.newCall(request).execute()
                    responseBody = response.body?.string() ?: ""
                    AppLog.d(TAG, "Minimal Schema retry response: ${response.code} ${responseBody.take(300)}")
                }
            }

            if (response.isSuccessful) {
                AppLog.d(TAG, "SUCCESS! Note created in Notion")
                Result.success("Notion မှာ သိမ်းပြီးပါပြီ!")
            } else {
                val errorMsg = try {
                    val errorJson = JSONObject(responseBody)
                    val msg = errorJson.optString("message", "")
                    val code = errorJson.optString("code", "")
                    "[$code] $msg"
                } catch (e: Exception) {
                    responseBody.take(200)
                }
                Log.e(TAG, "Notion API error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending to Notion: ${e.message}", e)
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    private fun chunkString(text: String, chunkSize: Int = 2000): List<String> {
        if (text.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            var end = minOf(index + chunkSize, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) &&
                Character.isLowSurrogate(text[end])) end--
            chunks.add(text.substring(index, end))
            index = end
        }
        return chunks
    }

    private fun buildMinimalNoteJson(databaseId: String, name: String, content: String): String {
        val contentChunks = chunkString(content)
        val contentRichTextArray = JSONArray()
        for (chunk in contentChunks.take(100)) {
            contentRichTextArray.put(JSONObject().apply {
                put("text", JSONObject().apply { put("content", chunk) })
            })
        }

        val props = JSONObject().apply {
            put("Name", JSONObject().apply {
                put("title", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", JSONObject().apply { put("content", name) })
                    })
                })
            })
        }

        val childrenArray = JSONArray().apply {
            put(JSONObject().apply {
                put("object", "block")
                put("type", "paragraph")
                put("paragraph", JSONObject().apply {
                    put("rich_text", contentRichTextArray)
                })
            })
        }

        return JSONObject().apply {
            put("parent", JSONObject().apply { put("database_id", databaseId) })
            put("properties", props)
            put("children", childrenArray)
        }.toString()
    }

    private fun buildNoteJson(
        databaseId: String,
        name: String,
        content: String,
        category: String,
        status: String,
        useStatusAsSelect: Boolean = false
    ): String {
        val contentChunks = chunkString(content)
        val contentRichTextArray = JSONArray()
        for (chunk in contentChunks.take(100)) {
            contentRichTextArray.put(JSONObject().apply {
                put("text", JSONObject().apply { put("content", chunk) })
            })
        }

        val props = JSONObject().apply {
            put("Name", JSONObject().apply {
                put("title", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", JSONObject().apply { put("content", name) })
                    })
                })
            })
            put("Category", JSONObject().apply {
                put("select", JSONObject().apply { put("name", category) })
            })
            put("Content", JSONObject().apply {
                put("rich_text", contentRichTextArray)
            })
            put("Status", if (useStatusAsSelect) {
                JSONObject().apply {
                    put("select", JSONObject().apply { put("name", status) })
                }
            } else {
                JSONObject().apply {
                    put("status", JSONObject().apply { put("name", status) })
                }
            })
        }

        val childrenArray = JSONArray().apply {
            put(JSONObject().apply {
                put("object", "block")
                put("type", "paragraph")
                put("paragraph", JSONObject().apply {
                    put("rich_text", contentRichTextArray)
                })
            })
        }

        return JSONObject().apply {
            put("parent", JSONObject().apply { put("database_id", databaseId) })
            put("properties", props)
            put("children", childrenArray)
        }.toString()
    }

    fun queryNotes(keyword: String = "", limit: Int = 20): List<NotionNote> {
        val token = getToken()
        val cleanDbId = getCleanDatabaseId()
        if (token.isNullOrBlank() || cleanDbId.isNullOrBlank()) return emptyList()
        val queryUrl = "https://api.notion.com/v1/databases/$cleanDbId/query"
        return try {
            val body = JSONObject().apply {
                put("page_size", 30)
                put("sorts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("timestamp", "last_edited_time")
                        put("direction", "descending")
                    })
                })
            }
            val request = Request.Builder()
                .url(queryUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val responseBody = response.body?.string() ?: return emptyList()
            val json = JSONObject(responseBody)
            val results = json.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<NotionNote>()
            val kw = if (keyword.equals("all", ignoreCase = true) || keyword.equals("အကုန်", ignoreCase = true) || keyword.equals("အားလုံး", ignoreCase = true)) "" else keyword.trim()
            for (i in 0 until results.length()) {
                val page = results.getJSONObject(i)
                val pageId = page.optString("id", "")
                val props = page.optJSONObject("properties") ?: continue
                
                val keys = props.keys()
                var name = ""
                var content = ""
                var category = ""
                var status = ""

                while (keys.hasNext()) {
                    val propName = keys.next()
                    val propObj = props.optJSONObject(propName) ?: continue
                    val type = propObj.optString("type", "")

                    if (type == "title" || (name.isEmpty() && propName.equals("Name", ignoreCase = true))) {
                        val titleArr = propObj.optJSONArray("title")
                        if (titleArr != null && titleArr.length() > 0) {
                            val t = titleArr.getJSONObject(0)
                            name = t.optString("plain_text", t.optJSONObject("text")?.optString("content", "") ?: "")
                        }
                    } else if (type == "rich_text" || (content.isEmpty() && propName.equals("Content", ignoreCase = true))) {
                        val richArr = propObj.optJSONArray("rich_text")
                        if (richArr != null && richArr.length() > 0) {
                            val r = richArr.getJSONObject(0)
                            val txt = r.optString("plain_text", r.optJSONObject("text")?.optString("content", "") ?: "")
                            if (txt.isNotBlank()) {
                                content = if (content.isEmpty()) txt else "$content\n$txt"
                            }
                        }
                    } else if (type == "select" && category.isEmpty()) {
                        category = propObj.optJSONObject("select")?.optString("name", "") ?: ""
                    } else if (type == "status" || propName.equals("Status", ignoreCase = true)) {
                        status = propObj.optJSONObject("status")?.optString("name", "") 
                            ?: propObj.optJSONObject("select")?.optString("name", "") ?: ""
                    }
                }

                var blocksContent = ""
                if (i < 10) {
                    blocksContent = fetchPageBlocksText(pageId)
                }
                val fullContent = listOf(content, blocksContent).filter { it.isNotBlank() }.joinToString("\n---\n")

                if (kw.isBlank() || name.contains(kw, ignoreCase = true) || 
                    fullContent.contains(kw, ignoreCase = true) || category.contains(kw, ignoreCase = true)) {
                    list.add(NotionNote(pageId, name.ifBlank { "Untitled Note" }, fullContent, category, status))
                }
                if (list.size >= limit) break
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "queryNotes: ${e.message}")
            emptyList()
        }
    }

    fun fetchPageBlocksText(pageId: String): String {
        val token = getToken()
        if (token.isNullOrBlank() || pageId.isBlank()) return ""
        val url = "https://api.notion.com/v1/blocks/$pageId/children"
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Notion-Version", NOTION_VERSION)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ""
            val bodyStr = response.body?.string() ?: return ""
            val json = JSONObject(bodyStr)
            val results = json.optJSONArray("results") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until results.length()) {
                val block = results.getJSONObject(i)
                val type = block.optString("type", "")
                val blockData = block.optJSONObject(type) ?: continue
                val richText = blockData.optJSONArray("rich_text") ?: continue
                for (j in 0 until richText.length()) {
                    val textItem = richText.getJSONObject(j)
                    val textContent = textItem.optString("plain_text", textItem.optJSONObject("text")?.optString("content", "") ?: "")
                    if (textContent.isNotBlank()) {
                        when (type) {
                            "heading_1", "heading_2", "heading_3" -> sb.append("\n# ").append(textContent).append("\n")
                            "bulleted_list_item", "numbered_list_item" -> sb.append("• ").append(textContent).append("\n")
                            "to_do" -> {
                                val checked = blockData.optBoolean("checked", false)
                                sb.append(if (checked) "[x] " else "[ ] ").append(textContent).append("\n")
                            }
                            else -> sb.append(textContent).append("\n")
                        }
                    }
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "fetchPageBlocksText error: ${e.message}")
            ""
        }
    }

    fun appendToPage(pageId: String, currentContent: String, additionalContent: String): Result<String> {
        val token = getToken()
        if (token.isNullOrBlank()) return Result.failure(Exception("Notion token missing"))
        return try {
            val newFullContent = if (currentContent.isNotBlank()) "$currentContent\n\n- $additionalContent" else additionalContent
            val contentChunks = chunkString(newFullContent)
            val contentRichTextArray = JSONArray()
            for (chunk in contentChunks.take(100)) {
                contentRichTextArray.put(JSONObject().apply {
                    put("text", JSONObject().apply { put("content", chunk) })
                })
            }

            val propsBody = JSONObject().apply {
                put("properties", JSONObject().apply {
                    put("Content", JSONObject().apply {
                        put("rich_text", contentRichTextArray)
                    })
                })
            }
            val pageUrl = "https://api.notion.com/v1/pages/$pageId"
            val propRequest = Request.Builder()
                .url(pageUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .patch(propsBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            var propertyUpdateSuccess = false
            try { 
                val response = client.newCall(propRequest).execute()
                propertyUpdateSuccess = response.isSuccessful
                if (!propertyUpdateSuccess) {
                    AppLog.d(TAG, "Property append failed (maybe missing Content column or length): ${response.body?.string()}")
                }
            } catch (e: Exception) { Log.w(TAG, "Property append error: ${e.message}") }

            val additionalContentChunks = chunkString(additionalContent)
            val additionalRichTextArray = JSONArray()
            for (chunk in additionalContentChunks.take(100)) {
                additionalRichTextArray.put(JSONObject().apply {
                    put("text", JSONObject().apply { put("content", chunk) })
                })
            }

            val blocksUrl = "https://api.notion.com/v1/blocks/$pageId/children"
            val blockBody = JSONObject().apply {
                put("children", JSONArray().apply {
                    put(JSONObject().apply {
                        put("object", "block")
                        put("type", "paragraph")
                        put("paragraph", JSONObject().apply {
                            put("rich_text", additionalRichTextArray)
                        })
                    })
                })
            }
            val blockRequest = Request.Builder()
                .url(blocksUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .patch(blockBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
                
            var blockUpdateSuccess = false
            try { 
                val blockResponse = client.newCall(blockRequest).execute() 
                blockUpdateSuccess = blockResponse.isSuccessful
                if (!blockUpdateSuccess) {
                    AppLog.d(TAG, "Block append failed: ${blockResponse.body?.string()}")
                }
            } catch (e: Exception) { Log.w(TAG, "Block append error: ${e.message}") }

            if (propertyUpdateSuccess || blockUpdateSuccess) {
                Result.success("Notion ရှိ မှတ်စုတွင်းသို့ အချက်အလက်သစ် အောင်မြင်စွာ ဖြည့်စွက်ပေးပြီးပါပြီ!")
            } else {
                Result.failure(Exception("Failed to update page properties and blocks in Notion"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun archivePage(pageId: String): Result<String> {
        val token = getToken()
        if (token.isNullOrBlank()) return Result.failure(Exception("Notion token missing"))
        return try {
            val pageUrl = "https://api.notion.com/v1/pages/$pageId"
            val body = JSONObject().apply {
                put("archived", true)
            }
            val request = Request.Builder()
                .url(pageUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", NOTION_VERSION)
                .patch(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success("Notion ထဲမှ မှတ်စုကို အောင်မြင်စွာ ဖျက်သိမ်း (Archive) ပြီးပါပြီ!")
            } else {
                Result.failure(Exception("Failed to delete (archive) Notion page"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
