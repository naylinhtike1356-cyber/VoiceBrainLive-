package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * Phase 1.3: Note Repository to handle migration and Room operations.
 */
class NoteRepository(private val context: Context) {
    private val database = VoiceBrainDatabase.getInstance(context)
    private val noteDao = database.noteDao()
    private val prefs = context.getSharedPreferences("voice_notes", Context.MODE_PRIVATE)

    suspend fun saveNote(title: String, content: String): Long {
        val note = NoteEntity(title = title, content = content)
        return noteDao.insertNote(note)
    }

    suspend fun getAllNotes(): List<NoteEntity> {
        checkMigration()
        return noteDao.getAllNotes()
    }

    fun getAllNotesFlow(): Flow<List<NoteEntity>> {
        return noteDao.getAllNotesFlow()
    }

    suspend fun deleteNote(note: NoteEntity) {
        noteDao.deleteNote(note)
    }

    suspend fun deleteNote(id: Long) {
        noteDao.deleteById(id)
    }

    suspend fun updateNote(id: Long, title: String, content: String) {
        noteDao.updateNote(id, title, content)
    }

    suspend fun deleteNoteByKeyword(keyword: String): Boolean {
        val all = noteDao.getAllNotes()
        val toDelete = all.find { it.content.contains(keyword, ignoreCase = true) || it.title.contains(keyword, ignoreCase = true) }
        return if (toDelete != null) {
            noteDao.deleteNote(toDelete)
            true
        } else false
    }

    suspend fun clearAll() {
        noteDao.clearAllNotes()
    }

    /**
     * Phase 1.3: Migration logic from SharedPreferences to Room.
     * Run once if migration hasn't been done.
     */
    private suspend fun checkMigration() {
        val migrationDoneKey = "migration_to_room_done"
        if (prefs.getBoolean(migrationDoneKey, false)) return

        val allPrefs = prefs.all
        if (allPrefs.isEmpty()) {
            prefs.edit().putBoolean(migrationDoneKey, true).apply()
            return
        }

        Log.d("NoteRepository", "Starting migration of ${allPrefs.size} notes from SharedPreferences to Room")
        allPrefs.forEach { (key, value) ->
            if (key == migrationDoneKey) return@forEach
            val valStr = value as? String ?: return@forEach
            val parts = valStr.split("|", limit = 3)
            val (date, category, content) = when (parts.size) {
                3 -> Triple(parts[0], parts[1], parts[2])
                2 -> Triple(parts[0], "", parts[1])
                else -> Triple("", "", valStr)
            }
            
            val title = if (category.isNotBlank()) "[$category] $date" else date
            noteDao.insertNote(NoteEntity(title = title, content = content))
        }

        prefs.edit().putBoolean(migrationDoneKey, true).apply()
        Log.d("NoteRepository", "Migration complete")
    }
}
