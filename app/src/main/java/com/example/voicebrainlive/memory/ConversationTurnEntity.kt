package com.example.voicebrainlive.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Conversation Log.
 * Stores each user/assistant turn for context injection and summarization.
 */
@Entity(
    tableName = "conversation_turns",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestamp"])]
)
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: String, // "user" | "model"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
