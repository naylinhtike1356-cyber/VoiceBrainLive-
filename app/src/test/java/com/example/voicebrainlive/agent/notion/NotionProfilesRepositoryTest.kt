package com.example.voicebrainlive.agent.notion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NotionProfilesRepositoryTest {
    @Test fun publicViewsPinsAndToStringNeverContainCredentialSecretAndVerificationIsSynthetic() = runBlocking {
        val f = NotionFixture(); val profile = f.profile("A"); val pin = f.pin(profile)
        assertFalse(f.repo.views().toString().contains("FAKE_A")); assertFalse(pin.toString().contains("FAKE_A"))
        assertEquals(DirectProfileStatus.SYNTHETIC_VERIFIED, profile.status)
        assertEquals(EvidenceSource.SYNTHETIC_FIXTURE, pin.evidence)
        assertNull(pin.namespace.providerWorkspaceId)
        assertEquals(NamespaceKind.LOCAL_PROFILE_NAMESPACE, pin.namespace.kind)
        SecretInput("FAKE_SECRET".toCharArray()).use { assertFalse(it.toString().contains("FAKE_SECRET")) }
        val raw = f.persistence.readOwned()
        try { assertFalse(raw.toString().contains("FAKE_A")) } finally { raw.clearSecrets() }
    }
    @Test fun sharedCredentialRotationBumpsEveryReferencingProfileAtomically() = runBlocking {
        val f = NotionFixture(); val credential = f.credential("shared")
        val a = f.profile("A", credential); val b = f.profile("B", credential); val oldA = f.pin(a); val oldB = f.pin(b)
        val changed = SecretInput("FAKE_ROTATED".toCharArray()).use { f.repo.rotateCredential(credential.credentialId, credential.version, it) }
        val views = f.repo.views()
        assertEquals(credential.version + 1, changed.version)
        assertTrue(views.profiles.all { it.revision == 3L && it.status == DirectProfileStatus.DRAFT && it.credential.version == changed.version })
        assertFalse(f.repo.isCurrent(oldA)); assertFalse(f.repo.isCurrentCached(oldB))
    }
    @Test fun revocationDuringLeaseDoesNotDeadlockAndCannotPublishOldResultAndLeaseIsWiped() = runBlocking {
        val f = NotionFixture(); val credential = f.credential("A"); val profile = f.profile("A", credential); val pin = f.pin(profile)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var retained: CharArray? = null
        val reading = async {
            try {
                f.repo.withCredential(pin) { secret -> retained = secret; entered.complete(Unit); release.await(); "old result" }
                null
            } catch (safe: ProfileStoreException) { safe.failure }
        }
        entered.await()
        withTimeout(1_000) { f.repo.revokeCredential(credential.credentialId, credential.version) }
        release.complete(Unit); assertEquals(ProfileFailure.STALE_BINDING, reading.await())
        assertTrue(retained!!.all { it == '\u0000' })
        val raw = f.persistence.readOwned()
        try { assertNull(raw.credentials.single().copySecret()); assertEquals(DirectProfileStatus.REVOKED, raw.profiles.single().status) }
        finally { raw.clearSecrets() }
    }
    @Test fun staleCredentialApiNamespaceResourceOrSchemaNeverInvokesSecretCallback() = runBlocking {
        val f = NotionFixture(); val pin = f.pin(f.profile("A")); var callbacks = 0
        val wrong = listOf(pin.copy(credential = pin.credential.copy(version = 99)),
            pin.copy(resource = pin.resource.copy(dataSourceId = "other")),
            pin.copy(namespace = pin.namespace.copy(namespaceId = "other_namespace")))
        for (candidate in wrong) profileRejects(ProfileFailure.STALE_BINDING) { f.repo.withCredential(candidate) { callbacks++ } }
        profileRejects(ProfileFailure.INVALID_REQUEST) { f.repo.withCredential(pin.copy(apiVersion = "2022-06-28")) { callbacks++ } }
        assertEquals(0, callbacks)
    }
    @Test fun selectionDoesNotMutateCapturedPinAndProfilesCanShareCredentialWithoutSharingResource() = runBlocking {
        val f = NotionFixture(); val credential = f.credential("shared"); val a = f.profile("A", credential); val b = f.profile("B", credential)
        val pin = f.pin(a); f.repo.select(b.profileId, b.revision)
        assertEquals(b.profileId, f.repo.views().selectedProfileId); assertTrue(f.repo.isCurrent(pin))
        assertEquals("ds_A", pin.resource.dataSourceId); assertEquals(credential.credentialId, pin.credential.credentialId)
    }
    @Test fun propertyIdsRemainOpaqueAndCallerMutationCannotRetargetSchema() = runBlocking {
        val f = NotionFixture(); val values = linkedSetOf("Todo")
        val original = taskSchema(1, PropertyPin("title", PropertyKind.TITLE), PropertyPin("f%5C%5C%3Ap", PropertyKind.STATUS), values)
        val mutable = original.copy(unfinishedValues = values)
        val profile = f.profile("A", schema = mutable); val pin = f.pin(profile); values.clear(); values += "Synced"
        assertEquals("f%5C%5C%3Ap", pin.schema.businessStatus.propertyId); assertEquals(setOf("Todo"), pin.schema.unfinishedValues)
        assertThrows(UnsupportedOperationException::class.java) { (pin.schema.unfinishedValues as MutableSet<String>).clear() }
        assertTrue(f.repo.isCurrent(pin))
    }
    @Test fun synchronizationOnlySchemaCannotBeVerifiedAsBusinessTasks() = runBlocking {
        val f = NotionFixture(); val credential = f.credential("A")
        val draft = f.repo.createDraft(ProfileDraft("sync only", CredentialRef(credential.credentialId, 1), DataSourceBinding("db", "ds"), f.schema(StatusMeaning.SYNC_ONLY)))
        profileRejects(ProfileFailure.BUSINESS_SCHEMA_UNAVAILABLE) { f.verify(draft) }
        assertEquals(DirectProfileStatus.DRAFT, f.repo.views().profiles.single().status)
        profileRejects(ProfileFailure.NOT_CONNECTED) { f.repo.pin(draft.profileId, draft.revision) }
    }
    @Test fun metadataCacheStartsClosedAndReadFailuresInvalidateItWithoutResettingVault() = runBlocking {
        val f = NotionFixture(); val pin = f.pin(f.profile("A")); val restarted = f.repository()
        assertFalse(restarted.isCurrentCached(pin)); restarted.views(); assertTrue(restarted.isCurrentCached(pin))
        val bytes = f.persistence.bytes.copyOf(); f.persistence.failRead = ProfileFailure.KEY_UNAVAILABLE
        profileRejects(ProfileFailure.KEY_UNAVAILABLE) { restarted.views() }
        assertFalse(restarted.isCurrentCached(pin)); assertArrayEquals(bytes, f.persistence.bytes)
    }
    @Test fun capacityPreservesCredentialAndProfileTombstonesAndNeverEvicts() = runBlocking {
        val f = NotionFixture(); val first = f.credential("first")
        repeat(15) { f.credential("credential_$it") }
        profileRejects(ProfileFailure.CAPACITY_REACHED) { f.credential("excess") }
        repeat(32) { f.repo.createDraft(ProfileDraft("profile_$it", CredentialRef(first.credentialId, first.version), DataSourceBinding("db", "ds_$it"), f.schema())) }
        val before = f.persistence.bytes.copyOf()
        profileRejects(ProfileFailure.CAPACITY_REACHED) { f.repo.createDraft(ProfileDraft("excess", CredentialRef(first.credentialId, first.version), DataSourceBinding("db", "ds"), f.schema())) }
        assertArrayEquals(before, f.persistence.bytes)
    }
    @Test fun clockRollbackAndFailedCommitNeverBecomeWritableEmptyOrDurableSuccess() = runBlocking {
        val f = NotionFixture(); val a = f.profile("A"); val before = f.persistence.bytes.copyOf()
        f.now--
        profileRejects(ProfileFailure.CLOCK_ANOMALY) { f.repo.disable(a.profileId, a.revision) }; assertArrayEquals(before, f.persistence.bytes)
        f.now++; f.persistence.failWrite = true
        profileRejects(ProfileFailure.STORAGE_UNAVAILABLE) { f.repo.disable(a.profileId, a.revision) }; assertArrayEquals(before, f.persistence.bytes)
        assertFalse(f.repo.isCurrentCached(f.pinAfterFailureForTest(a)))
    }
    private fun NotionFixture.pinAfterFailureForTest(profile: ConnectionProfileView) = profilePin(profile)
}
