package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Phase 4.2–4.4: Local Vector Store with Cosine Similarity search.
 * "ငါ အရင်က ဘာပြောခဲ့လဲ?" လို့ မေးလိုက်ရင် စာသားတစ်ခုလုံးကို လိုက်မရှာဘဲ
 * Cosine Similarity သုံးပြီး အနီးစပ်ဆုံး အချက်တွေကို ပြန်ထုတ်မယ်။
 */
object VectorStore {

    private const val TAG = "VectorStore"
    private const val MAX_EMBEDDINGS = 300
    private const val DEFAULT_TOP_K = 5
    private data class CacheEntry(
        val createdAt: Long,
        val results: List<Pair<String, Float>>
    )

    private val searchCache = mutableMapOf<String, CacheEntry>()

    /**
     * Cosine Similarity: dot(a,b) / (||a|| * ||b||)
     * Returns value in [-1, 1]. Higher = more similar.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-10f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    /**
     * Store text with its embedding. Call from Dispatchers.IO.
     */
    suspend fun store(
        context: Context,
        apiKey: String,
        text: String,
        sessionId: String = "",
        type: String = "summary"
    ): Boolean = storeBatch(context, apiKey, listOf(text), sessionId, type)

    /**
     * Store multiple texts with their embeddings in one batch.
     */
    suspend fun storeBatch(
        context: Context,
        apiKey: String,
        texts: List<String>,
        sessionId: String = "",
        type: String = "summary"
    ): Boolean = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext true
        val embeddings = EmbeddingHelper.embedBatch(apiKey, texts, "RETRIEVAL_DOCUMENT")
        val db = VoiceBrainDatabase.getInstance(context)
        val dao = db.conversationEmbeddingDao()
        
        var success = false
        texts.forEachIndexed { i, text ->
            val embedding = embeddings.getOrNull(i)
            if (embedding != null) {
                dao.insert(ConversationEmbeddingEntity(text = text, embedding = embedding, sessionId = sessionId, type = type))
                success = true
            }
        }
        
        if (success) pruneIfNeeded(context)
        Log.d(TAG, "Stored ${texts.size} items in batch (type=$type)")
        success
    }

    /**
     * Search by semantic similarity. Query is embedded, then Cosine Similarity finds top-k.
     * Always runs on Dispatchers.Default (computational thread) to avoid UI lag.
     * @return List of (text, similarityScore) sorted by score descending
     */
    suspend fun search(
        context: Context,
        apiKey: String,
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): List<Pair<String, Float>> = withContext(Dispatchers.Default) {
        val cacheKey = "$apiKey::$query::$topK"
        synchronized(searchCache) {
            val cached = searchCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.createdAt < MemoryConfig.vectorCacheDurationMs) {
                return@withContext cached.results
            }
        }
        val queryEmbedding = EmbeddingHelper.embed(apiKey, query, "RETRIEVAL_QUERY") ?: return@withContext emptyList()
        val db = VoiceBrainDatabase.getInstance(context)
        val dao = db.conversationEmbeddingDao()
        val all = dao.getAll()
        if (all.isEmpty()) return@withContext emptyList()
        val scored = all.map { entity ->
            val sim = cosineSimilarity(entity.embedding, queryEmbedding)
            entity.text to sim
        }
        val results = scored.sortedByDescending { it.second }.take(topK)
        synchronized(searchCache) {
            searchCache[cacheKey] = CacheEntry(System.currentTimeMillis(), results)
        }
        results
    }

    /**
     * Get top-k most relevant stored texts for a query. Used for context injection.
     */
    suspend fun getRelevantContext(
        context: Context,
        apiKey: String,
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): String = withContext(Dispatchers.IO) {
        val results = search(context, apiKey, query, topK)
        if (results.isEmpty()) return@withContext ""
        results
            .filter { it.second > MemoryConfig.relevantContextThreshold }
            .joinToString("\n") { (text, _) -> "- $text" }
            .take(800)  // limit context length
    }

    private suspend fun pruneIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val db = VoiceBrainDatabase.getInstance(context)
        val dao = db.conversationEmbeddingDao()
        val count = dao.count()
        if (count > MAX_EMBEDDINGS) {
            val all = dao.getAll().sortedBy { it.timestamp }
            val keepFromIndex = count - MAX_EMBEDDINGS
            val threshold = all[keepFromIndex].timestamp
            dao.deleteOlderThan(threshold)
            Log.d(TAG, "Pruned ${keepFromIndex} old embeddings")
        }
    }

    /** Clear local vector search cache */
    fun clearCache() {
        synchronized(searchCache) {
            searchCache.clear()
        }
        Log.d(TAG, "Cleared VectorStore search cache")
    }
}
