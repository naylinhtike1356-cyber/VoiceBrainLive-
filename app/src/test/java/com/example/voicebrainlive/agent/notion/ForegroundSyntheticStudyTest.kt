package com.example.voicebrainlive.agent.notion

import com.example.voicebrainlive.agent.AgentData
import com.example.voicebrainlive.agent.AgentObservation
import com.example.voicebrainlive.agent.AgentReadPort
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.ForegroundAgentTasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Docs-only integration tests against the replacement facade and core proposal; never executed. */
class ForegroundSyntheticStudyTest {
    private class LocalReads : AgentReadPort {
        var taskCalls = 0
        override suspend fun clock() = AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("Synthetic local clock"))
        override suspend fun battery() = AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery(73))
        override suspend fun tasks(connection: com.example.voicebrainlive.agent.AgentConnectionPin): AgentObservation {
            taskCalls++; return AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_CONNECTED")
        }
    }
    private class Owned(val f: NotionFixture, port: SyntheticNotionPort?, configured: Boolean = true) : AutoCloseable {
        val reads = LocalReads()
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val facade = ForegroundAgentTasks(reads, scope, { f.now }, if (configured) f.repo else null, port)
        override fun close() { scope.cancel() }
    }

    @Test fun defaultConstructorRetainsLocalReportAndRefusesSyntheticStudyWithoutConfiguredBridge() = runBlocking {
        val f = NotionFixture(); val pin = f.pin(f.profile("A"))
        Owned(f, null, configured = false).use { owned ->
            val lease = owned.facade.openView()
            assertFalse(owned.facade.previewStudyDraft(lease, pin, 30))
            assertTrue(owned.facade.previewLocalStatus(lease))
            val task = owned.facade.state.value.preview!!.taskId
            assertTrue(owned.facade.runPreview(lease, task))
            val state = owned.facade.state.value
            assertEquals(AgentStatus.SUCCEEDED, state.snapshot!!.status)
            assertEquals("Local report completed.", state.message)
            assertTrue(state.snapshot.steps.last().result is AgentData.Report)
            assertEquals(0, owned.reads.taskCalls)
        }
    }
    @Test fun sameFacadeRunsSyntheticStudyAndKeepsExactSourceWhileIgnoringSyncCompletion() = runBlocking {
        val f = NotionFixture(); val pin = f.pin(f.profile("A"))
        Owned(f, SyntheticNotionDemoPort).use { owned ->
            val lease = owned.facade.openView()
            assertTrue(owned.facade.previewStudyDraft(lease, pin, 40))
            val plan = owned.facade.state.value.preview!!
            assertTrue(owned.facade.state.value.message.contains("SYNTHETIC"))
            assertTrue(owned.facade.runPreview(lease, plan.taskId))
            val state = owned.facade.state.value
            assertEquals(plan.taskId, state.snapshot!!.taskId)
            assertEquals(AgentStatus.SUCCEEDED, state.snapshot.status)
            val draft = state.snapshot.steps.last().result as AgentData.Draft
            assertEquals(listOf("synthetic_open"), draft.sourceRowIds)
            assertEquals(pin, fullAgentBinding(draft.connection!!))
            assertTrue(draft.exactText.contains("SYNTHETIC")); assertTrue(draft.exactText.contains("40 minutes"))
            assertFalse(draft.exactText.contains("finished sample"))
            assertTrue(state.message.contains("No connected account")); assertFalse(state.runAllowed)
            assertEquals(0, owned.reads.taskCalls)
        }
    }
    @Test fun selectionChangesDuringAwaitNeverRetargetTheCapturedStudyTask() = runBlocking {
        val f = NotionFixture(); val a = f.profile("A"); val b = f.profile("B"); val pin = f.pin(a)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        Owned(f, SyntheticNotionPort { request, secret ->
            assertEquals(pin, request.pin); assertTrue(secret.contentEquals("FAKE_A".toCharArray()))
            entered.complete(Unit); release.await(); f.page(pin, listOf(f.row(pin)))
        }).use { owned ->
            val lease = owned.facade.openView(); assertTrue(owned.facade.previewStudyDraft(lease, pin, 30))
            val taskId = owned.facade.state.value.preview!!.taskId
            assertTrue(owned.facade.runPreview(lease, taskId)); withTimeout(2_000) { entered.await() }
            f.repo.select(b.profileId, b.revision); release.complete(Unit)
            withTimeout(2_000) { while (owned.facade.state.value.running) yield() }
            val result = owned.facade.state.value.snapshot!!
            assertEquals(taskId, result.taskId); assertEquals(AgentStatus.SUCCEEDED, result.status)
            assertEquals(pin, fullAgentBinding((result.steps.last().result as AgentData.Draft).connection!!))
            assertEquals(b.profileId, f.repo.views().selectedProfileId)
        }
    }
    @Test fun stoppedNonCooperativeReadCannotPublishIntoNewViewOrBorrowItsRun() = runBlocking {
        val f = NotionFixture(); val aPin = f.pin(f.profile("A")); val bPin = f.pin(f.profile("B"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        Owned(f, SyntheticNotionPort { request, _ ->
            if (request.pin == aPin) withContext(NonCancellable) {
                entered.complete(Unit); release.await(); f.page(aPin, listOf(f.row(aPin, title = "PRIVATE_OLD_SAMPLE")))
            } else f.page(bPin, listOf(f.row(bPin, title = "NEW_SAMPLE")))
        }).use { owned ->
            try {
                val oldLease = owned.facade.openView(); assertTrue(owned.facade.previewStudyDraft(oldLease, aPin, 30))
                assertTrue(owned.facade.runPreview(oldLease, owned.facade.state.value.preview!!.taskId))
                withTimeout(2_000) { entered.await() }
                // Test-only observation of the real retiring job, not a new production API.
                val retiringJob = ForegroundAgentTasks::class.java.getDeclaredField("inFlight")
                    .apply { isAccessible = true }.get(owned.facade) as kotlinx.coroutines.Job
                owned.facade.stop(oldLease)
                val newLease = owned.facade.openView()
                assertFalse(retiringJob.isCompleted)
                assertFalse(owned.facade.previewStudyDraft(newLease, bPin, 30))
                // Local preview is allowed without execution while the old runner retires.
                assertTrue(owned.facade.previewLocalStatus(newLease))
                val localPreview = owned.facade.state.value
                val localTask = localPreview.preview!!.taskId
                assertFalse(owned.facade.runPreview(newLease, localTask))
                release.complete(Unit)
                withTimeout(2_000) { retiringJob.join() }
                val afterOldCompletion = owned.facade.state.value
                assertEquals(localTask, afterOldCompletion.preview!!.taskId)
                assertEquals(localPreview.message, afterOldCompletion.message)
                assertNull(afterOldCompletion.snapshot) // No old private results reached the new view.
                assertTrue(owned.facade.previewStudyDraft(newLease, bPin, 30))
                val newTask = owned.facade.state.value.preview!!.taskId
                assertTrue(owned.facade.runPreview(newLease, newTask))
                withTimeout(2_000) { while (owned.facade.state.value.running) yield() }
                val state = owned.facade.state.value
                assertEquals(newTask, state.snapshot!!.taskId); assertEquals(AgentStatus.SUCCEEDED, state.snapshot.status)
                val draft = state.snapshot.steps.last().result as AgentData.Draft
                assertTrue(draft.exactText.contains("NEW_SAMPLE")); assertFalse(draft.exactText.contains("PRIVATE_OLD_SAMPLE"))
                assertEquals(bPin, fullAgentBinding(draft.connection!!))
                assertFalse(owned.facade.cancel(oldLease, newTask))
            } finally { release.complete(Unit) }
        }
    }
    @Test fun credentialRotationInsideAwaitBlocksLateRowsAndCannotProduceSuccessfulDraft() = runBlocking {
        val f = NotionFixture(); val credential = f.credential("A"); val pin = f.pin(f.profile("A", credential))
        Owned(f, SyntheticNotionPort { _, _ ->
            SecretInput("FAKE_ROTATED".toCharArray()).use { f.repo.rotateCredential(credential.credentialId, 1, it) }
            f.page(pin, listOf(f.row(pin, title = "OLD_SAMPLE")))
        }).use { owned ->
            val lease = owned.facade.openView(); assertTrue(owned.facade.previewStudyDraft(lease, pin, 30))
            assertTrue(owned.facade.runPreview(lease, owned.facade.state.value.preview!!.taskId))
            val state = owned.facade.state.value
            assertEquals(AgentStatus.CANCELLED, state.snapshot!!.status)
            assertTrue(state.snapshot.steps.all { it.result == null })
            assertFalse(state.message.contains("prepared locally"))
            assertFalse(owned.facade.previewStudyDraft(lease, pin, 30))
        }
    }
    @Test fun incompleteEmptyTraversalIsBlockedRatherThanAnEmptySuccessfulStudy() = runBlocking {
        val f = NotionFixture(); val pin = f.pin(f.profile("A"))
        Owned(f, SyntheticNotionPort { _, _ -> f.page(pin, status = QueryCompletion.INCOMPLETE) }).use { owned ->
            val lease = owned.facade.openView(); assertTrue(owned.facade.previewStudyDraft(lease, pin, 30))
            assertTrue(owned.facade.runPreview(lease, owned.facade.state.value.preview!!.taskId))
            val state = owned.facade.state.value
            assertEquals(AgentStatus.BLOCKED, state.snapshot!!.status)
            assertTrue(state.snapshot.steps.none { it.result is AgentData.Draft })
            assertTrue(state.message.contains("Synthetic task ended"))
        }
    }
    @Test fun expiredPreviewAndWrongLeaseNeverInvokeTheSyntheticPort() = runBlocking {
        val f = NotionFixture(); val pin = f.pin(f.profile("A")); var calls = 0
        Owned(f, SyntheticNotionPort { _, _ -> calls++; f.page(pin) }).use { owned ->
            val lease = owned.facade.openView(); assertTrue(owned.facade.previewStudyDraft(lease, pin, 30))
            val plan = owned.facade.state.value.preview!!; f.now += 60_000
            assertTrue(owned.facade.runPreview(lease, plan.taskId))
            assertFalse(owned.facade.state.value.running); assertFalse(owned.facade.state.value.runAllowed)
            assertNull(owned.facade.state.value.snapshot); assertEquals(0, calls)
            val replacement = owned.facade.openView()
            assertFalse(owned.facade.previewStudyDraft(lease, pin, 30))
            assertTrue(owned.facade.previewStudyDraft(replacement, pin, 30)); assertEquals(0, calls)
        }
    }
}
