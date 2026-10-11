package com.example.voicebrainlive

import android.content.Context

/**
 * Sleep / Focus mode (v2.0).
 * When active, assistant does not respond until end time or "wake up".
 */
object FocusMode {
    private const val PREFS = "focus_mode"
    private const val KEY_END_TIME = "end_time"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setFocusUntil(context: Context, endTimeMs: Long) {
        prefs(context).edit().putLong(KEY_END_TIME, endTimeMs).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_END_TIME).apply()
    }

    fun isActive(context: Context): Boolean {
        val end = prefs(context).getLong(KEY_END_TIME, 0)
        return end > System.currentTimeMillis()
    }
}
