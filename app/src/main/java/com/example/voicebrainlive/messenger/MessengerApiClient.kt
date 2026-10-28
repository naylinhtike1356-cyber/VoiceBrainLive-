package com.example.voicebrainlive.messenger

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.SettingsActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Facebook Graph API Client for direct Messenger messaging.
 * Requires a Page Access Token.
 * Automatically resolves contact names → PSID via /me/conversations.
 */
class MessengerApiClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "MessengerApiClient"
        private const val GRAPH_API_URL = "https://graph.facebook.com/v19.0/me/messages"
        private const val GRAPH_BASE = "https://graph.facebook.com/v19.0"

        // Settings key for the Page Access Token
        const val PREF_MESSENGER_TOKEN = "messenger_access_token"

        // Settings key for the User Access Token (auto-fetches page token via /me/accounts)
        const val PREF_MESSENGER_USER_TOKEN = "messenger_user_token"

        // In-memory PSID cache: name (lowercase) → PSID
        private val psidCache = mutableMapOf<String, String>()

        // In-memory page token cache resolved from user token
        private var cachedPageToken: String? = null
        private var cachedPageId: String? = null

        /** Returns true if the string looks like a numeric Facebook PSID */
        fun isNumericPsid(id: String): Boolean = id.isNotBlank() && id.all { it.isDigit() }
    }

    /**
     * Fetches the first Page Access Token owned by the User Token via /me/accounts.
     * Returns Pair(pageId, pageAccessToken) or null on failure.
     */
    fun fetchPageTokenFromUserToken(userToken: String): Pair<String, String>? {
        if (!SettingsActivity.isSecureSettingsAvailable(context)) return null
        if (cachedPageToken != null && cachedPageId != null) {
            return Pair(cachedPageId!!, cachedPageToken!!)
        }
        return try {
            val url = "$GRAPH_BASE/me/accounts?access_token=$userToken&fields=id,name,access_token"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            Log.d(TAG, "me/accounts response: ${body.take(300)}")
            val json = JSONObject(body)
            if (json.has("error")) {
                Log.e(TAG, "me/accounts error: ${json.optJSONObject("error")?.optString("message")}")
                return null
            }
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) {
                Log.w(TAG, "me/accounts: no pages found for this user token")
                return null
            }
            val page = data.getJSONObject(0)
            val pageId = page.optString("id")
            val pageToken = page.optString("access_token")
            if (pageId.isBlank() || pageToken.isBlank()) return null
            Log.d(TAG, "Fetched page token for page: ${page.optString("name")} ($pageId)")
            cachedPageId = pageId
            cachedPageToken = pageToken
            Pair(pageId, pageToken)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPageTokenFromUserToken error: ${e.message}")
            null
        }
    }

    /**
     * Returns the effective token to use for sending:
     * 1. Page Token (direct) if set
     * 2. Page Token fetched from User Token if User Token is set
     * Returns null if neither is available.
     */
    private fun getEffectivePageToken(): String? {
        val prefs = SettingsActivity.trySecurePrefs(context) ?: return null
        val pageToken = prefs.getString(PREF_MESSENGER_TOKEN, "")
        if (!pageToken.isNullOrBlank()) return pageToken

        val userToken = prefs.getString(PREF_MESSENGER_USER_TOKEN, "")
        if (!userToken.isNullOrBlank()) {
            val result = fetchPageTokenFromUserToken(userToken)
            return result?.second
        }
        return null
    }

    /** Returns user token if set, null otherwise */
    private fun getUserToken(): String? {
        val t = SettingsActivity.trySecurePrefs(context)?.getString(PREF_MESSENGER_USER_TOKEN, "")
        return if (t.isNullOrBlank()) null else t
    }


    /**
     * Looks up the PSID for a given name from the Page's conversation list.
     * Searches /me/conversations?fields=participants and matches name.
     * Returns PSID string if found, null otherwise.
     */
    fun lookupPsidByName(name: String): String? {
        if (!SettingsActivity.isSecureSettingsAvailable(context)) return null
        val searchName = name.trim().lowercase()
        if (searchName == "__prefetch__") {
            // Just warm up the cache, don't search
            prefetchPsidCache()
            return null
        }
        // Check cache first
        psidCache[searchName]?.let { return it }

        // Populate cache then retry
        prefetchPsidCache()
        return psidCache.entries.firstOrNull { (k, _) ->
            k == searchName || k.contains(searchName) || searchName.contains(k)
        }?.value.also { Log.d(TAG, "PSID lookup '$name' → $it") }
    }

    /**
     * Fetches all conversation participants into psidCache.
     * Uses user-token-derived page token (has proper permissions).
     * Skips the page's own ID to avoid self-to-self confusion.
     */
    private fun prefetchPsidCache() {
        // Prefer user-token derived page token for conversations (has correct scope)
        val userToken = getUserToken()
        val token: String
        val pageIdToSkip: String
        if (!userToken.isNullOrBlank()) {
            val result = fetchPageTokenFromUserToken(userToken) ?: return
            token = result.second
            pageIdToSkip = result.first
        } else {
            // Fallback: try direct page token, but /me/conversations may require read_mailbox
            token = SettingsActivity.trySecurePrefs(context)?.getString(PREF_MESSENGER_TOKEN, "")?.takeIf { it.isNotBlank() } ?: return
            pageIdToSkip = cachedPageId ?: ""
        }

        try {
            val url = "$GRAPH_BASE/me/conversations?fields=participants,id&limit=100&access_token=$token"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return
            Log.d(TAG, "Conversations prefetch: ${body.take(300)}")

            val json = JSONObject(body)
            if (json.has("error")) {
                val errCode = json.optJSONObject("error")?.optInt("code") ?: -1
                val errMsg = json.optJSONObject("error")?.optString("message") ?: ""
                Log.w(TAG, "prefetchPsidCache error $errCode: $errMsg")
                // 298 = read_mailbox not granted; skip silently
                return
            }
            val data: JSONArray = json.optJSONArray("data") ?: return

            for (i in 0 until data.length()) {
                val convo = data.getJSONObject(i)
                val participants = convo.optJSONObject("participants")?.optJSONArray("data") ?: continue
                for (j in 0 until participants.length()) {
                    val p = participants.getJSONObject(j)
                    val pId = p.optString("id", "")
                    val pName = p.optString("name", "").trim()
                    // Skip the page itself — we don't want to send to ourselves
                    if (pId.isBlank() || pName.isBlank() || pId == pageIdToSkip) continue
                    psidCache[pName.lowercase()] = pId
                    Log.d(TAG, "Cached PSID: '$pName' → $pId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "prefetchPsidCache error: ${e.message}")
        }
    }


    /**
     * Sends a message directly via Graph API (Synchronous).
     * Tries Page Token first, then auto-fetches from User Token.
     * @param recipientIdOrName  Numeric PSID or contact name (will auto-lookup).
     * @param message The text to send.
     * @return Result message in Burmese.
     */
    fun sendMessageSync(recipientIdOrName: String, message: String): String =
        "BLOCKED: Messenger sending requires a verified stable recipient adapter and exact approval."

    private fun sendMessageUnchecked(recipientIdOrName: String, message: String): String {
        // Always resolve via user token first to ensure we have the real page ID
        val (token, pageId) = resolveTokenAndPageId()
            ?: return "Messenger Token မရှိသေးပါ။ Settings မှာ Page Token (သို့မဟုတ်) User Token ထည့်ပေးပါ။"

        // Resolve PSID from name if needed
        val psid: String = if (isNumericPsid(recipientIdOrName)) {
            recipientIdOrName
        } else {
            lookupPsidByName(recipientIdOrName)
                ?: return "'$recipientIdOrName' ရဲ့ PSID မတွေ့ပါ။ " +
                    "သူ/သူမ က Page ဆီ message ဦးစွာ ပို့ဖူးမှ ရပါမည်။\n" +
                    "💡 Notification access ဖွင့်ပြီး သူ့ notification ကနေ ပြန်ပို့ကြည့်ပါ။"
        }

        // Use /{pageId}/messages instead of /me/messages to avoid "me" ambiguity (subcode 33)
        val sendUrl = "$GRAPH_BASE/$pageId/messages?access_token=$token"
        Log.d(TAG, "Sending to PSID $psid via $GRAPH_BASE/$pageId/messages")

        val json = JSONObject().apply {
            put("recipient", JSONObject().apply { put("id", psid) })
            put("message", JSONObject().apply { put("text", message) })
            put("messaging_type", "RESPONSE")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(sendUrl)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            Log.d(TAG, "Full API Response: $respBody")

            if (response.isSuccessful) {
                "Messenger ကတစ်ဆင့် (API မှ) တိုက်ရိုက်ပို့ပြီးပါပြီ။"
            } else {
                val errorJson = JSONObject(respBody).optJSONObject("error")
                val errorMsg = errorJson?.optString("message") ?: "Unknown API error"
                val errorCode = errorJson?.optInt("code") ?: -1
                val errorSubcode = errorJson?.optInt("error_subcode") ?: -1

                Log.e(TAG, "API Error: code=$errorCode, subcode=$errorSubcode, msg=$errorMsg")

                when (errorCode) {
                    190 -> "Messenger Token သက်တမ်းကုန်နေပါတယ်။ Token အသစ်ပြန်ယူပါ။"
                    10 -> "Permission မရှိပါ။ Page Settings မှာ Advanced Access စစ်ဆေးပါ။"
                    100 -> if (errorSubcode == 33) {
                        "Page ID ကို မသိပါ။ Settings မှာ User Token စစ်ကြည့်ပြီး Test Connection နှိပ်ပါ။"
                    } else {
                        "Messenger API Error ($errorCode): $errorMsg\n" +
                        "💡 24 နာရီ window ကုန်သွားနိုင်တယ်။ သူ/သူမ က ဦးသွားပြောမှ ပြန်ပို့လို့ရပါမည်။"
                    }
                    else -> "Messenger API Error ($errorCode): $errorMsg"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during sendMessageSync", e)
            "Messenger ပို့လို့မရပါ: ${e.message}"
        }
    }

    /**
     * Returns Pair(pageToken, pageId) for sending, always using explicit page ID.
     * Priority: User Token → /me/accounts (explicit pageId) > direct Page Token (pageId="me" fallback)
     */
    private fun resolveTokenAndPageId(): Pair<String, String>? {
        val prefs = SettingsActivity.trySecurePrefs(context) ?: return null
        // Prefer User Token path — gives us explicit pageId (avoids "me" ambiguity)
        val userToken = prefs.getString(PREF_MESSENGER_USER_TOKEN, "")
        if (!userToken.isNullOrBlank()) {
            val result = fetchPageTokenFromUserToken(userToken)
            if (result != null) return Pair(result.second, result.first)
        }
        // Fallback: direct Page Token with cached pageId (or "me" if unknown)
        val pageToken = prefs.getString(PREF_MESSENGER_TOKEN, "")
        if (!pageToken.isNullOrBlank()) {
            val pageId = cachedPageId ?: run {
                // Try to discover pageId from this token
                try {
                    val r = Request.Builder()
                        .url("$GRAPH_BASE/me?access_token=$pageToken&fields=id")
                        .get().build()
                    val resp = client.newCall(r).execute()
                    if (resp.isSuccessful) {
                        JSONObject(resp.body?.string() ?: "").optString("id").takeIf { it.isNotBlank() }
                    } else null
                } catch (e: Exception) { null }
            } ?: "me"
            return Pair(pageToken, pageId)
        }
        return null
    }


    /**
     * Tests both tokens independently.
     * @param pageCallback receives the Page Token test result (empty string if no page token)
     * @param userCallback receives the User Token test result (empty string if no user token)
     */
    fun testConnection(
        pageCallback: (String) -> Unit,
        userCallback: (String) -> Unit
    ) {
        val prefs = SettingsActivity.trySecurePrefs(context) ?: run {
            pageCallback(com.example.voicebrainlive.security.SecureSettingsStorage.ERROR)
            userCallback(com.example.voicebrainlive.security.SecureSettingsStorage.ERROR)
            return
        }
        val pageToken = prefs.getString(PREF_MESSENGER_TOKEN, "")
        val userToken = prefs.getString(PREF_MESSENGER_USER_TOKEN, "")

        // Test Page Token
        if (!pageToken.isNullOrBlank()) {
            client.newCall(
                Request.Builder()
                    .url("$GRAPH_BASE/me?access_token=$pageToken&fields=id,name")
                    .get().build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    pageCallback("❌ ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val respBody = it.body?.string() ?: ""
                        if (it.isSuccessful) {
                            val obj = JSONObject(respBody)
                            pageCallback("✅ Page: ${obj.optString("name")} (ID: ${obj.optString("id")})")
                        } else {
                            val err = JSONObject(respBody).optJSONObject("error")
                            pageCallback("❌ Error ${err?.optInt("code")}: ${err?.optString("message")}")
                        }
                    }
                }
            })
        } else {
            pageCallback("— (ထည့်မထားပါ)")
        }

        // Test User Token → fetch page token → show page name
        if (!userToken.isNullOrBlank()) {
            Thread {
                try {
                    // Step 1: verify user token
                    val meReq = Request.Builder()
                        .url("$GRAPH_BASE/me?access_token=$userToken&fields=id,name")
                        .get().build()
                    val meResp = client.newCall(meReq).execute()
                    val meBody = meResp.body?.string() ?: ""
                    if (!meResp.isSuccessful) {
                        val err = JSONObject(meBody).optJSONObject("error")
                        userCallback("❌ User Token Error ${err?.optInt("code")}: ${err?.optString("message")}")
                        return@Thread
                    }
                    val meObj = JSONObject(meBody)
                    val userName = meObj.optString("name")

                    // Step 2: fetch managed pages
                    val result = fetchPageTokenFromUserToken(userToken)
                    if (result == null) {
                        userCallback("✅ User: $userName — ❌ manage လုပ်တဲ့ Page မတွေ့ပါ")
                        return@Thread
                    }
                    val (pageId, fetchedPageToken) = result

                    // Step 3: get page info
                    val pageReq = Request.Builder()
                        .url("$GRAPH_BASE/me?access_token=$fetchedPageToken&fields=id,name")
                        .get().build()
                    val pageResp = client.newCall(pageReq).execute()
                    val pageBody = pageResp.body?.string() ?: ""
                    if (pageResp.isSuccessful) {
                        val pageObj = JSONObject(pageBody)
                        // Prefetch conversations cache in background
                        Thread { prefetchPsidCache() }.start()
                        userCallback("✅ User: $userName → Page: ${pageObj.optString("name")} (ID: $pageId)")
                    } else {
                        userCallback("✅ User: $userName → Page ID: $pageId (page info fetch failed)")
                    }
                } catch (e: Exception) {
                    userCallback("❌ User Token Exception: ${e.message}")
                }
            }.start()
        } else {
            userCallback("— (ထည့်မထားပါ)")
        }
    }

    /**
     * Sends a message directly via Graph API (Async).
     */
    fun sendMessage(recipientIdOrName: String, message: String, callback: (String) -> Unit) {
        Thread {
            callback(sendMessageSync(recipientIdOrName, message))
        }.start()
    }
}

