package com.example.voicebrainlive.agent.background

import android.content.Context
import androidx.work.*
import com.example.voicebrainlive.agent.proactive.*
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** Deliberately not configurable by provider output, Settings, stored jobs or restored preferences. */
internal object TaskReviewLiveGate { const val ENABLED = false }
internal data class TaskReviewWorkerInput(val ruleId: String, val revision: Long) {
    fun data(): Data = Data.Builder().putString("review_rule_id", ruleId).putLong("review_config_revision", revision).build()
    companion object {
        fun parse(input: Data): TaskReviewWorkerInput? {
            if (input.keyValueMap.keys != setOf("review_rule_id", "review_config_revision")) return null
            val id = input.keyValueMap["review_rule_id"] as? String ?: return null
            val revision = input.keyValueMap["review_config_revision"] as? Long ?: return null
            return if (ruleIdValid(id) && revision > 0) TaskReviewWorkerInput(id, revision) else null
        }
    }
}
/** Uses the existing queue abstraction; only host fake queues are injected in this phase. */
internal class TaskReviewWorkReadiness(private val queue: AgentWorkQueue, private val clock: () -> Long,
    private val hostOnly: Boolean = false) : MockReviewWakeups {
    override suspend fun enqueue(ruleId: String, configRevision: Long, eligibleAt: Long) {
        ruleCheck(hostOnly && ruleIdValid(ruleId) && configRevision > 0)
        val now = clock(); ruleCheck(now >= 0 && eligibleAt >= 0 && (eligibleAt <= now || eligibleAt - now <= 30L * 86_400_000))
        val request = OneTimeWorkRequest.Builder(TaskReviewReadinessWorker::class.java)
            .setInputData(TaskReviewWorkerInput(ruleId, configRevision).data())
            .setInitialDelay((eligibleAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .addTag(tag(ruleId)).build()
        ruleCheck(queue.enqueue("${tag(ruleId)}_${configRevision}_$eligibleAt", request), RuleFailure.STORAGE_UNAVAILABLE)
    }
    override suspend fun cancel(ruleId: String) {
        ruleCheck(hostOnly && ruleIdValid(ruleId)); ruleCheck(queue.cancelTag(tag(ruleId)), RuleFailure.STORAGE_UNAVAILABLE)
    }
    companion object { fun tag(id: String) = "voicebrain_review_readiness_$id" }
}
internal enum class ReadinessWorkerStatus { DISABLED, INVALID_INPUT, COMPLETED, BLOCKED, NO_EFFECT }
/** Delegates ownership, quiet hours, expiration, deduplication, retries/history to the existing coordinator.
 * Never retries through WorkManager. Canonical safe read-backoff owns the next one-time wakeup.
 */
internal class TaskReviewWorkerEntry(private val coordinator: MockReviewCoordinator? = null, private val hostOnly: Boolean = false) {
    private val recovery = Mutex()
    private var recovered = false
    suspend fun execute(data: Data): ReadinessWorkerStatus {
        if (!hostOnly || coordinator == null) return ReadinessWorkerStatus.DISABLED
        val input = TaskReviewWorkerInput.parse(data) ?: return ReadinessWorkerStatus.INVALID_INPUT
        return try {
            // Recovery only changes uncertain old-process claims to BLOCKED, never replays an effect.
            recovery.withLock {
                if (!recovered) { coordinator.recoverInterrupted(); recovered = true }
            }
            val outcome = coordinator.trigger(input.ruleId, input.revision)
            when (outcome.execution?.state) {
                PersistentTaskState.COMPLETED -> ReadinessWorkerStatus.COMPLETED
                PersistentTaskState.BLOCKED, PersistentTaskState.WAITING_FOR_APPROVAL, PersistentTaskState.FAILED -> ReadinessWorkerStatus.BLOCKED
                else -> ReadinessWorkerStatus.NO_EFFECT
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReadinessWorkerStatus.BLOCKED }
    }
}
/** No runtime factory/registration. Actual OS execution fails closed before opening app stores or network.
 * Existing AgentTaskNotifications.CHANNEL remains the planned generic, non-private result channel.
 */
class TaskReviewReadinessWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!TaskReviewLiveGate.ENABLED) return Result.failure()
        return when (TaskReviewWorkerEntry().execute(inputData)) {
            ReadinessWorkerStatus.COMPLETED -> Result.success()
            else -> Result.failure()
        }
    }
}
