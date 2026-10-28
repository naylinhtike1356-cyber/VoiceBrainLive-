package com.example.voicebrainlive.memory.confirmed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Implement with one distinct file in Context.noBackupFilesDir and Android AtomicFile. */
interface AtomicMemoryBytes {
    fun readOrNull(): ByteArray?
    fun replaceAtomically(bytes: ByteArray)
}

/**
 * Additive store only: a separate key alias and file, no legacy import, migration or fallback.
 * Android integration must supply a Keystore AES key; JVM tests supply an ephemeral test key.
 * Exceptions propagate. An unreadable file is preserved, and cannot be overwritten as empty.
 */
class EncryptedConfirmedMemoryPersistence(
    private val file: AtomicMemoryBytes,
    private val secretKey: () -> SecretKey
) : ConfirmedMemoryPersistence {
    override fun read(): ConfirmedMemorySnapshot {
        val envelope = file.readOrNull() ?: return ConfirmedMemorySnapshot()
        require(envelope.size in 30..MAX_BYTES) { "Invalid encrypted memory size" }
        require(envelope[0].toInt() == 1) { "Unsupported encryption envelope" }
        val iv = envelope.copyOfRange(1, 13)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return decode(cipher.doFinal(envelope.copyOfRange(13, envelope.size)))
    }

    override fun writeAtomically(snapshot: ConfirmedMemorySnapshot) {
        val payload = encode(snapshot)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        require(cipher.iv.size == 12)
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(payload)
        val envelope = byteArrayOf(1) + cipher.iv + ciphertext
        require(envelope.size <= MAX_BYTES)
        file.replaceAtomically(envelope)
    }

    private fun encode(snapshot: ConfirmedMemorySnapshot): ByteArray {
        require(snapshot.schemaVersion == 1 && snapshot.records.size <= ConfirmedMemoryRepository.MAX_RECORDS)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(snapshot.schemaVersion)
            out.writeInt(snapshot.records.size)
            snapshot.records.forEach { record ->
                out.writeUTF(record.id)
                out.writeLong(record.revision)
                out.writeUTF(record.draft.title)
                out.writeUTF(record.draft.content)
                out.writeUTF(record.draft.category.name)
                out.writeUTF(record.draft.source.name)
                out.writeUTF(record.draft.privacy.name)
                out.writeBoolean(record.draft.includeInContext)
                out.writeBoolean(record.draft.expiresAt != null)
                record.draft.expiresAt?.let { out.writeLong(it) }
                out.writeLong(record.createdAt)
                out.writeLong(record.updatedAt)
                out.writeLong(record.confirmedAt)
            }
        }
        return buffer.toByteArray().also { require(it.size <= MAX_BYTES - 29) }
    }

    private fun decode(bytes: ByteArray): ConfirmedMemorySnapshot =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == 1) { "Unsupported memory schema" }
            val count = input.readInt()
            require(count in 0..ConfirmedMemoryRepository.MAX_RECORDS)
            val records = List(count) {
                val id = input.readUTF()
                val revision = input.readLong()
                val title = input.readUTF()
                val content = input.readUTF()
                val category = ConfirmedMemoryCategory.valueOf(input.readUTF())
                val source = ConfirmedMemorySource.valueOf(input.readUTF())
                val privacy = MemoryPrivacy.valueOf(input.readUTF())
                val include = input.readBoolean()
                val expires = if (input.readBoolean()) input.readLong() else null
                ConfirmedMemory(id, revision, MemoryDraft(title, content, category, source,
                    privacy, include, expires), input.readLong(), input.readLong(), input.readLong())
            }
            require(input.available() == 0) { "Trailing memory data" }
            ConfirmedMemorySnapshot(version, records)
        }

    companion object {
        private const val MAX_BYTES = 2_000_000
        private val AAD = "VoiceBrainLive/confirmed-memory/schema-1".toByteArray(Charsets.UTF_8)
    }
}
