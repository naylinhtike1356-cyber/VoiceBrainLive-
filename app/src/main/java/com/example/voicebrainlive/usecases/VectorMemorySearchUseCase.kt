package com.example.voicebrainlive.usecases

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.memory.ConversationEmbeddingEntity
import com.example.voicebrainlive.memory.EmbeddingHelper
import com.example.voicebrainlive.memory.VoiceBrainDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Phase 2.2: RAG (Retrieval-Augmented Generation) - Semantic Similarity Search.
 * Finds the most relevant facts from conversational memory based on a query embedding.
 */
class VectorMemorySearchUseCase(private val context: Context) {

    private val db by lazy { VoiceBrainDatabase.getInstance(context) }
    private val embeddingDao by lazy { db.conversationEmbeddingDao() }

    /**
     * Finds top-K most relevant facts for the given query.
     * @return List of relevant text facts, formatted for LLM context.
     */
    suspend fun invoke(query: String, limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val apiKey = SettingsActivity.getGeminiApiKey(context)
        if (apiKey.isBlank()) return@withContext emptyList<String>()

        // 1. Get embedding for the current query
        val queryVector = EmbeddingHelper.embed(apiKey, query, taskType = "RETRIEVAL_QUERY")
            ?: return@withContext emptyList<String>()

        // 2. Fetch all stored embeddings
        val allFacts = embeddingDao.getAll()
        if (allFacts.isEmpty()) return@withContext emptyList<String>()

        // 3. Calculate similarity and sort
        val results = allFacts.map { fact ->
            val similarity = calculateCosineSimilarity(queryVector, fact.embedding)
            fact.text to similarity
        }
        .filter { it.second > 0.65 } // Similarity threshold for relevance
        .sortedByDescending { it.second }
        .take(limit)

        Log.d("VectorSearch", "Found ${results.size} relevant facts for query: $query")
        results.map { it.first }
    }

    private fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in vecA.indices) {
            dotProduct += (vecA[i] * vecB[i])
            normA += (vecA[i] * vecA[i])
            normB += (vecB[i] * vecB[i])
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 0.0) 0f else (dotProduct / denom).toFloat()
    }
}
