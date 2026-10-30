package com.example.voicebrainlive.agent.notion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Requires the separately reviewed additive captureSelectedPin patch; never executed here. */
class SelectedNotionPinCaptureTest {
    @Test fun selectedDraftOrMissingSelectionFailsClosedWithoutInventingConnection() = runBlocking {
        val f = NotionFixture(); val wrapper = RepositoryDirectNotionProfiles(f.repo)
        profileRejects(ProfileFailure.NOT_CONNECTED) { wrapper.captureSelectedPin() }
        val draft = wrapper.createSyntheticDraft("Sample only")
        wrapper.select(draft.profileId, draft.revision)
        profileRejects(ProfileFailure.NOT_CONNECTED) { wrapper.captureSelectedPin() }
        val verified = wrapper.verifySynthetic(draft.profileId, draft.revision)
        val pin = wrapper.captureSelectedPin()
        assertEquals(verified.profileId, pin.profileId); assertEquals(verified.revision, pin.profileRevision)
        assertEquals(EvidenceSource.SYNTHETIC_FIXTURE, pin.evidence)
    }
    @Test fun capturedSelectionIsImmutableAndFutureSelectionOrDisableCannotRetargetIt() = runBlocking {
        val f = NotionFixture(); val a = f.profile("A"); val b = f.profile("B")
        val wrapper = RepositoryDirectNotionProfiles(f.repo)
        wrapper.select(a.profileId, a.revision); val captured = wrapper.captureSelectedPin()
        wrapper.select(b.profileId, b.revision)
        assertEquals(a.profileId, captured.profileId); assertEquals(a.resource, captured.resource)
        assertTrue(f.repo.isCurrent(captured))
        assertEquals(b.profileId, wrapper.captureSelectedPin().profileId)
        wrapper.disable(a.profileId, a.revision)
        assertFalse(f.repo.isCurrent(captured)); assertEquals(a.resource, captured.resource)
    }
}
