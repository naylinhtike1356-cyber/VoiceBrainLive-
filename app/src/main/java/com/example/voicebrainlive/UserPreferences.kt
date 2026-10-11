package com.example.voicebrainlive

import android.content.Context
import android.util.Log

/**
 * User preferences / context memory (v2.0).
 * Stored in SharedPreferences, injected into Gemini system instruction.
 */
object UserPreferences {
    private const val PREFS = "user_preferences"
    private const val PREFIX = "pref_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): String? =
        prefs(context).getString(PREFIX + key, null)

    fun set(context: Context, key: String, value: String) {
        prefs(context).edit().putString(PREFIX + key, value).apply()
    }

    fun getAll(context: Context): Map<String, String> =
        prefs(context).all
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            .mapKeys { it.key.removePrefix(PREFIX) }
            .mapValues { it.value as String }

    fun toSystemInstructionString(context: Context): String {
        val map = getAll(context)
        if (map.isEmpty()) return ""
        return "User preferences (use when relevant): " + map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Clear all custom learned preferences */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        Log.d("UserPreferences", "Cleared all user preferences")
    }
}
