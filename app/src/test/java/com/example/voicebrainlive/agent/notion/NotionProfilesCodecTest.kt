package com.example.voicebrainlive.agent.notion

import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import com.example.voicebrainlive.storage.snapshot.AuthenticatedSnapshotBytes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class NotionProfilesCodecTest {
    private class Bytes : AtomicMemoryBytes {
        var value: ByteArray? = null; var writes = 0; var fail = false
        override fun readOrNull() = value?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) {
            if (fail) throw IOException("FAKE_SECRET_IO")
            value = bytes.copyOf(); writes++
        }
    }
    private fun key(value: Int = 1) = SecretKeySpec(ByteArray(32) { value.toByte() }, "AES")
    private val aad = "VoiceBrainLive/notion-profiles/schema-1"
    private fun store(bytes: Bytes, value: Int = 1, domain: String = aad) = EncryptedNotionProfiles(AuthenticatedSnapshotBytes(bytes, { key(value) }, domain))
    private fun state(): ProfileVaultState {
        val ns = LocalNamespace("namespace"); val view = CredentialView("credential", 1, "Demo credential", ns, false)
        val schema = taskSchema(1, PropertyPin("title", PropertyKind.TITLE), PropertyPin("f%5C%5C%3Ap", PropertyKind.STATUS), setOf("Todo"))
        val profile = ConnectionProfileView("profile", 2, "Synthetic demo", DirectProfileStatus.SYNTHETIC_VERIFIED,
            CredentialRef(view.credentialId, 1), ns, "2026-03-11", DataSourceBinding("database", "data_source"), schema, EvidenceSource.SYNTHETIC_FIXTURE)
        return ProfileVaultState(1, 1_000, listOf(profile), listOf(StoredCredential(view, "FAKE_TOKEN".toCharArray())))
    }
    private fun reject(expected: ProfileFailure, action: () -> Unit) {
        val actual = assertThrows(ProfileStoreException::class.java) { action() }; assertEquals(expected, actual.failure)
        assertFalse(actual.message.orEmpty().contains("FAKE_TOKEN"))
    }
    @Test fun encryptedSnapshotRoundTripRetainsOneAtomicBindingAndSecretOnlyThroughLeaseStorage() {
        val bytes = Bytes(); val source = state()
        try { assertTrue(store(bytes).compareAndReplace(0, source)) } finally { source.clearSecrets() }
        val restored = store(bytes).readOwned()
        try {
            assertEquals("f%5C%5C%3Ap", restored.profiles.single().schema.businessStatus.propertyId)
            assertTrue(restored.credentials.single().copySecret()!!.contentEquals("FAKE_TOKEN".toCharArray()))
            assertFalse(restored.toString().contains("FAKE_TOKEN"))
            assertFalse(store(bytes).compareAndReplace(0, restored)); assertEquals(1, bytes.writes)
        } finally { restored.clearSecrets() }
    }
    @Test fun lostWrongKeyTamperAndOtherDomainCannotResetExistingVault() {
        val bytes = Bytes(); val source = state(); try { store(bytes).compareAndReplace(0, source) } finally { source.clearSecrets() }
        val retained = bytes.value!!.copyOf()
        reject(ProfileFailure.DATA_UNREADABLE) { store(bytes, value = 2).readOwned() }
        reject(ProfileFailure.DATA_UNREADABLE) { store(bytes, domain = "VoiceBrainLive/proactive-rules/schema-1").readOwned() }
        val absent = EncryptedNotionProfiles(AuthenticatedSnapshotBytes(bytes, { error("FAKE_TOKEN") }, aad))
        reject(ProfileFailure.KEY_UNAVAILABLE) { absent.readOwned() }; assertArrayEquals(retained, bytes.value)
        bytes.value!![bytes.value!!.lastIndex] = (bytes.value!!.last().toInt() xor 1).toByte(); val corrupt = bytes.value!!.copyOf()
        val next = state(); try { reject(ProfileFailure.DATA_UNREADABLE) { store(bytes).compareAndReplace(0, next) } } finally { next.clearSecrets() }
        assertArrayEquals(corrupt, bytes.value); assertEquals(1, bytes.writes)
    }
    @Test fun futureEnvelopeAndAuthenticatedSchemaArePreservedWithoutReset() {
        val bytes = Bytes().apply { value = ByteArray(40).apply { this[0] = 2 } }; val original = bytes.value!!.copyOf()
        reject(ProfileFailure.UNSUPPORTED_SCHEMA) { store(bytes).readOwned() }; assertArrayEquals(original, bytes.value)
        val payload = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> out.writeInt(2) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad.toByteArray()) }
        bytes.value = byteArrayOf(1) + cipher.iv + cipher.doFinal(payload); val future = bytes.value!!.copyOf()
        val source = state(); try { reject(ProfileFailure.UNSUPPORTED_SCHEMA) { store(bytes).compareAndReplace(0, source) } } finally { source.clearSecrets() }
        assertArrayEquals(future, bytes.value); assertEquals(0, bytes.writes)
    }
    @Test fun stableWireBoundsAndMalformedTrailingDataNeverDecodeAsEmpty() {
        val source = state(); val encoded = try { NotionProfilesCodec.encode(source) } finally { source.clearSecrets() }
        assertTrue(String(encoded, Charsets.ISO_8859_1).contains("SYNTHETIC_VERIFIED"))
        reject(ProfileFailure.DATA_UNREADABLE) { NotionProfilesCodec.decode(encoded + byteArrayOf(1)) }
        reject(ProfileFailure.DATA_UNREADABLE) { NotionProfilesCodec.decode(encoded.copyOf(encoded.size - 1)) }
        reject(ProfileFailure.DATA_UNREADABLE) { NotionProfilesCodec.decode(ByteArray(NotionProfilesCodec.LIMIT + 1)) }
    }
    @Test fun failedAtomicReplacementKeepsPriorCredentialAndProfileRevision() {
        val bytes = Bytes(); val source = state(); try { store(bytes).compareAndReplace(0, source) } finally { source.clearSecrets() }
        val before = bytes.value!!.copyOf(); val current = store(bytes).readOwned(); bytes.fail = true
        val next = ProfileVaultState(2, 1_000, current.profiles, current.credentials, current.selectedProfileId, current.cooldowns)
        try { reject(ProfileFailure.STORAGE_UNAVAILABLE) { store(bytes).compareAndReplace(1, next) } }
        finally { current.clearSecrets() }
        assertArrayEquals(before, bytes.value); assertEquals(1, bytes.writes)
    }
}
