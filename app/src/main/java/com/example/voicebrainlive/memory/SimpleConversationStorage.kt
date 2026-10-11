package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.File

/**
 * Phase I Memory: Conversation Log storage.
 * Uses Room DB (ConversationTurnEntity) for persistence.
 * Call from Dispatchers.IO.
 */
object SimpleConversationStorage {

    private const val MAX_TURNS = 350
    private const val MAX_FOR_CONTEXT = 24
    private const val LEGACY_FILE = "conversation_turns.json"

    private fun dao(context: Context) =
        VoiceBrainDatabase.getInstance(context.applicationContext).conversationTurnDao()

    private suspend fun migrateFromFileIfNeeded(context: Context) {
        val file = File(context.filesDir, LEGACY_FILE)
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val d = dao(context)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                d.insert(
                    ConversationTurnEntity(
                        sessionId = obj.optString("sessionId", ""),
                        role = obj.optString("role", "model"),
                        content = obj.optString("content", ""),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                )
            }
            file.delete()
            Log.d("Memory", "Migrated ${arr.length()} turns from file to Room")
        } catch (e: Exception) {
            Log.e("SimpleConversationStorage", "migrateFromFile", e)
        }
    }

    /** Insert a turn. Call from Dispatchers.IO. */
    fun insert(context: Context, sessionId: String, role: String, content: String) {
        if (content.isBlank()) return
        runBlocking(Dispatchers.IO) {
            try {
                dao(context).insert(
                    ConversationTurnEntity(
                        sessionId = sessionId,
                        role = role,
                        content = content,
                        timestamp = System.currentTimeMillis()
                    )
                )
                dao(context).pruneOldTurns(MAX_TURNS)
                SessionContextProvider.invalidateCache(context)
                Log.d("Memory", "insert $role: ${content.take(50)}...")
            } catch (e: Exception) {
                Log.e("SimpleConversationStorage", "insert", e)
            }
        }
    }

    /** Get recent turns for context injection. Call from Dispatchers.IO. */
    fun getRecentForContext(context: Context, limit: Int = MAX_FOR_CONTEXT): List<TurnRecord> {
        return runBlocking(Dispatchers.IO) {
            try {
                migrateFromFileIfNeeded(context)
                dao(context).getRecentTurns(limit)
                    .reversed()
                    .map { TurnRecord(it.sessionId, it.role, it.content, it.timestamp) }
            } catch (e: Exception) {
                Log.e("SimpleConversationStorage", "getRecentForContext", e)
                emptyList()
            }
        }
    }

    /** Clear all conversation logs and invalidate cache */
    fun clearAll(context: Context) {
        try {
            VoiceBrainDatabase.getInstance(context.applicationContext).clearAllTables()
            SessionContextProvider.invalidateCache(context)
            Log.d("SimpleConversationStorage", "Cleared all conversation turns database and cache")
        } catch (e: Exception) {
            Log.e("SimpleConversationStorage", "clearAll error", e)
        }
    }

    data class TurnRecord(
        val sessionId: String,
        val role: String,
        val content: String,
        val timestamp: Long
    )
}
