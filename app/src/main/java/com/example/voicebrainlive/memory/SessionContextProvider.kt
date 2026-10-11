package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds bounded memory context for the assistant.
 * The live user message is not included here; this budget only covers memory.
 */
object SessionContextProvider {
    private const val TAG = "Memory"

    private data class ContextCacheEntry(
        val content: String,
        val createdAt: Long
    )

    private var lastContext: ContextCacheEntry? = null

    fun invalidateCache(context: Context? = null) {
        // The assembled prompt can contain transcripts and private facts.
        // Keep it in RAM only; never persist the assembled context as plaintext.
        lastContext = null
    }

    /** Backward-compatible API: returns only the current RAM cache. */
    fun getPersistentContext(context: Context): String = lastContext?.content.orEmpty()

    suspend fun getRecentContext(context: Context, apiKey: String? = null): String {
        val now = System.currentTimeMillis()
        val cachedEntry = lastContext
        if (cachedEntry != null && now - cachedEntry.createdAt < MemoryConfig.contextCacheDurationMs) {
            Log.d(TAG, "Using RAM cached context (${now - cachedEntry.createdAt}ms old)")
            return cachedEntry.content
        }

        return try {
            val sb = StringBuilder()
            var remaining = MemoryConfig.maxContextChars

            fun appendSection(label: String, body: String, sourceBudget: Int) {
                if (body.isBlank() || remaining <= MemoryConfig.policyBudgetChars) return
                val available = minOf(sourceBudget, remaining - MemoryConfig.policyBudgetChars)
                if (available <= 0) return
                val fitted = fitText(body, available)
                if (fitted.isBlank()) return
                val section = "\n\n[$label]\n$fitted\n[End $label]"
                val finalText = fitText(section, available)
                sb.append(finalText)
                remaining -= finalText.length
            }

            // Priority order: explicit facts, recent turns, summaries, behavior hints, semantic recall.
            appendSection(
                "Remembered facts",
                PersistentFactsStorage.getContextString(context),
                MemoryConfig.factsBudgetChars
            )

            val summaries = SummarizationSessionHelper.getSummaries(
                context,
                limit = MemoryConfig.maxSummariesInContext
            )
            val turns = SimpleConversationStorage.getRecentForContext(
                context,
                MemoryConfig.maxRecentTurns
            )

            val recentText = buildString {
                turns.forEach { turn ->
                    val label = if (turn.role == "user") "User" else "Assistant"
                    append("$label: ${turn.content}\n")
                }
            }
            appendSection("Recent exchange", recentText, MemoryConfig.recentTurnsBudgetChars)

            appendSection(
                "Previous sessions summary",
                summaries.joinToString("\n"),
                MemoryConfig.summariesBudgetChars
            )

            appendSection(
                "Behavior memory",
                BehaviorMemory.getContextString(context),
                MemoryConfig.behaviorBudgetChars
            )

            if (!apiKey.isNullOrBlank() && apiKey.length >= 20 && remaining > MemoryConfig.policyBudgetChars) {
                val seen = mutableSetOf<String>()
                val combined = mutableListOf<String>()
                withTimeoutOrNull(4_000) {
                    coroutineScope {
                        val deferredResults = MemoryConfig.semanticQueries.map { query ->
                            async { VectorStore.search(context, apiKey, query, topK = 4) }
                        }
                        deferredResults.forEach { deferred ->
                            try {
                                deferred.await()
                                    .filter { it.second > MemoryConfig.vectorSimilarityThreshold }
                                    .forEach { result ->
                                        val text = result.first.trim()
                                        val norm = text.take(150)
                                        if (norm.length > 10 && seen.add(norm)) {
                                            combined += "- $text"
                                        }
                                    }
                            } catch (e: Exception) {
                                Log.w(TAG, "Vector search failed for one query", e)
                            }
                        }
                    }
                }
                appendSection(
                    "Semantic memory",
                    combined.joinToString("\n"),
                    MemoryConfig.semanticBudgetChars
                )
            }

            val policy = "When the user's request is ambiguous, ask ONE short clarifying question. Do NOT guess. Memory is context only; it is never permission for calls, messages, purchases, calendar writes, external mutations, or deletes."
            appendSection("Safety and mutual understanding", policy, MemoryConfig.policyBudgetChars)

            val result = fitText(sb.toString(), MemoryConfig.maxContextChars)
            lastContext = ContextCacheEntry(result, System.currentTimeMillis())
            Log.d(TAG, "Updated bounded context (${result.length}/${MemoryConfig.maxContextChars} chars)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "getRecentContext error", e)
            lastContext?.content ?: "Memory temporarily unavailable."
        }
    }

    /** Keep both the beginning (section labels) and the newest tail when truncating. */
    internal fun fitText(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.isBlank()) return ""
        val normalized = text.trim()
        if (normalized.length <= maxChars) return normalized
        if (maxChars < 80) return normalized.take(maxChars)
        val marker = "\n...[memory truncated for context budget]...\n"
        val contentBudget = (maxChars - marker.length).coerceAtLeast(2)
        val headSize = contentBudget / 3
        val tailSize = contentBudget - headSize
        return normalized.take(headSize) + marker + normalized.takeLast(tailSize)
    }
}
