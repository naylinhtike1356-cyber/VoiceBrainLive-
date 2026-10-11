package com.example.voicebrainlive.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 1.3: Note Entity for Room Database
 */
@Entity(
    tableName = "notes",
    indices = [androidx.room.Index(value = ["timestamp"])]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
