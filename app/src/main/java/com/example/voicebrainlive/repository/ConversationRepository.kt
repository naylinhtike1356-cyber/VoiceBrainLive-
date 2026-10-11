package com.example.voicebrainlive.repository

import android.content.Context
import com.example.voicebrainlive.memory.SimpleConversationStorage
import com.example.voicebrainlive.memory.SimpleConversationStorage.TurnRecord

/**
 * Phase 5.1: Repository for conversation turns.
 * Abstracts SimpleConversationStorage for Clean Architecture.
 */
interface ConversationRepository {
    fun insert(context: Context, sessionId: String, role: String, content: String)
    fun getRecentForContext(context: Context, limit: Int): List<TurnRecord>
}

class ConversationRepositoryImpl : ConversationRepository {
    override fun insert(context: Context, sessionId: String, role: String, content: String) {
        SimpleConversationStorage.insert(context, sessionId, role, content)
    }

    override fun getRecentForContext(context: Context, limit: Int): List<TurnRecord> {
        return SimpleConversationStorage.getRecentForContext(context, limit)
    }
}
