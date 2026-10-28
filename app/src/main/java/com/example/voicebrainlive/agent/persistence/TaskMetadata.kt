package com.example.voicebrainlive.agent.persistence

import java.util.Collections

/** Wire names are stable schema-1 identifiers: never rename/reorder their meaning. */
enum class PersistentTaskState { DRAFT, WAITING_FOR_APPROVAL, SCHEDULED, RUNNING, PAUSED, BLOCKED, FAILED, COMPLETED, CANCELLED }
enum class PersistentTaskRecipe { LOCAL_STATUS, FOREGROUND_REVIEW_ONLY }
enum class PersistentTaskReason { NONE, USER_CANCELLED, PROCESS_DEATH_RUNNING, FRESH_FOREGROUND_REVIEW,
    DEADLINE_EXPIRED, RUN_FAILED, PAUSED_AFTER_START, SCHEDULER_UNAVAILABLE, NOTIFICATION_UNAVAILABLE, WORKER_STOPPED }
enum class TaskFailure { INVALID_REQUEST, STALE_REVISION_OR_OWNER, CAPACITY_REACHED, CLOCK_ANOMALY,
    DEADLINE_EXPIRED, KEY_UNAVAILABLE, STORAGE_UNAVAILABLE, DATA_UNREADABLE, UNSUPPORTED_SCHEMA,
    RECOVERY_REQUIRED, SCHEDULER_UNAVAILABLE, NOTIFICATION_UNAVAILABLE, LOCAL_READ_FAILED }
class TaskStoreException(val failure: TaskFailure) : Exception("Agent task: ${failure.name}")

data class PersistentStepIdentity(val stepId: String, val idempotencyKey: String)
data class PersistentTaskIdentity(val taskId: String, val parentTaskId: String?, val idempotencyKey: String,
    val sourceCreatedAt: Long, val sourceDeadlineAt: Long, val steps: List<PersistentStepIdentity>)
/** Callback ownership only. Neither value is an approval or a reusable execution capability. */
data class PersistentRunOwner(val processId: String, val attemptId: String)
data class PersistentTaskRecord(val identity: PersistentTaskIdentity, val recipe: PersistentTaskRecipe,
    val state: PersistentTaskState, val revision: Long, val updatedAt: Long, val deadlineAt: Long,
    val dueAt: Long?, val attempts: Int, val runOwner: PersistentRunOwner?, val reason: PersistentTaskReason,
    val notificationFailure: TaskFailure? = null, val notificationFailureAt: Long? = null,
    val queueCancellationPending: Boolean = false)
data class PersistentTaskSnapshot(val schemaVersion: Int = 1, val revision: Long = 0,
    val tasks: List<PersistentTaskRecord> = emptyList())

