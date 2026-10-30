package com.example.voicebrainlive.commands

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fake-only macro regression tests. Fake definitions/clock/guard outcomes only; no Android effects. */
class BoundedMacroRunnerTest {
    @Test fun automationRootKeepsNullSecondValueAndNestedMacroKeepsLegacyTargetFallback() = runBlocking {
        val calls = mutableListOf<MacroLeaf>()
        val definitions = mapOf("root" to "set_volume:30;run_macro:child", "child" to "set_volume:40")
        val runner = BoundedMacroRunner(definitions::get, { MacroLeafRisk.SAFE },
            { leaf -> calls += leaf; MacroLeafResult(MacroOutcomeStatus.VERIFIED) }, { 1000L },
            fallbackValueToTarget = false)
        assertEquals(MacroOutcomeStatus.COMPLETED, runner.run("root").status)
        assertNull(calls[0].value)
        assertEquals("30", calls[0].target)
        assertEquals("40", calls[1].target)
        assertEquals("40", calls[1].value)
    }
    private var now = 1_000L
    private val definitions = mutableMapOf<String, String>()
    private val observed = mutableListOf<MacroLeaf>()
    private fun risk(command: String) = when (command) {
        "get_time", "get_date", "run_macro", "save_note", "create_macro", "set_volume" -> MacroLeafRisk.SAFE
        "send_sms", "make_call" -> MacroLeafRisk.SENSITIVE
        else -> MacroLeafRisk.UNKNOWN
    }
    private fun runner(dispatch: suspend (MacroLeaf) -> MacroLeafResult = {
        observed += it; MacroLeafResult(MacroOutcomeStatus.VERIFIED)
    }) = BoundedMacroRunner(definitions::get, ::risk, dispatch, { now })

    @Test fun safeTypedLeavesRunInOrderThroughInjectedGuardDispatcher() = runBlocking {
        definitions["root"] = "get_time;get_date"
        val result = runner().run("root")
        assertEquals(MacroOutcomeStatus.COMPLETED, result.status)
        assertEquals(listOf("get_time", "get_date"), observed.map { it.command })
        assertEquals(2, result.calls)
    }

