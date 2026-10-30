package com.example.voicebrainlive.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.ArrayDeque

class PlaybackDrainLeaseTest {
    private fun await(latch: CountDownLatch) =
        assertTrue("Lifecycle checkpoint not reached", latch.await(5, TimeUnit.SECONDS))

    @Test fun delayedDrainCannotAbandonFocusOrOverwriteFreshSpeakingState() {
        val lease = PlaybackDrainLease()
        var focus = true
        var state = "Speaking"
        val oldDrain = lease.replace {}
        val queuedCompletion = { lease.withCurrent(oldDrain) { focus = false; state = "Listening" } }
        lease.replace { focus = true; state = "Speaking" }
        assertFalse(queuedCompletion())
        assertTrue(focus)
        assertEquals("Speaking", state)
    }

    @Test fun mainThreadCompletionRechecksOwnershipAfterIoDrainAdmission() {
        val lease = PlaybackDrainLease()
        val token = lease.replace {}
        var completionCalls = 0
        assertTrue(lease.withCurrent(token) { /* IO-side focus completion admitted. */ })
        val delayedMainCallback = { lease.withCurrent(token) { completionCalls++ } }
        lease.revoke { /* Activity teardown or disconnect before Main dispatch. */ }
        assertFalse(delayedMainCallback())
        assertEquals(0, completionCalls)
    }

    @Test fun admittedCompletionAndNewTurnEffectsRemainSerialized() {
        val lease = PlaybackDrainLease()
        val old = lease.replace {}
        val completing = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val replacing = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val events = mutableListOf<String>()
        val failures = AtomicReference<Throwable?>()
        val drain = Thread {
            try {
                lease.withCurrent(old) {
                    events += "old completion begins"
                    completing.countDown()
                    await(allowCompletion)
                    events += "old focus abandoned"
                }
            } catch (t: Throwable) { failures.set(t) }
            finally { finished.countDown() }
        }
        val fresh = Thread {
            try {
                await(completing)
                replacing.countDown()
                lease.replace { events += "fresh focus acquired" }
            } catch (t: Throwable) { failures.set(t) }
            finally { finished.countDown() }
        }
        drain.start(); fresh.start()
        try {
            await(replacing)
            allowCompletion.countDown()
            await(finished)
            assertNull(failures.get())
            assertEquals(listOf("old completion begins", "old focus abandoned", "fresh focus acquired"), events)
            assertFalse(lease.withCurrent(old) { fail("Old completion admitted twice") })
        } finally {
            allowCompletion.countDown()
            drain.join(5_000); fresh.join(5_000)
        }
    }

    @Test fun revocationCannotMissJobRegisteredDuringReplacement() {
        val lease = PlaybackDrainLease()
        val registering = CountDownLatch(1)
        val revoking = CountDownLatch(1)
        val finishRegistration = CountDownLatch(1)
        val done = CountDownLatch(2)
        val registered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val failures = AtomicReference<Throwable?>()
        val job = Thread {
            try {
                lease.replace {
                    registering.countDown()
                    await(finishRegistration)
                    registered.set(true)
                }
            } catch (t: Throwable) { failures.set(t) }
            finally { done.countDown() }
        }
        val stop = Thread {
            try {
                await(registering)
                revoking.countDown()
                lease.revoke { cancelled.set(registered.get()) }
            } catch (t: Throwable) { failures.set(t) }
            finally { done.countDown() }
        }
        job.start(); stop.start()
        try {
            await(revoking)
            finishRegistration.countDown()
            await(done)
            assertNull(failures.get())
            assertTrue(cancelled.get())
        } finally {
            finishRegistration.countDown()
            job.join(5_000); stop.join(5_000)
        }
    }

    @Test fun queuedOldAudioCannotChangeFocusOrListenerStateAfterNativeGenerationRevocation() {
        val pcm = ByteBoundedPcmQueue(8)
        val lease = PlaybackDrainLease()
        val main = ArrayDeque<() -> Unit>()
        val oldProducer = pcm.open()
        var focusRequests = 0
        var listenerCalls = 0
        var state = "Listening"
        fun queuedAudio(captured: ByteBoundedPcmQueue.Token) {
            main.addLast {
                if (pcm.current(captured)) {
                    lease.replace { focusRequests++; state = "Speaking" }
                    listenerCalls++
                    pcm.offer(captured, ByteArray(2))
                }
            }
        }
        queuedAudio(oldProducer)
        // Actual interruption clears/revokes PCM synchronously; Main revocation is ordered later.
        val freshProducer = pcm.open()
        main.addLast { lease.revoke {} }
        queuedAudio(freshProducer)
        main.removeFirst().invoke()
        assertEquals(0, focusRequests)
        assertEquals(0, listenerCalls)
        assertEquals("Listening", state)
        while (main.isNotEmpty()) main.removeFirst().invoke()
        assertEquals(1, focusRequests)
        assertEquals(1, listenerCalls)
        assertEquals("Speaking", state)
        assertEquals(2, pcm.snapshot().queuedBytes)
    }

    @Test fun orderedFreshAudioCancelsPreviouslyRegisteredDrainBeforeCompletionUi() {
        val lease = PlaybackDrainLease()
        val main = ArrayDeque<() -> Unit>()
        var jobCancelled = false
        var focus = true
        var state = "Speaking"
        var old: PlaybackDrainLease.Token? = null
        main.addLast { old = lease.replace {} }
        main.addLast { lease.replace { jobCancelled = true; focus = true; state = "Speaking" } }
        main.addLast {
            lease.withCurrent(old!!) { focus = false; state = "Listening" }
        }
        while (main.isNotEmpty()) main.removeFirst().invoke()
        assertTrue(jobCancelled)
        assertTrue(focus)
        assertEquals("Speaking", state)
    }
}
