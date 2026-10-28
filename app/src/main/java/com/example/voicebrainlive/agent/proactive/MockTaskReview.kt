package com.example.voicebrainlive.agent.proactive

import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.brain.BrainFailure
import com.example.voicebrainlive.agent.notion.*
import com.example.voicebrainlive.agent.persistence.PersistentRunOwner
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Explicit user configuration, never an LLM instruction or action grant. Synthetic profiles only. */
data class MockTaskReviewConfig(val objective: String, val profileId: String, val profileRevision: Long,
    val expiresAt: Long, val maxAttempts: Int = 1, val backoffMillis: Long = 60_000) {
    override fun toString() = "MockTaskReviewConfig(objective=<redacted>, mode=HOST_MOCK_ONLY)"
}
enum class MockReviewReason { NONE, NETWORK, TIMEOUT, RATE_LIMITED, AUTHENTICATION, PERMISSION,
    STALE_SOURCE, MEMORY_UNAVAILABLE, APPROVAL_UNAVAILABLE, READ_FAILED, USER_CANCELLED,
    CONFIG_CHANGED, PROCESS_RESTART, EXPIRED, QUIET_HOURS, MISSED_WINDOW }
/** Ownership metadata only. PersistentRunOwner is NOT an approval capability. No briefing text is stored. */
data class MockReviewExecution(val owner: PersistentRunOwner, val attempts: Int, val state: PersistentTaskState,
    val updatedAt: Long, val reason: MockReviewReason = MockReviewReason.NONE,
    val retryAt: Long? = null, val sourceId: String? = null)
internal fun validateMockConfig(config: MockTaskReviewConfig) {
    ruleCheck(config.objective.isNotBlank() && config.objective.length <= 2_000 && !config.objective.contains('\u0000'))
    ruleCheck(ruleIdValid(config.profileId) && config.profileRevision > 0 && config.expiresAt > 0 &&
        config.maxAttempts in 1..2 && config.backoffMillis in 60_000..3_600_000)
}
internal fun validateMockExecution(snapshot: ProactiveSnapshot, rule: ProactiveRule, occurrence: RuleOccurrence) {
    val value = occurrence.execution ?: return
    ruleCheck(ruleIdValid(value.owner.processId) && ruleIdValid(value.owner.attemptId) && value.attempts in 1..2 &&
        value.updatedAt in occurrence.attemptedAt..snapshot.updatedAt && (value.sourceId == null || ruleIdValid(value.sourceId)))
    ruleCheck(value.state in setOf(PersistentTaskState.RUNNING, PersistentTaskState.SCHEDULED, PersistentTaskState.COMPLETED,
        PersistentTaskState.FAILED, PersistentTaskState.BLOCKED, PersistentTaskState.CANCELLED, PersistentTaskState.WAITING_FOR_APPROVAL))
    ruleCheck((value.state == PersistentTaskState.SCHEDULED) == (value.retryAt != null))
    if (value.state in setOf(PersistentTaskState.RUNNING, PersistentTaskState.COMPLETED)) ruleCheck(value.reason == MockReviewReason.NONE)
    value.retryAt?.let { ruleCheck(it > value.updatedAt && value.reason in SAFE_MOCK_RETRIES) }
    if (value.state in setOf(PersistentTaskState.RUNNING, PersistentTaskState.SCHEDULED)) {
        ruleCheck(!occurrence.revoked && occurrence.configRevision == rule.configRevision && rule.lifecycle == RuleLifecycle.ACTIVE &&
            rule.config?.monitor != null && value.attempts <= requireNotNull(rule.config.monitor).maxAttempts)
    }
}
internal val SAFE_MOCK_RETRIES = setOf(MockReviewReason.NETWORK, MockReviewReason.TIMEOUT, MockReviewReason.RATE_LIMITED)

/** Fake scheduling seam; Android WorkManager's closed LOCAL_STATUS worker is deliberately unchanged. */
interface MockReviewWakeups {
    suspend fun enqueue(ruleId: String, configRevision: Long, eligibleAt: Long)
    suspend fun cancel(ruleId: String)
}
fun interface MockReviewWork {
    /** Trusted host adapter delegates to ReadOnlyTaskReview / existing AgentRunner. No effect executor. */
    suspend fun review(config: MockTaskReviewConfig, pin: ConnectionReadPin, current: () -> Boolean): ReadOnlyReviewResult
}
data class MockReviewDispatch(val decision: RuleDecision, val execution: MockReviewExecution? = null,
    val briefing: ReadOnlyReviewResult? = null) {
    override fun toString() = "MockReviewDispatch($decision, state=${execution?.state}, briefing=<redacted>)"
}

