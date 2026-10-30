package com.example.voicebrainlive.memory.confirmed

import android.app.Application
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic temporary ciphertext only; hardware Keystore is deliberately not exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidAtomicMemoryBytesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun keyFailurePreservesEveryRecoveryArtifact() {
        val base = File(temp.root, "memory.enc")
        val files = listOf(base, File(base.path + ".bak"), File(base.path + ".new"))
        files.forEachIndexed { i, file -> file.writeBytes(byteArrayOf(i.toByte(), 42)) }
        val original = files.map { it.readBytes() }
        val bytes = AndroidAtomicMemoryBytes(base) { throw IOException("synthetic missing key") }
        try { bytes.readOrNull(); fail("Expected failure") } catch (_: IOException) { }
        files.forEachIndexed { i, file -> assertArrayEquals(original[i], file.readBytes()) }
    }

    @Test fun loneInterruptedWriteIsNeverAnEmptyWritableStore() {
        val base = File(temp.root, "memory.enc")
        val pending = File(base.path + ".new").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val bytes = AndroidAtomicMemoryBytes(base) { }
        try { bytes.readOrNull(); fail("Expected recovery failure") } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(1, 2, 3), pending.readBytes())
        assertFalse(base.exists())
    }

    @Test fun readingBackupDoesNotRemoveBaseOrInterruptedWrite() {
        val base = File(temp.root, "memory.enc").apply { writeBytes(byteArrayOf(1)) }
        val backup = File(base.path + ".bak").apply { writeBytes(byteArrayOf(2)) }
        val pending = File(base.path + ".new").apply { writeBytes(byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(2), AndroidAtomicMemoryBytes(base) { }.readOrNull())
        assertArrayEquals(byteArrayOf(1), base.readBytes())
        assertArrayEquals(byteArrayOf(2), backup.readBytes())
        assertArrayEquals(byteArrayOf(3), pending.readBytes())
    }

    @Test fun oversizedReplacementRejectsBeforeChangingExistingBytes() {
        val base = File(temp.root, "memory.enc").apply { writeBytes(byteArrayOf(4, 5)) }
        val bytes = AndroidAtomicMemoryBytes(base) { }
        try { bytes.replaceAtomically(ByteArray(2_000_001)); fail("Expected size rejection") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(4, 5), base.readBytes())
    }

    @Test fun completedAtomicReplacementCanBeReadWithoutRecoveryMutation() {
        val base = File(temp.root, "memory.enc")
        val bytes = AndroidAtomicMemoryBytes(base) { }
        bytes.replaceAtomically(byteArrayOf(6, 7))
        bytes.replaceAtomically(byteArrayOf(8, 9, 10))
        assertArrayEquals(byteArrayOf(8, 9, 10), bytes.readOrNull())
        assertArrayEquals(byteArrayOf(8, 9, 10), bytes.readOrNull())
        assertFalse(File(base.path + ".bak").exists())
    }
}
