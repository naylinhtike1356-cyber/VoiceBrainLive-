package com.example.voicebrainlive.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Pure JVM fake sink only. Tests coordinate workers with latches, never native audio or providers. */
class BoundedPlaybackControllerTest {
    private class Sink(script: List<Int> = emptyList(), private val expectedBytes: Int = 0) : PcmPlaybackSink {
        private val results = ArrayDeque(script)
        private val output = ByteArrayOutputStream()
        val accepted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val events = mutableListOf<String>()
        val threads = mutableSetOf<Long>()
        var throwPause = false
        var decision: ((ByteArray, Int, Int) -> Int)? = null
        @Synchronized private fun event(name: String) { events += name; threads += Thread.currentThread().id }
        override fun play() { event("play") }
        override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
            event("write:${bytes[offset]}")
            val amount = decision?.invoke(bytes, offset, count) ?: if (results.isEmpty()) count else results.removeFirst()
            if (amount in 1..count) {
                synchronized(output) {
                    output.write(bytes, offset, amount)
                    if (output.size() >= expectedBytes) accepted.countDown()
                }
            }
            return amount
        }
        override fun pause() { event("pause"); if (throwPause) error("synthetic exception containing private data") }
        override fun flush() { event("flush") }
        override fun stop() { event("stop") }
        override fun release() { event("release"); released.countDown() }
        fun bytes(): ByteArray = synchronized(output) { output.toByteArray() }
        @Synchronized fun recordedEvents(): List<String> = events.toList()
        @Synchronized fun recordedThreads(): Set<Long> = threads.toSet()
    }
    private fun await(latch: CountDownLatch) { assertTrue("Worker did not reach test checkpoint", latch.await(5, TimeUnit.SECONDS)) }
    private fun uninterruptibleAwait(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { /* Deliberately bad sink/factory fixture. */ }
    }
    private fun controller(sink: Sink, onError: (BoundedPlaybackController.Error) -> Unit = {}) =
        BoundedPlaybackController({ sink }, maxQueuedBytes = 16, writeSliceBytes = 4,
            maxZeroWrites = 4, zeroWriteDelayMillis = 1, stopJoinMillis = 1_000, onError = onError)

    @Test fun partialAndZeroWritesKeepExactByteOrderWithoutDuplicatingAcceptedBytes() {
        val sink = Sink(listOf(2, 0, 2, 4), expectedBytes = 8)
        val player = controller(sink)
        val exact = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        try {
            assertEquals(BoundedPlaybackController.Start.STARTED, player.start())
            player.offer(player.captureProducer()!!, exact)
            await(sink.accepted)
            assertTrue(player.stop())
            assertArrayEquals(exact, sink.bytes())
            assertEquals(8L, player.snapshot().writtenBytes)
            assertEquals(2L, player.snapshot().partialWrites)
            assertEquals(1L, player.snapshot().zeroWrites)
            assertEquals(listOf("pause", "flush", "stop", "release"), sink.recordedEvents().takeLast(4))
            assertEquals(1, sink.recordedThreads().size)
            assertFalse(sink.recordedThreads().contains(Thread.currentThread().id))
        } finally { player.stop() }
    }

    @Test fun clearRejectsCapturedOldProducerAndFlushesBeforeNewGenerationWrites() {
        val sink = Sink(expectedBytes = 4)
        val attemptedOld = CountDownLatch(1)
        sink.decision = { bytes, offset, count -> if (bytes[offset] == 1.toByte()) { attemptedOld.countDown(); 0 } else count }
        val player = BoundedPlaybackController({ sink }, maxQueuedBytes = 16, maxZeroWrites = 10_000,
            zeroWriteDelayMillis = 1, stopJoinMillis = 1_000)
        try {
            player.start()
            val old = player.captureProducer()!!
            player.offer(old, byteArrayOf(1, 1, 1, 1))
            await(attemptedOld)
            val fresh = player.clear()!!
            assertEquals(ByteBoundedPcmQueue.Admission.STALE, player.offer(old, byteArrayOf(1, 1)))
            player.offer(fresh, byteArrayOf(2, 2, 2, 2))
            await(sink.accepted)
            player.stop()
            assertArrayEquals(byteArrayOf(2, 2, 2, 2), sink.bytes())
            val events = sink.recordedEvents()
            assertTrue(events.indexOf("flush") < events.indexOf("write:2"))
            assertEquals(4L, player.snapshot().cancelledInFlightBytes)
        } finally { player.stop() }
    }

    @Test fun stopDoesNotReleaseSinkFromCallerWhileItsWriterIsStuckAndRestartFailsClosed() {
        val sink = Sink(expectedBytes = 4)
        val inWrite = CountDownLatch(1)
        val resumeWrite = CountDownLatch(1)
        val factories = AtomicInteger()
        sink.decision = { _, _, count -> inWrite.countDown(); uninterruptibleAwait(resumeWrite); count }
        val player = BoundedPlaybackController({ factories.incrementAndGet(); sink }, stopJoinMillis = 0)
        try {
            player.start()
            val old = player.captureProducer()!!
            player.offer(old, ByteArray(4))
            await(inWrite)
            assertFalse(player.stop())
            assertTrue(player.snapshot().retiring)
            assertFalse(sink.recordedEvents().contains("release"))
            assertEquals(BoundedPlaybackController.Start.RETIRING, player.start())
            assertEquals(ByteBoundedPcmQueue.Admission.STALE, player.offer(old, ByteArray(2)))
            assertEquals(1, factories.get())
        } finally {
            resumeWrite.countDown()
            await(sink.released)
            player.stop()
        }
    }

    @Test fun repeatedZeroProgressStopsAfterBoundedRetriesAndDropsRemainder() {
        val sink = Sink(listOf(0, 0, 0, 0))
        val reported = AtomicReference<BoundedPlaybackController.Error?>()
        val error = CountDownLatch(1)
        val player = controller(sink) { reported.set(it); error.countDown() }
        try {
            player.start()
            player.offer(player.captureProducer()!!, ByteArray(4))
            await(error)
            assertEquals(BoundedPlaybackController.Error.WRITE_STALLED, reported.get())
            assertEquals(4L, player.snapshot().zeroWrites)
            assertEquals(4L, player.snapshot().droppedInFlightBytes)
            assertTrue(sink.recordedEvents().contains("release"))
            assertFalse(player.isRunning())
        } finally { player.stop() }
    }

    @Test fun negativeWriteIsNeverRetriedAndCleanupStillRuns() {
        val sink = Sink(listOf(-3))
        val error = CountDownLatch(1)
        val reported = AtomicReference<BoundedPlaybackController.Error?>()
        val player = controller(sink) { reported.set(it); error.countDown() }
        try {
            player.start()
            player.offer(player.captureProducer()!!, ByteArray(4))
            await(error)
            assertEquals(BoundedPlaybackController.Error.WRITE_FAILED, reported.get())
            assertEquals(1L, player.snapshot().writeCalls)
            assertEquals(0, sink.bytes().size)
            assertTrue(sink.recordedEvents().contains("release"))
        } finally { player.stop() }
    }

    @Test fun factoryFailureRevokesQueuedWorkAndOnlyReportsAnAllowedCategory() {
        val error = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val enteredFactory = CountDownLatch(1)
        val reported = AtomicReference<BoundedPlaybackController.Error?>()
        val player = BoundedPlaybackController({
            enteredFactory.countDown(); uninterruptibleAwait(proceed)
            kotlin.error("fixture secret transcript")
        }, onError = { reported.set(it); error.countDown() }, stopJoinMillis = 1_000)
        try {
            player.start()
            await(enteredFactory)
            val producer = player.captureProducer()!!
            player.offer(producer, ByteArray(4))
            proceed.countDown()
            await(error)
            assertEquals(BoundedPlaybackController.Error.INITIALIZATION, reported.get())
            assertEquals(0, player.snapshot().queue.queuedBytes)
            assertEquals(ByteBoundedPcmQueue.Admission.STALE, player.offer(producer, ByteArray(2)))
        } finally { proceed.countDown(); player.stop() }
    }

    @Test fun cleanupAttemptsFlushStopAndReleaseWhenPauseThrows() {
        val sink = Sink(expectedBytes = 2).apply { throwPause = true }
        val player = controller(sink)
        try {
            player.start()
            player.offer(player.captureProducer()!!, ByteArray(2))
            await(sink.accepted)
            player.stop()
            await(sink.released)
            assertEquals(listOf("pause", "flush", "stop", "release"), sink.recordedEvents().takeLast(4))
            assertEquals(1L, player.snapshot().failures)
        } finally { player.stop() }
    }

    @Test fun budgetsBoundQueuedPcmWhileFactoryIsPreparingAndInputBufferIsCopied() {
        val sink = Sink(expectedBytes = 8)
        val gate = CountDownLatch(1)
        val player = BoundedPlaybackController({ uninterruptibleAwait(gate); sink }, maxQueuedBytes = 8,
            maxQueuedChunks = 2, stopJoinMillis = 1_000)
        try {
            player.start()
            val token = player.captureProducer()!!
            val mutable = byteArrayOf(1, 2, 3, 4)
            player.offer(token, mutable)
            mutable.fill(9)
            assertEquals(ByteBoundedPcmQueue.Admission.ACCEPTED, player.offer(token, ByteArray(4)))
            assertEquals(ByteBoundedPcmQueue.Admission.FULL, player.offer(token, ByteArray(2)))
            assertEquals(ByteBoundedPcmQueue.Admission.TOO_LARGE, player.offer(token, ByteArray(10)))
            assertEquals(8, player.snapshot().queue.queuedBytes)
            assertEquals(2, player.snapshot().queue.queuedChunks)
            gate.countDown()
            await(sink.accepted)
            player.stop()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0), sink.bytes())
        } finally { gate.countDown(); player.stop() }
    }

    @Test fun idleStopWakesQueueWaitAndReleasesExactlyOnce() {
        val sink = Sink()
        val initialized = CountDownLatch(1)
        val player = BoundedPlaybackController({ initialized.countDown(); sink }, stopJoinMillis = 1_000)
        try {
            player.start()
            await(initialized)
            assertTrue(player.stop())
            assertTrue(player.stop())
            assertEquals(1, sink.recordedEvents().count { it == "release" })
            assertFalse(player.isSpeaking())
            assertNull(player.captureProducer())
        } finally { player.stop() }
    }

    @Test fun replacementUsesANewCapturedSinkAndCannotAdmitOldProducerWork() {
        val first = Sink(expectedBytes = 2)
        val second = Sink(expectedBytes = 2)
        val factories = AtomicInteger()
        val player = BoundedPlaybackController({ if (factories.getAndIncrement() == 0) first else second }, stopJoinMillis = 1_000)
        try {
            player.start()
            val old = player.captureProducer()!!
            player.offer(old, byteArrayOf(1, 1))
            await(first.accepted)
            assertTrue(player.stop())
            assertEquals(BoundedPlaybackController.Start.STARTED, player.start())
            assertEquals(ByteBoundedPcmQueue.Admission.STALE, player.offer(old, byteArrayOf(3, 3)))
            player.offer(player.captureProducer()!!, byteArrayOf(2, 2))
            await(second.accepted)
            assertTrue(player.stop())
            assertArrayEquals(byteArrayOf(1, 1), first.bytes())
            assertArrayEquals(byteArrayOf(2, 2), second.bytes())
            assertEquals(1, first.recordedEvents().count { it == "release" })
            assertEquals(1, second.recordedEvents().count { it == "release" })
            assertEquals(2, factories.get())
        } finally { player.stop() }
    }

    @Test fun queuedProviderWorkKeepsCapturedGenerationAndNextValidTurnCanSpeak() {
        val sink = Sink(expectedBytes = 2)
        val ready = CountDownLatch(1)
        val player = BoundedPlaybackController({ ready.countDown(); sink }, stopJoinMillis = 1_000)
        try {
            player.start()
            await(ready)
            // Actual owners capture before listener/UI dispatch; the closure never fetches latest.
            val capturedBeforeUi = player.captureProducer()!!
            val delayedUiWork = { player.offer(capturedBeforeUi, byteArrayOf(1, 1)) }
            player.clear()
            assertEquals(ByteBoundedPcmQueue.Admission.STALE, delayedUiWork())
            val nextValidProviderCallback = player.captureProducer()!!
            assertEquals(ByteBoundedPcmQueue.Admission.ACCEPTED,
                player.offer(nextValidProviderCallback, byteArrayOf(2, 2)))
            await(sink.accepted)
            assertTrue(player.stop())
            assertArrayEquals(byteArrayOf(2, 2), sink.bytes())
        } finally { player.stop() }
    }

    @Test fun rapidRepeatedInterruptionsRevokeAllOlderProducersWithoutQueueGrowth() {
        val factoryGate = CountDownLatch(1)
        val sink = Sink(expectedBytes = 2)
        val player = BoundedPlaybackController({ uninterruptibleAwait(factoryGate); sink },
            maxQueuedBytes = 8, maxQueuedChunks = 2, stopJoinMillis = 1_000)
        try {
            player.start()
            val older = mutableListOf<BoundedPlaybackController.Producer>()
            repeat(100) {
                val producer = player.captureProducer()!!
                older += producer
                player.offer(producer, ByteArray(2))
                player.clear()
                assertEquals(0, player.snapshot().queue.queuedBytes)
            }
            older.forEach {
                assertEquals(ByteBoundedPcmQueue.Admission.STALE, player.offer(it, ByteArray(2)))
            }
            player.offer(player.captureProducer()!!, byteArrayOf(3, 3))
            factoryGate.countDown()
            await(sink.accepted)
            assertTrue(player.stop())
            assertArrayEquals(byteArrayOf(3, 3), sink.bytes())
        } finally { factoryGate.countDown(); player.stop() }
    }

    @Test fun malformedNativeWriteResultFailsWithoutRetryOrUnboundedProgress() {
        for (invalidWrite in listOf(1, 6)) {
            val sink = Sink(listOf(invalidWrite))
            val reported = AtomicReference<BoundedPlaybackController.Error?>()
            val failure = CountDownLatch(1)
            val player = controller(sink) { reported.set(it); failure.countDown() }
            try {
                player.start()
                player.offer(player.captureProducer()!!, ByteArray(4))
                await(failure)
                assertEquals(BoundedPlaybackController.Error.WRITE_FAILED, reported.get())
                assertEquals(1L, player.snapshot().writeCalls)
                assertEquals(0L, player.snapshot().writtenBytes)
                assertTrue(sink.recordedEvents().contains("release"))
            } finally { player.stop() }
        }
    }

    @Test fun stoppingDuringFactoryPreparationRevokesWorkAndWorkerAloneReleases() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val sink = Sink()
        val player = BoundedPlaybackController({ entered.countDown(); uninterruptibleAwait(gate); sink },
            stopJoinMillis = 0)
        try {
            player.start()
            await(entered)
            val producer = player.captureProducer()!!
            player.offer(producer, ByteArray(4))
            assertFalse(player.stop())
            assertEquals(ByteBoundedPcmQueue.Admission.STALE, player.offer(producer, ByteArray(2)))
            assertEquals(BoundedPlaybackController.Start.RETIRING, player.start())
            gate.countDown()
            await(sink.released)
            assertFalse(sink.recordedEvents().contains("play"))
            assertEquals(1, sink.recordedThreads().size)
            assertEquals(1, sink.recordedEvents().count { it == "release" })
        } finally { gate.countDown(); player.stop() }
    }
}
