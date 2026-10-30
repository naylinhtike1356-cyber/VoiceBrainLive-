package com.example.voicebrainlive.agent.brain

import android.content.Context
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.AndroidAgentReadPort
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent

/** Creation does not open storage. A user-armed goal is required before a model proposal is saved. */
internal object AndroidConversationAgent {
    fun create(context: Context, session: String, current: () -> Boolean, display: (String) -> Unit): ConversationAgentSession {
        val app = context.applicationContext as VoiceBrainApplication
        return ConversationAgentSession(session, current, { AndroidBrainAlpha.store(app) },
            BrainMemoryAccess({ ConfirmedMemoryContextConsent.isEnabled(app) }, { app.confirmedMemoryStore.list() }, System::currentTimeMillis),
            AndroidAgentReadPort(app), display = display)
    }
}
