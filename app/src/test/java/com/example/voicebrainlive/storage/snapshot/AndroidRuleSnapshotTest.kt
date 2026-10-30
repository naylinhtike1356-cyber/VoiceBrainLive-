package com.example.voicebrainlive.storage.snapshot

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

/** Temporary synthetic files/injected keys only; no native Keystore or process-crash proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AndroidRuleSnapshotTest {
    @get:Rule val folder = TemporaryFolder()
    private class Keys : SnapshotKeyAccess {
        var secret: SecretKey? = null; var generated = 0
        override fun existing() = secret
        override fun create(): SecretKey { generated++; return SecretKeySpec(ByteArray(32) { 7 }, "AES").also { secret = it } }
    }
    private fun storage(file: File, keys: Keys) = AndroidNoBackupSnapshot(file, SnapshotDomain.PROACTIVE_RULES, keys).authenticated
    @Test fun absentReadDoesNotCreateKeyAndFirstCommitIsEncrypted() {
        val file = File(folder.root, "rule.enc"); val keys = Keys(); val bytes = storage(file, keys)
        assertNull(bytes.read()); assertEquals(0, keys.generated); assertFalse(file.exists())
        val payload = "GENERIC_RULE_METADATA".toByteArray(); bytes.replace(payload)
        assertEquals(1, keys.generated); assertArrayEquals(payload, bytes.read()); assertFalse(String(file.readBytes()).contains("GENERIC_RULE_METADATA"))
    }
    @Test fun lostKeyOverExistingCiphertextCannotGenerateReplacement() {
        val file = File(folder.root, "rule.enc"); val keys = Keys(); val bytes = storage(file, keys)
        bytes.replace(byteArrayOf(1, 2, 3)); val before = file.readBytes(); keys.secret = null
        assertEquals(SnapshotFailure.KEY_UNAVAILABLE, assertThrows(SnapshotStorageException::class.java) { bytes.replace(byteArrayOf(4)) }.failure)
        assertArrayEquals(before, file.readBytes()); assertEquals(1, keys.generated)
    }
    @Test fun backupAndNewArtifactsArePreservedInsteadOfRecoveringOlderClaims() {
        for (suffix in listOf(".bak", ".new")) {
            val file = File(folder.root, "rule${suffix.drop(1)}.enc"); val keys = Keys(); val bytes = storage(file, keys)
            bytes.replace(byteArrayOf(1, 2, 3)); val before = file.readBytes()
            val artifact = File(file.path + suffix).apply { writeBytes(before) }
            assertEquals(SnapshotFailure.RECOVERY_REQUIRED, assertThrows(SnapshotStorageException::class.java) { bytes.read() }.failure)
            assertEquals(SnapshotFailure.RECOVERY_REQUIRED, assertThrows(SnapshotStorageException::class.java) { bytes.replace(byteArrayOf(4)) }.failure)
            assertArrayEquals(before, file.readBytes()); assertArrayEquals(before, artifact.readBytes())
        }
    }
    @Test fun loneInterruptedArtifactAndNoKeyCannotBecomeAnEmptyWritableStore() {
        val file = File(folder.root, "rule.enc"); val artifact = File(file.path + ".new").apply { writeBytes(byteArrayOf(8, 9)) }
        val keys = Keys(); val bytes = storage(file, keys)
        assertEquals(SnapshotFailure.KEY_UNAVAILABLE, assertThrows(SnapshotStorageException::class.java) { bytes.read() }.failure)
        assertEquals(SnapshotFailure.KEY_UNAVAILABLE, assertThrows(SnapshotStorageException::class.java) { bytes.replace(byteArrayOf(4)) }.failure)
        assertEquals(0, keys.generated); assertFalse(file.exists()); assertArrayEquals(byteArrayOf(8, 9), artifact.readBytes())
    }
}
