package com.example.voicebrainlive.agent.persistence

import com.example.voicebrainlive.agent.AgentGoal
import com.example.voicebrainlive.agent.AgentGoalTemplates
import com.example.voicebrainlive.agent.AgentPlan
import com.example.voicebrainlive.agent.AgentPlanAdmission
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.AgentTaskSnapshot
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TaskRunClaim(val record: PersistentTaskRecord, val owner: PersistentRunOwner, val plan: AgentPlan)

/** Sole app-owned IO facade. No API accepts a caller-generated executable plan or replacement snapshot. */
class TaskRepository(
    private val persistence: TaskMetadataPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val processId = ids().also { taskCheck(validTaskId(it)) }
    private val latest = AtomicReference<PersistentTaskSnapshot?>()

    suspend fun list(): List<PersistentTaskRecord> = access { load().tasks }

    /** Exact due/deadline are persisted in DRAFT; this call starts/enqueues nothing. */
    suspend fun previewLocalStatus(dueAt: Long, deadlineAt: Long): PersistentTaskRecord = access {
        val state = load(); val now = time(state)
        taskCheck(now <= Long.MAX_VALUE - 86_400_000 && dueAt >= now && deadlineAt > dueAt && deadlineAt - now <= 86_400_000)
        taskCheck(state.tasks.size < 100, TaskFailure.CAPACITY_REACHED)
        val taskId = ids(); val key = ids()
        taskCheck(state.tasks.none { it.identity.taskId == taskId || it.identity.idempotencyKey == key }, TaskFailure.STALE_REVISION_OR_OWNER)
        // Construct the authoritative closed template here, rather than recognizing caller-supplied names.
        val plan = AgentPlanAdmission.admit(AgentGoalTemplates.interpret(AgentGoal.LocalStatus, taskId, key, now), now).plan
        val identity = PersistentTaskIdentity(plan.taskId, plan.parentTaskId, plan.idempotencyKey, now, plan.deadlineMillis,
            taskFrozen(plan.steps.map { PersistentStepIdentity(it.stepId, it.idempotencyKey) }))
        val draft = PersistentTaskRecord(identity, PersistentTaskRecipe.LOCAL_STATUS, PersistentTaskState.DRAFT,
            1, now, deadlineAt, dueAt, 0, null, PersistentTaskReason.NONE)
        commit(state, state.tasks + draft).tasks.last()
    }

    suspend fun schedule(taskId: String, expectedRevision: Long): PersistentTaskRecord = change(taskId, expectedRevision) { old, now ->
        live(old, now)
        taskCheck(!old.queueCancellationPending, TaskFailure.SCHEDULER_UNAVAILABLE)
        taskCheck(old.recipe == PersistentTaskRecipe.LOCAL_STATUS && old.attempts == 0 &&
            old.state in setOf(PersistentTaskState.DRAFT, PersistentTaskState.PAUSED) && old.dueAt!! >= now)
        next(old, PersistentTaskState.SCHEDULED, now)
    }

    suspend fun cancel(taskId: String, expectedRevision: Long): PersistentTaskRecord = change(taskId, expectedRevision) { old, now ->
        taskCheck(old.state !in TERMINAL, TaskFailure.STALE_REVISION_OR_OWNER)
        next(old, PersistentTaskState.CANCELLED, now, PersistentTaskReason.USER_CANCELLED).copy(queueCancellationPending = true)
    }
    suspend fun pause(taskId: String, expectedRevision: Long): PersistentTaskRecord = change(taskId, expectedRevision) { old, now ->
        live(old, now)
        taskCheck(old.state in setOf(PersistentTaskState.DRAFT, PersistentTaskState.SCHEDULED, PersistentTaskState.RUNNING))
        next(old, PersistentTaskState.PAUSED, now,
            if (old.attempts > 0) PersistentTaskReason.PAUSED_AFTER_START else PersistentTaskReason.NONE).copy(queueCancellationPending = true)
    }

    /** Reconciliation never guesses success or replays started work / restored approvals. */
    suspend fun recover(): List<PersistentTaskRecord> = access {
        val state = load(); val now = time(state)
        val recovered = state.tasks.map { old ->
            when {
                old.state in TERMINAL -> old
                now >= old.deadlineAt && !(old.state == PersistentTaskState.BLOCKED && old.reason == PersistentTaskReason.DEADLINE_EXPIRED) ->
                    next(old, PersistentTaskState.BLOCKED, now, PersistentTaskReason.DEADLINE_EXPIRED)
                old.state == PersistentTaskState.RUNNING && old.runOwner?.processId != processId ->
                    next(old, PersistentTaskState.BLOCKED, now, PersistentTaskReason.PROCESS_DEATH_RUNNING)
                old.state == PersistentTaskState.WAITING_FOR_APPROVAL ->
                    next(old, PersistentTaskState.BLOCKED, now, PersistentTaskReason.FRESH_FOREGROUND_REVIEW)
                else -> old
            }
        }
        if (recovered == state.tasks) state.tasks else commit(state, recovered).tasks
    }

    internal suspend fun claim(taskId: String, expectedRevision: Long,
        onCommitted: (TaskRunClaim) -> Unit = {}): TaskRunClaim = access {
        val state = load(); val now = time(state); val old = exact(state, taskId, expectedRevision)
        live(old, now)
        taskCheck(old.state == PersistentTaskState.SCHEDULED && old.recipe == PersistentTaskRecipe.LOCAL_STATUS && old.attempts == 0,
            TaskFailure.STALE_REVISION_OR_OWNER)
        taskCheck(now >= old.dueAt!!)
        val attemptId = ids(); taskCheck(validTaskId(attemptId) && attemptId != taskId)
        val owner = PersistentRunOwner(processId, attemptId)
        val childDeadline = minOf(old.deadlineAt, now + minOf(60_000, Long.MAX_VALUE - now))
        taskCheck(childDeadline > now, TaskFailure.DEADLINE_EXPIRED)
        val fresh = AgentGoalTemplates.interpret(AgentGoal.LocalStatus, attemptId, attemptId, now)
        val plan = AgentPlanAdmission.admit(fresh.copy(parentTaskId = taskId, deadlineMillis = childDeadline,
            steps = fresh.steps.map { it.copy(deadlineMillis = childDeadline) }), now).plan
        val running = next(old, PersistentTaskState.RUNNING, now).copy(attempts = 1, runOwner = owner)
        val committed = commit(state, state.tasks.map { if (it.identity.taskId == taskId) running else it })
        TaskRunClaim(committed.tasks.single { it.identity.taskId == taskId }, owner, plan).also(onCommitted)
    }

    internal fun isCurrent(claim: TaskRunClaim): Boolean = latest.get()?.tasks?.any {
        it.identity.taskId == claim.record.identity.taskId && it.revision == claim.record.revision &&
            it.state == PersistentTaskState.RUNNING && it.runOwner == claim.owner
    } == true

    /** Only LocalTaskEngine passes its own fresh runner snapshot; no UI/model completion API. */
    internal suspend fun finish(claim: TaskRunClaim, observed: AgentTaskSnapshot?, stopped: Boolean = false): PersistentTaskRecord =
        change(claim.record.identity.taskId, claim.record.revision) { old, now ->
            taskCheck(old.state == PersistentTaskState.RUNNING && old.runOwner == claim.owner, TaskFailure.STALE_REVISION_OR_OWNER)
            when {
                now >= old.deadlineAt -> next(old, PersistentTaskState.BLOCKED, now, PersistentTaskReason.DEADLINE_EXPIRED)
                stopped -> next(old, PersistentTaskState.BLOCKED, now, PersistentTaskReason.WORKER_STOPPED)
                else -> {
                    val success = observed != null && observed.taskId == claim.plan.taskId && observed.parentTaskId == old.identity.taskId &&
                        observed.idempotencyKey == claim.plan.idempotencyKey && observed.createdAtMillis == claim.plan.createdAtMillis &&
                        observed.updatedAtMillis in observed.createdAtMillis..now && observed.deadlineMillis == claim.plan.deadlineMillis &&
                        observed.status == AgentStatus.SUCCEEDED && observed.calls == 3 &&
                        observed.steps.map { it.stepId } == listOf("clock", "battery", "report") &&
                        observed.steps.all { it.status == AgentStatus.SUCCEEDED }
                    next(old, if (success) PersistentTaskState.COMPLETED else PersistentTaskState.FAILED, now,
                        if (success) PersistentTaskReason.NONE else PersistentTaskReason.RUN_FAILED)
                }
            }
        }

    internal suspend fun blockScheduled(taskId: String, expectedRevision: Long, reason: PersistentTaskReason): PersistentTaskRecord =
        change(taskId, expectedRevision) { old, now ->
            taskCheck(old.state == PersistentTaskState.SCHEDULED, TaskFailure.STALE_REVISION_OR_OWNER)
            taskCheck(reason in setOf(PersistentTaskReason.SCHEDULER_UNAVAILABLE, PersistentTaskReason.NOTIFICATION_UNAVAILABLE))
            next(old, PersistentTaskState.BLOCKED, now, reason)
        }

    /** Failure-only metadata: execution revision remains stable so an enqueued exact claim is valid.
     * The global snapshot CAS still advances; late publication cannot modify a newer state/revision.
     */
    internal suspend fun recordNotificationFailure(observed: PersistentTaskRecord): PersistentTaskRecord = access {
        val state = load(); val now = time(state)
        val old = exact(state, observed.identity.taskId, observed.revision)
        taskCheck(old.state == observed.state, TaskFailure.STALE_REVISION_OR_OWNER)
        if (old.notificationFailure != null) old
        else {
            val failed = old.copy(updatedAt = now, notificationFailure = TaskFailure.NOTIFICATION_UNAVAILABLE, notificationFailureAt = now)
            commit(state, state.tasks.map { if (it.identity.taskId == old.identity.taskId) failed else it }).tasks.single { it.identity.taskId == old.identity.taskId }
        }
    }

    /** Only a completed scheduler cancellation may settle this flag; unknown operations block reuse. */
    internal suspend fun acknowledgeCancellation(observed: PersistentTaskRecord): PersistentTaskRecord = access {
        val state = load(); val now = time(state)
        val old = exact(state, observed.identity.taskId, observed.revision)
        taskCheck(old.state == observed.state && old.queueCancellationPending, TaskFailure.STALE_REVISION_OR_OWNER)
        val settled = old.copy(updatedAt = now, queueCancellationPending = false)
        commit(state, state.tasks.map { if (it.identity.taskId == old.identity.taskId) settled else it }).tasks.single { it.identity.taskId == old.identity.taskId }
    }

    private suspend fun change(taskId: String, revision: Long, transform: (PersistentTaskRecord, Long) -> PersistentTaskRecord): PersistentTaskRecord = access {
        val state = load(); val now = time(state); val old = exact(state, taskId, revision)
        val changed = transform(old, now)
        commit(state, state.tasks.map { if (it.identity.taskId == taskId) changed else it }).tasks.single { it.identity.taskId == taskId }
    }
    private fun load(): PersistentTaskSnapshot = freezeTasks(persistence.read()).also { latest.set(it) }
    private fun commit(state: PersistentTaskSnapshot, tasks: List<PersistentTaskRecord>): PersistentTaskSnapshot {
        taskCheck(state.revision < Long.MAX_VALUE, TaskFailure.CAPACITY_REACHED)
        val next = freezeTasks(PersistentTaskSnapshot(1, state.revision + 1, tasks))
        taskCheck(persistence.compareAndReplace(state.revision, next), TaskFailure.STALE_REVISION_OR_OWNER)
        latest.set(next)
        return next
    }
    private fun time(state: PersistentTaskSnapshot): Long = clock().also { now ->
        taskCheck(now >= 0 && state.tasks.all { now >= it.updatedAt }, TaskFailure.CLOCK_ANOMALY)
    }
    private fun exact(state: PersistentTaskSnapshot, taskId: String, revision: Long): PersistentTaskRecord {
        taskCheck(validTaskId(taskId))
        return state.tasks.singleOrNull { it.identity.taskId == taskId && it.revision == revision }
            ?: throw TaskStoreException(TaskFailure.STALE_REVISION_OR_OWNER)
    }
    private fun live(old: PersistentTaskRecord, now: Long) = taskCheck(now < old.deadlineAt, TaskFailure.DEADLINE_EXPIRED)
    private fun next(old: PersistentTaskRecord, state: PersistentTaskState, now: Long,
        reason: PersistentTaskReason = PersistentTaskReason.NONE): PersistentTaskRecord {
        taskCheck(old.revision < Long.MAX_VALUE, TaskFailure.CAPACITY_REACHED)
        return old.copy(state = state, revision = old.revision + 1, updatedAt = now, runOwner = null, reason = reason)
    }
    private suspend fun <T> access(block: () -> T): T = withContext(io) {
        mutex.withLock {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (safe: TaskStoreException) {
                if (safe.failure !in setOf(TaskFailure.INVALID_REQUEST, TaskFailure.STALE_REVISION_OR_OWNER,
                        TaskFailure.CAPACITY_REACHED, TaskFailure.DEADLINE_EXPIRED)) latest.set(null)
                throw safe
            }
            catch (_: Exception) { latest.set(null); throw TaskStoreException(TaskFailure.STORAGE_UNAVAILABLE) }
        }
    }
    companion object { private val TERMINAL = setOf(PersistentTaskState.COMPLETED, PersistentTaskState.CANCELLED, PersistentTaskState.FAILED) }
}
