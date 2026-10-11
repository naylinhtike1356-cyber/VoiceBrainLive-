package com.example.voicebrainlive

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences

object ThemeUtils {
    private const val PREFS = "app_prefs"
    private const val KEY_THEME = "theme" // "dark" | "light"
    
    fun getTheme(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "dark") ?: "dark"
    }
    
    fun setTheme(context: Context, theme: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme)
            .apply()
    }
    
    fun applyTheme(activity: Activity) {
        val theme = getTheme(activity)
        activity.setTheme(
            if (theme == "light") R.style.Theme_VoiceBrainLive_Light
            else R.style.Theme_VoiceBrainLive
        )
    }
}
