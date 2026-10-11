package com.example.voicebrainlive.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phase 4.1: Gemini Embedding API - gemini-embedding-2.
 * Fallback: gemini-embedding-001 if the latest model is unavailable.
 * Converts text to embedding vector (FloatArray) for semantic similarity search.
 */
object EmbeddingHelper {

    private const val MODEL_PRIMARY = "text-embedding-004"
    private const val MODEL_FALLBACK = "gemini-embedding-001"
    private const val OUTPUT_DIMENSIONALITY = 768
    private const val TAG = "EmbeddingHelper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Embed a single text. Call from Dispatchers.IO.
     * @param apiKey Gemini API key
     * @param text Text to embed
     * @param taskType RETRIEVAL_DOCUMENT for storing, RETRIEVAL_QUERY for search
     * @return FloatArray embedding or null on failure
     */
    suspend fun embed(
        apiKey: String,
        text: String,
        taskType: String = "RETRIEVAL_DOCUMENT"
    ): FloatArray? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.length < 20) return@withContext null
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext null
        callEmbedApi(apiKey, trimmed, taskType, MODEL_PRIMARY)
            ?: callEmbedApi(apiKey, trimmed, taskType, MODEL_FALLBACK)
    }

    /**
     * Embed multiple texts in one batch (more efficient). Call from Dispatchers.IO.
     */
    suspend fun embedBatch(
        apiKey: String,
        texts: List<String>,
        taskType: String = "RETRIEVAL_DOCUMENT"
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.length < 20) return@withContext texts.map { null }
        val nonEmpty = texts.mapIndexedNotNull { index, text ->
            text.trim().takeIf { it.isNotBlank() }?.let { index to it }
        }
        if (nonEmpty.isEmpty()) return@withContext texts.map { null }

        val compactTexts = nonEmpty.map { it.second }
        val compactResult = callBatchEmbedApi(apiKey, compactTexts, taskType, MODEL_PRIMARY)
            .takeIf { result -> result.any { it != null } }
            ?: callBatchEmbedApi(apiKey, compactTexts, taskType, MODEL_FALLBACK)

        MutableList<FloatArray?>(texts.size) { null }.apply {
            nonEmpty.forEachIndexed { resultIndex, (originalIndex, _) ->
                this[originalIndex] = compactResult.getOrNull(resultIndex)
            }
        }
    }

    private fun callEmbedApi(apiKey: String, text: String, taskType: String, model: String): FloatArray? {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"
            val preparedText = prepareTextForModel(text, taskType, model)
            val body = JSONObject().apply {
                put("model", "models/$model")
                put("content", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", preparedText)))
                })
                if (model == MODEL_PRIMARY) {
                    put("outputDimensionality", OUTPUT_DIMENSIONALITY)
                } else {
                    put("taskType", taskType)
                }
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "embedContent error: ${response.code} ${response.body?.string()}")
                return null
            }
            parseEmbeddingResponse(response.body?.string())
        } catch (e: Exception) {
            Log.e(TAG, "embedContent", e)
            null
        }
    }

    private fun callBatchEmbedApi(
        apiKey: String,
        texts: List<String>,
        taskType: String,
        model: String
    ): List<FloatArray?> {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:batchEmbedContents?key=$apiKey"
            val requests = texts.map { text ->
                JSONObject().apply {
                    put("model", "models/$model")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", prepareTextForModel(text, taskType, model))))
                    })
                    if (model == MODEL_PRIMARY) {
                        put("outputDimensionality", OUTPUT_DIMENSIONALITY)
                    } else {
                        put("taskType", taskType)
                    }
                }
            }
            val body = JSONObject().apply {
                put("model", "models/$model")
                put("requests", JSONArray().apply { requests.forEach { put(it) } })
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "batchEmbedContents error: ${response.code} ${response.body?.string()}")
                return texts.map { null }
            }
            parseBatchEmbeddingResponse(response.body?.string(), texts.size)
        } catch (e: Exception) {
            Log.e(TAG, "batchEmbedContents", e)
            texts.map { null }
        }
    }

    private fun prepareTextForModel(text: String, taskType: String, model: String): String {
        if (model != MODEL_PRIMARY) return text
        return when (taskType) {
            "RETRIEVAL_QUERY" -> "task: search result | query: $text"
            "QUESTION_ANSWERING" -> "task: question answering | query: $text"
            "FACT_VERIFICATION" -> "task: fact checking | query: $text"
            "CODE_RETRIEVAL_QUERY" -> "task: code retrieval | query: $text"
            "RETRIEVAL_DOCUMENT" -> "title: none | text: $text"
            else -> "task: sentence similarity | query: $text"
        }
    }

    private fun parseEmbeddingResponse(json: String?): FloatArray? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            // EmbedContentResponse variants: embedding.values, embeddings[0].values, or embedding array.
            val values = when {
                obj.has("embeddings") -> obj.optJSONArray("embeddings")
                    ?.optJSONObject(0)
                    ?.optJSONArray("values")
                obj.has("embedding") -> {
                    val emb = obj.get("embedding")
                    when (emb) {
                        is JSONObject -> emb.optJSONArray("values")
                        is JSONArray -> emb
                        else -> null
                    }
                }
                else -> null
            } ?: return null
            FloatArray(values.length()) { i -> values.getDouble(i).toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "parseEmbeddingResponse", e)
            null
        }
    }

    private fun parseBatchEmbeddingResponse(json: String?, expectedCount: Int): List<FloatArray?> {
        if (json.isNullOrBlank()) return List(expectedCount) { null }
        return try {
            val obj = JSONObject(json)
            val embeddings = obj.optJSONArray("embeddings")
            if (embeddings != null) {
                return (0 until expectedCount).map { i ->
                    val emb = embeddings.optJSONObject(i) ?: return@map null
                    val values = emb.optJSONArray("values") ?: return@map null
                    FloatArray(values.length()) { j -> values.getDouble(j).toFloat() }
                }
            }

            val responses = obj.optJSONArray("responses") ?: return List(expectedCount) { null }
            (0 until expectedCount).map { i ->
                val emb = responses.optJSONObject(i)
                    ?.optJSONObject("embedding")
                    ?: return@map null
                val values = emb.optJSONArray("values") ?: return@map null
                FloatArray(values.length()) { j -> values.getDouble(j).toFloat() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseBatchEmbeddingResponse", e)
            List(expectedCount) { null }
        }
    }
}
