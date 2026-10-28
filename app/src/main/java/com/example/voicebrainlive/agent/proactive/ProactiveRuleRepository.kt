package com.example.voicebrainlive.agent.proactive

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ProactiveRulePersistence {
    fun read(): ProactiveSnapshot
    fun compareAndReplace(expectedRevision: Long, next: ProactiveSnapshot): Boolean
}

/** Own once in Application. Local reviewed UI only; no dispatcher, task insertion or native action. */
class ProactiveRuleRepository(private val persistence: ProactiveRulePersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val slotId: (String, Long) -> String = ::occurrenceId) {
    private val mutex = Mutex()
    suspend fun history(): ProactiveSnapshot = access { load() }
    suspend fun createDisabled(config: DailySimulationConfig): ProactiveRule = access {
        val state = load(); val now = time(state); val frozen = config.copy().also(::validateConfig)
        ruleCheck(state.rules.size < 20, RuleFailure.CAPACITY_REACHED)
        val id = ids(); ruleCheck(ruleIdValid(id) && state.rules.none { it.ruleId == id })
        frozen.monitor?.let { ruleCheck(it.expiresAt > now && it.expiresAt - now <= RULE_DAY * 30) }
        val rule = ProactiveRule(id, 1, 1, RuleLifecycle.DISABLED, frozen, null, now, now)
        commit(state, now, state.rules + rule).rules.last()
    }
    suspend fun updateDisabled(ruleId: String, expectedConfigRevision: Long, exactConfig: DailySimulationConfig): ProactiveRule =
        change(ruleId, expectedConfigRevision) { old, _, now ->
            ruleCheck(old.lifecycle in setOf(RuleLifecycle.DISABLED, RuleLifecycle.PAUSED))
            exactConfig.monitor?.let { ruleCheck(it.expiresAt > now && it.expiresAt - old.createdAt <= RULE_DAY * 30) }
            old.copy(configRevision = increment(old.configRevision), stateRevision = increment(old.stateRevision),
                config = exactConfig.copy().also(::validateConfig), lifecycle = RuleLifecycle.DISABLED, reviewedRevision = null,
                updatedAt = now, nextAdvisoryAt = null, lastDecision = RuleDecision.OFF)
        }
    /** Trusted nonexported UI review action only. Exact revision is staleness checking, not a grant. */
    suspend fun enableReviewed(ruleId: String, expectedConfigRevision: Long): ProactiveRule =
        change(ruleId, expectedConfigRevision) { old, state, now ->
            ruleCheck(old.lifecycle in setOf(RuleLifecycle.DISABLED, RuleLifecycle.PAUSED))
            ruleCheck(state.rules.none { it.ruleId != ruleId && it.lifecycle == RuleLifecycle.ACTIVE })
            old.config?.monitor?.let { ruleCheck(it.expiresAt > now) }
            val revision = increment(old.configRevision)
            old.copy(configRevision = revision, stateRevision = increment(old.stateRevision), lifecycle = RuleLifecycle.ACTIVE,
                reviewedRevision = revision, updatedAt = now, nextAdvisoryAt = nextCheck(requireNotNull(old.config), now), lastDecision = RuleDecision.NOT_DUE)
        }
    suspend fun pause(ruleId: String, expectedConfigRevision: Long): ProactiveRule = change(ruleId, expectedConfigRevision) { old, _, now ->
        ruleCheck(old.lifecycle != RuleLifecycle.DELETED)
        old.copy(configRevision = increment(old.configRevision), stateRevision = increment(old.stateRevision),
            lifecycle = RuleLifecycle.PAUSED, reviewedRevision = null, updatedAt = now, nextAdvisoryAt = null, lastDecision = RuleDecision.PAUSED)
    }
    suspend fun delete(ruleId: String, expectedConfigRevision: Long): ProactiveRule = change(ruleId, expectedConfigRevision) { old, _, now ->
        ruleCheck(old.lifecycle != RuleLifecycle.DELETED)
        old.copy(configRevision = increment(old.configRevision), stateRevision = increment(old.stateRevision),
            lifecycle = RuleLifecycle.DELETED, config = null, reviewedRevision = null, updatedAt = now,
            nextAdvisoryAt = null, lastDecision = RuleDecision.DELETED)
    }
    suspend fun evaluateSynthetic(ruleId: String, expectedConfigRevision: Long, expectedStateRevision: Long,
        signal: ClosedSyntheticSignal): EvaluationView = evaluateRule(ruleId, expectedConfigRevision, expectedStateRevision, signal, null)
    internal suspend fun claimMockReview(ruleId: String, revision: Long, stateRevision: Long,
        owner: com.example.voicebrainlive.agent.persistence.PersistentRunOwner): EvaluationView =
        evaluateRule(ruleId, revision, stateRevision, ClosedSyntheticSignal(true), owner)
    private suspend fun evaluateRule(ruleId: String, expectedConfigRevision: Long, expectedStateRevision: Long,
        signal: ClosedSyntheticSignal, monitorOwner: com.example.voicebrainlive.agent.persistence.PersistentRunOwner?): EvaluationView = access {
        val state = load(); val now = time(state); val old = exact(state, ruleId, expectedConfigRevision)
        ruleCheck((old.config?.monitor != null) == (monitorOwner != null))
        monitorOwner?.let { ruleCheck(ruleIdValid(it.processId) && ruleIdValid(it.attemptId)); ruleCheck(requireNotNull(old.config?.monitor).expiresAt > now) }
        ruleCheck(old.stateRevision == expectedStateRevision, RuleFailure.STALE_REVISION)
        if (old.lifecycle != RuleLifecycle.ACTIVE) {
            val decision = when (old.lifecycle) {
            RuleLifecycle.DISABLED -> RuleDecision.OFF; RuleLifecycle.PAUSED -> RuleDecision.PAUSED
            RuleLifecycle.DELETED -> RuleDecision.DELETED; else -> error("unreachable") }
            val checked = old.copy(stateRevision = increment(old.stateRevision), updatedAt = now,
                lastCheckAt = now, nextAdvisoryAt = null, lastDecision = decision)
            val committed = commit(state, now, state.rules.map { if (it.ruleId == ruleId) checked else it })
            return@access EvaluationView(committed.rules.single { it.ruleId == ruleId }, decision)
        }
        val config = requireNotNull(old.config); val local = Instant.ofEpochMilli(now).atZone(ZoneId.of(config.zoneId))
        val day = local.toLocalDate().toEpochDay(); val minute = local.hour * 60 + local.minute
        val duplicate = old.highestConsumedDay?.let { day <= it } == true
        val notDue = minute < config.triggerMinute
        var decision = when {
            duplicate -> RuleDecision.DUPLICATE
            notDue -> RuleDecision.NOT_DUE
            minute >= config.windowEndMinute -> RuleDecision.MISSED_WINDOW
            quiet(config, minute) -> RuleDecision.QUIET_HOURS
            !config.showGenericResults -> RuleDecision.RESULTS_HIDDEN
            state.lastGlobalResultAt?.let { now - it < RULE_DAY } == true -> RuleDecision.QUOTA_REACHED
            state.occurrences.count { it.ruleId == ruleId && it.result != null && it.attemptedAt > now - RULE_DAY * 7 } >= config.maxResultsPerSevenDays -> RuleDecision.QUOTA_REACHED
            !signal.ready -> RuleDecision.CONDITION_FALSE
            else -> RuleDecision.RESULT_READY
        }
        var occurrence: RuleOccurrence? = null
        var changed = old.copy(stateRevision = increment(old.stateRevision), updatedAt = now, lastCheckAt = now,
            lastDecision = decision, nextAdvisoryAt = nextCheck(config, now))
        if (decision in CONSUMED_RULE_DECISIONS) {
            if (state.occurrences.size == 100) {
                decision = RuleDecision.CAPACITY_REACHED
                changed = changed.copy(configRevision = increment(old.configRevision), lifecycle = RuleLifecycle.PAUSED,
                    reviewedRevision = null, highestConsumedDay = maxOf(old.highestConsumedDay ?: day, day),
                    lastAttemptAt = now, nextAdvisoryAt = null, lastDecision = decision)
            } else {
                val id = slotId(ruleId, day); ruleCheck(ruleIdValid(id))
                ruleCheck(state.occurrences.none { it.occurrenceId == id }, RuleFailure.STALE_REVISION)
                occurrence = RuleOccurrence(id, ruleId, old.configRevision, day, now, decision,
                    if (decision == RuleDecision.RESULT_READY) GenericRuleResult.SYNTHETIC_CHECK_IN_READY else null,
                    execution = if (decision == RuleDecision.RESULT_READY && monitorOwner != null)
                        MockReviewExecution(monitorOwner.copy(), 1, com.example.voicebrainlive.agent.persistence.PersistentTaskState.RUNNING, now) else null)
                changed = changed.copy(highestConsumedDay = maxOf(old.highestConsumedDay ?: day, day), lastAttemptAt = now,
                    lastResultAt = if (occurrence.result != null) now else old.lastResultAt)
            }
        }
        val occurrences = state.occurrences + listOfNotNull(occurrence)
        val committed = commit(state, now, state.rules.map { if (it.ruleId == ruleId) changed else it }, occurrences)
        EvaluationView(committed.rules.single { it.ruleId == ruleId }, decision, occurrence)
    }
    suspend fun isCurrentResult(occurrenceId: String): Boolean = access {
        val state = load(); val occurrence = state.occurrences.singleOrNull { it.occurrenceId == occurrenceId }
            ?: return@access false
        val rule = state.rules.single { it.ruleId == occurrence.ruleId }
        occurrence.result != null && !occurrence.revoked && rule.lifecycle == RuleLifecycle.ACTIVE &&
            occurrence.configRevision == rule.configRevision
    }
    internal suspend fun expireMockReview(id: String, revision: Long): ProactiveRule = change(id, revision) { old, _, now ->
        ruleCheck(old.config?.monitor?.expiresAt?.let { now >= it } == true)
        old.copy(configRevision = increment(old.configRevision), stateRevision = increment(old.stateRevision),
            lifecycle = RuleLifecycle.PAUSED, reviewedRevision = null, updatedAt = now,
            nextAdvisoryAt = null, lastDecision = RuleDecision.EXPIRED)
    }
    internal suspend fun claimMockRetry(occurrenceId: String,
        owner: com.example.voicebrainlive.agent.persistence.PersistentRunOwner): EvaluationView = access {
        val state = load(); val now = time(state)
        ruleCheck(ruleIdValid(owner.processId) && ruleIdValid(owner.attemptId))
        val occurrence = state.occurrences.singleOrNull { it.occurrenceId == occurrenceId }
            ?: throw RuleStoreException(RuleFailure.STALE_REVISION)
        val old = state.rules.single { it.ruleId == occurrence.ruleId }; val run = requireNotNull(occurrence.execution)
        val config = requireNotNull(old.config); val monitor = requireNotNull(config.monitor)
        ruleCheck(!occurrence.revoked && old.lifecycle == RuleLifecycle.ACTIVE && old.configRevision == occurrence.configRevision &&
            run.state == com.example.voicebrainlive.agent.persistence.PersistentTaskState.SCHEDULED &&
            run.reason in SAFE_MOCK_RETRIES && run.attempts < monitor.maxAttempts && now < monitor.expiresAt, RuleFailure.STALE_REVISION)
        if (now < requireNotNull(run.retryAt)) return@access EvaluationView(old, RuleDecision.NOT_DUE, occurrence)
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.of(config.zoneId)); val minute = local.hour * 60 + local.minute
        val day = local.toLocalDate().toEpochDay()
        val decision = when {
            day != occurrence.localEpochDay || minute !in config.triggerMinute until config.windowEndMinute -> RuleDecision.MISSED_WINDOW
            quiet(config, minute) -> RuleDecision.QUIET_HOURS
            else -> RuleDecision.RESULT_READY
        }
        val nextRun = if (decision == RuleDecision.RESULT_READY) run.copy(owner = owner.copy(), attempts = run.attempts + 1,
            state = com.example.voicebrainlive.agent.persistence.PersistentTaskState.RUNNING, updatedAt = now,
            reason = MockReviewReason.NONE, retryAt = null) else run.copy(
            state = com.example.voicebrainlive.agent.persistence.PersistentTaskState.BLOCKED, updatedAt = now, retryAt = null,
            reason = if (decision == RuleDecision.QUIET_HOURS) MockReviewReason.QUIET_HOURS else MockReviewReason.MISSED_WINDOW)
        val committed = replaceMockExecution(state, occurrence, nextRun, now)
        EvaluationView(committed.rules.single { it.ruleId == old.ruleId }, decision,
            committed.occurrences.single { it.occurrenceId == occurrenceId })
    }
    internal suspend fun finishMockReview(occurrenceId: String,
        owner: com.example.voicebrainlive.agent.persistence.PersistentRunOwner,
        outcome: com.example.voicebrainlive.agent.persistence.PersistentTaskState, reason: MockReviewReason,
        sourceId: String? = null): RuleOccurrence = access {
        val state = load(); val now = time(state)
        val occurrence = state.occurrences.singleOrNull { it.occurrenceId == occurrenceId }
            ?: throw RuleStoreException(RuleFailure.STALE_REVISION)
        val rule = state.rules.single { it.ruleId == occurrence.ruleId }; val previous = requireNotNull(occurrence.execution)
        ruleCheck(previous.owner == owner && previous.state == com.example.voicebrainlive.agent.persistence.PersistentTaskState.RUNNING &&
            !occurrence.revoked && rule.lifecycle == RuleLifecycle.ACTIVE && occurrence.configRevision == rule.configRevision,
            RuleFailure.STALE_REVISION)
        ruleCheck(outcome in setOf(com.example.voicebrainlive.agent.persistence.PersistentTaskState.COMPLETED,
            com.example.voicebrainlive.agent.persistence.PersistentTaskState.FAILED, com.example.voicebrainlive.agent.persistence.PersistentTaskState.BLOCKED,
            com.example.voicebrainlive.agent.persistence.PersistentTaskState.CANCELLED, com.example.voicebrainlive.agent.persistence.PersistentTaskState.WAITING_FOR_APPROVAL))
        ruleCheck(sourceId == null || ruleIdValid(sourceId))
        val config = requireNotNull(rule.config?.monitor)
        val expired = now >= config.expiresAt
        val retry = !expired && outcome == com.example.voicebrainlive.agent.persistence.PersistentTaskState.FAILED &&
            reason in SAFE_MOCK_RETRIES && previous.attempts < config.maxAttempts && config.expiresAt - now > config.backoffMillis
        val next = previous.copy(state = if (expired) com.example.voicebrainlive.agent.persistence.PersistentTaskState.BLOCKED
            else if (retry) com.example.voicebrainlive.agent.persistence.PersistentTaskState.SCHEDULED else outcome,
            updatedAt = now, reason = if (expired) MockReviewReason.EXPIRED else reason,
            retryAt = if (retry) now + config.backoffMillis else null, sourceId = sourceId)
        replaceMockExecution(state, occurrence, next, now).occurrences.single { it.occurrenceId == occurrenceId }
    }
    internal suspend fun recoverMockReviews(processId: String): ProactiveSnapshot = access {
        ruleCheck(ruleIdValid(processId)); val state = load(); val now = time(state)
        val affected = state.occurrences.filter { it.execution?.let { run -> run.owner.processId != processId &&
            run.state == com.example.voicebrainlive.agent.persistence.PersistentTaskState.RUNNING } == true }
        if (affected.isEmpty()) return@access state
        val ids = affected.map { it.occurrenceId }.toSet(); val rules = affected.map { it.ruleId }.toSet()
        commit(state, now, state.rules.map { if (it.ruleId in rules) it.copy(stateRevision = increment(it.stateRevision), updatedAt = now,
            nextAdvisoryAt = if (it.lifecycle == RuleLifecycle.ACTIVE) nextCheck(requireNotNull(it.config), now) else null) else it },
            state.occurrences.map { item -> if (item.occurrenceId !in ids) item else item.copy(execution = requireNotNull(item.execution).copy(
                state = com.example.voicebrainlive.agent.persistence.PersistentTaskState.BLOCKED,
                updatedAt = now, reason = MockReviewReason.PROCESS_RESTART, retryAt = null)) })
    }
    private fun replaceMockExecution(state: ProactiveSnapshot, occurrence: RuleOccurrence, run: MockReviewExecution, now: Long): ProactiveSnapshot {
        val old = state.rules.single { it.ruleId == occurrence.ruleId }
        val changed = old.copy(stateRevision = increment(old.stateRevision), updatedAt = now, lastCheckAt = now,
            nextAdvisoryAt = if (old.lifecycle == RuleLifecycle.ACTIVE) run.retryAt ?: nextCheck(requireNotNull(old.config), now) else null)
        return commit(state, now, state.rules.map { if (it.ruleId == old.ruleId) changed else it },
            state.occurrences.map { if (it.occurrenceId == occurrence.occurrenceId) it.copy(execution = run) else it })
    }
    private suspend fun change(id: String, revision: Long,
        update: (ProactiveRule, ProactiveSnapshot, Long) -> ProactiveRule): ProactiveRule = access {
        val state = load(); val now = time(state); val old = exact(state, id, revision)
        val changed = update(old, state, now)
        val occurrences = state.occurrences.map { item -> if (item.ruleId != id) item else item.copy(revoked = true,
            execution = item.execution?.let { run -> if (run.state in setOf(com.example.voicebrainlive.agent.persistence.PersistentTaskState.RUNNING,
                com.example.voicebrainlive.agent.persistence.PersistentTaskState.SCHEDULED)) run.copy(
                    state = com.example.voicebrainlive.agent.persistence.PersistentTaskState.CANCELLED, retryAt = null,
                    updatedAt = now, reason = if (changed.lastDecision == RuleDecision.EXPIRED) MockReviewReason.EXPIRED else MockReviewReason.CONFIG_CHANGED) else run }) }
        commit(state, now, state.rules.map { if (it.ruleId == id) changed else it }, occurrences).rules.single { it.ruleId == id }
    }
    private fun load() = freezeRules(persistence.read())
    private fun time(state: ProactiveSnapshot) = clock().also {
        ruleCheck(it in state.updatedAt..Long.MAX_VALUE - RULE_DAY * 8, RuleFailure.CLOCK_ANOMALY)
    }
    private fun exact(state: ProactiveSnapshot, id: String, revision: Long): ProactiveRule {
        ruleCheck(ruleIdValid(id))
        return state.rules.singleOrNull { it.ruleId == id && it.configRevision == revision }
            ?: throw RuleStoreException(RuleFailure.STALE_REVISION)
    }
    private fun increment(value: Long): Long { ruleCheck(value < Long.MAX_VALUE, RuleFailure.CAPACITY_REACHED); return value + 1 }
    private fun commit(old: ProactiveSnapshot, now: Long, rules: List<ProactiveRule>, occurrences: List<RuleOccurrence> = old.occurrences): ProactiveSnapshot {
        val next = freezeRules(ProactiveSnapshot(1, increment(old.revision), now,
            occurrences.filter { it.result != null }.maxOfOrNull { it.attemptedAt }, rules, occurrences))
        ruleCheck(persistence.compareAndReplace(old.revision, next), RuleFailure.STALE_REVISION)
        return next
    }
    private suspend fun <T> access(block: () -> T): T = withContext(io) { mutex.withLock {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: RuleStoreException) { throw safe }
        catch (_: Exception) { throw RuleStoreException(RuleFailure.STORAGE_UNAVAILABLE) }
    } }
    companion object {
        private fun quiet(config: DailySimulationConfig, minute: Int): Boolean {
            val start = config.quietStartMinute ?: return false; val end = requireNotNull(config.quietEndMinute)
            return if (start < end) minute in start until end else minute >= start || minute < end
        }
        private fun nextCheck(config: DailySimulationConfig, now: Long): Long {
            val zone = ZoneId.of(config.zoneId); val local = Instant.ofEpochMilli(now).atZone(zone)
            val time = java.time.LocalTime.of(config.triggerMinute / 60, config.triggerMinute % 60)
            val today = local.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
            return if (today > now) today else local.toLocalDate().plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        private fun occurrenceId(ruleId: String, day: Long): String = "pr_" + MessageDigest.getInstance("SHA-256")
            .digest("$ruleId/$day".toByteArray(Charsets.UTF_8)).take(20).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
