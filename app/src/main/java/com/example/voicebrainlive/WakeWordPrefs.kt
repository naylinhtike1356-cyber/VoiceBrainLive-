package com.example.voicebrainlive

import android.content.Context

/** Whether "ဟေး ကောင်လေး" wake word listening is enabled (when bubble is on). */
object WakeWordPrefs {
    private const val PREFS = "wake_word_prefs"
    private const val KEY_ENABLED = "wake_word_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
