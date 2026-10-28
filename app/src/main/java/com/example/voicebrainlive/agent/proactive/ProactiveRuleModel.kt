package com.example.voicebrainlive.agent.proactive

import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

enum class RuleFailure { INVALID_REQUEST, STALE_REVISION, CLOCK_ANOMALY, CAPACITY_REACHED,
    KEY_UNAVAILABLE, STORAGE_UNAVAILABLE, DATA_UNREADABLE, UNSUPPORTED_SCHEMA, RECOVERY_REQUIRED }
class RuleStoreException(val failure: RuleFailure) : Exception("Proactive rule: ${failure.name}")
enum class RuleLifecycle { DISABLED, ACTIVE, PAUSED, DELETED }
enum class RuleKind { DAILY_LOCAL_CHECK_IN }
enum class RuleMode { MANUAL_SYNTHETIC }
enum class RuleDecision { NOT_DUE, OFF, PAUSED, DELETED, DUPLICATE, MISSED_WINDOW, QUIET_HOURS,
    RESULTS_HIDDEN, CONDITION_FALSE, QUOTA_REACHED, RESULT_READY, CAPACITY_REACHED, EXPIRED }
enum class GenericRuleResult { SYNTHETIC_CHECK_IN_READY }

data class DailySimulationConfig(val zoneId: String, val triggerMinute: Int, val windowEndMinute: Int,
    val quietStartMinute: Int? = null, val quietEndMinute: Int? = null,
    val showGenericResults: Boolean = true, val maxResultsPerSevenDays: Int = 3,
    val kind: RuleKind = RuleKind.DAILY_LOCAL_CHECK_IN, val mode: RuleMode = RuleMode.MANUAL_SYNTHETIC,
    val monitor: MockTaskReviewConfig? = null)
data class ClosedSyntheticSignal(val ready: Boolean)
data class ProactiveRule(val ruleId: String, val configRevision: Long, val stateRevision: Long,
    val lifecycle: RuleLifecycle, val config: DailySimulationConfig?, val reviewedRevision: Long?,
    val createdAt: Long, val updatedAt: Long, val highestConsumedDay: Long? = null,
    val lastCheckAt: Long? = null, val lastAttemptAt: Long? = null, val lastResultAt: Long? = null,
    val nextAdvisoryAt: Long? = null, val lastDecision: RuleDecision = RuleDecision.OFF)
data class RuleOccurrence(val occurrenceId: String, val ruleId: String, val configRevision: Long,
    val localEpochDay: Long, val attemptedAt: Long, val decision: RuleDecision,
    val result: GenericRuleResult? = null, val revoked: Boolean = false, val execution: MockReviewExecution? = null)
data class ProactiveSnapshot(val schemaVersion: Int = 1, val revision: Long = 0,
    val updatedAt: Long = 0, val lastGlobalResultAt: Long? = null,
    val rules: List<ProactiveRule> = emptyList(), val occurrences: List<RuleOccurrence> = emptyList())
data class EvaluationView(val rule: ProactiveRule, val decision: RuleDecision, val occurrence: RuleOccurrence? = null)

internal const val RULE_DAY = 86_400_000L
internal val CONSUMED_RULE_DECISIONS = setOf(RuleDecision.MISSED_WINDOW, RuleDecision.QUIET_HOURS,
    RuleDecision.RESULTS_HIDDEN, RuleDecision.CONDITION_FALSE, RuleDecision.QUOTA_REACHED, RuleDecision.RESULT_READY)
