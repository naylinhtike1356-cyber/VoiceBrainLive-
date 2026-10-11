package com.example.voicebrainlive.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Feedback Learning - Learns from user corrections and feedback to improve responses
 * 
 * When user says things like:
 * - "မဟုတ်ဘူး" (That's not right)
 * - "ဒါမဟုတ်ဘူး" (That's wrong)
 * - "နောက်တစ်ခေါက် ... လုပ်ပေး" (Next time do ... instead)
 * 
 * The system learns and adjusts future responses accordingly.
 * 
 * Types of feedback learned:
 * 1. Command corrections - User wanted different action
 * 2. Response quality - User didn't like the response style
 * 3. Preference learning - User prefers certain responses
 * 
 * Usage:
 * ```
 * val learning = FeedbackLearning(context)
 * 
 * // Record feedback
 * learning.recordFeedback("မဟုတ်ဘူး နံနက် ၇ နာရီမှ alarm ထားပေး")
 * 
 * // Check for learned preferences
 * val preferred = learning.getPreferredAction("set_alarm", "morning")
 * ```
 */
class FeedbackLearning(private val context: Context) {

    companion object {
        private const val TAG = "FeedbackLearning"
        private const val PREFS_NAME = "feedback_learning"
        private const val KEY_FEEDBACK_LOG = "feedback_log"
        private const val KEY_PREFERENCES = "preferences"
        private const val KEY_AVOIDED_RESPONSES = "avoided_responses"
        private const val MAX_FEEDBACK_LOG = 100
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Record user feedback
     * 
     * @param feedback The user's feedback message
     * @param contextInfo Optional context about the situation
     * @param commandType The type of command that was corrected
     */
    fun recordFeedback(feedback: String, contextInfo: Map<String, String> = emptyMap(), commandType: String? = null) {
        if (feedback.isBlank()) return
        
        val normalizedFeedback = normalizeFeedback(feedback)
        val timestamp = System.currentTimeMillis()
        
        Log.d(TAG, "Recording feedback: '$normalizedFeedback' (commandType=$commandType)")
        
        // Extract learning from feedback
        val learnedPrefs = extractPreferences(normalizedFeedback, commandType)
        
        // Save preferences
        if (learnedPrefs.isNotEmpty()) {
            savePreferences(learnedPrefs)
        }
        
        // Log feedback for analysis
        logFeedback(normalizedFeedback, contextInfo, commandType, timestamp)
        
        // Clean old feedback log
        cleanOldFeedback()
    }

    /**
     * Check if there's a learned preference for this command
     * 
     * @param commandType The command type
     * @param context The context (e.g., "morning", "evening")
     * @return Learned preference or null
     */
    fun getPreferredAction(commandType: String, context: String? = null): String? {
        val prefsJson = getPreferencesJson()
        val key = if (context != null) "${commandType}_$context" else commandType
        
        return prefsJson.optString(key).takeIf { it.isNotBlank() }
    }

    /**
     * Check if a response should be avoided based on past feedback
     * 
     * @param response The response to check
     * @return True if this response has been negatively received
     */
    fun shouldAvoidResponse(response: String): Boolean {
        val avoidedResponses = getAvoidedResponses()
        return avoidedResponses.any { avoided ->
            response.contains(avoided, ignoreCase = true) ||
            avoided.contains(response, ignoreCase = true)
        }
    }

    /**
     * Get all learned preferences
     */
    fun getAllPreferences(): Map<String, String> {
        val prefsJson = getPreferencesJson()
        val result = mutableMapOf<String, String>()
        prefsJson.keys().forEach { key ->
            result[key] = prefsJson.getString(key)
        }
        return result
    }

    /**
     * Clear all learned feedback
     */
    fun clearAll() {
        prefs.edit()
            .remove(KEY_FEEDBACK_LOG)
            .remove(KEY_PREFERENCES)
            .remove(KEY_AVOIDED_RESPONSES)
            .apply()
        Log.d(TAG, "All feedback learning cleared")
    }

    /**
     * Get feedback summary for debugging
     */
    fun getFeedbackSummary(): String {
        val prefs = getAllPreferences()
        val avoided = getAvoidedResponses()
        val feedbackLog = getFeedbackLog()
        
        return buildString {
            append("=== Feedback Learning Summary ===\n\n")
            append("Learned Preferences (${prefs.size}):\n")
            prefs.forEach { (key, value) ->
                append("  $key -> $value\n")
            }
            append("\nAvoided Responses (${avoided.size}):\n")
            avoided.forEach { response ->
                append("  - $response\n")
            }
            append("\nRecent Feedback Log (${feedbackLog.size}):\n")
            feedbackLog.take(5).forEach { log ->
                append("  - ${log.feedback} (command: ${log.commandType})\n")
            }
        }
    }

    /**
     * Check if an utterance contains negative feedback or correction
     */
    fun isCorrectionOrFeedback(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.contains("မဟုတ်ဘူး") || t.contains("ဒါမဟုတ်ဘူး") || t.contains("ဒီလိုမဟုတ်ဘူး") ||
               t.contains("မှားနေတယ်") || t.contains("လွဲနေတယ်") || t.contains("မလုပ်နဲ့တော့") ||
               t.contains("ရပ်လိုက်တော့") || t.contains("နောက်တစ်ခါ") || t.contains("နောက်တစ်ခေါက်") ||
               t.contains("နောင်ဆိုရင်") || t.contains("အဲဒါမကြိုက်ဘူး") || t.contains("မကြိုက်ဘူး") ||
               t.contains("တိုတိုပြော") || t.contains("လိုရင်းပဲပြော")
    }

    /**
     * Get formatted feedback rules string for system instruction injection.
     */
    fun getFeedbackContextString(): String {
        val prefs = getAllPreferences()
        if (prefs.isEmpty()) return ""
        val sb = StringBuilder("\n[Learned User Rules & Corrections - STRICTLY FOLLOW]\n")
        prefs.forEach { (k, v) ->
            sb.append("• Rule: $k -> $v\n")
        }
        sb.append("[End Learned User Rules]\n")
        return sb.toString()
    }

    // ===== Private Methods =====

    private fun normalizeFeedback(feedback: String): String {
        return feedback.trim().lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private fun extractPreferences(feedback: String, commandType: String?): Map<String, String> {
        val preferences = mutableMapOf<String, String>()
        val f = feedback.lowercase()

        // Check for time preferences
        val timeMatch = Regex("(\\d{1,2})[:.]?(\\d{2})?\\s*(နာရီ|am|pm)").find(f)
        if (timeMatch != null && commandType != null) {
            preferences["${commandType}_preferred_time"] = timeMatch.value
        }

        // Check for "မဟုတ်ဘူး / ဒါမဟုတ်ဘူး / မှားနေတယ်" followed by correction
        val correctionPatterns = listOf(
            Regex("(?:မဟုတ်ဘူး|ဒါမဟုတ်ဘူး|ဒီလိုမဟုတ်ဘူး|မှားနေတယ်|လွဲနေတယ်)[၊,\\s]*(.+)"),
            Regex("(?:နောက်တစ်ခါ|နောက်တစ်ခေါက်|နောင်ဆိုရင်)[၊,\\s]*(.+)")
        )
        for (pattern in correctionPatterns) {
            val match = pattern.find(f)
            if (match != null) {
                val correctedAction = match.groupValues[1].trim()
                if (correctedAction.isNotBlank() && correctedAction.length > 2) {
                    val ruleKey = if (commandType != null) "rule_${commandType}" else "rule_correction_${System.currentTimeMillis() % 10000}"
                    preferences[ruleKey] = correctedAction
                }
                break
            }
        }

        // Check for tone preferences (brevity / detail)
        if (f.contains("တိုတို") || f.contains("လိုရင်း") || f.contains("အရှည်ကြီးမပြောနဲ့")) {
            preferences["tone_preference"] = "Keep responses extremely brief and direct, strictly no fluff."
        }

        // Check for music preferences
        if (f.contains("သီချင်း") || f.contains("music") || f.contains("volume") || f.contains("အသံ")) {
            if (f.contains("တိုး") || f.contains("လျှော့")) {
                preferences["music_volume"] = "low"
            } else if (f.contains("ကျယ်") || f.contains("တိုးတယ်") || f.contains("မြှင့်")) {
                preferences["music_volume"] = "high"
            }
        }

        return preferences
    }

    private fun savePreferences(preferences: Map<String, String>) {
        val prefsJson = getPreferencesJson()
        preferences.forEach { (key, value) ->
            prefsJson.put(key, value)
        }
        prefs.edit()
            .putString(KEY_PREFERENCES, prefsJson.toString())
            .apply()
        Log.d(TAG, "Saved preferences: $preferences")
    }

    private fun getPreferencesJson(): JSONObject {
        return try {
            val jsonStr = prefs.getString(KEY_PREFERENCES, "{}") ?: "{}"
            JSONObject(jsonStr)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun getAvoidedResponses(): List<String> {
        return try {
            val jsonStr = prefs.getString(KEY_AVOIDED_RESPONSES, "[]") ?: "[]"
            val jsonArray = org.json.JSONArray(jsonStr)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun logFeedback(feedback: String, contextInfo: Map<String, String>, commandType: String?, timestamp: Long) {
        val logJson = try {
            val existing = prefs.getString(KEY_FEEDBACK_LOG, "[]") ?: "[]"
            org.json.JSONArray(existing)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        
        val entry = JSONObject().apply {
            put("feedback", feedback)
            put("commandType", commandType ?: "")
            put("timestamp", timestamp)
            put("context", JSONObject(contextInfo))
        }
        
        logJson.put(entry)
        
        prefs.edit()
            .putString(KEY_FEEDBACK_LOG, logJson.toString())
            .apply()
    }

    private fun getFeedbackLog(): List<FeedbackEntry> {
        return try {
            val jsonStr = prefs.getString(KEY_FEEDBACK_LOG, "[]") ?: "[]"
            val jsonArray = org.json.JSONArray(jsonStr)
            List(jsonArray.length()) { index ->
                val entry = jsonArray.getJSONObject(index)
                FeedbackEntry(
                    feedback = entry.optString("feedback", ""),
                    commandType = entry.optString("commandType", ""),
                    timestamp = entry.optLong("timestamp", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cleanOldFeedback() {
        val log = getFeedbackLog()
        if (log.size > MAX_FEEDBACK_LOG) {
            val recent = log.takeLast(MAX_FEEDBACK_LOG)
            val jsonArray = org.json.JSONArray()
            recent.forEach { entry ->
                jsonArray.put(JSONObject().apply {
                    put("feedback", entry.feedback)
                    put("commandType", entry.commandType)
                    put("timestamp", entry.timestamp)
                })
            }
            prefs.edit()
                .putString(KEY_FEEDBACK_LOG, jsonArray.toString())
                .apply()
        }
    }

    /**
     * Feedback log entry
     */
    data class FeedbackEntry(
        val feedback: String,
        val commandType: String,
        val timestamp: Long
    )
}