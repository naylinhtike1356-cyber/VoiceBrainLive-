package com.example.voicebrainlive.agent.proactive

import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import com.example.voicebrainlive.storage.snapshot.AuthenticatedSnapshotBytes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ProactiveRuleCodecTest {
    private class Bytes : AtomicMemoryBytes {
        var value: ByteArray? = null; var writes = 0; var fail = false; var beforeRead: (() -> Unit)? = null
        override fun readOrNull(): ByteArray? { beforeRead?.invoke(); return value?.copyOf() }
        override fun replaceAtomically(bytes: ByteArray) {
            if (fail) throw IOException("PRIVATE_IO_DETAIL")
            value = bytes.copyOf(); writes++
        }
    }
    private fun key(value: Int = 1) = SecretKeySpec(ByteArray(32) { value.toByte() }, "AES")
    private val aad = "VoiceBrainLive/proactive-rules/schema-1"
    private fun rule() = ProactiveRule("rule", 1, 1, RuleLifecycle.DISABLED,
        DailySimulationConfig("UTC", 600, 660), null, 1_000, 1_000)
    private fun state() = ProactiveSnapshot(1, 1, 1_000, rules = listOf(rule()))
    private fun persistence(bytes: Bytes, secret: Int = 1, domain: String = aad) =
        EncryptedProactiveRules(AuthenticatedSnapshotBytes(bytes, { key(secret) }, domain))
    private fun reject(expected: RuleFailure, action: () -> Unit) {
        val actual = assertThrows(RuleStoreException::class.java) { action() }
        assertEquals(expected, actual.failure); assertFalse(actual.message.orEmpty().contains("PRIVATE"))
    }
    @Test fun stableCodecRoundTripOwnsAllListsAndStoresNoPrivateOrCommandPayload() {
        val raw = ProactiveRuleCodec.encode(state()); val decoded = ProactiveRuleCodec.decode(raw)
        assertEquals(state(), decoded)
        assertTrue(String(raw, Charsets.ISO_8859_1).contains("MANUAL_SYNTHETIC"))
        assertFalse(String(raw).contains("CommandPlan")); assertFalse(String(raw).contains("PRIVATE_"))
        assertThrows(UnsupportedOperationException::class.java) { (decoded.rules as MutableList<ProactiveRule>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (decoded.occurrences as MutableList<RuleOccurrence>).clear() }
    }
    @Test fun encryptedCasRejectsOldRevisionWithoutOverwritingCurrentBytes() {
        val bytes = Bytes(); val store = persistence(bytes); assertTrue(store.compareAndReplace(0, state()))
        val committed = bytes.value!!.copyOf()
        assertFalse(store.compareAndReplace(0, state())); assertArrayEquals(committed, bytes.value)
        assertEquals(state(), persistence(bytes).read()); assertEquals(1, bytes.writes)
    }
    @Test fun mutationDuringAuthenticatedReadCannotChangeFrozenAdmission() {
        val rules = mutableListOf(rule()); val occurrences = mutableListOf<RuleOccurrence>()
        val input = state().copy(rules = rules, occurrences = occurrences)
        val bytes = Bytes().apply { beforeRead = { rules.clear(); occurrences.clear() } }
        val store = persistence(bytes); assertTrue(store.compareAndReplace(0, input)); bytes.beforeRead = null
        assertEquals(state(), store.read()); assertEquals(1, bytes.writes)
    }
    @Test fun wrongLostKeyOrCorruptionCannotReplaceExistingData() {
        val bytes = Bytes(); val store = persistence(bytes); store.compareAndReplace(0, state()); val original = bytes.value!!.copyOf()
        reject(RuleFailure.DATA_UNREADABLE) { persistence(bytes, 2).read() }
        val absent = EncryptedProactiveRules(AuthenticatedSnapshotBytes(bytes, { error("PRIVATE_KEY_DETAIL") }, aad))
        reject(RuleFailure.KEY_UNAVAILABLE) { absent.compareAndReplace(0, state()) }
        assertArrayEquals(original, bytes.value)
        bytes.value!![bytes.value!!.lastIndex] = (bytes.value!!.last().toInt() xor 1).toByte(); val corrupt = bytes.value!!.copyOf()
        reject(RuleFailure.DATA_UNREADABLE) { store.compareAndReplace(0, state()) }
        assertArrayEquals(corrupt, bytes.value); assertEquals(1, bytes.writes)
    }
    @Test fun futureEnvelopeOrAuthenticatedSchemaCannotBeResetAsEmpty() {
        val bytes = Bytes().apply { value = ByteArray(40).apply { this[0] = 2 } }; val store = persistence(bytes)
        val envelope = bytes.value!!.copyOf(); reject(RuleFailure.UNSUPPORTED_SCHEMA) { store.compareAndReplace(0, state()) }
        assertArrayEquals(envelope, bytes.value)
        val payload = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> out.writeInt(2) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad.toByteArray()) }
        bytes.value = byteArrayOf(1) + cipher.iv + cipher.doFinal(payload); val schema = bytes.value!!.copyOf()
        reject(RuleFailure.UNSUPPORTED_SCHEMA) { store.read() }; reject(RuleFailure.UNSUPPORTED_SCHEMA) { store.compareAndReplace(0, state()) }
        assertArrayEquals(schema, bytes.value); assertEquals(0, bytes.writes)
    }
    @Test fun domainSeparationRejectsRulesCiphertextForTaskOrDifferentMetadataDomain() {
        val bytes = Bytes(); persistence(bytes).compareAndReplace(0, state()); val original = bytes.value!!.copyOf()
        reject(RuleFailure.DATA_UNREADABLE) { persistence(bytes, domain = "VoiceBrainLive/task-metadata/schema-1").read() }
        reject(RuleFailure.DATA_UNREADABLE) { persistence(bytes, domain = "VoiceBrainLive/future-profile/schema-1").compareAndReplace(0, state()) }
        assertArrayEquals(original, bytes.value)
    }
    @Test fun malformedWireTrailingBytesBoundsAndIllegalActiveCombinationsAreRejected() {
        val raw = ProactiveRuleCodec.encode(state())
        reject(RuleFailure.DATA_UNREADABLE) { ProactiveRuleCodec.decode(raw + byteArrayOf(1)) }
        reject(RuleFailure.DATA_UNREADABLE) { ProactiveRuleCodec.decode(raw.copyOf(raw.size - 1)) }
        reject(RuleFailure.DATA_UNREADABLE) { ProactiveRuleCodec.decode(ByteArray(ProactiveRuleCodec.LIMIT + 1)) }
        val unknown = String(raw, Charsets.ISO_8859_1).replace("DISABLED", "UNKNOWNX").toByteArray(Charsets.ISO_8859_1)
        reject(RuleFailure.DATA_UNREADABLE) { ProactiveRuleCodec.decode(unknown) }
        reject(RuleFailure.INVALID_REQUEST) { ProactiveRuleCodec.encode(state().copy(rules = listOf(rule().copy(lifecycle = RuleLifecycle.ACTIVE)))) }
        reject(RuleFailure.INVALID_REQUEST) { ProactiveRuleCodec.encode(state().copy(rules = List(21) { rule().copy(ruleId = "rule_$it") })) }
    }
    @Test fun failedAtomicReplacementPreservesOldCommitAndNoSuccessRevision() {
        val bytes = Bytes(); val store = persistence(bytes); store.compareAndReplace(0, state()); val before = bytes.value!!.copyOf()
        bytes.fail = true
        reject(RuleFailure.STORAGE_UNAVAILABLE) { store.compareAndReplace(1, state().copy(revision = 2)) }
        assertArrayEquals(before, bytes.value); assertEquals(state(), store.read()); assertEquals(1, bytes.writes)
    }
}