internal fun ruleCheck(ok: Boolean, failure: RuleFailure = RuleFailure.INVALID_REQUEST) {
    if (!ok) throw RuleStoreException(failure)
}
internal fun ruleIdValid(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,80}"))
internal fun <T> ruleFrozen(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
internal fun validateConfig(config: DailySimulationConfig) {
    ruleCheck(config.zoneId.length in 1..80)
    try { ZoneId.of(config.zoneId) } catch (_: Exception) { throw RuleStoreException(RuleFailure.INVALID_REQUEST) }
    ruleCheck(config.triggerMinute in 0..1439 && config.windowEndMinute in 1..1440 && config.triggerMinute < config.windowEndMinute)
    ruleCheck((config.quietStartMinute == null) == (config.quietEndMinute == null))
    config.quietStartMinute?.let { start ->
        val end = config.quietEndMinute
        ruleCheck(start in 0..1439 && end != null && end in 0..1439 && start != end)
    }
    ruleCheck(config.maxResultsPerSevenDays in 1..3)
    ruleCheck(config.kind == RuleKind.DAILY_LOCAL_CHECK_IN && config.mode == RuleMode.MANUAL_SYNTHETIC)
    config.monitor?.let(::validateMockConfig)
}
internal fun freezeRules(input: ProactiveSnapshot): ProactiveSnapshot {
    ruleCheck(input.rules.size <= 20 && input.occurrences.size <= 100)
    val frozen = input.copy(rules = ruleFrozen(input.rules.map { it.copy(config = it.config?.copy()) }),
        occurrences = ruleFrozen(input.occurrences.map { it.copy(execution = it.execution?.let { run -> run.copy(owner = run.owner.copy()) }) }))
    validateRules(frozen)
    return frozen
}
internal fun validateRules(snapshot: ProactiveSnapshot) {
    ruleCheck(snapshot.schemaVersion == 1, RuleFailure.UNSUPPORTED_SCHEMA)
    ruleCheck(snapshot.revision >= 0 && snapshot.updatedAt in 0..Long.MAX_VALUE - RULE_DAY * 8)
    ruleCheck(snapshot.rules.size <= 20 && snapshot.occurrences.size <= 100)
    ruleCheck(snapshot.rules.map { it.ruleId }.toSet().size == snapshot.rules.size &&
        snapshot.occurrences.map { it.occurrenceId }.toSet().size == snapshot.occurrences.size &&
        snapshot.occurrences.map { it.ruleId to it.localEpochDay }.toSet().size == snapshot.occurrences.size)
    ruleCheck(snapshot.rules.count { it.lifecycle == RuleLifecycle.ACTIVE } <= 1)
    ruleCheck(snapshot.rules.isEmpty() || snapshot.revision > 0)
    snapshot.rules.forEach { rule ->
        ruleCheck(ruleIdValid(rule.ruleId) && rule.configRevision > 0 && rule.stateRevision > 0 &&
            rule.createdAt in 0..rule.updatedAt && rule.updatedAt <= snapshot.updatedAt)
        ruleCheck((rule.lifecycle == RuleLifecycle.DELETED) == (rule.config == null))
        rule.config?.let(::validateConfig)
        rule.config?.monitor?.let { ruleCheck(it.expiresAt > rule.createdAt && it.expiresAt - rule.createdAt <= RULE_DAY * 30) }
        ruleCheck(if (rule.lifecycle == RuleLifecycle.ACTIVE) rule.reviewedRevision == rule.configRevision else rule.reviewedRevision == null)
        rule.highestConsumedDay?.let { ruleCheck(it in LocalDate.MIN.toEpochDay()..LocalDate.MAX.toEpochDay()) }
        listOf(rule.lastCheckAt, rule.lastAttemptAt, rule.lastResultAt).filterNotNull().forEach { ruleCheck(it in rule.createdAt..rule.updatedAt) }
        rule.nextAdvisoryAt?.let { ruleCheck(rule.lifecycle == RuleLifecycle.ACTIVE && it >= rule.updatedAt) }
        ruleCheck(rule.lastResultAt == snapshot.occurrences.filter { it.ruleId == rule.ruleId && it.result != null }.maxOfOrNull { it.attemptedAt })
    }
    snapshot.occurrences.forEach { occurrence ->
        val rule = snapshot.rules.singleOrNull { it.ruleId == occurrence.ruleId }
            ?: throw RuleStoreException(RuleFailure.INVALID_REQUEST)
        ruleCheck(ruleIdValid(occurrence.occurrenceId) && occurrence.configRevision in 1..rule.configRevision &&
            occurrence.localEpochDay in LocalDate.MIN.toEpochDay()..LocalDate.MAX.toEpochDay() &&
            occurrence.localEpochDay <= (rule.highestConsumedDay ?: Long.MIN_VALUE) &&
            occurrence.attemptedAt in rule.createdAt..snapshot.updatedAt && occurrence.decision in CONSUMED_RULE_DECISIONS)
        ruleCheck((occurrence.decision == RuleDecision.RESULT_READY) == (occurrence.result == GenericRuleResult.SYNTHETIC_CHECK_IN_READY))
        ruleCheck(rule.lifecycle != RuleLifecycle.DELETED || occurrence.revoked)
        validateMockExecution(snapshot, rule, occurrence)
    }
    val last = snapshot.occurrences.filter { it.result != null }.maxOfOrNull { it.attemptedAt }
    ruleCheck(snapshot.lastGlobalResultAt == last)
}
