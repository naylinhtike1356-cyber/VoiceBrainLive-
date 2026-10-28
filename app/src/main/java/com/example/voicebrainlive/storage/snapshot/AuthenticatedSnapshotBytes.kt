package com.example.voicebrainlive.storage.snapshot

import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class SnapshotFailure { KEY_UNAVAILABLE, STORAGE_UNAVAILABLE, DATA_UNREADABLE, UNSUPPORTED_SCHEMA, RECOVERY_REQUIRED }
internal class SnapshotStorageException(val failure: SnapshotFailure) : Exception("Snapshot: ${failure.name}")

/** New metadata domains only; never changes verified task/memory envelopes or their AAD. */
internal class AuthenticatedSnapshotBytes(private val bytes: AtomicMemoryBytes,
    private val key: () -> SecretKey, aad: String, private val limit: Int = 256 * 1024) {
    private val domain = aad.toByteArray(Charsets.UTF_8)
    init { require(domain.size in 1..128 && limit in 1_024..1_048_576) }
    @Synchronized fun read(): ByteArray? {
        val envelope = try { bytes.readOrNull() } catch (safe: SnapshotStorageException) { throw safe }
        catch (_: IOException) { throw SnapshotStorageException(SnapshotFailure.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.STORAGE_UNAVAILABLE) }
        if (envelope == null) return null
        if (envelope.size !in 30..limit) throw SnapshotStorageException(SnapshotFailure.DATA_UNREADABLE)
        if (envelope[0].toInt() != 1) throw SnapshotStorageException(SnapshotFailure.UNSUPPORTED_SCHEMA)
        val secret = secret()
        return try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
            updateAAD(domain); doFinal(envelope.copyOfRange(13, envelope.size))
        } } catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.DATA_UNREADABLE) }
    }
    @Synchronized fun replace(payload: ByteArray) {
        require(payload.size in 1..limit - 29)
        val admitted = payload.copyOf()
        read() // Existing authentication must succeed even if a caller incorrectly assumes empty.
        val secret = secret()
        val envelope = try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, secret); require(iv.size == 12); updateAAD(domain)
            byteArrayOf(1) + iv + doFinal(admitted)
        } } catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) }
        try { bytes.replaceAtomically(envelope) } catch (safe: SnapshotStorageException) { throw safe }
        catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.STORAGE_UNAVAILABLE) }
    }
    private fun secret(): SecretKey = try { key() } catch (safe: SnapshotStorageException) { throw safe }
    catch (_: Exception) { throw SnapshotStorageException(SnapshotFailure.KEY_UNAVAILABLE) }
}
