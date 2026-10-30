package com.example.voicebrainlive.agent.persistence

import com.example.voicebrainlive.agent.AgentReadPort
import com.example.voicebrainlive.agent.AgentRunner
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TaskSchedulerPort {
    suspend fun enqueue(taskId: String, revision: Long, dueAt: Long): Boolean
    suspend fun cancel(taskId: String): Boolean
}
interface TaskNotificationPort {
    suspend fun isAvailable(): Boolean
    /** Metadata only. No result text/goal/approval intent is supplied to notification adapters. */
    suspend fun publish(taskId: String, state: PersistentTaskState): Boolean
}
/** ran means a durable attempt was consumed, not proof that every read or notification finished. */
data class TaskExecutionOutcome(val record: PersistentTaskRecord?, val failure: TaskFailure? = null, val ran: Boolean = false)

/** Sole trusted local worker orchestration. No effects, retries, general plan restore or approval replay. */
class LocalTaskEngine(
    private val repository: TaskRepository,
    private val reads: AgentReadPort,
    private val scheduler: TaskSchedulerPort,
    private val notifications: TaskNotificationPort,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // One app-owned engine; slots exist only for authenticated retained tasks (bounded at 100).
    // Queue operations share a per-task lock. Worker execution does NOT hold this lock while reading.
    private val operations = ConcurrentHashMap<String, Mutex>()
    suspend fun schedule(taskId: String, expectedRevision: Long): PersistentTaskRecord = operation(taskId) {
        taskCheck(available(), TaskFailure.NOTIFICATION_UNAVAILABLE)
        val scheduled = repository.schedule(taskId, expectedRevision) // Durable first, enqueue second.
        enqueueOrBlock(scheduled)
        val current = current(taskId) ?: throw TaskStoreException(TaskFailure.STORAGE_UNAVAILABLE)
        if (current.state == PersistentTaskState.SCHEDULED)
            taskCheck(notify(current), TaskFailure.NOTIFICATION_UNAVAILABLE)
        current
    }
    suspend fun cancel(taskId: String, expectedRevision: Long): PersistentTaskRecord = operation(taskId) {
        val cancelled = repository.cancel(taskId, expectedRevision) // Owner revoked before external cancellation.
        taskCheck(cancelScheduled(taskId), TaskFailure.SCHEDULER_UNAVAILABLE)
        val settled = repository.acknowledgeCancellation(cancelled)
        taskCheck(notify(settled), TaskFailure.NOTIFICATION_UNAVAILABLE)
        settled
    }
    suspend fun pause(taskId: String, expectedRevision: Long): PersistentTaskRecord = operation(taskId) {
        val paused = repository.pause(taskId, expectedRevision)
        taskCheck(cancelScheduled(taskId), TaskFailure.SCHEDULER_UNAVAILABLE)
        val settled = repository.acknowledgeCancellation(paused)
        taskCheck(notify(settled), TaskFailure.NOTIFICATION_UNAVAILABLE)
        settled
    }
    suspend fun reconcile(): List<PersistentTaskRecord> {
        val restored = repository.recover()
        for (record in restored) {
            operation(record.identity.taskId) {
                // Re-read under queue ownership; an earlier pause/cancel may have completed meanwhile.
                val actual = current(record.identity.taskId) ?: throw TaskStoreException(TaskFailure.STALE_REVISION_OR_OWNER)
                if (actual.state == PersistentTaskState.SCHEDULED) {
                    if (!available()) blockIfCurrent(actual, PersistentTaskReason.NOTIFICATION_UNAVAILABLE)
                    else enqueueOrBlock(actual)
                } else if (actual.state == PersistentTaskState.BLOCKED)
                    taskCheck(cancelScheduled(actual.identity.taskId), TaskFailure.SCHEDULER_UNAVAILABLE)
            }
        }
        return repository.list()
    }

    suspend fun execute(taskId: String, scheduledRevision: Long): TaskExecutionOutcome {
        // Published before claim's IO return: cancellation at dispatcher return still owns cleanup.
        val ownership = AtomicReference<TaskRunClaim?>()
        var runner: AgentRunner? = null
        try {
            if (!available()) {
                val blocked = repository.blockScheduled(taskId, scheduledRevision, PersistentTaskReason.NOTIFICATION_UNAVAILABLE)
                return TaskExecutionOutcome(blocked, TaskFailure.NOTIFICATION_UNAVAILABLE)
            }
            val admitted = repository.claim(taskId, scheduledRevision) { ownership.set(it) }
            runner = AgentRunner(reads, sessionCurrent = { repository.isCurrent(admitted) },
                connectionCurrent = { false }, wallClock = clock)
            // No untrusted completion objects cross this boundary; this exact runner owns the child.
            val observed = runner.run(admitted.plan)
            val terminal = repository.finish(admitted, observed)
            val delivered = notify(terminal)
            return TaskExecutionOutcome(current(taskId) ?: terminal, when {
                !delivered -> TaskFailure.NOTIFICATION_UNAVAILABLE
                terminal.state == PersistentTaskState.BLOCKED -> TaskFailure.DEADLINE_EXPIRED
                terminal.state != PersistentTaskState.COMPLETED -> TaskFailure.LOCAL_READ_FAILED
                else -> null
            }, ran = true)
        } catch (cancelled: CancellationException) {
            // Best-effort bounded metadata finalization only; cancellation never starts another read.
            val owned = ownership.get()
            if (owned != null) withContext(NonCancellable) {
                withTimeoutOrNull(2_000) {
                    try { if (repository.isCurrent(owned)) repository.finish(owned, null, stopped = true) }
                    catch (_: TaskStoreException) { /* Persisted attempt remains consumed; no replay. */ }
                }
            }
            throw cancelled
        } catch (safe: TaskStoreException) {
            if (safe.failure == TaskFailure.DEADLINE_EXPIRED) {
                try { repository.recover() } catch (_: TaskStoreException) { /* Preserve the original category. */ }
            }
            return TaskExecutionOutcome(currentOrNull(taskId), safe.failure, ran = ownership.get() != null)
        } catch (_: Exception) {
            val owned = ownership.get()
            if (owned != null) {
                try {
                    val failed = repository.finish(owned, null)
                    return TaskExecutionOutcome(failed, TaskFailure.LOCAL_READ_FAILED, ran = true)
                } catch (safe: TaskStoreException) {
                    return TaskExecutionOutcome(currentOrNull(taskId), safe.failure, ran = true)
                }
            }
            return TaskExecutionOutcome(currentOrNull(taskId), TaskFailure.LOCAL_READ_FAILED)
        } finally {
            ownership.get()?.plan?.taskId?.let { runner?.clearResultPayloads(it) }
        }
    }

    private suspend fun enqueueOrBlock(record: PersistentTaskRecord) {
        val enqueued = try { withTimeoutOrNull(10_000) {
            scheduler.enqueue(record.identity.taskId, record.revision, requireNotNull(record.dueAt)) } ?: false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!enqueued) blockIfCurrent(record, PersistentTaskReason.SCHEDULER_UNAVAILABLE)
    }
    private suspend fun blockIfCurrent(record: PersistentTaskRecord, reason: PersistentTaskReason) {
        try { repository.blockScheduled(record.identity.taskId, record.revision, reason) }
        catch (safe: TaskStoreException) {
            // Cancellation/replacement wins. Storage and other failures remain visible.
            if (safe.failure != TaskFailure.STALE_REVISION_OR_OWNER) throw safe
        }
    }
    private suspend fun available(): Boolean = try { withTimeoutOrNull(2_000) { notifications.isAvailable() } ?: false }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    private suspend fun notify(record: PersistentTaskRecord): Boolean = try {
        val accepted = withTimeoutOrNull(2_000) { notifications.publish(record.identity.taskId, record.state) } ?: false
        if (!accepted) persistNotificationFailure(record)
        accepted // Adapter acceptance is not proof of native/device delivery.
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (safe: TaskStoreException) { throw safe }
    catch (_: Exception) { persistNotificationFailure(record); false }
    private suspend fun persistNotificationFailure(record: PersistentTaskRecord) {
        try { repository.recordNotificationFailure(record) }
        catch (safe: TaskStoreException) { if (safe.failure != TaskFailure.STALE_REVISION_OR_OWNER) throw safe }
    }
    private suspend fun <T> operation(taskId: String, action: suspend () -> T): T {
        taskCheck(validTaskId(taskId))
        taskCheck(repository.list().any { it.identity.taskId == taskId }, TaskFailure.STALE_REVISION_OR_OWNER)
        return operations.computeIfAbsent(taskId) { Mutex() }.withLock { action() }
    }
    private suspend fun cancelScheduled(id: String): Boolean = try { withTimeoutOrNull(10_000) { scheduler.cancel(id) } ?: false }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    private suspend fun current(id: String) = repository.list().singleOrNull { it.identity.taskId == id }
    private suspend fun currentOrNull(id: String): PersistentTaskRecord? = try { current(id) }
    catch (_: TaskStoreException) { null }
}
