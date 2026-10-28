package com.example.voicebrainlive.storage.snapshot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.voicebrainlive.memory.confirmed.AndroidAtomicMemoryBytes
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Trusted code-owned domains only; do not take filename/alias/AAD from UI or model input. */
internal data class SnapshotDomain(val fileName: String, val keyAlias: String, val aad: String) {
    init { require(fileName.matches(Regex("[a-z0-9-]{1,80}\\.enc")) && keyAlias.length in 1..120 && aad.length in 1..128) }
    companion object {
        val PROACTIVE_RULES = SnapshotDomain("agent-proactive-rules-v1.enc",
            "voicebrainlive.agent-proactive-rules.aes-gcm.v1", "VoiceBrainLive/proactive-rules/schema-1")
    }
}
internal interface SnapshotKeyAccess { fun existing(): SecretKey?; fun create(): SecretKey }
internal class AndroidNoBackupSnapshot(file: File, domain: SnapshotDomain,
    private val keys: SnapshotKeyAccess = AndroidSnapshotKeys(domain.keyAlias)) {
    private val bytes = AndroidAtomicMemoryBytes(file) {
        if (existing() == null) throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE)
        if (File(file.path + ".bak").exists() || File(file.path + ".new").exists())
            throw SnapshotStorageException(SnapshotFailure.RECOVERY_REQUIRED)
    }
    val authenticated = AuthenticatedSnapshotBytes(bytes, ::key, domain.aad)
    private fun existing(): SecretKey? = try { keys.existing() } catch (safe: SnapshotStorageException) { throw safe }
    catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) }
    private fun key(): SecretKey {
        existing()?.let { return it }
        if (bytes.hasAnyArtifact()) throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE)
        return try { keys.create() } catch (safe: SnapshotStorageException) { throw safe }
        catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) }
    }
}
internal class AndroidSnapshotKeys(private val alias: String) : SnapshotKeyAccess {
    override fun existing(): SecretKey? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
            if (containsAlias(alias)) getKey(alias, null) as? SecretKey
                ?: throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) else null
        }
    } catch (safe: SnapshotStorageException) { throw safe }
    catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) }
    override fun create(): SecretKey = try {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build()); generateKey()
        }
    } catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) }
}
internal fun androidRuleSnapshot(context: Context): AuthenticatedSnapshotBytes {
    val domain = SnapshotDomain.PROACTIVE_RULES
    return AndroidNoBackupSnapshot(File(context.applicationContext.noBackupFilesDir, domain.fileName), domain).authenticated
}