    @Test fun selfAndMutualCyclesAreRejectedBeforeAnyDispatch() = runBlocking {
        definitions["root"] = "get_time;run_macro:root"
        assertEquals("MACRO_CYCLE", runner().run("root").reason)
        definitions["root"] = "get_time;run_macro:other"
        definitions["other"] = "run_macro:root"
        assertEquals("MACRO_CYCLE", runner().run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun wholeNestedGraphPreflightRejectsUnknownAfterSafeLeafWithoutEffects() = runBlocking {
        definitions["root"] = "get_time;run_macro:later"
        definitions["later"] = "guessed_delete:anything"
        assertEquals("UNKNOWN_COMMAND", runner().run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun depthFourIsAllowedAndFiveRejectedBeforeEffects() = runBlocking {
        definitions.putAll(mapOf("root" to "run_macro:b", "b" to "run_macro:c", "c" to "run_macro:d", "d" to "get_time"))
        assertEquals(MacroOutcomeStatus.COMPLETED, runner().run("root").status)
        observed.clear()
        definitions["d"] = "run_macro:e"; definitions["e"] = "get_time"
        assertEquals("MACRO_DEPTH_LIMIT", runner().run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun aggregateTwelveLeafBoundIncludesRepeatedNestedExpansion() = runBlocking {
        definitions["root"] = "run_macro:child;run_macro:child"
        definitions["child"] = List(7) { "get_time" }.joinToString(";")
        assertEquals("MACRO_LEAF_LIMIT", runner().run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun oversizedDefinitionAndArgumentsNeverTruncateOrDispatch() = runBlocking {
        definitions["root"] = "get_time" + " ".repeat(1_000)
        assertEquals("MACRO_LENGTH_LIMIT", runner().run("root").reason)
        definitions["root"] = "send_sms:+821012345678:" + "x".repeat(201)
        assertEquals("ARGUMENT_LENGTH_LIMIT", runner().run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun sensitiveConsumesRemainderWithExactMultilingualBodyAndLiteralPipe() = runBlocking {
        val target = "  +821012345678  "
        val body = "\r\nမြန်မာ 한국어 English 🙂 | literal:colon;get_time  "
        definitions["root"] = "send_sms:$target:$body"
        val result = runner {
            observed += it; MacroLeafResult(MacroOutcomeStatus.WAITING_FOR_APPROVAL)
        }.run("root")
        assertEquals(MacroOutcomeStatus.WAITING_FOR_APPROVAL, result.status)
        assertEquals(target, observed.single().target)
        assertEquals(body, observed.single().value)
        assertEquals(1, result.calls)
    }

    @Test fun ambiguousSafeFreeTextAndThirdArgumentSemicolonsRejectBeforeEffects() = runBlocking {
        definitions["root"] = "get_time;save_note:Title:body;get_date"
        assertEquals("AMBIGUOUS_PAYLOAD_SEPARATOR", runner().run("root").reason)
        definitions["root"] = "set_volume:speaker:30;get_date"
        assertEquals("AMBIGUOUS_PAYLOAD_SEPARATOR", runner().run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun finalSafePayloadPreservesWhitespaceColonAndPipes() = runBlocking {
        definitions["root"] = "get_time;save_note:  Title  :\r\nမြန်မာ | 한글:English  "
        assertEquals(MacroOutcomeStatus.COMPLETED, runner().run("root").status)
        assertEquals("  Title  ", observed.last().target)
        assertEquals("\r\nမြန်မာ | 한글:English  ", observed.last().value)
    }

    @Test fun approvalBlockedFailedUnknownAndOpaqueResultsStopSuffixWithoutRetry() = runBlocking {
        definitions["root"] = "get_time;get_date"
        for (status in listOf(MacroOutcomeStatus.WAITING_FOR_APPROVAL, MacroOutcomeStatus.BLOCKED,
            MacroOutcomeStatus.FAILED, MacroOutcomeStatus.OUTCOME_UNKNOWN, MacroOutcomeStatus.UNVERIFIED)) {
            observed.clear()
            val result = runner { observed += it; MacroLeafResult(status) }.run("root")
            assertEquals(status, result.status)
            assertEquals(1, observed.size)
            assertEquals(1, result.calls)
        }
    }

    @Test fun sensitiveLeafCannotBeReportedVerifiedOrContinueFromAClaimedSuccess() = runBlocking {
        definitions["root"] = "get_time;make_call:+821012345678"
        val result = runner().run("root")
        assertEquals(MacroOutcomeStatus.BLOCKED, result.status)
        assertEquals("SENSITIVE_LEAF_MUST_STOP_AT_GUARD", result.observations.last().result.message)
        assertEquals(2, result.calls)
    }

    @Test fun fakeGuardDeniesUnapprovedCallAndSmsWithZeroSimulatedEffects() = runBlocking {
        var effects = 0
        val fakeGuard: suspend (MacroLeaf) -> MacroLeafResult = { leaf ->
            observed += leaf
            if (leaf.risk == MacroLeafRisk.SENSITIVE) MacroLeafResult(MacroOutcomeStatus.BLOCKED, "No approval")
            else { effects++; MacroLeafResult(MacroOutcomeStatus.VERIFIED) }
        }
        definitions["root"] = "make_call:+821012345678"
        assertEquals(MacroOutcomeStatus.BLOCKED, runner(fakeGuard).run("root").status)
        definitions["root"] = "send_sms:+821012345678:Exact text"
        assertEquals(MacroOutcomeStatus.BLOCKED, runner(fakeGuard).run("root").status)
        assertEquals(0, effects)
    }

    @Test fun editedCapturedDefinitionStopsBeforeNextLeaf() = runBlocking {
        definitions["root"] = "get_time;get_date"
        val result = runner {
            observed += it; definitions["root"] = "make_call:+821012345678"
            MacroLeafResult(MacroOutcomeStatus.VERIFIED)
        }.run("root")
        assertEquals("MACRO_CHANGED_REVIEW_AGAIN", result.reason)
        assertEquals(1, result.calls)
    }

    @Test fun deadlineAndClockRollbackRejectLateResultsWithoutReplay() = runBlocking {
        definitions["root"] = "get_time;get_date"
        val result = runner {
            observed += it; now += 30_000
            MacroLeafResult(MacroOutcomeStatus.VERIFIED)
        }.run("root")
        assertEquals(MacroOutcomeStatus.OUTCOME_UNKNOWN, result.status)
        assertEquals(1, result.calls)
        observed.clear(); now = 1_000
        val reversed = runner {
            observed += it; now = 999
            MacroLeafResult(MacroOutcomeStatus.VERIFIED)
        }.run("root")
        assertEquals(MacroOutcomeStatus.OUTCOME_UNKNOWN, reversed.status)
        assertEquals(1, observed.size)
    }

    @Test fun cancellationDuringLeafPropagatesAndNoSuffixDispatchOccurs() = runBlocking {
        definitions["root"] = "get_time;get_date"
        val entered = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            runner { observed += it; entered.complete(Unit); awaitCancellation() }.run("root")
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, observed.size)
    }

    @Test fun completePreflightConsumesDeadlineBeforeAnyDispatch() = runBlocking {
        definitions["root"] = "get_time"
        val candidate = BoundedMacroRunner({ id -> now += 30_000; definitions[id] }, ::risk,
            { observed += it; MacroLeafResult(MacroOutcomeStatus.VERIFIED) }, { now })
        assertEquals("PREFLIGHT_BUDGET_OR_CLOCK_INVALID", candidate.run("root").reason)
        assertTrue(observed.isEmpty())
    }

    @Test fun monotonicRollbackBetweenLeavesIsRejectedEvenIfStillAfterRunStart() = runBlocking {
        definitions["root"] = "get_time;get_date"
        val ticks = ArrayDeque(listOf(1_000L, 1_000L, 1_000L, 2_000L, 1_500L))
        val candidate = BoundedMacroRunner(definitions::get, ::risk,
            { observed += it; MacroLeafResult(MacroOutcomeStatus.VERIFIED) }, { ticks.removeFirst() })
        val result = candidate.run("root")
        assertEquals("DEADLINE_OR_CLOCK_INVALID", result.reason)
        assertEquals(1, observed.size)
    }

    @Test fun cooperativeTimeoutHasUnknownOutcomeAndNoRetryOrSuffix() = runBlocking {
        definitions["root"] = "get_time;get_date"
        val candidate = BoundedMacroRunner(definitions::get, ::risk,
            { observed += it; awaitCancellation() }, { now }, durationMillis = 100, perLeafMillis = 1)
        val result = candidate.run("root")
        assertEquals(MacroOutcomeStatus.OUTCOME_UNKNOWN, result.status)
        assertEquals(1, observed.size)
    }

    @Test fun opaqueLegacySuccessTextIsUnverifiedAndExplicitPrefixesRemainNonSuccess() {
        assertEquals(MacroOutcomeStatus.UNVERIFIED, OpaqueMacroResults.classify("Macro completed").status)
        assertEquals(MacroOutcomeStatus.UNVERIFIED, OpaqueMacroResults.classify("Call requested.").status)
        assertEquals(MacroOutcomeStatus.WAITING_FOR_APPROVAL, OpaqueMacroResults.classify("NEEDS_APPROVAL: id").status)
        assertEquals(MacroOutcomeStatus.OUTCOME_UNKNOWN, OpaqueMacroResults.classify("FAILED/OUTCOME_UNKNOWN: no retry").status)
        assertEquals(MacroOutcomeStatus.BLOCKED, OpaqueMacroResults.classify("BLOCKED: Exact target missing").status)
    }

    @Test fun renderedCanonicalPrefixesRetainExactGuardResultWithoutMintingApproval() {
        val guard = "NEEDS_APPROVAL: actual-guard-id. Exact reviewed action required."
        val waiting = MacroRunResult(MacroOutcomeStatus.WAITING_FOR_APPROVAL, "STOPPED", 1,
            listOf(MacroStepObservation("make_call", OpaqueMacroResults.classify(guard))))
        assertTrue(waiting.renderLegacy().startsWith(guard))
        val fabricated = waiting.copy(observations = emptyList())
        assertTrue(fabricated.renderLegacy().startsWith("BLOCKED:"))
        assertFalse(fabricated.renderLegacy().contains("actual-guard-id"))
        for ((status, prefix) in listOf(MacroOutcomeStatus.BLOCKED to "BLOCKED:", MacroOutcomeStatus.FAILED to "FAILED:",
            MacroOutcomeStatus.OUTCOME_UNKNOWN to "FAILED/OUTCOME_UNKNOWN:", MacroOutcomeStatus.UNVERIFIED to "UNVERIFIED:")) {
            val message = "$prefix exact captured result"
            val result = MacroRunResult(status, "STOPPED", 1,
                listOf(MacroStepObservation("get_time", MacroLeafResult(status, message))))
            assertTrue(result.renderLegacy().startsWith(message))
        }
    }
}
