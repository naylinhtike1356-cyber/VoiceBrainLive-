package com.example.voicebrainlive.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ByteBoundedPcmQueueTest {
    @Test fun byteLimitRejectsNewestWithoutDisturbingOlderSpeech() {
        val queue = ByteBoundedPcmQueue(8)
        val token = queue.open()
        assertEquals(ByteBoundedPcmQueue.Admission.ACCEPTED, queue.offer(token, byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertEquals(ByteBoundedPcmQueue.Admission.FULL, queue.offer(token, ByteArray(4)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), queue.poll(token)!!.bytes)
        assertNull(queue.poll(token))
        assertEquals(6, queue.snapshot().highWaterBytes)
        assertEquals(4L, queue.snapshot().rejectedBytes)
    }

    @Test fun hugeAndOddFramesAreRejectedWithoutTruncatingOrSplitting() {
        val queue = ByteBoundedPcmQueue(8)
        val token = queue.open()
        assertEquals(ByteBoundedPcmQueue.Admission.TOO_LARGE, queue.offer(token, ByteArray(10)))
        assertEquals(ByteBoundedPcmQueue.Admission.UNALIGNED, queue.offer(token, ByteArray(3)))
        assertEquals(ByteBoundedPcmQueue.Admission.EMPTY, queue.offer(token, ByteArray(0)))
        assertEquals(0, queue.snapshot().queuedBytes)
    }

    @Test fun chunkLimitBoundsObjectOverheadForTinyFrames() {
        val queue = ByteBoundedPcmQueue(100, maxChunks = 2)
        val token = queue.open()
        repeat(2) { assertEquals(ByteBoundedPcmQueue.Admission.ACCEPTED, queue.offer(token, ByteArray(2))) }
        assertEquals(ByteBoundedPcmQueue.Admission.FULL, queue.offer(token, ByteArray(2)))
        assertEquals(2, queue.snapshot().queuedChunks)
    }

    @Test fun copiedFramesAreNotCorruptedByProducerBufferReuse() {
        val queue = ByteBoundedPcmQueue(8)
        val token = queue.open()
        val mutable = byteArrayOf(1, 2)
        queue.offer(token, mutable)
        mutable.fill(9)
        assertArrayEquals(byteArrayOf(1, 2), queue.poll(token)!!.bytes)
    }

    @Test fun replacementRejectsOldProducerAndOldConsumer() {
        val queue = ByteBoundedPcmQueue(8)
        val old = queue.open()
        queue.offer(old, ByteArray(4))
        val fresh = queue.open()
        assertEquals(ByteBoundedPcmQueue.Admission.STALE, queue.offer(old, ByteArray(2)))
        assertNull(queue.poll(old))
        assertFalse(queue.close(old))
        assertTrue(queue.current(fresh))
        assertEquals(1L, queue.snapshot().cancelledChunks)
        assertEquals(4L, queue.snapshot().cancelledBytes)
    }

    @Test fun interruptionRevokesAlreadyDequeuedFrame() {
        val queue = ByteBoundedPcmQueue(8)
        val token = queue.open()
        queue.offer(token, ByteArray(4))
        val inFlight = queue.poll(token)!!
        queue.close(token)
        assertFalse(queue.current(inFlight.token))
        assertEquals(ByteBoundedPcmQueue.Admission.STALE, queue.offer(token, ByteArray(2)))
    }

    @Test fun drainingReleasesByteBudget() {
        val queue = ByteBoundedPcmQueue(4)
        val token = queue.open()
        queue.offer(token, ByteArray(4))
        queue.poll(token)
        assertEquals(ByteBoundedPcmQueue.Admission.ACCEPTED, queue.offer(token, ByteArray(4)))
        assertEquals(4, queue.snapshot().queuedBytes)
        assertEquals(2L, queue.snapshot().accepted)
    }

    @Test fun closeWakesBlockedPlaybackWithoutSentinelFrames() {
        val queue = ByteBoundedPcmQueue(4)
        val token = queue.open()
        val entering = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            try { entering.countDown(); assertNull(queue.await(token, 60_000)) }
            catch (t: Throwable) { failure.set(t) }
            finally { finished.countDown() }
        }
        reader.start()
        try {
            assertTrue(entering.await(2, TimeUnit.SECONDS))
            queue.close(token)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally { queue.close(token); reader.interrupt(); reader.join(2_000) }
    }

    @Test fun zeroTimeoutIsNonblockingAndRespectsClosedTokens() {
        val queue = ByteBoundedPcmQueue(4)
        val token = queue.open()
        assertNull(queue.await(token, 0))
        queue.offer(token, ByteArray(4))
        assertNotNull(queue.await(token, 0))
        queue.close(token)
        assertNull(queue.await(token, 0))
    }

    @Test fun concurrentProducersNeverExceedEitherBudget() {
        val queue = ByteBoundedPcmQueue(256, 16)
        val token = queue.open()
        val start = CountDownLatch(1)
        val producers = (1..4).map { Thread { start.await(); repeat(200) { queue.offer(token, ByteArray(32)) } } }
        producers.forEach { it.start() }
        start.countDown()
        producers.forEach { it.join(2_000); assertFalse(it.isAlive) }
        val result = queue.snapshot()
        assertEquals(256, result.queuedBytes)
        assertEquals(8, result.queuedChunks)
        assertEquals(8L, result.accepted)
        assertEquals(792L, result.rejected)
    }
}
