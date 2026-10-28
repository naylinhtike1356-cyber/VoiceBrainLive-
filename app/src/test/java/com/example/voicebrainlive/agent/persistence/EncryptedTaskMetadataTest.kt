package com.example.voicebrainlive.agent.persistence

import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class EncryptedTaskMetadataTest {
    private class Bytes : AtomicMemoryBytes {
        var value: ByteArray? = null; var writes = 0; var failWrite = false
        var beforeRead: (() -> Unit)? = null
        override fun readOrNull(): ByteArray? { beforeRead?.invoke(); return value?.copyOf() }
        override fun replaceAtomically(bytes: ByteArray) {
            if (failWrite) throw IOException("PRIVATE_IO_DETAIL")
            value = bytes.copyOf(); writes++
        }
    }
    private fun key(byte: Int = 1) = SecretKeySpec(ByteArray(32) { byte.toByte() }, "AES")
    private fun record(): PersistentTaskRecord = PersistentTaskRecord(
        PersistentTaskIdentity("task", null, "key", 1_000, 61_000,
            listOf("clock", "battery", "report").map { PersistentStepIdentity(it, "task_$it") }),
        PersistentTaskRecipe.LOCAL_STATUS, PersistentTaskState.DRAFT, 1, 1_000, 100_000, 2_000, 0, null, PersistentTaskReason.NONE)
    private fun snapshot() = PersistentTaskSnapshot(1, 1, listOf(record()))
    private fun seal(payload: ByteArray, key: SecretKey, aad: String = "VoiceBrainLive/task-metadata/schema-1"): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); updateAAD(aad.toByteArray()); byteArrayOf(1) + iv + doFinal(payload)
        }
    private fun expect(expected: TaskFailure, action: () -> Unit) {
        val failure = assertThrows(TaskStoreException::class.java) { action() }
        assertEquals(expected, failure.failure); assertFalse(failure.message.orEmpty().contains("PRIVATE"))
    }

    @Test fun encryptedRoundTripAndStaleCasPreserveCommittedBytes() {
        val bytes = Bytes(); val store = EncryptedTaskMetadata(bytes) { key() }
        assertTrue(store.compareAndReplace(0, snapshot()))
        val retained = bytes.value!!.copyOf()
        assertFalse(store.compareAndReplace(0, snapshot()))
        assertArrayEquals(retained, bytes.value); assertEquals(snapshot(), store.read()); assertEquals(1, bytes.writes)
        assertFalse(String(retained).contains("task_clock"))
    }
    @Test fun callerCollectionsCannotMutatePersistedOrReturnedSnapshot() {
        val steps = record().identity.steps.toMutableList(); val tasks = mutableListOf(record().copy(identity = record().identity.copy(steps = steps)))
        val bytes = Bytes(); val store = EncryptedTaskMetadata(bytes) { key() }
        store.compareAndReplace(0, PersistentTaskSnapshot(1, 1, tasks))
        steps.clear(); tasks.clear()
        val loaded = store.read()
        assertEquals(3, loaded.tasks.single().identity.steps.size)
        assertThrows(UnsupportedOperationException::class.java) { (loaded.tasks as MutableList<PersistentTaskRecord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (loaded.tasks.single().identity.steps as MutableList<PersistentStepIdentity>).clear() }
    }
    @Test fun corruptionAndWrongOrMissingKeyCannotResetStore() {
        val bytes = Bytes(); val store = EncryptedTaskMetadata(bytes) { key() }; store.compareAndReplace(0, snapshot())
        val original = bytes.value!!.copyOf()
        expect(TaskFailure.DATA_UNREADABLE) { EncryptedTaskMetadata(bytes) { key(2) }.read() }
        expect(TaskFailure.KEY_UNAVAILABLE) { EncryptedTaskMetadata(bytes) { error("PRIVATE_KEY_DETAIL") }.read() }
        assertArrayEquals(original, bytes.value)
        bytes.value!![bytes.value!!.lastIndex] = (bytes.value!!.last().toInt() xor 1).toByte()
        val corrupted = bytes.value!!.copyOf()
        expect(TaskFailure.DATA_UNREADABLE) { store.compareAndReplace(0, snapshot()) }
        assertArrayEquals(corrupted, bytes.value); assertEquals(1, bytes.writes)
    }
    @Test fun mutationDuringAuthenticatedCasReadCannotChangePreviouslyAdmittedPayload() {
        val steps = record().identity.steps.toMutableList()
        val tasks = mutableListOf(record().copy(identity = record().identity.copy(steps = steps)))
        val bytes = Bytes().apply { beforeRead = { tasks.clear(); steps.clear() } }
        val store = EncryptedTaskMetadata(bytes) { key() }
        assertTrue(store.compareAndReplace(0, PersistentTaskSnapshot(1, 1, tasks)))
        bytes.beforeRead = null
        assertEquals(snapshot(), store.read())
    }
    @Test fun unsupportedEnvelopeAndFutureAuthenticatedSchemaArePreserved() {
        val bytes = Bytes().apply { value = ByteArray(40).apply { this[0] = 2 } }
        val store = EncryptedTaskMetadata(bytes) { key() }; val futureEnvelope = bytes.value!!.copyOf()
        expect(TaskFailure.UNSUPPORTED_SCHEMA) { store.compareAndReplace(0, snapshot()) }
        assertArrayEquals(futureEnvelope, bytes.value)
        val future = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> out.writeInt(2) } }.toByteArray()
        bytes.value = seal(future, key()); val futureSchema = bytes.value!!.copyOf()
        expect(TaskFailure.UNSUPPORTED_SCHEMA) { store.read() }
        expect(TaskFailure.UNSUPPORTED_SCHEMA) { store.compareAndReplace(0, snapshot()) }
        assertArrayEquals(futureSchema, bytes.value); assertEquals(0, bytes.writes)
    }
    @Test fun memoryDomainCiphertextCannotBeAcceptedAsTaskMetadata() {
        val bytes = Bytes().apply { value = seal(TaskMetadataCodec.encode(snapshot()), key(), "VoiceBrainLive/confirmed-memory/schema-1") }
        val original = bytes.value!!.copyOf()
        expect(TaskFailure.DATA_UNREADABLE) { EncryptedTaskMetadata(bytes) { key() }.compareAndReplace(0, snapshot()) }
        assertArrayEquals(original, bytes.value); assertEquals(0, bytes.writes)
    }
    @Test fun stableWireNamesAndMalformedOrUnboundedRecordsFailClosed() {
        val encoded = TaskMetadataCodec.encode(snapshot())
        assertTrue(String(encoded, Charsets.ISO_8859_1).contains("LOCAL_STATUS"))
        assertTrue(String(encoded, Charsets.ISO_8859_1).contains("DRAFT"))
        val unknownWireState = String(encoded, Charsets.ISO_8859_1).replace("DRAFT", "DRXFT").toByteArray(Charsets.ISO_8859_1)
        expect(TaskFailure.DATA_UNREADABLE) { TaskMetadataCodec.decode(unknownWireState) }
        expect(TaskFailure.DATA_UNREADABLE) { TaskMetadataCodec.decode(encoded + byteArrayOf(1)) }
        expect(TaskFailure.DATA_UNREADABLE) { TaskMetadataCodec.decode(encoded.copyOf(encoded.size - 1)) }
        expect(TaskFailure.DATA_UNREADABLE) { TaskMetadataCodec.decode(ByteArray(TaskMetadataCodec.MAX_BYTES)) }
        val invalid = snapshot().copy(tasks = listOf(record().copy(recipe = PersistentTaskRecipe.FOREGROUND_REVIEW_ONLY,
            state = PersistentTaskState.RUNNING, attempts = 1, runOwner = PersistentRunOwner("p", "a"))))
        expect(TaskFailure.INVALID_REQUEST) { TaskMetadataCodec.encode(invalid) }
    }
    @Test fun failedAtomicWriteRetainsPreviousStateAndNoSuccessRevision() {
        val bytes = Bytes(); val store = EncryptedTaskMetadata(bytes) { key() }; store.compareAndReplace(0, snapshot())
        val before = bytes.value!!.copyOf(); bytes.failWrite = true
        expect(TaskFailure.STORAGE_UNAVAILABLE) { store.compareAndReplace(1, snapshot().copy(revision = 2,
            tasks = listOf(record().copy(revision = 2, state = PersistentTaskState.SCHEDULED)))) }
        assertArrayEquals(before, bytes.value); assertEquals(snapshot(), store.read()); assertEquals(1, bytes.writes)
    }
    @Test fun notificationFailureAndUncertainCancellationSurviveEncryptedRestart() {
        val failedNotice = record().copy(state = PersistentTaskState.PAUSED, queueCancellationPending = true,
            notificationFailure = TaskFailure.NOTIFICATION_UNAVAILABLE, notificationFailureAt = 1_000)
        val stored = snapshot().copy(tasks = listOf(failedNotice)); val bytes = Bytes()
        EncryptedTaskMetadata(bytes) { key() }.compareAndReplace(0, stored)
        val restored = EncryptedTaskMetadata(bytes) { key() }.read()
        assertEquals(stored, restored); assertTrue(restored.tasks.single().queueCancellationPending)
        assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, restored.tasks.single().notificationFailure)
    }
}