internal fun taskCheck(condition: Boolean, failure: TaskFailure = TaskFailure.INVALID_REQUEST) {
    if (!condition) throw TaskStoreException(failure)
}
internal fun validTaskId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,80}"))
internal fun <T> taskFrozen(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

/** Deep ownership at every store/repository boundary, including caller supplied shallow copies. */
internal fun freezeTasks(input: PersistentTaskSnapshot): PersistentTaskSnapshot {
    taskCheck(input.tasks.size <= 100)
    val frozen = input.copy(tasks = taskFrozen(input.tasks.map { task ->
        taskCheck(task.identity.steps.size in 1..8)
        task.copy(identity = task.identity.copy(steps = taskFrozen(task.identity.steps.map { it.copy() })),
            runOwner = task.runOwner?.copy())
    }))
    validateTaskSnapshot(frozen)
    return frozen
}

internal fun validateTaskSnapshot(snapshot: PersistentTaskSnapshot) {
    taskCheck(snapshot.schemaVersion == 1, TaskFailure.UNSUPPORTED_SCHEMA)
    taskCheck(snapshot.revision >= 0 && snapshot.tasks.size <= 100)
    taskCheck(snapshot.tasks.map { it.identity.taskId }.toSet().size == snapshot.tasks.size &&
        snapshot.tasks.map { it.identity.idempotencyKey }.toSet().size == snapshot.tasks.size)
    taskCheck(snapshot.tasks.isEmpty() || snapshot.revision > 0)
    snapshot.tasks.forEach { record ->
        val id = record.identity
        taskCheck(validTaskId(id.taskId) && validTaskId(id.idempotencyKey) &&
            (id.parentTaskId == null || validTaskId(id.parentTaskId)) && id.parentTaskId != id.taskId)
        taskCheck(id.sourceCreatedAt >= 0 && id.sourceDeadlineAt > id.sourceCreatedAt &&
            id.sourceDeadlineAt - id.sourceCreatedAt <= 60_000)
        taskCheck(id.steps.size in 1..8 && id.steps.map { it.stepId }.toSet().size == id.steps.size &&
            id.steps.map { it.idempotencyKey }.toSet().size == id.steps.size &&
            id.steps.all { validTaskId(it.stepId) && validTaskId(it.idempotencyKey) })
        taskCheck(record.revision > 0 && record.updatedAt >= id.sourceCreatedAt &&
            record.deadlineAt > id.sourceCreatedAt && record.deadlineAt - id.sourceCreatedAt <= 86_400_000 &&
            record.attempts in 0..1)
        taskCheck(record.dueAt == null || record.dueAt in id.sourceCreatedAt until record.deadlineAt)
        taskCheck((record.state == PersistentTaskState.RUNNING) == (record.runOwner != null))
        record.runOwner?.let { taskCheck(validTaskId(it.processId) && validTaskId(it.attemptId) &&
            it.attemptId != id.taskId && record.attempts == 1) }
        if (record.recipe == PersistentTaskRecipe.FOREGROUND_REVIEW_ONLY) {
            taskCheck(record.state !in setOf(PersistentTaskState.SCHEDULED, PersistentTaskState.RUNNING,
                PersistentTaskState.COMPLETED) && record.attempts == 0 && record.dueAt == null)
        } else {
            // Only this closed template can ever be reconstructed by the repository.
            taskCheck(id.steps.map { it.stepId } == listOf("clock", "battery", "report") && record.dueAt != null)
        }
        if (record.state in setOf(PersistentTaskState.DRAFT, PersistentTaskState.SCHEDULED,
                PersistentTaskState.WAITING_FOR_APPROVAL)) taskCheck(record.attempts == 0)
        if (record.state in setOf(PersistentTaskState.COMPLETED, PersistentTaskState.FAILED)) taskCheck(record.attempts == 1)
        taskCheck(record.state != PersistentTaskState.WAITING_FOR_APPROVAL || record.recipe == PersistentTaskRecipe.FOREGROUND_REVIEW_ONLY)
        taskCheck((record.state == PersistentTaskState.CANCELLED) == (record.reason == PersistentTaskReason.USER_CANCELLED))
        taskCheck((record.notificationFailure == null) == (record.notificationFailureAt == null))
        record.notificationFailure?.let {
            taskCheck(it == TaskFailure.NOTIFICATION_UNAVAILABLE && record.notificationFailureAt!! in id.sourceCreatedAt..record.updatedAt)
        }
        if (record.queueCancellationPending) taskCheck(record.state in setOf(PersistentTaskState.PAUSED,
            PersistentTaskState.CANCELLED, PersistentTaskState.BLOCKED))
        when (record.state) {
            PersistentTaskState.DRAFT, PersistentTaskState.SCHEDULED, PersistentTaskState.RUNNING,
            PersistentTaskState.COMPLETED -> taskCheck(record.reason == PersistentTaskReason.NONE)
            PersistentTaskState.WAITING_FOR_APPROVAL -> taskCheck(record.reason == PersistentTaskReason.FRESH_FOREGROUND_REVIEW)
            PersistentTaskState.PAUSED -> taskCheck(record.reason == if (record.attempts == 0)
                PersistentTaskReason.NONE else PersistentTaskReason.PAUSED_AFTER_START)
            PersistentTaskState.FAILED -> taskCheck(record.reason == PersistentTaskReason.RUN_FAILED)
            PersistentTaskState.BLOCKED -> when (record.reason) {
                PersistentTaskReason.PROCESS_DEATH_RUNNING, PersistentTaskReason.WORKER_STOPPED -> taskCheck(record.attempts == 1)
                PersistentTaskReason.SCHEDULER_UNAVAILABLE, PersistentTaskReason.NOTIFICATION_UNAVAILABLE -> taskCheck(record.attempts == 0)
                PersistentTaskReason.FRESH_FOREGROUND_REVIEW -> taskCheck(record.recipe == PersistentTaskRecipe.FOREGROUND_REVIEW_ONLY)
                PersistentTaskReason.DEADLINE_EXPIRED -> Unit
                else -> taskCheck(false)
            }
            PersistentTaskState.CANCELLED -> Unit
        }
    }
}
