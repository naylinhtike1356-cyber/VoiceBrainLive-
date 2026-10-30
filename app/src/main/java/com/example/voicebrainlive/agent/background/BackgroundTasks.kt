package com.example.voicebrainlive.agent.background

import android.content.Context
import com.example.voicebrainlive.agent.AndroidAgentReadPort
import com.example.voicebrainlive.agent.persistence.AndroidTaskStoreFactory
import com.example.voicebrainlive.agent.persistence.LocalTaskEngine
import com.example.voicebrainlive.agent.persistence.PersistentTaskRecord
import com.example.voicebrainlive.agent.persistence.TaskExecutionOutcome
import com.example.voicebrainlive.agent.persistence.TaskFailure
import com.example.voicebrainlive.agent.persistence.TaskRepository
import com.example.voicebrainlive.agent.persistence.TaskStoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Application-owned narrow surface. No generic tool, result/completion writer, or approval API. */
interface BackgroundTasks {
    suspend fun history(): List<PersistentTaskRecord>
    suspend fun previewLocalStatus(): PersistentTaskRecord
    suspend fun schedule(taskId: String, expectedRevision: Long): PersistentTaskRecord
    suspend fun pause(taskId: String, expectedRevision: Long): PersistentTaskRecord
    suspend fun cancel(taskId: String, expectedRevision: Long): PersistentTaskRecord
    suspend fun execute(taskId: String, scheduledRevision: Long): TaskExecutionOutcome
    suspend fun reconcile(): List<PersistentTaskRecord>
    suspend fun notificationsAvailable(): Boolean
}

/** Factory/repository/engine initialize on IO; exactly one instance is owned by the Application. */
class AndroidBackgroundTasks(context: Context, private val clock: () -> Long = System::currentTimeMillis) : BackgroundTasks {
    private val app = context.applicationContext
    private val notifications by lazy { AgentTaskNotifications(app) }
    private val repository by lazy { TaskRepository(AndroidTaskStoreFactory.create(app), clock) }
    private val engine by lazy { LocalTaskEngine(repository, AndroidAgentReadPort(app),
        WorkManagerTaskScheduler(AndroidWorkQueue(app), clock), notifications, clock) }
    override suspend fun history() = withContext(Dispatchers.IO) { repository.list() }
    override suspend fun previewLocalStatus() = withContext(Dispatchers.IO) {
        val now = clock()
        if (now < 0 || now > Long.MAX_VALUE - EXPIRY_MILLIS) throw TaskStoreException(TaskFailure.CLOCK_ANOMALY)
        repository.previewLocalStatus(now + DELAY_MILLIS, now + EXPIRY_MILLIS)
    }
    override suspend fun schedule(taskId: String, expectedRevision: Long) = withContext(Dispatchers.IO) { engine.schedule(taskId, expectedRevision) }
    override suspend fun pause(taskId: String, expectedRevision: Long) = withContext(Dispatchers.IO) { engine.pause(taskId, expectedRevision) }
    override suspend fun cancel(taskId: String, expectedRevision: Long) = withContext(Dispatchers.IO) { engine.cancel(taskId, expectedRevision) }
    override suspend fun execute(taskId: String, scheduledRevision: Long) = withContext(Dispatchers.IO) { engine.execute(taskId, scheduledRevision) }
    override suspend fun reconcile() = withContext(Dispatchers.IO) { engine.reconcile() }
    override suspend fun notificationsAvailable() = withContext(Dispatchers.IO) { notifications.isAvailable() }
    companion object {
        const val DELAY_MILLIS = 15 * 60 * 1_000L
        const val EXPIRY_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
