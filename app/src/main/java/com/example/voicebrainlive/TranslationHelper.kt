package com.example.voicebrainlive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TranslationHelper(private val apiKey: String) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
    
    companion object {
        const val TAG = "TranslationHelper"
        
        val SUPPORTED_LANGUAGES = mapOf(
            "english" to "en",
            "myanmar" to "my",
            "burmese" to "my",
            "korean" to "ko",
            "japanese" to "ja",
            "chinese" to "zh",
            "thai" to "th",
            "vietnamese" to "vi",
            "indonesian" to "id",
            "spanish" to "es",
            "french" to "fr",
            "german" to "de",
            "italian" to "it",
            "portuguese" to "pt",
            "russian" to "ru",
            "arabic" to "ar"
        )
        
        val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "my" to "Myanmar",
            "ko" to "Korean",
            "ja" to "Japanese",
            "zh" to "Chinese",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "id" to "Indonesian",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ar" to "Arabic"
        )
    }
    
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val sourceCode = SUPPORTED_LANGUAGES[sourceLang.lowercase()] ?: "auto"
            val targetCode = SUPPORTED_LANGUAGES[targetLang.lowercase()] ?: "en"
            
            val url = "https://translation.googleapis.com/language/translate/v2?key=$apiKey"
            
            val jsonBody = JSONObject().apply {
                put("q", text)
                put("source", sourceCode)
                put("target", targetCode)
                put("format", "text")
            }
            
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val translations = json.getJSONObject("data").getJSONArray("translations")
                    translations.getJSONObject(0).getString("translatedText")
                } else {
                    Log.e(TAG, "Translation request failed: HTTP ${response.code}")
                    "Translation failed"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation exception: ${e.message}")
            "Translation error: ${e.message}"
        }
    }
    
    suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://translation.googleapis.com/language/translate/v2/detect?key=$apiKey"
            
            val jsonBody = JSONObject().apply {
                put("q", text)
            }
            
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val detections = json.getJSONObject("data").getJSONArray("detections")
                    val detected = detections.getJSONArray(0).getJSONObject(0)
                    detected.getString("language")
                } else {
                    Log.e(TAG, "Language detection failed: HTTP ${response.code}")
                    "unknown"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection exception: ${e.message}")
            "unknown"
        }
    }
    
    fun getLanguageName(code: String): String {
        return LANGUAGE_NAMES[code] ?: code
    }
    
    fun getLanguageCode(name: String): String {
        return SUPPORTED_LANGUAGES[name.lowercase()] ?: name
    }
}
