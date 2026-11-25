package com.example.voicebrainlive.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.routines.RoutineStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortableMemoryBackupTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking {
            MemoryResetHelper.resetAllMemory(app)
        }
    }

    @Test
    fun exportAndImportRoundTripPreservesFactsNotesTurnsAndRoutinesIdempotently() = runBlocking {
        PersistentFactsStorage.addOrUpdateFact(app, "User name", "Aung")
        val db = VoiceBrainDatabase.getInstance(app)
        db.noteDao().insertNote(NoteEntity(title = "Grocery", content = "Milk and coffee", timestamp = 1000L))
        db.conversationTurnDao().insert(
            ConversationTurnEntity(sessionId = "main_chat", role = "user", content = "Hello", timestamp = 2000L)
        )
        RoutineStorage.add(app, type = "work", hour = 9, minute = 0, name = "Work Mode", triggerPhrase = "အလုပ်ဝင်တော့မယ်")

        val json = PortableMemoryBackup.exportJson(app)
        val parsed = org.json.JSONObject(json)
        assertEquals(PortableMemoryBackup.SCHEMA_VERSION, parsed.getInt("schemaVersion"))
        assertTrue(json.contains("Aung"))
        assertTrue(json.contains("Milk and coffee"))
        assertTrue(json.contains("Work Mode"))

        assertEquals(1, parsed.getJSONArray("routines").length())

        // Clear all Room + Facts + Routines and restore
        MemoryResetHelper.resetAllMemory(app)
        RoutineStorage.clearAll(app)
        assertTrue(RoutineStorage.getAll(app).isEmpty())
        val summary = PortableMemoryBackup.importJson(app, json, replaceExisting = true)
        assertEquals(1, summary.factsRestored)
        assertEquals(1, summary.notesRestored)
        assertEquals(1, summary.turnsRestored)
        assertEquals(1, summary.routinesRestored)

        // Re-importing without replaceExisting should deduplicate cleanly
        val secondPass = PortableMemoryBackup.importJson(app, json, replaceExisting = false)
        assertEquals(0, secondPass.notesRestored)
        assertEquals(0, secondPass.turnsRestored)
        assertEquals(0, secondPass.routinesRestored)
    }
}
