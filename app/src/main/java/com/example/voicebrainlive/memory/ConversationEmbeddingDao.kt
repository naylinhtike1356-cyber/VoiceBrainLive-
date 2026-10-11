package com.example.voicebrainlive.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Phase 4.2: DAO for conversation embeddings.
 * Similarity search done in-memory via Cosine Similarity (Room has no native vector search).
 */
@Dao
interface ConversationEmbeddingDao {

    @Insert
    suspend fun insert(entity: ConversationEmbeddingEntity): Long

    @Query("SELECT * FROM conversation_embeddings ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ConversationEmbeddingEntity>

    @Query("SELECT * FROM conversation_embeddings")
    suspend fun getAll(): List<ConversationEmbeddingEntity>

    @Query("DELETE FROM conversation_embeddings WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
    
    @Query("DELETE FROM conversation_embeddings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM conversation_embeddings")
    suspend fun count(): Int
}
