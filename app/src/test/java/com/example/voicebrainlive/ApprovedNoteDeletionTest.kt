package com.example.voicebrainlive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.memory.NoteEntity
import com.example.voicebrainlive.memory.VoiceBrainDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory SQLite only: no production notes or memory migrations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApprovedNoteDeletionTest {
    @Test fun compareAndDelete_retainsChangedTargets_andNewItemsOutsideApprovedScope() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VoiceBrainDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.noteDao()
            val id = dao.insertNote(NoteEntity(title = "မြန်မာ / 한국어", content = "MiXeD!\nExact body", timestamp = 100))
            val reviewed = dao.getAllNotes().single()
            dao.updateNote(id, reviewed.title, "changed body", 101)
            val newId = dao.insertNote(NoteEntity(title = "New, outside scope", content = "retain", timestamp = 102))
            assertEquals(0, dao.deleteApprovedSnapshot(id, reviewed.title, reviewed.content, reviewed.timestamp))
            val current = dao.getAllNotes().single { it.id == id }
            assertEquals(1, dao.deleteApprovedSnapshot(id, current.title, current.content, current.timestamp))
            assertEquals(0, dao.deleteApprovedSnapshot(id, current.title, current.content, current.timestamp))
            assertEquals(listOf(newId), dao.getAllNotes().map { it.id })
        } finally { db.close() }
    }
}
