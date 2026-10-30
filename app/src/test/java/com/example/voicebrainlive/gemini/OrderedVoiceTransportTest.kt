package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Pure local fake frames/tools; Unconfined admits work deterministically without timers or networking. */
class OrderedVoiceTransportTest {
    private class Fixture {
        val epochs = ConnectionEpoch()
        val epoch = epochs.open(null)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val frames = mutableListOf<String>()
        val executions = mutableListOf<String>()
        val results = mutableListOf<String>()
        var overloads = 0
        var drops = 0
        var cancelled = 0
        fun actor(
            frame: suspend (String) -> Unit = { frames += it },
            tool: suspend (OrderedVoiceTransport.Call) -> String = { executions += it.id; "ok" },
            frameCapacity: Int = 32, toolCapacity: Int = 8, maxCalls: Int = 128,
            maxFrameChars: Int = 1_048_576, timeout: Long = 30_000
        ) = OrderedVoiceTransport(epochs, epoch, scope, frame, tool,
            { call, result -> results += "${call.id}:$result" },
            { overloads++; epochs.suspendCallbacks(epoch) }, { drops++ }, { cancelled++ },
            frameCapacity, toolCapacity, maxCalls, maxFrameChars, timeout)
        fun close() { epochs.close() }
    }
    private fun call(id: String) = OrderedVoiceTransport.Call(id, "execute_phone_command", "{}")

    @Test fun suspendedToolDoesNotBlockOrderedMultilingualFramesAndToolsRemainSerial() = runBlocking {
        val f = Fixture(); val release = CompletableDeferred<Unit>()
        val actor = f.actor(tool = { f.executions += it.id; if (it.id == "a") release.await(); "ok" })
        try {
            assertTrue(actor.offerTool(call("a"))); assertTrue(actor.offerTool(call("b")))
            val exact = listOf("  မြန်မာ\r\n한국어  ", "English 🙂", "interrupt", "turnComplete")
            exact.forEach { assertTrue(actor.offerFrame(it)) }
            assertEquals(exact, f.frames); assertEquals(listOf("a"), f.executions)
            release.complete(Unit)
            assertEquals(listOf("a", "b"), f.executions); assertEquals(listOf("a:ok", "b:ok"), f.results)
        } finally { f.close() }
    }

    @Test fun duplicateIdsIncludingCompletedCallsNeverExecuteTwice() = runBlocking {
        val f = Fixture(); val actor = f.actor()
        try {
            assertTrue(actor.offerTool(call("same"))); assertFalse(actor.offerTool(call("same")))
            assertFalse(actor.offerTool(call("same").copy(arguments = "{\"different\":true}")))
            assertEquals(listOf("same"), f.executions); assertEquals(2, f.drops)
        } finally { f.close() }
    }

    @Test fun cancelRunningAndQueuedCallsDropsResultsAndPreservesSerialProgress() = runBlocking {
        val f = Fixture(); val actor = f.actor(tool = { f.executions += it.id; if (it.id == "a") awaitCancellation(); "ok" })
        try {
            actor.offerTool(call("a")); actor.offerTool(call("b"))
            actor.cancel(listOf("b", "a"))
            actor.offerTool(call("c"))
            assertEquals(listOf("a", "c"), f.executions); assertEquals(listOf("c:ok"), f.results)
            assertEquals(2, f.cancelled)
        } finally { f.close() }
    }

    @Test fun cancellationBeforeArrivalCreatesTombstoneRatherThanExecutingLater() = runBlocking {
        val f = Fixture(); val actor = f.actor()
        try {
            actor.cancel(listOf("future")); assertFalse(actor.offerTool(call("future")))
            assertTrue(f.executions.isEmpty()); assertTrue(f.results.isEmpty())
        } finally { f.close() }
    }

