package com.example.voicebrainlive.memory.confirmed

import android.content.Context
import com.example.voicebrainlive.VoiceBrainApplication

/** Persists one consent boolean only. No memory payload, provider credential or execution grant. */
object ConfirmedMemoryContextConsent {
    private const val PREFS = "confirmed_memory_context_consent"
    private const val ENABLED = "selected_provider_context_enabled"
    const val DISCLOSURE = "Enable confirmed memory in AI sessions? Only records with their own context checkbox enabled, still active and confirmed within 180 days will be included. At the next new voice session these records will leave this device as context sent to your selected AI provider. This setting does not start a session or send anything now. Turning it off or deleting a record affects future sessions; it cannot remove information already sent to a provider. Legacy memory is separate and is not controlled by this setting. Conflicting normalized title/category records are withheld; different-title semantic conflicts are not automatically detected."

    fun isEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    } catch (_: Exception) { false }

    /** UI calls this only after its exact foreground disclosure; not exposed as an AI tool. */
    fun setAfterReview(context: Context, enabled: Boolean): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).commit()
    } catch (_: Exception) { false }

    fun freshContext(context: Context): String = ConfirmedMemoryContext.read(isEnabled(context), System.currentTimeMillis()) {
        val owner = context.applicationContext as? VoiceBrainApplication
            ?: error("Application-owned memory unavailable")
        owner.confirmedMemoryStore.list()
    }
}
