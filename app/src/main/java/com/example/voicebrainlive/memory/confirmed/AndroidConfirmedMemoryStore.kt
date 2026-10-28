package com.example.voicebrainlive.memory.confirmed

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

enum class ConfirmedMemoryFailure { STORAGE_UNAVAILABLE, KEY_UNAVAILABLE, DATA_UNREADABLE, INVALID_OR_STALE_REQUEST }
class ConfirmedMemoryStoreException(val failure: ConfirmedMemoryFailure) : Exception("Confirmed memory: ${failure.name}")

/** Owned once by VoiceBrainApplication. Never import/migrate/reset legacy memory from this store. */
class AndroidConfirmedMemoryStore internal constructor(context: Context) : ConfirmedMemoryStore {
    private val bytes = AndroidAtomicMemoryBytes(File(context.applicationContext.noBackupFilesDir, "confirmed-memory-v1.enc"), ::requireExistingKey)
    private val repository = ConfirmedMemoryRepository(
        EncryptedConfirmedMemoryPersistence(bytes, ::key),
        System::currentTimeMillis,
        { UUID.randomUUID().toString() }
    )

    override fun list(includeExpired: Boolean): List<ConfirmedMemory> = safe { repository.list(includeExpired) }
    override fun save(draft: MemoryDraft, confirmedExactPreview: Boolean): ConfirmedMemory =
        safe { repository.save(draft, confirmedExactPreview) }
    override fun correct(id: String, revision: Long, draft: MemoryDraft, confirmedExactPreview: Boolean): ConfirmedMemory =
        safe { repository.correct(id, revision, draft, confirmedExactPreview) }
    override fun forget(id: String, revision: Long, confirmedExactPreview: Boolean): Boolean =
        safe { repository.forget(id, revision, confirmedExactPreview) }

    private fun key(): SecretKey {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) {
                return store.getKey(KEY_ALIAS, null) as? SecretKey
                    ?: throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.KEY_UNAVAILABLE)
            }
            // Include backups and interrupted-write artifacts. Never replace a lost key over data.
            if (bytes.hasAnyArtifact()) throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.KEY_UNAVAILABLE)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            return generator.generateKey()
        } catch (safe: ConfirmedMemoryStoreException) {
            throw safe
        } catch (_: Exception) {
            throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.KEY_UNAVAILABLE)
        }
    }

    private fun requireExistingKey() {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(KEY_ALIAS) || store.getKey(KEY_ALIAS, null) !is SecretKey) {
                throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.KEY_UNAVAILABLE)
            }
        } catch (safe: ConfirmedMemoryStoreException) { throw safe
        } catch (_: Exception) {
            throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.KEY_UNAVAILABLE)
        }
    }

    private fun <T> safe(block: () -> T): T = try {
        block()
    } catch (safe: ConfirmedMemoryStoreException) {
        throw safe
    } catch (_: IOException) {
        throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.STORAGE_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        // Validation, unsupported/corrupt snapshots and stale revision fail closed.
        throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.INVALID_OR_STALE_REQUEST)
    } catch (_: Exception) {
        throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.DATA_UNREADABLE)
    }

    companion object { private const val KEY_ALIAS = "voicebrainlive.confirmed-memory.aes-gcm.v1" }
}

/** Bounded no-backup AtomicFile adapter, used only through the singleton repository lock. */
internal class AndroidAtomicMemoryBytes(private val base: File, private val beforeExistingRead: () -> Unit) : AtomicMemoryBytes {
    private val atomic = AtomicFile(base)
    fun hasAnyArtifact(): Boolean = base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()

    override fun readOrNull(): ByteArray? {
        if (!hasAnyArtifact()) return null
        // Check the existing key before any framework recovery can mutate ciphertext artifacts.
        beforeExistingRead()
        // A lone .new is evidence of data, not permission to pretend the store is empty.
        if (!base.exists() && !File(base.path + ".bak").exists()) throw IOException("Memory recovery required")
        // Non-mutating reads: AtomicFile.openRead may rename/delete .bak/.new before authentication.
        // Prefer legacy backup exactly as framework recovery does, but preserve every artifact.
        val backup = File(base.path + ".bak")
        val readable = if (backup.exists()) backup else base
        return readable.inputStream().use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < bytes.size) {
                val count = input.read(bytes, total, bytes.size - total)
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    bytes[total++] = value.toByte()
                } else total += count
            }
            if (total > MAX_BYTES) throw IOException("Memory file exceeds bound")
            bytes.copyOf(total)
        }
    }

    override fun replaceAtomically(bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            output?.let { atomic.failWrite(it) }
            throw failure
        }
    }

    companion object { private const val MAX_BYTES = 2_000_000 }
}