    @Test fun replacementRejectsOldFramesAndLateNonCooperativeCompletion() = runBlocking {
        val f = Fixture(); val release = CompletableDeferred<Unit>()
        val actor = f.actor(tool = { f.executions += it.id; withContext(NonCancellable) { release.await() }; "old private result" })
        try {
            actor.offerTool(call("old")); f.epochs.open(null)
            assertFalse(actor.offerFrame("stale")); assertFalse(actor.offerTool(call("new")))
            release.complete(Unit)
            assertTrue(f.results.isEmpty()); assertTrue(f.frames.isEmpty())
        } finally { f.close() }
    }

    @Test fun boundedFrameQueueClosesEpochInsteadOfDroppingAnInterruptionSilently() = runBlocking {
        val f = Fixture(); val block = CompletableDeferred<Unit>()
        val actor = f.actor(frame = { f.frames += it; block.await() }, frameCapacity = 1)
        try {
            assertTrue(actor.offerFrame("first")); assertTrue(actor.offerFrame("queued"))
            assertFalse(actor.offerFrame("interrupt")); assertEquals(1, f.overloads)
            assertFalse(f.epochs.current(f.epoch)); assertEquals(listOf("first"), f.frames)
            assertFalse(actor.offerFrame("more")); assertEquals(1, f.overloads)
        } finally { f.close() }
    }

    @Test fun boundedToolQueueAndCallLedgerFailClosedWithNoRetry() = runBlocking {
        val f = Fixture(); val actor = f.actor(tool = { f.executions += it.id; awaitCancellation() }, toolCapacity = 1)
        try {
            assertTrue(actor.offerTool(call("a"))); assertTrue(actor.offerTool(call("b")))
            assertFalse(actor.offerTool(call("c"))); assertEquals(1, f.overloads)
            assertEquals(listOf("a"), f.executions); assertTrue(f.results.isEmpty())
        } finally { f.close() }
        val limited = Fixture(); val ledger = limited.actor(maxCalls = 1)
        try { ledger.offerTool(call("once")); assertFalse(ledger.offerTool(call("twice"))); assertEquals(1, limited.overloads) }
        finally { limited.close() }
    }

    @Test fun missingIdentityAndOversizedArgumentsDoNotDispatch() = runBlocking {
        val f = Fixture(); val actor = f.actor()
        try {
            assertFalse(actor.offerTool(call(""))); assertFalse(actor.offerTool(call("a").copy(name = "")))
            assertFalse(actor.offerTool(call("b").copy(arguments = "x".repeat(65_537))))
            assertTrue(f.executions.isEmpty()); assertEquals(3, f.drops)
        } finally { f.close() }
    }

    @Test fun oversizedFrameFailsClosedWithoutParsingOrPlayingRawBytes() = runBlocking {
        val f = Fixture(); val actor = f.actor(maxFrameChars = 4)
        try { assertFalse(actor.offerFrame("12345")); assertTrue(f.frames.isEmpty()); assertEquals(1, f.overloads) }
        finally { f.close() }
    }

    @Test fun timeoutAndExceptionAreSanitizedAndNeverRetryTool() = runBlocking {
        val f = Fixture(); val delivered = CompletableDeferred<Unit>()
        val actor = OrderedVoiceTransport(f.epochs, f.epoch, f.scope, {},
            { f.executions += it.id; awaitCancellation() },
            { _, result -> f.results += result; delivered.complete(Unit) }, {}, toolTimeoutMillis = 10)
        try {
            actor.offerTool(call("timeout")); withTimeout(2_000) { delivered.await() }
            assertEquals(listOf("timeout"), f.executions); assertEquals(listOf("OUTCOME_UNKNOWN: Tool timed out; check the outcome before retrying an action."), f.results)
        } finally { f.close() }
        val fail = Fixture(); val errors = fail.actor(tool = { fail.executions += it.id; error("SECRET target payload") })
        try {
            errors.offerTool(call("failure")); assertEquals(1, fail.executions.size)
            assertTrue(fail.results.single().contains("OUTCOME_UNKNOWN")); assertFalse(fail.results.single().contains("SECRET"))
        } finally { fail.close() }
    }
}
