package com.example.voicebrainlive.agent.background

import android.content.Context
import androidx.work.*
import com.example.voicebrainlive.agent.persistence.*
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fake native queue/application only. Real Worker.doWork executes without work-testing or a queue DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = BackgroundTestApplication::class)
class BackgroundPlatformTest {
    private lateinit var fake: FakeBackgroundTasks
    @Before fun fixture() { fake = FakeBackgroundTasks(); BackgroundTestApplication.fake = fake }
    @Test fun exactWorkerInputRejectsExtraReservedKeysAndWrongPrimitiveTypes() {
        assertEquals(BackgroundWorkInput("local_1", 2), BackgroundWorkInput.parse(BackgroundWorkInput("local_1", 2).toData()))
        assertNull(BackgroundWorkInput.parse(Data.EMPTY))
        assertNull(BackgroundWorkInput.parse(Data.Builder().putString(BackgroundWorkInput.TASK_ID, "local_1").putInt(BackgroundWorkInput.REVISION, 2).build()))
        assertNull(BackgroundWorkInput.parse(Data.Builder().putAll(BackgroundWorkInput("local_1", 2).toData())
            .putString("androidx.work.impl.workers.ConstraintTrackingWorker.ARGUMENT_CLASS_NAME", LocalStatusWorker::class.java.name).build()))
        assertNull(BackgroundWorkInput.parse(BackgroundWorkInput("../other", 2).toData()))
        assertNull(BackgroundWorkInput.parse(BackgroundWorkInput("local_1", 0).toData()))
    }
    @Test fun schedulerBuildsExactRevisionUniqueWorkWithInexactDelayBatteryAndNoNetwork() = runBlocking {
        class Queue : AgentWorkQueue {
            val requests = mutableListOf<Pair<String, OneTimeWorkRequest>>()
            val cancelled = mutableListOf<String>()
            override suspend fun enqueue(uniqueName: String, request: OneTimeWorkRequest): Boolean { requests += uniqueName to request; return true }
            override suspend fun cancelTag(tag: String): Boolean { cancelled += tag; return true }
        }
        val queue = Queue(); val scheduler = WorkManagerTaskScheduler(queue) { 1_000L }
        assertTrue(scheduler.enqueue("local_1", 2, 1_000L + AndroidBackgroundTasks.DELAY_MILLIS))
        assertTrue(scheduler.enqueue("local_1", 3, 900L)) // Reconcile overdue, still-live work immediately.
        val first = queue.requests[0]; val second = queue.requests[1]
        assertNotEquals(first.first, second.first)
        assertEquals(BackgroundWorkInput("local_1", 2), BackgroundWorkInput.parse(first.second.workSpec.input))
        assertEquals(AndroidBackgroundTasks.DELAY_MILLIS, first.second.workSpec.initialDelay)
        assertEquals(0L, second.second.workSpec.initialDelay)
        assertTrue(first.second.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, first.second.workSpec.constraints.requiredNetworkType)
        assertTrue(scheduler.cancel("local_1")); assertEquals(listOf(WorkManagerTaskScheduler.taskTag("local_1")), queue.cancelled)
        assertFalse(scheduler.enqueue("invalid/id", 2, 2_000)); assertEquals(2, queue.requests.size)
    }
    @Test fun schedulerWaitsForQueueAcknowledgementAndPropagatesFalseWithoutRetry() = runBlocking {
        val release = CompletableDeferred<Boolean>(); var calls = 0
        val queue = object : AgentWorkQueue {
            override suspend fun enqueue(uniqueName: String, request: OneTimeWorkRequest): Boolean { calls++; return release.await() }
            override suspend fun cancelTag(tag: String) = true
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) { WorkManagerTaskScheduler(queue) { 1_000L }.enqueue("local_1", 2, 2_000) }
        assertFalse(result.isCompleted); release.complete(false); assertFalse(result.await()); assertEquals(1, calls)
    }
    @Test fun actualWorkerSuccessUsesOnlyPersistedCompletedOutcomeAndExactInput() = runBlocking {
        val draft = fake.previewLocalStatus()
        fake.executeAction = { id, revision ->
            assertEquals(draft.identity.taskId, id); assertEquals(2L, revision)
            TaskExecutionOutcome(draft.copy(state = PersistentTaskState.COMPLETED, revision = 4, attempts = 1), ran = true)
        }
        assertEquals(ListenableWorker.Result.success(), worker(BackgroundWorkInput(draft.identity.taskId, 2).toData()).doWork())
        assertEquals(1, fake.executeCalls)
    }
    @Test fun actualWorkerFailureAndInvalidInputNeverBecomeSuccessOrRetry() = runBlocking {
        assertEquals(ListenableWorker.Result.failure(), worker(Data.EMPTY).doWork()); assertEquals(0, fake.executeCalls)
        fake.executeAction = { _, _ -> TaskExecutionOutcome(null, TaskFailure.STALE_REVISION_OR_OWNER) }
        assertEquals(ListenableWorker.Result.failure(), worker(BackgroundWorkInput("local_1", 2).toData()).doWork())
        fake.executeAction = { _, _ -> error("PRIVATE EXECUTION ERROR") }
        assertEquals(ListenableWorker.Result.failure(), worker(BackgroundWorkInput("local_1", 2).toData()).doWork())
        assertEquals(2, fake.executeCalls)
    }
    @Test fun actualWorkerCancellationPropagatesWithoutAnotherDispatch() = runBlocking {
        fake.executeAction = { _, _ -> throw CancellationException("synthetic stop") }
        try { worker(BackgroundWorkInput("local_1", 2).toData()).doWork(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(1, fake.executeCalls)
    }
    @Test fun notificationReviewIntentsHaveExactDistinctIdentityAndNoActionAuthority() {
        val context = RuntimeEnvironment.getApplication()
        val a = AgentTaskNotifications.reviewIntent(context, "local_a")
        val b = AgentTaskNotifications.reviewIntent(context, "local_b")
        assertFalse(a.filterEquals(b)); assertTrue(a.filterEquals(AgentTaskNotifications.reviewIntent(context, "local_a")))
        assertEquals(ScheduledAgentTasksActivity::class.java.name, a.component?.className)
        assertEquals("local_a", a.data?.lastPathSegment)
        assertNull(a.extras)
        assertTrue(a.action!!.contains("REVIEW"))
    }

    @Test @Config(sdk = [34]) fun deniedNotificationPermissionDoesNotPublishOrClaimAvailability() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications = AgentTaskNotifications(context)
        assertFalse(notifications.isAvailable())
        assertFalse(notifications.publish("local_permission_denied", PersistentTaskState.COMPLETED))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private fun worker(input: Data): LocalStatusWorker {
        val direct = Executor { it.run() }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        val constructor = WorkerParameters::class.java.constructors.single { it.parameterTypes.firstOrNull() == UUID::class.java }
        val arguments = constructor.parameterTypes.map { type -> when {
            type == UUID::class.java -> UUID.randomUUID()
            type == Data::class.java -> input
            java.util.Collection::class.java.isAssignableFrom(type) -> emptyList<String>()
            type == Int::class.javaPrimitiveType -> 0
            type == Executor::class.java -> direct
            type.name == "kotlin.coroutines.CoroutineContext" -> Dispatchers.Unconfined
            type == WorkerFactory::class.java -> factory
            type.name == "androidx.work.WorkerParameters\$RuntimeExtras" -> type.getDeclaredConstructor().newInstance()
            type.name == "androidx.work.impl.utils.taskexecutor.TaskExecutor" -> Class.forName("androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor").getConstructor(Executor::class.java).newInstance(direct)
            type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> throw AssertionError("Unexpected native progress/foreground operation: ${method.name}") }
            else -> error("Unrecognized installed WorkerParameters field: ${type.name}")
        } }.toTypedArray()
        val parameters = constructor.newInstance(*arguments) as WorkerParameters
        return LocalStatusWorker(RuntimeEnvironment.getApplication(), parameters)
    }
}
