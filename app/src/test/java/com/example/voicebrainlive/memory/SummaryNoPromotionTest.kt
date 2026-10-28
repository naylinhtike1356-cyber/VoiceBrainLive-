package com.example.voicebrainlive.memory

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.voicebrainlive.memory.confirmed.*
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SummaryNoPromotionTest {
    private fun activeProfileFile(app: Application): File {
        val name = com.example.voicebrainlive.ProfileManager(app).getActiveProfile()
            .lowercase().replace(Regex("[^a-z0-9_]"), "_")
        return File(app.filesDir, "personal_profile_${name}.json")
    }
    @Test fun modelFactsPatternsAndStyleCannotRewriteLegacyData() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val legacy = listOf("persistent_facts.json", "personal_profile.json", "intent_patterns.json", activeProfileFile(app).name)
        val before = legacy.associateWith { name -> File(app.filesDir, name).apply { writeText("legacy-$name") }.readBytes() }
        SummarizationSessionHelper.saveDerivedSummaryResponse(app, """
            SECTION 1 - FACTS
            - User name: Unconfirmed
            SECTION 2 - INTENT PATTERNS
            - "call" → make_call Unconfirmed
            SECTION 3 - PERSONAL STYLE
            Guessed communication style
            SECTION 4 - BRIEF SUMMARY
            Derived conversation summary only.
        """.trimIndent())
        legacy.forEach { name -> assertArrayEquals(before[name], File(app.filesDir, name).readBytes()) }
        assertEquals(listOf("Derived conversation summary only."), SummarizationSessionHelper.getSummaries(app))
    }
    @Test fun responseWithoutSummaryCannotCreateDurableFactsOrStyle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SummarizationSessionHelper.saveDerivedSummaryResponse(app, "SECTION 1 - FACTS\n- Name: Unconfirmed")
        assertFalse(File(app.filesDir, "persistent_facts.json").exists())
        assertFalse(File(app.filesDir, "personal_profile.json").exists())
        assertFalse(activeProfileFile(app).exists())
        assertTrue(SummarizationSessionHelper.getSummaries(app).isEmpty())
    }

    private class MemoryBytes : AtomicMemoryBytes {
        var bytes: ByteArray? = null
        var writes = 0
        override fun readOrNull() = bytes?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf(); writes++ }
    }
    private fun suggestion() = MemoryDraft("Study preference", "한국어 မြန်မာ English", ConfirmedMemoryCategory.PREFERENCE,
        ConfirmedMemorySource.USER_CONFIRMED_SUGGESTION)
    private fun encrypted(file: MemoryBytes): EncryptedConfirmedMemoryPersistence {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return EncryptedConfirmedMemoryPersistence(file) { key }
    }

    @Test fun modelSuggestionWithoutExplicitConfirmationCannotEnterConfirmedStore() {
        val file = MemoryBytes()
        val repo = ConfirmedMemoryRepository(encrypted(file), { 100L }, { "confirmed-one" })
        assertThrows(IllegalArgumentException::class.java) { repo.save(suggestion(), false) }
        assertNull(file.bytes)
        assertEquals(0, file.writes)
        assertTrue(repo.list().isEmpty())
    }

    @Test fun explicitConfirmedFactSurvivesRestartAndUnrelatedModelSummary() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = MemoryBytes()
        val persistence = encrypted(file)
        val repo = ConfirmedMemoryRepository(persistence, { 100L }, { "confirmed-one" })
        val saved = repo.save(suggestion(), true)
        val before = file.bytes!!.copyOf()
        SummarizationSessionHelper.saveDerivedSummaryResponse(app,
            "SECTION 1 - FACTS\n- Inferred replacement\nSECTION 4 - BRIEF SUMMARY\nDerived only")
        assertArrayEquals(before, file.bytes)
        assertEquals(saved, ConfirmedMemoryRepository(persistence, { 100L }, { "new-id" }).list().single())
        assertEquals(ConfirmedMemorySource.USER_CONFIRMED_SUGGESTION, saved.draft.source)
        assertFalse(String(file.bytes!!, Charsets.UTF_8).contains(saved.draft.content))
    }

    @Test fun unconfirmedCorrectionAndDeletionLeaveCiphertextExactlyUnchanged() {
        val file = MemoryBytes()
        val repo = ConfirmedMemoryRepository(encrypted(file), { 100L }, { "confirmed-one" })
        val saved = repo.save(suggestion(), true)
        val before = file.bytes!!.copyOf()
        assertThrows(IllegalArgumentException::class.java) { repo.correct(saved.id, 1, suggestion().copy(content = "guess"), false) }
        assertThrows(IllegalArgumentException::class.java) { repo.forget(saved.id, 1, false) }
        assertArrayEquals(before, file.bytes)
        assertEquals(1, file.writes)
        assertEquals(saved, repo.list().single())
    }

    @Test fun confirmedCorrectionAndDeletionAreExactAndPreserveOtherRecords() {
        val file = MemoryBytes()
        val persistence = encrypted(file)
        var sequence = 0
        val repo = ConfirmedMemoryRepository(persistence, { 100L }, { "id-${++sequence}" })
        val one = repo.save(suggestion(), true)
        val two = repo.save(suggestion().copy(title = "Other", content = "keep"), true)
        val updated = repo.correct(one.id, one.revision, suggestion().copy(content = "user correction"), true)
        val beforeStaleDelete = file.bytes!!.copyOf()
        assertThrows(IllegalArgumentException::class.java) { repo.forget(one.id, one.revision, true) }
        assertArrayEquals(beforeStaleDelete, file.bytes)
        assertTrue(repo.forget(updated.id, updated.revision, true))
        assertEquals(listOf(two), ConfirmedMemoryRepository(persistence, { 100L }, { "new-id" }).list())
    }

    @Test fun unavailableKeyCannotReadCorrectOrDeleteExistingEncryptedMemory() {
        val file = MemoryBytes()
        val saved = ConfirmedMemoryRepository(encrypted(file), { 100L }, { "confirmed-one" }).save(suggestion(), true)
        val before = file.bytes!!.copyOf()
        val unavailable = EncryptedConfirmedMemoryPersistence(file) { throw java.security.UnrecoverableKeyException("unavailable") }
        val repo = ConfirmedMemoryRepository(unavailable, { 100L }, { "new-id" })
        assertThrows(java.security.UnrecoverableKeyException::class.java) { repo.list() }
        assertThrows(java.security.UnrecoverableKeyException::class.java) { repo.correct(saved.id, 1, suggestion(), true) }
        assertThrows(java.security.UnrecoverableKeyException::class.java) { repo.forget(saved.id, 1, true) }
        assertArrayEquals(before, file.bytes)
        assertEquals(1, file.writes)
    }

    @Test fun wrongKeyAndTamperedCiphertextNeverBecomeAnEmptyWritableStore() {
        val file = MemoryBytes()
        val persistence = encrypted(file)
        ConfirmedMemoryRepository(persistence, { 100L }, { "confirmed-one" }).save(suggestion(), true)
        val before = file.bytes!!.copyOf()
        val wrongKeyRepo = ConfirmedMemoryRepository(encrypted(file), { 100L }, { "new-id" })
        assertThrows(javax.crypto.AEADBadTagException::class.java) { wrongKeyRepo.save(suggestion(), true) }
        assertArrayEquals(before, file.bytes)
        file.bytes!![file.bytes!!.lastIndex] = (file.bytes!!.last().toInt() xor 1).toByte()
        val damaged = file.bytes!!.copyOf()
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            ConfirmedMemoryRepository(persistence, { 100L }, { "new-id" }).forget("confirmed-one", 1, true)
        }
        assertArrayEquals(damaged, file.bytes)
        assertEquals(1, file.writes)
    }
}
