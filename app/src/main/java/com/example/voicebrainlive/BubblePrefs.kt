package com.example.voicebrainlive

import android.content.Context

/** v2.1: Whether floating bubble is enabled. */
object BubblePrefs {
    private const val PREFS = "bubble_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_THEME = "bubble_theme"

    enum class BubbleTheme(val id: String, val titleMm: String) {
        PYIT_TINE_HTAUNG("pyit_tine_htaung", "ပစ်တိုင်းထောင်"),
        ROBOT("robot", "ဆိုက်ဘာ ရိုဘော့"),
        NEON_ORB("neon_orb", "နီယွန် အလင်းလုံး");

        companion object {
            fun fromId(id: String?): BubbleTheme {
                return entries.find { it.id == id } ?: PYIT_TINE_HTAUNG
            }
        }
    }

    /**
     * ယာယီ: ပါဝါခလုတ် conflict စစ်ဆေးရန် Bubble ကို ပိတ်ထား။
     * true ပြောင်းရင် Bubble ပြန်အလုပ်လုပ်မည်။
     */
    private const val BUBBLE_DISABLED_FOR_POWER_BUTTON = false

    fun isEnabled(context: Context): Boolean =
        if (BUBBLE_DISABLED_FOR_POWER_BUTTON) false
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTheme(context: Context): BubbleTheme {
        val themeId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, BubbleTheme.PYIT_TINE_HTAUNG.id)
        return BubbleTheme.fromId(themeId)
    }

    fun setTheme(context: Context, theme: BubbleTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, theme.id).apply()
    }
}
