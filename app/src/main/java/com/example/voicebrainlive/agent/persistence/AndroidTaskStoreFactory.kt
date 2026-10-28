package com.example.voicebrainlive.agent.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.voicebrainlive.memory.confirmed.AndroidAtomicMemoryBytes
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Lazy factory: path/key/file work first occurs inside TaskRepository's IO boundary, not app init. */
object AndroidTaskStoreFactory {
    fun create(context: Context): TaskMetadataPersistence {
        val app = context.applicationContext
        return object : TaskMetadataPersistence {
            private val delegate by lazy { AndroidTaskPersistence(File(app.noBackupFilesDir, FILE_NAME)) }
            override fun read() = delegate.read()
            override fun compareAndReplace(expectedRevision: Long, next: PersistentTaskSnapshot) = delegate.compareAndReplace(expectedRevision, next)
        }
    }
    internal const val FILE_NAME = "agent-task-metadata-v1.enc"
    internal const val KEY_ALIAS = "voicebrainlive.agent-task-metadata.aes-gcm.v1"
}

internal interface TaskKeyAccess {
    fun existing(): SecretKey?
    fun create(): SecretKey
}

internal class AndroidTaskPersistence(private val file: File,
    private val keys: TaskKeyAccess = AndroidTaskKeyAccess()) : TaskMetadataPersistence {
    private val bytes = AndroidAtomicMemoryBytes(file, ::beforeRead)
    private val encrypted = EncryptedTaskMetadata(bytes, ::key)
    override fun read() = encrypted.read()
    override fun compareAndReplace(expectedRevision: Long, next: PersistentTaskSnapshot) = encrypted.compareAndReplace(expectedRevision, next)

    private fun beforeRead() {
        requireExistingKey()
        // A backup may be authentic but older than an already consumed claim. Never replay it.
        // Preserve interrupted artifacts for explicit recovery instead of silently selecting old state.
        if (File(file.path + ".bak").exists() || File(file.path + ".new").exists())
            throw TaskStoreException(TaskFailure.RECOVERY_REQUIRED)
    }
    private fun requireExistingKey() { existingStoreKey() ?: throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
    private fun existingStoreKey(): SecretKey? = try { keys.existing() }
    catch (safe: TaskStoreException) { throw safe }
    catch (_: Exception) { throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
    private fun key(): SecretKey {
        existingStoreKey()?.let { return it }
        taskCheck(!bytes.hasAnyArtifact(), TaskFailure.KEY_UNAVAILABLE)
        return try { keys.create() } catch (safe: TaskStoreException) { throw safe }
        catch (_: Exception) { throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
    }
}

private class AndroidTaskKeyAccess : TaskKeyAccess {
    override fun existing(): SecretKey? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
            if (containsAlias(AndroidTaskStoreFactory.KEY_ALIAS))
                getKey(AndroidTaskStoreFactory.KEY_ALIAS, null) as? SecretKey
                    ?: throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE)
            else null
        }
    } catch (safe: TaskStoreException) { throw safe }
    catch (_: Exception) { throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
    override fun create(): SecretKey = try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(AndroidTaskStoreFactory.KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        } catch (_: Exception) { throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
}
