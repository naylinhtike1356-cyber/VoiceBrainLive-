package com.example.voicebrainlive.agent.persistence

import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic temporary files and injected keys only; no real Android Keystore/device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AndroidTaskPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private class Keys : TaskKeyAccess {
        var key: SecretKey? = null; var creations = 0
        override fun existing() = key
        override fun create(): SecretKey { creations++; return SecretKeySpec(ByteArray(32) { 7 }, "AES").also { key = it } }
    }
    private fun first() = PersistentTaskSnapshot(1, 1, listOf(PersistentTaskRecord(
        PersistentTaskIdentity("task", null, "key", 1_000, 61_000,
            listOf("clock", "battery", "report").map { PersistentStepIdentity(it, "task_$it") }),
        PersistentTaskRecipe.LOCAL_STATUS, PersistentTaskState.DRAFT, 1, 1_000, 100_000, 2_000, 0, null, PersistentTaskReason.NONE)))
    @Test fun trulyAbsentStoreCreatesKeyOnlyWhenFirstAtomicCommitOccurs() {
        val file = File(folder.root, "tasks.enc"); val keys = Keys(); val store = AndroidTaskPersistence(file, keys)
        assertEquals(PersistentTaskSnapshot(), store.read()); assertEquals(0, keys.creations); assertFalse(file.exists())
        assertTrue(store.compareAndReplace(0, first())); assertEquals(1, keys.creations)
        assertEquals(first(), store.read()); assertFalse(File(file.path + ".new").exists())
    }
    @Test fun lostKeyOverBaseCannotCreateReplacementOrOverwriteCiphertext() {
        val file = File(folder.root, "tasks.enc"); val keys = Keys(); val store = AndroidTaskPersistence(file, keys)
        store.compareAndReplace(0, first()); val bytes = file.readBytes(); keys.key = null
        assertEquals(TaskFailure.KEY_UNAVAILABLE, assertThrows(TaskStoreException::class.java) { store.read() }.failure)
        assertEquals(TaskFailure.KEY_UNAVAILABLE, assertThrows(TaskStoreException::class.java) { store.compareAndReplace(0, first()) }.failure)
        assertArrayEquals(bytes, file.readBytes()); assertEquals(1, keys.creations)
    }
    @Test fun authenticatedBaseWithBackupOrNewRemainsBlockedWithoutRecoveryMutation() {
        for (suffix in listOf(".bak", ".new")) {
            val file = File(folder.root, "tasks${suffix.drop(1)}.enc"); val keys = Keys(); val store = AndroidTaskPersistence(file, keys)
            store.compareAndReplace(0, first()); val original = file.readBytes()
            val artifact = File(file.path + suffix).apply { writeBytes(original) }
            assertEquals(TaskFailure.RECOVERY_REQUIRED, assertThrows(TaskStoreException::class.java) { store.read() }.failure)
            assertEquals(TaskFailure.RECOVERY_REQUIRED, assertThrows(TaskStoreException::class.java) { store.compareAndReplace(0, first()) }.failure)
            assertArrayEquals(original, file.readBytes()); assertArrayEquals(original, artifact.readBytes()); assertEquals(1, keys.creations)
        }
    }
    @Test fun loneInterruptedArtifactWithLostKeyIsNotAnEmptyStore() {
        val file = File(folder.root, "tasks.enc"); val artifact = File(file.path + ".new").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val keys = Keys(); val store = AndroidTaskPersistence(file, keys)
        assertEquals(TaskFailure.KEY_UNAVAILABLE, assertThrows(TaskStoreException::class.java) { store.read() }.failure)
        assertFalse(file.exists()); assertEquals(0, keys.creations); assertArrayEquals(byteArrayOf(1, 2, 3), artifact.readBytes())
    }
}
