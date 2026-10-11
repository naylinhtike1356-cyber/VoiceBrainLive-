package com.example.voicebrainlive.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Phase 1.3: Data Access Object for Notes
 */
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotesFlow(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    suspend fun getAllNotes(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id AND title = :title AND content = :content AND timestamp = :timestamp")
    suspend fun deleteApprovedSnapshot(id: Long, title: String, content: String, timestamp: Long): Int

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE notes SET title = :title, content = :content, timestamp = :timestamp WHERE id = :id")
    suspend fun updateNote(id: Long, title: String, content: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM notes WHERE title = :title")
    suspend fun deleteNoteByTitle(title: String)

    @Query("DELETE FROM notes")
    suspend fun clearAllNotes()
}
