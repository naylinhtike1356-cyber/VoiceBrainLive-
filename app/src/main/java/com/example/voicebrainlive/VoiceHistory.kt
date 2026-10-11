package com.example.voicebrainlive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val timestamp: Long,
    val userText: String,
    val assistantText: String
)

object VoiceHistory {
    private const val PREFS = "voice_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private const val TAG = "VoiceHistory"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, userText: String, assistantText: String) {
        Log.d(TAG, ">>> VoiceHistory.add() called")
        Log.d(TAG, "    userText: [$userText]")
        Log.d(TAG, "    assistantText: [$assistantText]")
        
        if (userText.isBlank() && assistantText.isBlank()) {
            Log.d(TAG, "    SKIPPED: Both texts are blank")
            return
        }
        
        val prefs = getPrefs(context)
        val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
        val obj = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("user", userText)
            put("assistant", assistantText)
        }
        arr.put(0, obj)
        while (arr.length() > MAX_ENTRIES) arr.remove(arr.length() - 1)
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        
        Log.d(TAG, "    SAVED! Total entries: ${arr.length()}")
    }

    fun getAll(context: Context): List<HistoryEntry> {
        val prefs = getPrefs(context)
        val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
        Log.d(TAG, "getAll() returning ${arr.length()} entries")
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(
                obj.optLong("ts", 0),
                obj.optString("user", ""),
                obj.optString("assistant", "")
            )
        }
    }

    fun clear(context: Context) {
        getPrefs(context).edit().remove(KEY_ENTRIES).apply()
        Log.d(TAG, "clear() - history cleared")
    }
}
