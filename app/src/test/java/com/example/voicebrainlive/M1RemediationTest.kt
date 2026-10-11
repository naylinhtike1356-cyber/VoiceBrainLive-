package com.example.voicebrainlive

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.commands.*
import com.example.voicebrainlive.commands.handlers.*
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local fake-effect regressions only; no providers, device camera, real calls or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class M1RemediationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val action = ActionSnapshot("send_sms", "Alice", "SMS", "tel:01012345678", "Exact", "one recipient")

    @Test fun invalidationRevokesPreparationAndFutureRequestsInOldSession() {
        val store = ActionApprovalStore({ 100L })
        val epoch = store.epoch("old")!!
        store.invalidate("old")
        assertNull(store.requestIfCurrent("old", action, epoch))
        assertNull(store.epoch("old"))
        assertNotNull(store.requestIfCurrent("new", action, store.epoch("new")!!))
    }

    @Test fun invalidationRevokesAlreadyConsumedInFlightEpoch() {
        val store = ActionApprovalStore({ 100L })
        val pending = store.request("old", action)
        assertTrue(store.approve(pending.id, action))
        assertTrue(store.consume(pending.id, "old", action))
        store.invalidate("old")
        assertFalse(store.isCurrent(pending))
    }

    @Test fun sleepInclusiveElapsedClockExpiresAtDeadline() {
        val store = ActionApprovalStore(ttlMs = 1000L)
        val request = store.request("sleep", action)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000))
        assertTrue(store.isExpired(request))
        assertNull(store.pending(request.id))
        assertFalse(store.approve(request.id, action))
    }

    @Test fun pauseResumeAndRecreationCannotReuseExplicitTap() {
        val owner = Any()
        assertNull(ApprovalUiLease.tap(owner))
        ApprovalUiLease.resume(owner)
        val tapped = ApprovalUiLease.tap(owner)!!
        assertTrue(ApprovalUiLease.current(tapped))
        ApprovalUiLease.pause(owner)
        assertFalse(ApprovalUiLease.current(tapped))
        ApprovalUiLease.resume(owner)
        assertFalse(ApprovalUiLease.current(tapped))
        assertTrue(ApprovalUiLease.current(ApprovalUiLease.tap(owner)))
        ApprovalUiLease.pause(owner)
        val recreated = Any()
        ApprovalUiLease.resume(recreated)
        assertFalse(ApprovalUiLease.current(tapped))
        ApprovalUiLease.pause(recreated)
    }

    @Test fun missingOrPausedTapBlocksAndRevokesApprovalWithoutEffect() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        for (paused in listOf(false, true)) {
            val session = java.util.UUID.randomUUID().toString()
            val output = SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("legacy") }
            val id = output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
            val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
            val owner = Any()
            ApprovalUiLease.resume(owner)
            val lease = ApprovalUiLease.tap(owner)
            ApprovalUiLease.pause(owner)
            assertTrue(SensitiveActionGuard.executeApproved(app, id, shown, if (paused) lease else null).startsWith("BLOCKED"))
            assertNull(SensitiveActionGuard.store.pending(id))
            assertNull(shadowOf(app).nextStartedActivity)
            SensitiveActionGuard.invalidate(session)
        }
    }

    @Test fun quickContactCaseCollisionBlocksExecutionForAddEditAndDelete() = runBlocking {
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        for (cmd in listOf("add_contact", "edit_contact", "delete_contact")) {
            prefs.edit().clear().commit()
            if (cmd != "add_contact") QuickContactStore.write(prefs) { putString("alice", "01011112222") }
            val operation = ActionTargetResolver.prepare(app, cmd, "alice", "01012345678")!!
            QuickContactStore.write(prefs) { putString("Alice", "01099998888") }
            val before = prefs.all.toMap()
            assertFalse(operation.isCurrent())
            assertTrue(operation.perform().startsWith("BLOCKED"))
            assertEquals(before, prefs.all)
        }
    }

    @Test fun sharedWriterWaitsForAtomicContactBoundary() {
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        val attempting = CountDownLatch(1)
        val completed = CountDownLatch(1)
        lateinit var writer: Thread
        synchronized(QuickContactStore.lock) {
            writer = Thread {
                attempting.countDown()
                QuickContactStore.write(prefs) { putString("Alice", "01012345678") }
                completed.countDown()
            }
            writer.start()
            assertTrue(attempting.await(2, TimeUnit.SECONDS))
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        writer.join()
        assertEquals("01012345678", prefs.getString("Alice", null))
    }

    @Test fun agentCameraPathsAndBridgeNeverInvokeCaptureCallback() = runBlocking {
        var captures = 0
        VisionCaptureBridge.onCaptureRequested = { captures++ }
        try {
            val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
            for (cmd in listOf("take_photo", "analyze_frame")) {
                assertEquals(CommandSafetyPolicy.Classification.SENSITIVE, CommandSafetyPolicy.classify(cmd))
                assertTrue(VisionHandler(app).execute(cmd, null, null)!!.startsWith("BLOCKED"))
                assertTrue(ToolRegistryImpl(app, prefs).executeCommandAsync(cmd, null, null)!!.startsWith("BLOCKED"))
                assertTrue(PhoneCommandExecutor(app).executeAsync(cmd, null, null).startsWith("BLOCKED"))
                assertTrue(AutomationCommandRunner(app).run("command", "$cmd:").contains("BLOCKED"))
            }
            assertFalse(VisionCaptureBridge.requestCapture())
            assertEquals(0, captures)
        } finally { VisionCaptureBridge.onCaptureRequested = null }
    }
}
