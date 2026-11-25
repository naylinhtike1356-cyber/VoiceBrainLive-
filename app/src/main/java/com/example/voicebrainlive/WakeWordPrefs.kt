package com.example.voicebrainlive

import android.content.Context

/**
 * Wake word, assistant persona, and futuristic HUD preferences.
 */
object WakeWordPrefs {
    private const val PREFS = "wake_word_prefs"
    private const val KEY_ENABLED = "wake_word_enabled"
    private const val KEY_CUSTOM_PHRASE = "wake_custom_phrase"
    private const val KEY_PERSONA = "assistant_persona"
    private const val KEY_HUD_STYLE = "hud_style"

    enum class AssistantPersona(
        val id: String,
        val displayName: String,
        val wakeHint: String,
        val subtitleMm: String
    ) {
        JARVIS(
            id = "jarvis",
            displayName = "J.A.R.V.I.S.",
            wakeHint = "Hey Jarvis",
            subtitleMm = "Tony Stark Tactical AI — ပြတ်သားမြန်ဆန်ပြီး Agent အလုပ်များ ဦးဆောင်လုပ်မည်"
        ),
        LUMA(
            id = "luma",
            displayName = "Luma",
            wakeHint = "Hey Luma",
            subtitleMm = "နွေးထွေးရင်းနှီးသော ကိုယ်ပိုင်ပါတနာ AI"
        ),
        FRIDAY(
            id = "friday",
            displayName = "F.R.I.D.A.Y.",
            wakeHint = "Hey Friday",
            subtitleMm = "Executive Co-Pilot — လုပ်ငန်းနှင့် အချိန်ဇယား စီမံခန့်ခွဲမှု အထူး"
        );

        companion object {
            fun fromId(id: String?): AssistantPersona =
                entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: JARVIS
        }
    }

    enum class HudStyle(val id: String, val titleMm: String) {
        JARVIS_EDGE("jarvis_edge", "J.A.R.V.I.S. Holographic HUD + Edge Glow"),
        NOTCH_BAR("notch_bar", "Compact Camera Notch Bar");

        companion object {
            fun fromId(id: String?): HudStyle =
                entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: JARVIS_EDGE
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getCustomPhrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PHRASE, "")?.trim().orEmpty()

    fun setCustomPhrase(context: Context, phrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_PHRASE, phrase.trim()).apply()
    }

    fun getPersona(context: Context): AssistantPersona {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERSONA, AssistantPersona.JARVIS.id)
        return AssistantPersona.fromId(raw)
    }

    fun setPersona(context: Context, persona: AssistantPersona) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PERSONA, persona.id).apply()
    }

    fun getHudStyle(context: Context): HudStyle {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HUD_STYLE, HudStyle.JARVIS_EDGE.id)
        return HudStyle.fromId(raw)
    }

    fun setHudStyle(context: Context, style: HudStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HUD_STYLE, style.id).apply()
    }

    fun getWakeDisplayLabel(context: Context): String {
        val custom = getCustomPhrase(context)
        return if (custom.isNotBlank()) custom else getPersona(context).wakeHint
    }
}
