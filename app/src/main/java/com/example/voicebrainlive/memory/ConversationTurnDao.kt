package com.example.voicebrainlive.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO for Conversation Log.
 */
@Dao
interface ConversationTurnDao {

    @Insert
    suspend fun insert(turn: ConversationTurnEntity): Long

    /** Get recent turns for context injection, newest last. */
    @Query("SELECT * FROM conversation_turns ORDER BY timestamp ASC")
    suspend fun getAllTurns(): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId OR (:includeMainHistory AND sessionId GLOB 'main_[0-9]*') ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getSessionTurns(sessionId: String, includeMainHistory: Boolean, limit: Int): List<ConversationTurnEntity>

    @Query("DELETE FROM conversation_turns WHERE sessionId = :sessionId OR (:includeMainHistory AND sessionId GLOB 'main_[0-9]*')")
    suspend fun deleteSessionTurns(sessionId: String, includeMainHistory: Boolean)

    @Query("DELETE FROM conversation_turns WHERE id NOT IN (SELECT id FROM conversation_turns ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun pruneOldTurns(keep: Int)

    @Query("DELETE FROM conversation_turns")
    suspend fun deleteAll()
}
