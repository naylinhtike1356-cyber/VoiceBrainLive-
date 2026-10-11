package com.example.voicebrainlive.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 4.2: Room database for Vector Store + Conversation Log.
 * Tables: conversation_embeddings, conversation_turns
 */
@Database(
    entities = [ConversationEmbeddingEntity::class, ConversationTurnEntity::class, NoteEntity::class],
    version = 3,
    exportSchema = false
)
abstract class VoiceBrainDatabase : RoomDatabase() {
    abstract fun conversationEmbeddingDao(): ConversationEmbeddingDao
    abstract fun conversationTurnDao(): ConversationTurnDao
    abstract fun noteDao(): NoteDao

    companion object {
        private const val DB_NAME = "voice_brain_db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conversation_turns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_turns_sessionId ON conversation_turns(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_turns_timestamp ON conversation_turns(timestamp)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_timestamp ON notes(timestamp)")
            }
        }

        @Volatile
        private var instance: VoiceBrainDatabase? = null

        fun getInstance(context: Context): VoiceBrainDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoiceBrainDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
