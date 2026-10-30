package com.example.voicebrainlive.agent.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.example.voicebrainlive.agent.persistence.TaskSchedulerPort
import com.example.voicebrainlive.agent.persistence.validTaskId
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Test seam stops at native queue operations; it cannot claim/complete an agent task. */
interface AgentWorkQueue {
    suspend fun enqueue(uniqueName: String, request: OneTimeWorkRequest): Boolean
    suspend fun cancelTag(tag: String): Boolean
}
class WorkManagerTaskScheduler(private val queue: AgentWorkQueue,
    private val clock: () -> Long = System::currentTimeMillis) : TaskSchedulerPort {
    override suspend fun enqueue(taskId: String, revision: Long, dueAt: Long): Boolean {
        val now = clock()
        if (!validTaskId(taskId) || revision <= 0 || now < 0 || dueAt < 0 || (dueAt > now && dueAt - now > AndroidBackgroundTasks.EXPIRY_MILLIS)) return false
        val request = OneTimeWorkRequest.Builder(LocalStatusWorker::class.java)
            .setInputData(BackgroundWorkInput(taskId, revision).toData())
            .setInitialDelay((dueAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(taskTag(taskId)).build()
        return queue.enqueue("${taskTag(taskId)}_revision_$revision", request)
    }
    override suspend fun cancel(taskId: String): Boolean = validTaskId(taskId) && queue.cancelTag(taskTag(taskId))
    companion object { fun taskTag(taskId: String) = "voicebrain_agent_local_$taskId" }
}

class AndroidWorkQueue(context: Context) : AgentWorkQueue {
    private val app = context.applicationContext
    override suspend fun enqueue(uniqueName: String, request: OneTimeWorkRequest): Boolean =
        await(WorkManager.getInstance(app).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request))
    override suspend fun cancelTag(tag: String): Boolean = await(WorkManager.getInstance(app).cancelAllWorkByTag(tag))
    private suspend fun await(operation: Operation): Boolean = suspendCancellableCoroutine { continuation ->
        val future = operation.result
        future.addListener({
            val accepted = try { future.get(); true } catch (_: Exception) { false }
            if (continuation.isActive) continuation.resume(accepted)
        }, Executor { it.run() })
        // Cancels waiting only, not a claim that the native queue operation was undone.
        continuation.invokeOnCancellation { future.cancel(false) }
    }
}

/** Exact primitive worker input; no model/provider/tool/target/payload can enter this worker. */
data class BackgroundWorkInput(val taskId: String, val scheduledRevision: Long) {
    fun toData(): Data = Data.Builder().putString(TASK_ID, taskId).putLong(REVISION, scheduledRevision).build()
    companion object {
        const val TASK_ID = "task_id"
        const val REVISION = "scheduled_revision"
        fun parse(data: Data): BackgroundWorkInput? {
            if (data.keyValueMap.keys != setOf(TASK_ID, REVISION)) return null
            val id = data.keyValueMap[TASK_ID] as? String ?: return null
            val revision = data.keyValueMap[REVISION] as? Long ?: return null
            if (!validTaskId(id) || revision <= 0) return null
            return BackgroundWorkInput(id, revision)
        }
    }
}