/** Scheduling/ownership coordinator, NOT a tool execution engine. No production factory or worker registration.
 * Default work/queue are unavailable; tests explicitly inject fake providers and fake clocks.
 */
class MockReviewCoordinator(private val rules: ProactiveRuleRepository, private val profiles: NotionProfilesRepository,
    private val work: MockReviewWork? = null, private val wakeups: MockReviewWakeups? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val processId: String = UUID.randomUUID().toString()) {
    private data class Running(val ruleId: String, val job: Job, val current: AtomicBoolean)
    private val running = AtomicReference<Running?>()
    private val mutex = Mutex()
    init { ruleCheck(ruleIdValid(processId)) }
    suspend fun enableReviewed(id: String, revision: Long): ProactiveRule {
        ruleCheck(work != null && wakeups != null)
        val rule = rules.history().rules.singleOrNull { it.ruleId == id && it.configRevision == revision }
            ?: throw RuleStoreException(RuleFailure.STALE_REVISION)
        ruleCheck(rule.config?.monitor != null)
        val enabled = rules.enableReviewed(id, revision)
        queue(enabled); return enabled
    }
    private suspend fun queue(rule: ProactiveRule) {
        if (rule.lifecycle != RuleLifecycle.ACTIVE || rule.nextAdvisoryAt == null) return
        if (rule.config?.monitor?.expiresAt?.let { clock() >= it } == true) { rules.expireMockReview(rule.ruleId, rule.configRevision); return }
        try { requireNotNull(wakeups).enqueue(rule.ruleId, rule.configRevision, rule.nextAdvisoryAt) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            rules.pause(rule.ruleId, rule.configRevision)
            throw RuleStoreException(RuleFailure.STORAGE_UNAVAILABLE)
        }
    }
    suspend fun recoverInterrupted(): ProactiveSnapshot = rules.recoverMockReviews(processId)
    suspend fun pause(id: String, revision: Long): ProactiveRule {
        revoke(id); val result = rules.pause(id, revision); wakeups?.cancel(id); return result
    }
    suspend fun cancel(id: String, revision: Long): ProactiveRule {
        revoke(id); val result = rules.delete(id, revision); wakeups?.cancel(id); return result
    }
    private fun revoke(id: String) {
        running.get()?.takeIf { it.ruleId == id }?.let { it.current.set(false); it.job.cancel() }
    }
    suspend fun trigger(id: String, revision: Long): MockReviewDispatch {
        ruleCheck(work != null && wakeups != null)
        if (!mutex.tryLock()) return MockReviewDispatch(RuleDecision.DUPLICATE)
        var claim: RuleOccurrence? = null
        var active: Running? = null
        try {
            val snapshot = rules.history()
            val rule = snapshot.rules.singleOrNull { it.ruleId == id && it.configRevision == revision }
                ?: throw RuleStoreException(RuleFailure.STALE_REVISION)
            val config = rule.config?.monitor ?: throw RuleStoreException(RuleFailure.INVALID_REQUEST)
            if (clock() >= config.expiresAt) { rules.expireMockReview(id, revision); return MockReviewDispatch(RuleDecision.EXPIRED) }
            val owner = PersistentRunOwner(processId, UUID.randomUUID().toString())
            val retry = snapshot.occurrences.lastOrNull { it.ruleId == id && it.configRevision == revision &&
                !it.revoked && it.execution?.state == PersistentTaskState.SCHEDULED }
            val evaluated = if (retry == null) rules.claimMockReview(id, revision, rule.stateRevision, owner)
                else rules.claimMockRetry(retry.occurrenceId, owner)
            val occurrence = evaluated.occurrence
            if (occurrence?.execution?.state != PersistentTaskState.RUNNING) {
                queue(evaluated.rule); return MockReviewDispatch(evaluated.decision, occurrence?.execution)
            }
            claim = occurrence
            val current = AtomicBoolean(true)
            val live = Running(id, currentCoroutineContext().job, current)
            active = live; running.set(live)
            var briefing: ReadOnlyReviewResult? = null
            val finished = try {
                val pin = profiles.pin(config.profileId, config.profileRevision)
                if (profiles.views().selectedProfileId != pin.profileId) throw ProfileStoreException(ProfileFailure.STALE_BINDING)
                val result = withTimeout(20_000) {
                    val operation = currentCoroutineContext().job
                    requireNotNull(work).review(config, pin) { current.get() && live.job.isActive && operation.isActive }
                }
                currentCoroutineContext().ensureActive()
                if (!current.get() || result.provenance.pin != pin || !profiles.isCurrent(pin) || profiles.views().selectedProfileId != pin.profileId)
                    throw ProfileStoreException(ProfileFailure.STALE_BINDING)
                val approval = result.snapshot.steps.any { it.errorCode == "APPROVAL_ADAPTER_NOT_CONNECTED" }
                val state = when (result.snapshot.status) {
                    AgentStatus.SUCCEEDED, AgentStatus.REPORTED -> PersistentTaskState.COMPLETED
                    AgentStatus.WAITING_FOR_APPROVAL -> PersistentTaskState.WAITING_FOR_APPROVAL
                    AgentStatus.CANCELLED -> PersistentTaskState.CANCELLED
                    AgentStatus.FAILED -> PersistentTaskState.FAILED
                    else -> PersistentTaskState.BLOCKED
                }
                val reason = if (state == PersistentTaskState.COMPLETED) MockReviewReason.NONE
                    else if (approval) MockReviewReason.APPROVAL_UNAVAILABLE else MockReviewReason.READ_FAILED
                val committed = rules.finishMockReview(occurrence.occurrenceId, owner, state, reason, result.provenance.sourceId)
                if (committed.execution?.state == state && reason in setOf(MockReviewReason.NONE, MockReviewReason.APPROVAL_UNAVAILABLE)) briefing = result
                committed
            } catch (timeout: TimeoutCancellationException) {
                current.set(false); rules.finishMockReview(occurrence.occurrenceId, owner, PersistentTaskState.FAILED, MockReviewReason.TIMEOUT)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ProfileStoreException) {
                val reason = when (failure.failure) {
                    ProfileFailure.NETWORK_FAILURE -> MockReviewReason.NETWORK
                    ProfileFailure.TIMEOUT -> MockReviewReason.TIMEOUT
                    ProfileFailure.RATE_LIMITED -> MockReviewReason.RATE_LIMITED
                    ProfileFailure.AUTHENTICATION -> MockReviewReason.AUTHENTICATION
                    ProfileFailure.ACCESS_UNAVAILABLE -> MockReviewReason.PERMISSION
                    else -> MockReviewReason.STALE_SOURCE
                }
                rules.finishMockReview(occurrence.occurrenceId, owner,
                    if (reason in SAFE_MOCK_RETRIES) PersistentTaskState.FAILED else PersistentTaskState.BLOCKED, reason)
            } catch (_: BrainFailure) {
                rules.finishMockReview(occurrence.occurrenceId, owner, PersistentTaskState.BLOCKED, MockReviewReason.MEMORY_UNAVAILABLE)
            } catch (safe: RuleStoreException) { throw safe }
            catch (_: Exception) {
                rules.finishMockReview(occurrence.occurrenceId, owner, PersistentTaskState.BLOCKED, MockReviewReason.READ_FAILED)
            }
            current.set(false)
            val next = rules.history().rules.single { it.ruleId == id }
            queue(next)
            return MockReviewDispatch(evaluated.decision, finished.execution, briefing)
        } catch (cancelled: CancellationException) {
            active?.current?.set(false)
            claim?.let { item -> withContext(NonCancellable) {
                runCatching { rules.finishMockReview(item.occurrenceId, requireNotNull(item.execution).owner,
                    PersistentTaskState.CANCELLED, MockReviewReason.USER_CANCELLED) }
            } }
            throw cancelled
        } finally { active?.current?.set(false); if (active != null) running.compareAndSet(active, null); mutex.unlock() }
    }
}
