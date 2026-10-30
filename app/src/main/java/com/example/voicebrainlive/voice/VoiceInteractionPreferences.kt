package com.example.voicebrainlive.voice

import android.content.Context

/** Boolean UI choice only. No credentials, personal memory or authorization grants. */
object VoiceInteractionPreferences {
    private const val FILE = "voice_interaction_options"
    private const val INTERRUPTION = "allow_interrupt_while_speaking"

    fun interruptionEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(INTERRUPTION, false)

    fun setInterruptionEnabled(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(INTERRUPTION, enabled).commit()
}
