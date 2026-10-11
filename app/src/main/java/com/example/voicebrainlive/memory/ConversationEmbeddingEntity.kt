package com.example.voicebrainlive.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Phase 4.2: Room entity for storing embedding vectors.
 * gemini-embedding-2 outputs 768 dimensions here to keep local search lightweight.
 * Vector stored as comma-separated floats for Room compatibility.
 */
@Entity(
    tableName = "conversation_embeddings",
    indices = [
        androidx.room.Index(value = ["timestamp"]),
        androidx.room.Index(value = ["sessionId"]),
        androidx.room.Index(value = ["type"])
    ]
)
@TypeConverters(FloatArrayConverter::class)
data class ConversationEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Original text (summary or fact) that was embedded */
    val text: String,
    /** Embedding vector from Gemini Embedding */
    val embedding: FloatArray,
    /** Session ID when this was created */
    val sessionId: String = "",
    /** Timestamp for ordering */
    val timestamp: Long = System.currentTimeMillis(),
    /** Type: "summary" | "fact" | "turn" */
    val type: String = "summary"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConversationEmbeddingEntity
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int = embedding.contentHashCode()
}

/** Room TypeConverter: FloatArray <-> String (comma-separated) */
class FloatArrayConverter {
    @TypeConverter
    fun fromString(value: String): FloatArray {
        if (value.isBlank()) return floatArrayOf()
        return value.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }

    @TypeConverter
    fun toString(array: FloatArray): String {
        return array.joinToString(",")
    }
}
