package com.example.voicebrainlive.agent.persistence

import com.example.voicebrainlive.agent.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TaskRepositoryTest {
    private class Store : TaskMetadataPersistence {
        var snapshot = PersistentTaskSnapshot()
        var writes = 0
        var failWrite = false
        override fun read() = snapshot
        override fun compareAndReplace(expectedRevision: Long, next: PersistentTaskSnapshot): Boolean {
            if (failWrite) throw IOException("PRIVATE_STORAGE_DETAIL")
            if (snapshot.revision != expectedRevision) return false
            snapshot = freezeTasks(next); writes++; return true
        }
    }
    private class Reads : AgentReadPort {
        var clocks = 0; var batteries = 0; var remote = 0
        var clockAction: (suspend () -> AgentObservation)? = null
        var batteryAction: (suspend () -> AgentObservation)? = null
        override suspend fun clock(): AgentObservation {
            clocks++; return clockAction?.invoke() ?: AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("PRIVATE_LOCAL_CLOCK"))
        }
        override suspend fun battery(): AgentObservation {
            batteries++; return batteryAction?.invoke() ?: AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery(55))
        }
        override suspend fun tasks(connection: AgentConnectionPin): AgentObservation {
            remote++; error("Unexpected remote read")
        }
    }
    private class Scheduler : TaskSchedulerPort {
        val enqueued = mutableListOf<Triple<String, Long, Long>>()
        var enqueueAllowed = true; var cancelAllowed = true
        var cancelAction: (suspend () -> Boolean)? = null
        val queued = mutableMapOf<String, Long>()
        var beforeEnqueue: (() -> Unit)? = null; var beforeCancel: (() -> Unit)? = null
        override suspend fun enqueue(taskId: String, revision: Long, dueAt: Long): Boolean {
            beforeEnqueue?.invoke(); enqueued += Triple(taskId, revision, dueAt)
            if (enqueueAllowed) queued[taskId] = revision
            return enqueueAllowed
        }
        override suspend fun cancel(taskId: String): Boolean {
            beforeCancel?.invoke(); val settled = cancelAction?.invoke() ?: cancelAllowed
            if (settled) queued.remove(taskId)
            return settled
        }
    }
    private class Notifications : TaskNotificationPort {
        var available = true; var delivery = true
        var publishAction: (suspend (String, PersistentTaskState) -> Boolean)? = null
        val seen = mutableListOf<Pair<String, PersistentTaskState>>()
        override suspend fun isAvailable() = available
        override suspend fun publish(taskId: String, state: PersistentTaskState): Boolean {
            seen += taskId to state; return publishAction?.invoke(taskId, state) ?: delivery
        }
    }
    private class Fixture {
        var now = 1_000L
        val store = Store(); val ids = AtomicInteger()
        val reads = Reads(); val scheduler = Scheduler(); val notifications = Notifications()
        fun repository() = TaskRepository(store, { now }, { "id_${ids.incrementAndGet()}" }, Dispatchers.Unconfined)
        val repository = repository()
        private val primaryEngine by lazy { LocalTaskEngine(repository, reads, scheduler, notifications) { now } }
        fun engine(repo: TaskRepository = repository) = if (repo === repository) primaryEngine
            else LocalTaskEngine(repo, reads, scheduler, notifications) { now }
        suspend fun draft(due: Long = now) = repository.previewLocalStatus(due, 100_000)
        suspend fun scheduled(): PersistentTaskRecord { val draft = draft(); return engine().schedule(draft.identity.taskId, draft.revision) }
    }
    private suspend fun failure(expected: TaskFailure, action: suspend () -> Unit) {
        try { action(); fail("Expected controlled failure $expected") }
        catch (actual: TaskStoreException) {
            assertEquals(expected, actual.failure)
            assertFalse(actual.message.orEmpty().contains("PRIVATE_"))
        }
    }

    @Test fun previewIsCanonicalMetadataOnlyImmutableAndStartsNothing() = runBlocking {
        val f = Fixture(); val draft = f.draft(2_000)
        assertEquals(PersistentTaskState.DRAFT, draft.state)
        assertEquals(listOf("clock", "battery", "report"), draft.identity.steps.map { it.stepId })
        assertEquals(2_000L, draft.dueAt); assertEquals(100_000L, draft.deadlineAt)
        assertEquals(0, f.reads.clocks); assertTrue(f.scheduler.enqueued.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (draft.identity.steps as MutableList<PersistentStepIdentity>).clear() }
        val history = f.repository.list()
        assertThrows(UnsupportedOperationException::class.java) { (history as MutableList<PersistentTaskRecord>).clear() }
        val text = String(TaskMetadataCodec.encode(f.store.snapshot), Charsets.ISO_8859_1)
        assertFalse(text.contains("PRIVATE_")); assertFalse(text.contains("get_current_time")); assertFalse(text.contains("objective"))
    }
    @Test fun invalidDeadlineAndCapacityDoNotDropExistingHistory() = runBlocking {
        val f = Fixture()
        failure(TaskFailure.INVALID_REQUEST) { f.repository.previewLocalStatus(2_000, 2_000) }
        failure(TaskFailure.INVALID_REQUEST) { f.repository.previewLocalStatus(999, 100_000) }
        repeat(100) { f.draft() }
        val before = f.store.snapshot
        failure(TaskFailure.CAPACITY_REACHED) { f.draft() }
        assertEquals(before, f.store.snapshot)
    }
    @Test fun unknownTaskRequestsDoNotRetainLocksAndKnownTaskStillExecutes() = runBlocking {
        val f = Fixture(); val engine = f.engine()
        // Inspect the actual retained resource; no extra production diagnostic API is needed.
        val field = LocalTaskEngine::class.java.getDeclaredField("operations").apply { isAccessible = true }
        fun retained() = (field.get(engine) as Map<*, *>).size
        repeat(250) { index ->
            failure(TaskFailure.STALE_REVISION_OR_OWNER) { engine.schedule("unknown_$index", 1) }
            failure(TaskFailure.STALE_REVISION_OR_OWNER) { engine.cancel("unknown_$index", 1) }
            failure(TaskFailure.STALE_REVISION_OR_OWNER) { engine.pause("unknown_$index", 1) }
        }
        assertEquals(0, retained()); assertTrue(f.scheduler.enqueued.isEmpty())
        val draft = f.draft(); val scheduled = engine.schedule(draft.identity.taskId, draft.revision)
        assertEquals(1, retained())
        repeat(250) { index -> failure(TaskFailure.STALE_REVISION_OR_OWNER) { engine.schedule("more_unknown_$index", 1) } }
        assertEquals(1, retained())
        assertEquals(PersistentTaskState.COMPLETED, engine.execute(scheduled.identity.taskId, scheduled.revision).record?.state)
        assertEquals(1, retained()); assertEquals(1, f.reads.clocks); assertEquals(1, f.reads.batteries)
    }
    @Test fun schedulingPersistsExactRevisionBeforeSchedulerAndNoReadRuns() = runBlocking {
        val f = Fixture(); val draft = f.draft(2_000)
        f.scheduler.beforeEnqueue = {
            val saved = f.store.snapshot.tasks.single()
            assertEquals(PersistentTaskState.SCHEDULED, saved.state); assertEquals(2L, saved.revision)
        }
        val scheduled = f.engine().schedule(draft.identity.taskId, draft.revision)
        assertEquals(Triple(draft.identity.taskId, 2L, 2_000L), f.scheduler.enqueued.single())
        assertEquals(0, f.reads.clocks); assertEquals(PersistentTaskState.SCHEDULED, scheduled.state)
    }
    @Test fun failedDurableScheduleNeverEnqueuesAndDoesNotClaimSuccess() = runBlocking {
        val f = Fixture(); val draft = f.draft(); f.store.failWrite = true
        failure(TaskFailure.STORAGE_UNAVAILABLE) { f.engine().schedule(draft.identity.taskId, draft.revision) }
        assertTrue(f.scheduler.enqueued.isEmpty()); assertEquals(PersistentTaskState.DRAFT, f.store.snapshot.tasks.single().state)
    }
    @Test fun failedEnqueueBlocksExactPersistedTaskWithoutRetry() = runBlocking {
        val f = Fixture(); val draft = f.draft(); f.scheduler.enqueueAllowed = false
        val blocked = f.engine().schedule(draft.identity.taskId, draft.revision)
        assertEquals(PersistentTaskState.BLOCKED, blocked.state)
        assertEquals(PersistentTaskReason.SCHEDULER_UNAVAILABLE, blocked.reason)
        assertEquals(1, f.scheduler.enqueued.size); assertEquals(0, f.reads.clocks)
    }
    @Test fun deniedNotificationPreventsScheduleAndRevocationPreventsExecution() = runBlocking {
        val f = Fixture(); val draft = f.draft(); f.notifications.available = false
        failure(TaskFailure.NOTIFICATION_UNAVAILABLE) { f.engine().schedule(draft.identity.taskId, draft.revision) }
        assertEquals(PersistentTaskState.DRAFT, f.store.snapshot.tasks.single().state); assertTrue(f.scheduler.enqueued.isEmpty())
        f.notifications.available = true; val scheduled = f.engine().schedule(draft.identity.taskId, draft.revision)
        f.notifications.available = false
        val result = f.engine().execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, result.failure); assertFalse(result.ran)
        assertEquals(PersistentTaskState.BLOCKED, result.record?.state); assertEquals(0, f.reads.clocks)
    }
    @Test fun notificationDeliveryFailureIsVisibleWithoutInventingDeliverySuccess() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); f.notifications.delivery = false
        val result = f.engine().execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(PersistentTaskState.COMPLETED, result.record?.state)
        assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, result.failure)
        assertEquals(2, f.notifications.seen.size)
        val restarted = f.repository().recover().single()
        assertEquals(PersistentTaskState.COMPLETED, restarted.state)
        assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, restarted.notificationFailure)
        assertEquals(1_000L, restarted.notificationFailureAt)
        assertEquals(result.record, restarted)
    }
    @Test fun suspendedQueueCancellationCannotRemoveANewerSchedule() = runBlocking {
        val f = Fixture(); val draft = f.draft(2_000); val scheduled = f.engine().schedule(draft.identity.taskId, draft.revision)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.scheduler.cancelAction = { entered.complete(Unit); release.await(); true }
        val pausing = async { f.engine().pause(scheduled.identity.taskId, scheduled.revision) }; entered.await()
        val paused = f.repository.list().single()
        assertTrue(paused.queueCancellationPending)
        val rescheduling = async { f.engine().schedule(paused.identity.taskId, paused.revision) }
        yield()
        assertFalse(rescheduling.isCompleted); assertEquals(scheduled.revision, f.scheduler.queued[scheduled.identity.taskId])
        release.complete(Unit); assertFalse(pausing.await().queueCancellationPending)
        val newer = rescheduling.await()
        assertEquals(PersistentTaskState.SCHEDULED, newer.state)
        assertEquals(newer.revision, f.scheduler.queued[newer.identity.taskId])
        assertEquals(2, f.scheduler.enqueued.size)
    }
    @Test fun uncertainQueueCancellationPersistsAcrossRestartAndCannotReschedule() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); f.scheduler.cancelAllowed = false
        failure(TaskFailure.SCHEDULER_UNAVAILABLE) { f.engine().pause(scheduled.identity.taskId, scheduled.revision) }
        val restoredRepository = f.repository(); val paused = restoredRepository.recover().single()
        assertEquals(PersistentTaskState.PAUSED, paused.state); assertTrue(paused.queueCancellationPending)
        failure(TaskFailure.SCHEDULER_UNAVAILABLE) { f.engine(restoredRepository).schedule(paused.identity.taskId, paused.revision) }
        assertEquals(1, f.scheduler.enqueued.size)
    }
    @Test fun lateScheduledNotificationFailureCannotOverwriteCompletedNewerRevision() = runBlocking {
        val f = Fixture(); val draft = f.draft()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.notifications.publishAction = { _, state ->
            if (state == PersistentTaskState.SCHEDULED) { entered.complete(Unit); release.await(); false } else true
        }
        val scheduling = async {
            try { f.engine().schedule(draft.identity.taskId, draft.revision); null }
            catch (failure: TaskStoreException) { failure.failure }
        }
        entered.await(); val scheduled = f.repository.list().single()
        val completed = withTimeout(2_000) { f.engine().execute(scheduled.identity.taskId, scheduled.revision) }
        assertEquals(PersistentTaskState.COMPLETED, completed.record?.state)
        release.complete(Unit); assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, scheduling.await())
        assertEquals(completed.record, f.repository.list().single())
        assertNull(f.repository.list().single().notificationFailure)
    }
    @Test fun staleNotificationFailureCannotOverwriteCancelledHistory() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); val cancelled = f.engine().cancel(scheduled.identity.taskId, scheduled.revision)
        failure(TaskFailure.STALE_REVISION_OR_OWNER) { f.repository.recordNotificationFailure(scheduled) }
        assertEquals(cancelled, f.repository().list().single()); assertNull(cancelled.notificationFailure)
    }
    @Test fun persistedScheduleNotificationFailureDoesNotInvalidateAlreadyQueuedRevision() = runBlocking {
        val f = Fixture(); val draft = f.draft(); f.notifications.delivery = false
        failure(TaskFailure.NOTIFICATION_UNAVAILABLE) { f.engine().schedule(draft.identity.taskId, draft.revision) }
        val record = f.repository.list().single(); val queuedRevision = f.scheduler.queued.getValue(record.identity.taskId)
        assertEquals(PersistentTaskState.SCHEDULED, record.state); assertEquals(queuedRevision, record.revision)
        assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, record.notificationFailure)
        f.notifications.delivery = true
        val result = f.engine().execute(record.identity.taskId, queuedRevision)
        assertEquals(PersistentTaskState.COMPLETED, result.record?.state)
        assertEquals(TaskFailure.NOTIFICATION_UNAVAILABLE, result.record?.notificationFailure)
        assertNull(result.failure)
    }
    @Test fun duplicateWorkerCannotBorrowClaimOrInvalidateCurrentOwner() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.reads.clockAction = { entered.complete(Unit); release.await(); AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("PRIVATE_RESULT")) }
        val first = async { f.engine().execute(scheduled.identity.taskId, scheduled.revision) }; entered.await()
        val duplicate = f.engine().execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(TaskFailure.STALE_REVISION_OR_OWNER, duplicate.failure); assertFalse(duplicate.ran)
        release.complete(Unit)
        assertEquals(PersistentTaskState.COMPLETED, first.await().record?.state)
        assertEquals(1, f.reads.clocks); assertEquals(1, f.reads.batteries); assertEquals(0, f.reads.remote)
    }
    @Test fun cancelPersistsOwnerRevocationBeforeSchedulerAndRejectsLateResult() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.reads.clockAction = { entered.complete(Unit); release.await(); AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("PRIVATE_LATE")) }
        val running = async { f.engine().execute(scheduled.identity.taskId, scheduled.revision) }; entered.await()
        val record = f.repository.list().single()
        f.scheduler.beforeCancel = { assertEquals(PersistentTaskState.CANCELLED, f.store.snapshot.tasks.single().state); assertNull(f.store.snapshot.tasks.single().runOwner) }
        val cancelled = f.engine().cancel(record.identity.taskId, record.revision)
        release.complete(Unit)
        assertEquals(PersistentTaskState.CANCELLED, cancelled.state)
        assertEquals(PersistentTaskState.CANCELLED, running.await().record?.state)
        assertEquals(0, f.reads.batteries)
    }
    @Test fun schedulerCancelFailureCannotUndoDurableCancellation() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); f.scheduler.cancelAllowed = false
        failure(TaskFailure.SCHEDULER_UNAVAILABLE) { f.engine().cancel(scheduled.identity.taskId, scheduled.revision) }
        assertEquals(PersistentTaskState.CANCELLED, f.repository.list().single().state)
        assertEquals(TaskFailure.STALE_REVISION_OR_OWNER, f.engine().execute(scheduled.identity.taskId, scheduled.revision).failure)
        assertEquals(0, f.reads.clocks)
    }
    @Test fun newProcessBlocksRunningClaimAndNeverReplaysIt() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); val claimed = f.repository.claim(scheduled.identity.taskId, scheduled.revision)
        val replacement = f.repository(); val recovered = replacement.recover().single()
        assertEquals(PersistentTaskState.BLOCKED, recovered.state); assertEquals(PersistentTaskReason.PROCESS_DEATH_RUNNING, recovered.reason)
        assertEquals(1, recovered.attempts); assertNull(recovered.runOwner)
        assertEquals(TaskFailure.STALE_REVISION_OR_OWNER, f.engine(replacement).execute(scheduled.identity.taskId, scheduled.revision).failure)
        assertEquals(0, f.reads.clocks)
        failure(TaskFailure.STALE_REVISION_OR_OWNER) { f.repository.finish(claimed, null) }
    }
    @Test fun restartReenqueuesOnlyUnstartedExactScheduleAndDeadlineExpiryBlocksIt() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); val replacement = f.repository()
        val restored = f.engine(replacement).reconcile().single()
        assertEquals(scheduled, restored); assertEquals(2, f.scheduler.enqueued.size); assertEquals(0, f.reads.clocks)
        f.now = 100_000
        val expired = f.engine(replacement).execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(TaskFailure.DEADLINE_EXPIRED, expired.failure)
        assertEquals(PersistentTaskReason.DEADLINE_EXPIRED, expired.record?.reason); assertEquals(0, f.reads.clocks)
    }
    @Test fun pausedUnstartedTaskUsesSameFrozenScheduleAndStartedPauseNeverRetries() = runBlocking {
        val f = Fixture(); val draft = f.draft(2_000); val scheduled = f.engine().schedule(draft.identity.taskId, draft.revision)
        val paused = f.engine().pause(scheduled.identity.taskId, scheduled.revision)
        assertEquals(scheduled.dueAt, f.engine().schedule(paused.identity.taskId, paused.revision).dueAt)
        f.now = 2_000; val current = f.repository.list().single(); f.repository.claim(current.identity.taskId, current.revision)
        val claimed = f.repository.list().single(); val afterStart = f.engine().pause(claimed.identity.taskId, claimed.revision)
        assertEquals(PersistentTaskReason.PAUSED_AFTER_START, afterStart.reason)
        failure(TaskFailure.INVALID_REQUEST) { f.engine().schedule(afterStart.identity.taskId, afterStart.revision) }
    }
    @Test fun workerCancellationConsumesAttemptAndBlocksWithoutAutomaticRetry() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); val entered = CompletableDeferred<Unit>()
        f.reads.clockAction = { entered.complete(Unit); CompletableDeferred<Unit>().await(); error("unreachable") }
        val worker = launch { f.engine().execute(scheduled.identity.taskId, scheduled.revision) }
        entered.await(); worker.cancelAndJoin()
        val stopped = f.repository.list().single()
        assertEquals(PersistentTaskState.BLOCKED, stopped.state); assertEquals(PersistentTaskReason.WORKER_STOPPED, stopped.reason)
        assertEquals(1, stopped.attempts); assertEquals(0, f.reads.batteries)
        assertEquals(TaskFailure.STALE_REVISION_OR_OWNER, f.engine().execute(scheduled.identity.taskId, scheduled.revision).failure)
        assertEquals(1, f.reads.clocks)
    }
    @Test fun terminalWriteFailureKeepsConsumedClaimWithoutRetryOrFalseCompletion() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled()
        f.reads.batteryAction = { f.store.failWrite = true; AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery(55)) }
        val result = f.engine().execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(TaskFailure.STORAGE_UNAVAILABLE, result.failure)
        assertEquals(PersistentTaskState.RUNNING, f.store.snapshot.tasks.single().state); assertEquals(1, f.store.snapshot.tasks.single().attempts)
        f.store.failWrite = false
        assertEquals(TaskFailure.STALE_REVISION_OR_OWNER, f.engine().execute(scheduled.identity.taskId, scheduled.revision).failure)
        assertEquals(1, f.reads.clocks); assertEquals(1, f.reads.batteries)
    }
    @Test fun reportedReadIsNotProvenCompletionAndNoRemoteReadIsDispatched() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled()
        f.reads.clockAction = { AgentObservation(AgentStatus.REPORTED, AgentData.Clock("CLAIMED_SUCCESS")) }
        val result = f.engine().execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(PersistentTaskState.FAILED, result.record?.state); assertEquals(TaskFailure.LOCAL_READ_FAILED, result.failure)
        assertEquals(0, f.reads.batteries); assertEquals(0, f.reads.remote)
    }
    @Test fun freshChildCanRunLongAfterOriginalPreviewExecutionDeadline() = runBlocking {
        val f = Fixture(); val draft = f.draft(70_000); val scheduled = f.engine().schedule(draft.identity.taskId, draft.revision)
        assertTrue(draft.identity.sourceDeadlineAt < 70_000); f.now = 70_000
        val result = f.engine().execute(scheduled.identity.taskId, scheduled.revision)
        assertEquals(PersistentTaskState.COMPLETED, result.record?.state)
        val bytes = TaskMetadataCodec.encode(f.store.snapshot)
        assertFalse(String(bytes).contains("PRIVATE_LOCAL_CLOCK")); assertEquals(1, result.record?.attempts)
    }
    @Test fun clockRollbackPreservesBytesAndStaleScheduleCannotOverwriteCancellation() = runBlocking {
        val f = Fixture(); val scheduled = f.scheduled(); val before = f.store.snapshot; f.now = 999
        failure(TaskFailure.CLOCK_ANOMALY) { f.repository.cancel(scheduled.identity.taskId, scheduled.revision) }
        assertEquals(before, f.store.snapshot); f.now = 1_000
        val cancelled = f.engine().cancel(scheduled.identity.taskId, scheduled.revision)
        failure(TaskFailure.STALE_REVISION_OR_OWNER) { f.engine().schedule(scheduled.identity.taskId, scheduled.revision) }
        assertEquals(cancelled, f.repository.list().single())
    }
    @Test fun oldWaitingApprovalRecoversToReviewWithoutAnyRestoredGrant() = runBlocking {
        val f = Fixture(); val draft = f.draft()
        val marker = draft.copy(recipe = PersistentTaskRecipe.FOREGROUND_REVIEW_ONLY, dueAt = null,
            state = PersistentTaskState.WAITING_FOR_APPROVAL, reason = PersistentTaskReason.FRESH_FOREGROUND_REVIEW)
        f.store.snapshot = freezeTasks(f.store.snapshot.copy(tasks = listOf(marker)))
        val restored = f.repository().recover().single()
        assertEquals(PersistentTaskState.BLOCKED, restored.state); assertEquals(PersistentTaskReason.FRESH_FOREGROUND_REVIEW, restored.reason)
        assertEquals(0, restored.attempts); assertNull(restored.runOwner); assertEquals(0, f.reads.clocks)
        failure(TaskFailure.INVALID_REQUEST) { f.engine().schedule(restored.identity.taskId, restored.revision) }
        assertTrue(f.scheduler.enqueued.isEmpty())
    }
}
