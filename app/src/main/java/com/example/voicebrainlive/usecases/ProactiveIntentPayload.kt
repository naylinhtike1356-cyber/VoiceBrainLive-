package com.example.voicebrainlive.usecases

import android.content.Intent
import com.example.voicebrainlive.MainActivity

data class ProactiveIntentPayload(
    val message: String,
    val type: String?,
) {
    companion object {
        fun fromIntent(intent: Intent?): ProactiveIntentPayload? {
            if (intent?.getBooleanExtra(MainActivity.EXTRA_PROACTIVE_SPEAK, false) != true) {
                return null
            }

            val message = intent.getStringExtra(MainActivity.EXTRA_PROACTIVE_MESSAGE) ?: return null
            val type = intent.getStringExtra(MainActivity.EXTRA_PROACTIVE_TYPE)
            return ProactiveIntentPayload(message = message, type = type)
        }
    }
}
