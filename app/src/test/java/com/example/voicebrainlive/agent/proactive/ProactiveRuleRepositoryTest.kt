package com.example.voicebrainlive.agent.proactive

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Prepared only: synthetic dates/booleans and local metadata; no Android/provider/scheduler. */
class ProactiveRuleRepositoryTest {
    private class Store : ProactiveRulePersistence {
        var state = ProactiveSnapshot(); var writes = 0; var fail = false; var stale = false
        override fun read() = state
        override fun compareAndReplace(expectedRevision: Long, next: ProactiveSnapshot): Boolean {
            if (fail) throw IOException("PRIVATE_STORAGE_DETAIL")
            if (stale || state.revision != expectedRevision) return false
            state = ProactiveRuleCodec.decode(ProactiveRuleCodec.encode(next)); writes++; return true
        }
    }
    private class Fixture {
        var now = Instant.parse("2026-10-15T10:05:00Z").toEpochMilli()
        val store = Store(); val ids = AtomicInteger()
        fun repository(slot: ((String, Long) -> String)? = null): ProactiveRuleRepository = if (slot == null)
            ProactiveRuleRepository(store, { now }, { "rule_${ids.incrementAndGet()}" }, Dispatchers.Unconfined)
        else ProactiveRuleRepository(store, { now }, { "rule_${ids.incrementAndGet()}" }, Dispatchers.Unconfined, slot)
        val repo = repository()
        suspend fun enabled(config: DailySimulationConfig = DailySimulationConfig("UTC", 600, 660)): ProactiveRule {
            val rule = repo.createDisabled(config); return repo.enableReviewed(rule.ruleId, rule.configRevision)
        }
        suspend fun evaluate(rule: ProactiveRule, ready: Boolean = true) = repo.evaluateSynthetic(rule.ruleId,
            rule.configRevision, rule.stateRevision, ClosedSyntheticSignal(ready))
    }
    private suspend fun rejects(expected: RuleFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") } catch (safe: RuleStoreException) {
            assertEquals(expected, safe.failure); assertFalse(safe.message.orEmpty().contains("PRIVATE"))
        }
    }
    @Test fun defaultOffNeedsExactReviewedConfigurationAndOnlyOneRuleMayBeActive() = runBlocking {
        val f = Fixture(); val off = f.repo.createDisabled(DailySimulationConfig("UTC", 600, 660))
        assertEquals(RuleLifecycle.DISABLED, off.lifecycle); assertNull(off.reviewedRevision)
        assertEquals(RuleDecision.OFF, f.evaluate(off).decision); assertTrue(f.repo.history().occurrences.isEmpty())
        val active = f.repo.enableReviewed(off.ruleId, off.configRevision)
        assertEquals(active.configRevision, active.reviewedRevision)
        rejects(RuleFailure.STALE_REVISION) { f.repo.enableReviewed(off.ruleId, off.configRevision) }
        val other = f.repo.createDisabled(DailySimulationConfig("UTC", 700, 760))
        rejects(RuleFailure.INVALID_REQUEST) { f.repo.enableReviewed(other.ruleId, other.configRevision) }
    }
    @Test fun editingActiveRuleFailsAndPausedEditsNeedFreshReviewWithoutResettingDedupe() = runBlocking {
        val f = Fixture(); val active = f.enabled(); val first = f.evaluate(active)
        rejects(RuleFailure.INVALID_REQUEST) { f.repo.updateDisabled(active.ruleId, active.configRevision, DailySimulationConfig("UTC", 601, 660)) }
        val paused = f.repo.pause(active.ruleId, active.configRevision)
        val edited = f.repo.updateDisabled(paused.ruleId, paused.configRevision, DailySimulationConfig("UTC", 601, 660))
        assertEquals(RuleLifecycle.DISABLED, edited.lifecycle); assertNull(edited.reviewedRevision)
        val resumed = f.repo.enableReviewed(edited.ruleId, edited.configRevision)
        assertEquals(first.rule.highestConsumedDay, resumed.highestConsumedDay)
        assertEquals(RuleDecision.DUPLICATE, f.evaluate(resumed).decision)
    }
    @Test fun syntheticTrueCreatesOneAtomicGenericPersistedResultAndRestartOnlyReadsIt() = runBlocking {
        val f = Fixture(); val active = f.enabled(); val result = f.evaluate(active)
        assertEquals(RuleDecision.RESULT_READY, result.decision)
        assertEquals(GenericRuleResult.SYNTHETIC_CHECK_IN_READY, result.occurrence?.result)
        val restarted = f.repository().history()
        assertEquals(1, restarted.occurrences.size); assertEquals(result.occurrence, restarted.occurrences.single())
        assertEquals(result.rule, restarted.rules.single())
        assertEquals(RuleDecision.DUPLICATE, f.evaluate(result.rule).decision)
        assertEquals(1, f.repo.history().occurrences.size)
    }
    @Test fun writeFailureExposesNoNewOccurrenceAndCannotClaimSuccess() = runBlocking {
        val f = Fixture(); val active = f.enabled(); val before = f.store.state; f.store.fail = true
        rejects(RuleFailure.STORAGE_UNAVAILABLE) { f.evaluate(active) }
        assertEquals(before, f.store.state); assertTrue(f.store.state.occurrences.isEmpty())
    }
    @Test fun competingOldEvaluationsAdmitOneResultAndRejectStaleState() = runBlocking {
        val f = Fixture(); val active = f.enabled()
        suspend fun evaluate(): Boolean = try { f.evaluate(active); true } catch (safe: RuleStoreException) {
            assertEquals(RuleFailure.STALE_REVISION, safe.failure); false
        }
        val first = async { evaluate() }; val second = async { evaluate() }
        assertEquals(1, listOf(first.await(), second.await()).count { it })
        assertEquals(1, f.repo.history().occurrences.size)
    }
    @Test fun staleGlobalCasPreservesPriorAtomicSnapshot() = runBlocking {
        val f = Fixture(); val active = f.enabled(); val before = f.store.state; f.store.stale = true
        rejects(RuleFailure.STALE_REVISION) { f.evaluate(active) }; assertEquals(before, f.store.state)
    }
    @Test fun pauseResumeAndDeletionKeepClaimsAndDoNotRestoreOldPresentableResults() = runBlocking {
        val f = Fixture(); val active = f.enabled(); val first = f.evaluate(active); val id = first.occurrence!!.occurrenceId
        assertTrue(f.repo.isCurrentResult(id))
        val paused = f.repo.pause(active.ruleId, active.configRevision); assertFalse(f.repo.isCurrentResult(id))
        assertEquals(RuleDecision.PAUSED, f.evaluate(paused).decision)
        val resumed = f.repo.enableReviewed(paused.ruleId, paused.configRevision)
        assertEquals(RuleDecision.DUPLICATE, f.evaluate(resumed).decision); assertFalse(f.repo.isCurrentResult(id))
        val deleted = f.repo.delete(resumed.ruleId, resumed.configRevision)
        assertNull(deleted.config); assertEquals(first.rule.highestConsumedDay, deleted.highestConsumedDay)
        rejects(RuleFailure.INVALID_REQUEST) { f.repo.enableReviewed(deleted.ruleId, deleted.configRevision) }
        assertTrue(f.repository().history().occurrences.single().revoked)
    }
    @Test fun recreationCannotResetGlobalTwentyFourHourResultQuota() = runBlocking {
        val f = Fixture(); val active = f.enabled(); f.evaluate(active); f.repo.delete(active.ruleId, active.configRevision)
        f.now += 60_000
        val replacement = f.enabled(); val result = f.evaluate(replacement)
        assertEquals(RuleDecision.QUOTA_REACHED, result.decision); assertNull(result.occurrence?.result)
        assertEquals(1, f.repo.history().occurrences.count { it.result != null })
    }
    @Test fun falseQuietAndHiddenSlotsAreConsumedRatherThanRetried() = runBlocking {
        for (config in listOf(DailySimulationConfig("UTC", 600, 660),
            DailySimulationConfig("UTC", 600, 660, 590, 650), DailySimulationConfig("UTC", 600, 660, showGenericResults = false))) {
            val f = Fixture(); val active = f.enabled(config); val first = f.evaluate(active, false)
            assertTrue(first.decision in setOf(RuleDecision.CONDITION_FALSE, RuleDecision.QUIET_HOURS, RuleDecision.RESULTS_HIDDEN))
            assertEquals(RuleDecision.DUPLICATE, f.evaluate(first.rule, true).decision)
            assertEquals(1, f.repo.history().occurrences.size)
        }
    }
    @Test fun quietWindowCrossingMidnightIsEnforced() = runBlocking {
        val f = Fixture(); f.now = Instant.parse("2026-10-15T23:10:00Z").toEpochMilli()
        val rule = f.enabled(DailySimulationConfig("UTC", 1380, 1440, 1320, 420))
        assertEquals(RuleDecision.QUIET_HOURS, f.evaluate(rule).decision)
    }
    @Test fun notDueDoesNotConsumeButMissedWindowConsumesWithoutCatchUp() = runBlocking {
        val f = Fixture(); f.now = Instant.parse("2026-10-15T09:00:00Z").toEpochMilli()
        val rule = f.enabled(); val early = f.evaluate(rule)
        assertEquals(RuleDecision.NOT_DUE, early.decision); assertTrue(f.repo.history().occurrences.isEmpty())
        f.now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        val missed = f.evaluate(early.rule)
        assertEquals(RuleDecision.MISSED_WINDOW, missed.decision)
        assertEquals(RuleDecision.DUPLICATE, f.evaluate(missed.rule).decision)
    }
    @Test fun distinctDaysHaveFreshOccurrenceIdsAndRollingSevenDayQuotaDoesNotRetry() = runBlocking {
        val f = Fixture(); val active = f.enabled(DailySimulationConfig("UTC", 600, 660, maxResultsPerSevenDays = 1))
        val first = f.evaluate(active); f.now += RULE_DAY
        val capped = f.evaluate(first.rule)
        assertEquals(RuleDecision.QUOTA_REACHED, capped.decision)
        assertNotEquals(first.occurrence!!.occurrenceId, capped.occurrence!!.occurrenceId)
        f.now += RULE_DAY * 7
        val later = f.evaluate(capped.rule); assertEquals(RuleDecision.RESULT_READY, later.decision)
        assertEquals(3, f.repo.history().occurrences.size)
    }
    @Test fun timezoneChangeRequiresReviewAndCannotBypassGlobalQuota() = runBlocking {
        val f = Fixture(); val active = f.enabled(); f.evaluate(active)
        val paused = f.repo.pause(active.ruleId, active.configRevision)
        val edit = f.repo.updateDisabled(paused.ruleId, paused.configRevision, DailySimulationConfig("Pacific/Kiritimati", 0, 1440))
        val reviewed = f.repo.enableReviewed(edit.ruleId, edit.configRevision)
        assertEquals(RuleDecision.QUOTA_REACHED, f.evaluate(reviewed).decision)
    }
    @Test fun rollbackPreservesStateAndForwardJumpSkipsUnobservedDates() = runBlocking {
        val f = Fixture(); val active = f.enabled(); val first = f.evaluate(active); val before = f.store.state
        f.now -= 1; rejects(RuleFailure.CLOCK_ANOMALY) { f.evaluate(first.rule) }; assertEquals(before, f.store.state)
        f.now += RULE_DAY * 20 + 1
        assertEquals(RuleDecision.RESULT_READY, f.evaluate(first.rule).decision)
        assertEquals(2, f.repo.history().occurrences.size)
    }
    @Test fun identityCapacityIncludesDeletedTombstonesWithoutEviction() = runBlocking {
        val f = Fixture()
        repeat(20) { val rule = f.repo.createDisabled(DailySimulationConfig("UTC", 600, 660)); f.repo.delete(rule.ruleId, rule.configRevision) }
        val before = f.store.state
        rejects(RuleFailure.CAPACITY_REACHED) { f.repo.createDisabled(DailySimulationConfig("UTC", 600, 660)) }
        assertEquals(before, f.store.state); assertEquals(20, before.rules.size)
    }
    @Test fun occurrenceCapacityPausesWithoutEvictingHistoryOrForgettingDayHighWater() = runBlocking {
        val f = Fixture(); var rule = f.enabled()
        repeat(100) { rule = f.evaluate(rule, false).rule; f.now += RULE_DAY }
        val result = f.evaluate(rule)
        assertEquals(RuleDecision.CAPACITY_REACHED, result.decision); assertEquals(RuleLifecycle.PAUSED, result.rule.lifecycle)
        assertEquals(100, f.repo.history().occurrences.size)
        assertEquals(Instant.ofEpochMilli(f.now).atZone(ZoneId.of("UTC")).toLocalDate().toEpochDay(), result.rule.highestConsumedDay)
    }
    @Test fun injectedOccurrenceCollisionCannotMergeDifferentDays() = runBlocking {
        val f = Fixture(); val repo = f.repository { _, _ -> "same_occurrence" }
        val off = repo.createDisabled(DailySimulationConfig("UTC", 600, 660)); val active = repo.enableReviewed(off.ruleId, off.configRevision)
        val first = repo.evaluateSynthetic(active.ruleId, active.configRevision, active.stateRevision, ClosedSyntheticSignal(true))
        f.now += RULE_DAY; val before = f.store.state
        rejects(RuleFailure.STALE_REVISION) { repo.evaluateSynthetic(first.rule.ruleId, first.rule.configRevision, first.rule.stateRevision, ClosedSyntheticSignal(true)) }
        assertEquals(before, f.store.state)
    }
    @Test fun invalidZoneWindowAndQuietInputsDoNotCreateRecords() = runBlocking {
        val f = Fixture()
        for (config in listOf(DailySimulationConfig("invalid-zone", 600, 660), DailySimulationConfig("UTC", 600, 600),
            DailySimulationConfig("UTC", 600, 660, 100, null), DailySimulationConfig("UTC", 600, 660, maxResultsPerSevenDays = 4)))
            rejects(RuleFailure.INVALID_REQUEST) { f.repo.createDisabled(config) }
        assertEquals(0, f.store.writes); assertTrue(f.repo.history().rules.isEmpty())
    }
    @Test fun dstOverlapCannotAdmitTwoOccurrencesAndGapIsMissed() = runBlocking {
        val zone = ZoneId.of("America/New_York"); val transitions = mutableListOf<java.time.zone.ZoneOffsetTransition>()
        var cursor = Instant.parse("2026-01-01T00:00:00Z")
        repeat(3) { val next = zone.rules.nextTransition(cursor)!!; transitions += next; cursor = next.instant.plusSeconds(1) }
        val gap = transitions.first { it.isGap }; val f = Fixture(); f.now = gap.instant.plusSeconds(15 * 60).toEpochMilli()
        val gapRule = f.enabled(DailySimulationConfig(zone.id, 135, 165))
        assertEquals(RuleDecision.MISSED_WINDOW, f.evaluate(gapRule).decision)
        val overlap = transitions.first { it.isOverlap }; val second = Fixture()
        val date = overlap.dateTimeAfter.toLocalDate(); val local = date.atTime(1, 30); val offsets = zone.rules.getValidOffsets(local)
        assertEquals(2, offsets.size); second.now = local.toInstant(offsets.first()).toEpochMilli()
        val active = second.enabled(DailySimulationConfig(zone.id, 75, 105)); val first = second.evaluate(active)
        second.now = local.toInstant(offsets.last()).toEpochMilli()
        assertEquals(RuleDecision.DUPLICATE, second.evaluate(first.rule).decision)
    }
}
