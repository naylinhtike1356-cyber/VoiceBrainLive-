package com.example.voicebrainlive.agent.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.voicebrainlive.memory.confirmed.AndroidAtomicMemoryBytes
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** One foreground attachment store per app process. New namespace; no legacy import, migration or key rotation. */
internal object AndroidBrainAlpha {
    private const val ALIAS = "voicebrainlive.foreground-brain.aes-gcm.v1"
    private var singleton: EncryptedBrainStore? = null
    @Synchronized fun store(context: Context): EncryptedBrainStore = singleton ?: run {
        val file = File(context.applicationContext.noBackupFilesDir, "foreground-brain-v1.enc")
        fun artifacts() = file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()
        fun key(create: Boolean): SecretKey {
            val vault = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (vault.containsAlias(ALIAS)) return vault.getKey(ALIAS, null) as? SecretKey ?: throw BrainFailure("KEY_UNAVAILABLE")
            brainCheck(create && !artifacts(), "KEY_UNAVAILABLE")
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
        fun requireStable() {
            // Do not replay an older atomic backup or delete interrupted-write artifacts.
            brainCheck(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists(), "CHECKPOINT_RECOVERY_REQUIRED")
        }
        val atomic = AndroidAtomicMemoryBytes(file) { requireStable(); key(false) }
        val safeBytes = object : com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes {
            override fun readOrNull(): ByteArray? { requireStable(); return atomic.readOrNull() }
            override fun replaceAtomically(bytes: ByteArray) { requireStable(); atomic.replaceAtomically(bytes) }
        }
        EncryptedBrainStore(safeBytes) { key(true) }.also { singleton = it }
    }
}
