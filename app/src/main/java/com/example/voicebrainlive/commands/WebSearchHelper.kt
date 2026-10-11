package com.example.voicebrainlive.commands

import android.util.Log
import com.example.voicebrainlive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Helper class to perform Web and News searches via Serper API.
 */
class WebSearchHelper {
    private val client = OkHttpClient()
    private val apiKey = BuildConfig.SERPER_API_KEY

    suspend fun searchWeb(query: String, gl: String = "kr"): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "API Key မထည့်ရသေးပါ။ ယာယီအားဖြင့် Browser တွင် ရှာဖွေပေးပါမည်။" // Signal to fallback to browser
        }
        
        try {
            val json = JSONObject().apply { put("q", query); put("gl", gl) } 
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://google.serper.dev/search")
                .addHeader("X-API-KEY", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("WebSearchHelper", "Error from Serper: ${response.code}")
                return@withContext "ရှာဖွေမှု အဆင်မပြေပါ။ ဆာဗာ အမှားအယွင်း ဖြစ်နေပါတယ်။"
            }
            
            val responseBody = response.body?.string() ?: return@withContext "ရလဒ် မရရှိပါ။"
            val responseJson = JSONObject(responseBody)
            
            val answerBox = responseJson.optJSONObject("answerBox")
            if (answerBox != null && answerBox.has("answer")) {
                return@withContext "အဖြေ: ${answerBox.getString("answer")}"
            } else if (answerBox != null && answerBox.has("snippet")) {
                return@withContext "အဖြေ: ${answerBox.getString("snippet")}"
            }
            
            val organic = responseJson.optJSONArray("organic")
            if (organic != null && organic.length() > 0) {
                val results = mutableListOf<String>()
                val maxResults = minOf(3, organic.length())
                for (i in 0 until maxResults) {
                    val item = organic.getJSONObject(i)
                    val title = item.optString("title", "")
                    val snippet = item.optString("snippet", "")
                    if (title.isNotBlank() && snippet.isNotBlank()) {
                        results.add("$title: $snippet")
                    }
                }
                if (results.isEmpty()) return@withContext "ရှာဖွေမှု ရလဒ်ထဲမှာ ဖတ်လို့ရမယ့် အကြောင်းအရာ မတွေ့ပါ။"
                return@withContext "ဝက်ဘ်ဆိုက်များမှ တွေ့ရှိချက်:\n" + results.joinToString("\n\n")
            }
            
            return@withContext "အချက်အလက် ရှာမတွေ့ပါ။ အခြားစကားလုံးဖြင့် ထပ်ရှာကြည့်ပါ။"
        } catch (e: Exception) {
            Log.e("WebSearchHelper", "Exception during web search", e)
            return@withContext "ရှာဖွေရာမှာ အမှားအယွင်း ဖြစ်သွားပါတယ်။ အင်တာနက်ကို စစ်ဆေးပေးပါ။"
        }
    }

    suspend fun searchNews(query: String, gl: String = "kr"): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "API Key မထည့်ရသေးပါ။ Browser မှာ သတင်းဖွင့်ပေးပါမည်။"
        }

        try {
            val json = JSONObject().apply { put("q", query); put("gl", gl) }
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://google.serper.dev/news")
                .addHeader("X-API-KEY", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("WebSearchHelper", "Error from Serper News: ${response.code}")
                return@withContext "သတင်းရှာဖွေမှု အဆင်မပြေပါ။"
            }

            val responseBody = response.body?.string() ?: return@withContext "သတင်းရလဒ် မရရှိပါ။"
            val responseJson = JSONObject(responseBody)

            val newsArray = responseJson.optJSONArray("news")
            if (newsArray != null && newsArray.length() > 0) {
                val results = mutableListOf<String>()
                val maxResults = minOf(3, newsArray.length())
                for (i in 0 until maxResults) {
                    val item = newsArray.getJSONObject(i)
                    val title = item.optString("title", "")
                    val source = item.optString("source", "")
                    val date = item.optString("date", "")
                    if (title.isNotBlank()) {
                        results.add("($source, $date) - $title")
                    }
                }
                if (results.isEmpty()) return@withContext "သတင်းအသစ် မတွေ့ပါ။"
                return@withContext "သတင်း ခေါင်းစဉ်များ:\n" + results.joinToString("\n")
            }

            return@withContext "ဒီအကြောင်းအရာနဲ့ ပတ်သက်တဲ့ သတင်း မတွေ့ပါ။"
        } catch (e: Exception) {
            Log.e("WebSearchHelper", "Exception during news search", e)
            return@withContext "သတင်းရှာနေစဉ် အမှားအယွင်း ဖြစ်သွားပါတယ်။"
        }
    }
}
