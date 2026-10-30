package com.example.voicebrainlive.agent.background

import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.persistence.*

class BackgroundTestApplication : VoiceBrainApplication() {
    override val backgroundTasks get() = fake
    override fun onCreate() { /* No native startup, scheduler, credential or service in fixtures. */ }
    companion object { lateinit var fake: FakeBackgroundTasks }
}
class FakeBackgroundTasks : BackgroundTasks {
    var records = listOf<PersistentTaskRecord>()
    var unreadable = false
    var notices = true
    var scheduleCalls = 0; var pauseCalls = 0; var cancelCalls = 0; var executeCalls = 0
    var failAfterScheduleCommit = false
    var lastScheduled: Pair<String, Long>? = null
    var executeAction: (suspend (String, Long) -> TaskExecutionOutcome)? = null
    override suspend fun history(): List<PersistentTaskRecord> {
        if (unreadable) throw TaskStoreException(TaskFailure.DATA_UNREADABLE)
        return records.toList()
    }
    override suspend fun previewLocalStatus(): PersistentTaskRecord {
        val now = System.currentTimeMillis()
        val id = "local_${records.size + 1}"
        val identity = PersistentTaskIdentity(id, null, "key_$id", now, now + 60_000,
            listOf("clock", "battery", "report").map { PersistentStepIdentity(it, "${id}_$it") })
        return PersistentTaskRecord(identity, PersistentTaskRecipe.LOCAL_STATUS, PersistentTaskState.DRAFT,
            1, now, now + AndroidBackgroundTasks.EXPIRY_MILLIS, now + AndroidBackgroundTasks.DELAY_MILLIS,
            0, null, PersistentTaskReason.NONE).also { records = records + it }
    }
    private fun exact(id: String, revision: Long) = records.singleOrNull { it.identity.taskId == id && it.revision == revision }
        ?: throw TaskStoreException(TaskFailure.STALE_REVISION_OR_OWNER)
    private fun replace(next: PersistentTaskRecord): PersistentTaskRecord { records = records.map { if (it.identity.taskId == next.identity.taskId) next else it }; return next }
    override suspend fun schedule(taskId: String, expectedRevision: Long): PersistentTaskRecord {
        scheduleCalls++; lastScheduled = taskId to expectedRevision
        if (!notices) throw TaskStoreException(TaskFailure.NOTIFICATION_UNAVAILABLE)
        val old = exact(taskId, expectedRevision)
        val next = replace(old.copy(state = PersistentTaskState.SCHEDULED, revision = old.revision + 1))
        if (failAfterScheduleCommit) {
            replace(next.copy(notificationFailure = TaskFailure.NOTIFICATION_UNAVAILABLE, notificationFailureAt = System.currentTimeMillis()))
            throw TaskStoreException(TaskFailure.NOTIFICATION_UNAVAILABLE)
        }
        return next
    }
    override suspend fun pause(taskId: String, expectedRevision: Long): PersistentTaskRecord {
        pauseCalls++; val old = exact(taskId, expectedRevision)
        return replace(old.copy(state = PersistentTaskState.PAUSED, revision = old.revision + 1, runOwner = null))
    }
    override suspend fun cancel(taskId: String, expectedRevision: Long): PersistentTaskRecord {
        cancelCalls++; val old = exact(taskId, expectedRevision)
        return replace(old.copy(state = PersistentTaskState.CANCELLED, revision = old.revision + 1, runOwner = null))
    }
    override suspend fun execute(taskId: String, scheduledRevision: Long): TaskExecutionOutcome {
        executeCalls++
        return executeAction?.invoke(taskId, scheduledRevision) ?: TaskExecutionOutcome(null, TaskFailure.STALE_REVISION_OR_OWNER)
    }
    override suspend fun reconcile() = history()
    override suspend fun notificationsAvailable() = notices
}
