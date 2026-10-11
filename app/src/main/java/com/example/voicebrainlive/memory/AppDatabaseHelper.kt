package com.example.voicebrainlive.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Phase 2.2: SQLite Database for persistent memory and conversations.
 * Replaces SimpleConversationStorage for better query and scaling.
 */
class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "voice_brain.db"
        private const val DATABASE_VERSION = 1

        // Table: Conversations
        private const val TABLE_CONVERSATIONS = "conversations"
        private const val COL_ID = "id"
        private const val COL_SESSION_ID = "session_id"
        private const val COL_ROLE = "role" // user, model, system
        private const val COL_CONTENT = "content"
        private const val COL_TIMESTAMP = "timestamp"

        // Table: Behavioral Milestones (Expanded BehaviorMemory)
        private const val TABLE_MILESTONES = "milestones"
        private const val COL_M_KEY = "m_key"
        private const val COL_M_VALUE = "m_value"
        private const val COL_M_COUNT = "m_count"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createConv = """
            CREATE TABLE $TABLE_CONVERSATIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION_ID TEXT,
                $COL_ROLE TEXT,
                $COL_CONTENT TEXT,
                $COL_TIMESTAMP DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent()
        
        val createMilestones = """
            CREATE TABLE $TABLE_MILESTONES (
                $COL_M_KEY TEXT PRIMARY KEY,
                $COL_M_VALUE TEXT,
                $COL_M_COUNT INTEGER DEFAULT 0,
                $COL_TIMESTAMP DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent()

        db.execSQL(createConv)
        db.execSQL(createMilestones)
        Log.d("AppDatabase", "Database created with version $DATABASE_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // Simple upgrade strategy: clear and recreate
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MILESTONES")
        onCreate(db)
    }

    /** Inserts a message into the conversation history */
    fun insertMessage(sessionId: String, role: String, content: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_SESSION_ID, sessionId)
                put(COL_ROLE, role)
                put(COL_CONTENT, content)
            }
            db.insert(TABLE_CONVERSATIONS, null, values)
        } catch (e: Exception) {
            Log.e("AppDatabase", "insertMessage", e)
        }
    }

    /** Retrieves recent history for context injection */
    fun getRecentHistory(limit: Int = 10): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_CONVERSATIONS,
                arrayOf(COL_ROLE, COL_CONTENT),
                null, null, null, null,
                "$COL_TIMESTAMP DESC",
                limit.toString()
            )
            while (cursor.moveToNext()) {
                val role = cursor.getString(0)
                val content = cursor.getString(1)
                result.add(role to content)
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e("AppDatabase", "getRecentHistory", e)
        }
        return result.reversed() // Oldest first for LLM prompt
    }
}
