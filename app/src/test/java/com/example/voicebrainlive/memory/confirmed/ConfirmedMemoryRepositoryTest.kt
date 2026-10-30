package com.example.voicebrainlive.memory.confirmed

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class ConfirmedMemoryRepositoryTest {
    private class FakePersistence : ConfirmedMemoryPersistence {
        var snapshot = ConfirmedMemorySnapshot()
        var writes = 0
        var fail = false
        override fun read(): ConfirmedMemorySnapshot {
            if (fail) error("Unavailable")
            return snapshot
        }
        override fun writeAtomically(snapshot: ConfirmedMemorySnapshot) {
            if (fail) error("Unavailable")
            this.snapshot = snapshot
            writes++
        }
    }
    private val storage = FakePersistence()
    private var time = 100L
    private var sequence = 0
    private val repo = ConfirmedMemoryRepository(storage, { time }, { "id-${++sequence}" })
    private fun draft(content: String = "မြန်မာ 한국어 English\r\n မောင်မောင်  ") =
        MemoryDraft(" Name 이름 ", content, ConfirmedMemoryCategory.FACT, ConfirmedMemorySource.USER_ENTERED)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected fail-closed rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun unconfirmedSaveDoesNotPersist() {
        rejects { repo.save(draft(), false) }
        assertEquals(0, storage.writes)
    }
    @Test fun unicodeAndCrLfPreservedWithoutNormalization() {
        val preview = draft()
        assertEquals(preview, repo.save(preview, true).draft)
        assertEquals(preview, repo.list().single().draft)
    }
    @Test fun duplicateExactSaveDoesNotWriteAgain() {
        val first = repo.save(draft(), true)
        assertEquals(first, repo.save(draft(), true))
        assertEquals(1, storage.writes)
    }
    @Test fun contradictoryTitleRequiresCorrection() {
        repo.save(draft(), true)
        rejects { repo.save(draft("new fact"), true) }
        assertEquals(1, storage.writes)
    }
    @Test fun correctionIsExactAndRejectsStaleRevision() {
        val record = repo.save(draft(), true)
        time = 101L
        val corrected = repo.correct(record.id, record.revision, draft("အသစ် 새로운"), true)
        assertEquals(2L, corrected.revision)
        rejects { repo.correct(record.id, record.revision, draft("stale"), true) }
        assertEquals(corrected, repo.list().single())
    }
    @Test fun clockRollbackCannotChangeMemory() {
        val record = repo.save(draft(), true)
        time = 99L
        assertTrue(repo.list().isEmpty())
        rejects { repo.correct(record.id, 1, draft("change"), true) }
        assertEquals(1, storage.writes)
    }
    @Test fun forgetUsesExactIdAndRevision() {
        val record = repo.save(draft(), true)
        assertFalse(repo.forget(record.id.dropLast(1), 1, true))
        rejects { repo.forget(record.id, 2, true) }
        assertTrue(repo.forget(record.id, 1, true))
        assertTrue(repo.list().isEmpty())
    }
    @Test fun retentionExpiresAtBoundaryWithoutDeletingStoredData() {
        repo.save(draft().copy(expiresAt = 200), true)
        time = 199L
        assertEquals(1, repo.list().size)
        time = 200L
        assertTrue(repo.list().isEmpty())
        assertEquals(1, repo.list(includeExpired = true).size)
    }
    @Test fun contextRequiresSeparateOptInAndKeepsWholeEntries() {
        repo.save(draft(), true)
        assertEquals("", repo.retrieve(""))
        repo.save(draft().copy(title = "Language", includeInContext = true), true)
        val result = repo.retrieve("한국어")
        assertTrue(result.contains(draft().content))
        assertTrue(result.contains("context only"))
        assertEquals("", repo.retrieve("한국어", maxChars = 10))
        assertEquals("", repo.retrieve("unrelated"))
    }
    @Test fun localRetrievalPrioritizesPreferencesDeterministically() {
        repo.save(draft().copy(title = "Fact", includeInContext = true), true)
        repo.save(draft().copy(title = "Preferred language", category = ConfirmedMemoryCategory.PREFERENCE,
            includeInContext = true), true)
        assertTrue(repo.retrieve("", maxItems = 1).contains("Preferred language"))
    }
    @Test fun secretsCannotEnterMemory() {
        rejects { repo.save(draft().copy(privacy = MemoryPrivacy.SECRET), true) }
        assertEquals(0, storage.writes)
    }
    @Test fun boundsRejectWithoutTruncating() {
        rejects { repo.save(draft("x".repeat(4_097)), true) }
        rejects { repo.save(draft().copy(title = "x".repeat(161)), true) }
        rejects { repo.save(draft().copy(expiresAt = time), true) }
        assertEquals(0, storage.writes)
    }
    @Test fun unsupportedSchemaIsPreserved() {
        storage.snapshot = ConfirmedMemorySnapshot(schemaVersion = 2)
        rejects { repo.save(draft(), true) }
        assertEquals(2, storage.snapshot.schemaVersion)
        assertEquals(0, storage.writes)
    }
    @Test fun storageReadFailureNeverOverwritesAsEmpty() {
        storage.fail = true
        try { repo.save(draft(), true); fail("Expected read failure") } catch (_: IllegalStateException) { }
        assertEquals(0, storage.writes)
    }
    @Test fun capacityDoesNotEvictExistingMemories() {
        repeat(100) { repo.save(draft().copy(title = "Fact $it"), true) }
        rejects { repo.save(draft().copy(title = "Overflow"), true) }
        assertEquals(100, repo.list().size)
    }
    @Test fun encryptedRoundTripAndAuthentication() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = object : AtomicMemoryBytes {
            var bytes: ByteArray? = null
            override fun readOrNull() = bytes?.copyOf()
            override fun replaceAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        }
        val encrypted = EncryptedConfirmedMemoryPersistence(file) { key }
        val saved = ConfirmedMemoryRepository(encrypted, { time }, { "test" }).save(draft(), true)
        assertEquals(saved, ConfirmedMemoryRepository(encrypted, { time }, { "new" }).list().single())
        val preserved = file.bytes!!.copyOf()
        file.bytes!![file.bytes!!.lastIndex] = (file.bytes!!.last().toInt() xor 1).toByte()
        try { encrypted.read(); fail("Expected authenticated decrypt failure") }
        catch (_: javax.crypto.AEADBadTagException) { }
        assertFalse(preserved.contentEquals(file.bytes!!))
    }
}
