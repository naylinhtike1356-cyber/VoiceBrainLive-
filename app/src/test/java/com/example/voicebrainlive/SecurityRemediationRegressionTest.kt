package com.example.voicebrainlive

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.commands.*
import com.example.voicebrainlive.commands.handlers.MessagingHandler
import com.example.voicebrainlive.commands.handlers.CallCommands
import com.example.voicebrainlive.gemini.GeminiLiveClient
import com.example.voicebrainlive.messenger.MessengerApiClient
import com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated fake effects only: no real device operations or network connections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecurityRemediationRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val exact = "  မြန်မာ။\n한국어!\r\nEnglish: MiXeD, punctuation; 🙂  "
    private val snapshot = ActionSnapshot("send_sms", "Alice", "SMS", "tel:01012345678", exact, "one recipient")
    private class FakeSocket : okhttp3.WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = okhttp3.Request.Builder().url("https://fixture.invalid").build()
        override fun queueSize() = 0L
        @Synchronized override fun send(text: String): Boolean { messages += text; return true }
        override fun send(bytes: okio.ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
    private fun fakeConnectedClient(): Pair<GeminiLiveClient, FakeSocket> {
        val client = GeminiLiveClient("fixture-no-network")
        val socket = FakeSocket()
        val field = GeminiLiveClient::class.java.getDeclaredField("webSocket").apply { isAccessible = true }
        field.set(client, socket)
        val epochs = GeminiLiveClient::class.java.getDeclaredField("connectionEpoch").apply { isAccessible = true }.get(client) as ConnectionEpoch
        epochs.open(null)
        return client to socket
    }

    @Test fun directMessagingHandlerCannotSendSmsWithoutApproval() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        val sms = shadowOf(android.telephony.SmsManager.getDefault())
        sms.clearLastSentTextMessageParams()
        sms.clearLastSentMultipartTextMessageParams()
        val output = MessagingHandler(app, MessengerApiClient(app)).execute("send_sms", "01012345678", exact)!!
        assertTrue(output, output.startsWith("NEEDS_APPROVAL"))
        val id = output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        assertEquals(exact, SensitiveActionGuard.store.pending(id)!!.snapshot.payload)
        assertNull(sms.lastSentTextMessageParams)
        assertNull(sms.lastSentMultipartTextMessageParams)
        SensitiveActionGuard.cancel(app, id)
    }

    @Test fun contactSearchConstructionAndExecutionDoNotMoveOrClearLegacyData() = runBlocking {
        val legacy = app.getSharedPreferences("voice_contacts", Context.MODE_PRIVATE)
        val quick = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        legacy.edit().clear().putString("Legacy Alice", "01012345678").commit()
        quick.edit().clear().commit()
        val original = legacy.all.toMap()
        try {
            CallCommands(app).execute("search_phone_contacts", "absent", null)
            assertEquals(original, legacy.all)
            assertTrue(quick.all.isEmpty())
            val call = ActionTargetResolver.prepare(app, "make_call", "Legacy Alice", null)!!
            assertEquals("legacy:Legacy Alice:01012345678", call.snapshot.targetId)
            assertEquals(original, legacy.all)
            assertTrue(quick.all.isEmpty())
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { legacy.edit().clear().commit() }
    }

    @Test @Config(sdk = [35]) fun notificationReplyCandidateAndAllFallbacksNeverGrantSending() {
        val token = PendingIntent.getBroadcast(app, 888, Intent("fixture.reply").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
        val target = UnifiedNotificationListenerService.Companion.ExactReplyTarget("key", app.packageName, "Alice", 100, 0, "reply", token)
        assertFalse(UnifiedNotificationListenerService.isExactReplyCurrent(target))
        assertFalse(UnifiedNotificationListenerService.sendExactReply(target, exact))
        UnifiedNotificationListenerService.lastNotifiedSender = "Alice"
        try {
            assertFalse(UnifiedNotificationListenerService.trySendReply(null, exact))
            assertFalse(UnifiedNotificationListenerService.trySendReply("Ali", exact))
            assertFalse(UnifiedNotificationListenerService.trySendReply("Alice", exact))
        } finally { UnifiedNotificationListenerService.lastNotifiedSender = null }
    }

    @Test fun messengerAndWhatsAppToolsRemainBlockedEvenWithExplicitRecipient() = runBlocking {
        val handler = MessagingHandler(app, MessengerApiClient(app))
        for (cmd in listOf("messenger", "send_facebook_message", "whatsapp", "send_whatsapp")) {
            assertTrue(cmd, handler.execute(cmd, "Alice", exact)!!.startsWith("BLOCKED"))
        }
    }

    @Test fun concurrentPreparationCannotReopenInvalidatedSession() {
        val store = ActionApprovalStore({ 100L })
        val captured = store.epoch("old")!!
        val ready = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val done = CountDownLatch(1)
        var registered: ActionApprovalStore.Pending? = null
        val thread = Thread {
            ready.countDown()
            revoked.await()
            registered = store.requestIfCurrent("old", snapshot, captured)
            done.countDown()
        }
        thread.start()
        assertTrue(ready.await(3, TimeUnit.SECONDS))
        store.invalidate("old")
        revoked.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        thread.join()
        assertNull(registered)
        assertNull(store.epoch("old"))
    }

    @Test fun recreatedStoreCannotRestorePersistedApproval() {
        val original = ActionApprovalStore({ 100L })
        val pending = original.request("session", snapshot)
        assertTrue(original.approve(pending.id, snapshot))
        val recreated = ActionApprovalStore({ 100L })
        assertNull(recreated.pending(pending.id))
        assertFalse(recreated.consume(pending.id, "session", snapshot))
    }

    @Test fun oldConnectionJobsAndCallbacksAreRevokedBeforeReplacement() {
        val epochs = ConnectionEpoch()
        val first = epochs.open(null)
        val child = kotlinx.coroutines.Job(first.job)
        assertTrue(epochs.current(first))
        val replacement = epochs.open(null)
        assertTrue(child.isCancelled)
        assertFalse(epochs.current(first))
        assertFalse(epochs.close(first))
        assertTrue(epochs.current(replacement))
        assertTrue(epochs.close(replacement))
        assertFalse(epochs.current(replacement))
    }

    @Test fun directGraphSendingRemainsBlockedWithoutNetwork() {
        assertTrue(MessengerApiClient(app).sendMessageSync("12345678", exact).startsWith("BLOCKED"))
    }

    @Test fun quickContactAbaChangeInvalidatesPreparedCall() = runBlocking {
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        QuickContactStore.write(prefs) { clear(); putString("alice", "01012345678") }
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val call = ActionTargetResolver.prepare(app, "call", "Alice", null)!!
        QuickContactStore.write(prefs) { putString("alice", "01099998888") }
        QuickContactStore.write(prefs) { putString("alice", "01012345678") }
        assertFalse(call.isCurrent())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun invalidatedExecutorStaysClosedAndFreshExecutorNeedsFreshPreview() = runBlocking {
        val executor = PhoneCommandExecutor(app)
        executor.invalidateApprovals()
        assertFalse(executor.isAuthorizationSessionOpen())
        assertTrue(executor.executeAsync("call", "01012345678", null).startsWith("BLOCKED"))
        val fresh = PhoneCommandExecutor(app)
        try {
            assertTrue(fresh.isAuthorizationSessionOpen())
            assertTrue(fresh.executeAsync("call", "01012345678", null).startsWith("NEEDS_APPROVAL"))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { fresh.invalidateApprovals() }
    }

    @Test fun quickContactCompareAndWriteRejectsConcurrentAndAbaEdits() {
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        QuickContactStore.write(prefs) { clear(); putString("alice", "01011112222") }
        val captured = QuickContactStore.snapshot(prefs, "ALICE")
        QuickContactStore.write(prefs) { putString("alice", "01099998888") }
        QuickContactStore.write(prefs) { putString("alice", "01011112222") }
        assertFalse(QuickContactStore.current(prefs, captured))
        assertFalse(QuickContactStore.compareAndWrite(prefs, captured) { remove("alice") })
        assertEquals("01011112222", prefs.getString("alice", null))
    }

    @Test fun concurrentQuickContactWritersSpendSnapshotOnce() {
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        QuickContactStore.write(prefs) { clear() }
        val captured = QuickContactStore.snapshot(prefs, "Alice")
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val effects = AtomicInteger()
        try {
            repeat(8) { pool.execute {
                start.await()
                if (QuickContactStore.compareAndWrite(prefs, captured) { putString("Alice", "01012345678") }) effects.incrementAndGet()
                done.countDown()
            } }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, effects.get())
            assertEquals(1, QuickContactStore.matching(prefs, "ALICE").size)
        } finally { pool.shutdownNow() }
    }

    @Test fun imageWithoutTapOrWithOnlyVisibilityHasZeroTransmission() {
        val owner = Any()
        val receiver = Any()
        ApprovalUiLease.resume(owner)
        try {
            var effects = 0
            for (tap in listOf(null, ApprovalUiLease.resumedLease())) {
                val consent = ImageShareConsent(exact, receiver, { true })
                if (consent.consume(receiver, tap) != null) effects++
                assertNull(consent.consume(receiver, ApprovalUiLease.tap(owner)))
            }
            assertEquals(0, effects)
        } finally { ApprovalUiLease.pause(owner) }
    }

    @Test fun approvedFrameIsFrozenAndConsumedOnlyOnce() {
        val owner = Any()
        val receiver = Any()
        ApprovalUiLease.resume(owner)
        try {
            val consent = ImageShareConsent(exact, receiver, { true })
            val tap = ApprovalUiLease.tap(owner)
            assertEquals(exact, consent.frame)
            assertEquals(exact, consent.consume(receiver, tap))
            assertNull(consent.consume(receiver, tap))
        } finally { ApprovalUiLease.pause(owner) }
    }

    @Test fun frameSessionChangeExpiryCancelPauseAndDestinationMismatchDeny() {
        val owner = Any()
        val receiver = Any()
        var now = 100L
        ApprovalUiLease.resume(owner)
        try {
            val tap = ApprovalUiLease.tap(owner)
            val expired = ImageShareConsent(exact, receiver, { true }, { now }, 50)
            now = 150L
            assertNull(expired.consume(receiver, tap))
            assertNull(ImageShareConsent(exact, receiver, { false }).consume(receiver, tap))
            assertNull(ImageShareConsent(exact, receiver, { true }).consume(Any(), tap))
            val cancelled = ImageShareConsent(exact, receiver, { true })
            cancelled.cancel()
            assertNull(cancelled.consume(receiver, tap))
            val paused = ImageShareConsent(exact, receiver, { true })
            ApprovalUiLease.pause(owner)
            assertNull(paused.consume(receiver, tap))
        } finally { ApprovalUiLease.pause(owner) }
    }

    @Test fun concurrentFrameCallbacksTransmitExactlyOnce() {
        val owner = Any()
        val receiver = Any()
        ApprovalUiLease.resume(owner)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tap = ApprovalUiLease.tap(owner)
            val consent = ImageShareConsent(exact, receiver, { true })
            val effects = AtomicInteger()
            val done = CountDownLatch(8)
            repeat(8) { pool.execute {
                if (consent.consume(receiver, tap) != null) effects.incrementAndGet()
                done.countDown()
            } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, effects.get())
        } finally { pool.shutdownNow(); ApprovalUiLease.pause(owner) }
    }

    @Test fun publicImageSendApiCannotBypassConsent() {
        val (client, socket) = fakeConnectedClient()
        var errors = 0
        client.onError = { if (it.startsWith("BLOCKED")) errors++ }
        client.sendImageFrame(exact)
        assertEquals(1, errors)
        assertTrue(socket.messages.isEmpty())
        client.disconnect()
    }

    @Test fun connectedImageBoundaryTransmitsExactReviewedFrameOnce() {
        val (client, socket) = fakeConnectedClient()
        val owner = Any()
        val frame = "AP8BAuKAiw=="
        ApprovalUiLease.resume(owner)
        try {
            val session = client.imageSessionToken()!!
            val consent = ImageShareConsent(frame, client, { client.isImageSessionCurrent(session) })
            client.sendReviewedImage(consent, null)
            assertTrue(socket.messages.isEmpty())
            val fresh = ImageShareConsent(frame, client, { client.isImageSessionCurrent(session) })
            val tap = ApprovalUiLease.tap(owner)
            repeat(3) { client.sendReviewedImage(fresh, tap) }
            assertEquals(1, socket.messages.size)
            assertEquals(frame, org.json.JSONObject(socket.messages.single()).getJSONObject("realtimeInput").getJSONObject("video").getString("data"))
        } finally { ApprovalUiLease.pause(owner); client.disconnect() }
    }

    @Test fun reconnectCannotTransmitPreviouslyCapturedFrame() {
        val (client, socket) = fakeConnectedClient()
        val owner = Any()
        ApprovalUiLease.resume(owner)
        try {
            val old = client.imageSessionToken()!!
            val consent = ImageShareConsent("AP8BAuKAiw==", client, { client.isImageSessionCurrent(old) })
            val epochs = GeminiLiveClient::class.java.getDeclaredField("connectionEpoch").apply { isAccessible = true }.get(client) as ConnectionEpoch
            epochs.close()
            epochs.open(null)
            client.sendReviewedImage(consent, ApprovalUiLease.tap(owner))
            assertTrue(socket.messages.isEmpty())
        } finally { ApprovalUiLease.pause(owner); client.disconnect() }
    }

    @Test fun replacementTapAndResumedDifferentOwnerCannotReviveOldCallback() {
        val original = Any()
        val other = Any()
        ApprovalUiLease.resume(original)
        ApprovalUiLease.resume(other)
        try {
            val old = ApprovalUiLease.tap(original)
            ApprovalUiLease.tap(original)
            assertFalse(ApprovalUiLease.currentTap(old))
            val current = ApprovalUiLease.tap(original)
            ApprovalUiLease.pause(original)
            assertTrue(ApprovalUiLease.hasResumedOwner())
            assertFalse(ApprovalUiLease.currentTap(current))
        } finally { ApprovalUiLease.pause(original); ApprovalUiLease.pause(other) }
    }

    @Test fun approvalActivityBackgroundingRevokesPendingRequest() = runBlocking {
        val session = java.util.UUID.randomUUID().toString()
        val output = SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("legacy") }
        val id = output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        val controller = org.robolectric.Robolectric.buildActivity(com.example.voicebrainlive.routines.AutomationApprovalActivity::class.java,
            Intent(app, com.example.voicebrainlive.routines.AutomationApprovalActivity::class.java).putExtra(com.example.voicebrainlive.routines.AutomationApprovalActivity.EXTRA_APPROVAL_ID, id))
            .create().start().resume()
        try {
            assertNotNull(SensitiveActionGuard.store.pending(id))
            controller.pause()
            assertNull(SensitiveActionGuard.store.pending(id))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { controller.stop().destroy(); SensitiveActionGuard.invalidate(session) }
    }

    @Test fun recreatedApprovalActivityFailsClosedWithoutAnotherAuthentication() = runBlocking {
        val session = java.util.UUID.randomUUID().toString()
        val output = SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("legacy") }
        val id = output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        val controller = org.robolectric.Robolectric.buildActivity(com.example.voicebrainlive.routines.AutomationApprovalActivity::class.java,
            Intent(app, com.example.voicebrainlive.routines.AutomationApprovalActivity::class.java).putExtra(com.example.voicebrainlive.routines.AutomationApprovalActivity.EXTRA_APPROVAL_ID, id))
            .create(android.os.Bundle())
        try {
            assertTrue(controller.get().isFinishing)
            assertNull(SensitiveActionGuard.store.pending(id))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { controller.destroy(); SensitiveActionGuard.invalidate(session) }
    }

    @Test fun closedConnectionCannotInstallLateAuthorizationSession() {
        val connections = ConnectionEpoch()
        val old = connections.open(null)
        connections.close(old)
        val store = ActionApprovalStore()
        val session = java.util.UUID.randomUUID().toString()
        var installed = false
        assertFalse(connections.bind(old, { store.invalidate(session) }, { installed = true }))
        assertFalse(installed)
        assertNull(store.epoch(session))
    }

    @Test fun replacementSynchronouslyRevokesBoundApprovalBeforeInstallingNewSession() {
        val connections = ConnectionEpoch()
        val store = ActionApprovalStore()
        val session = java.util.UUID.randomUUID().toString()
        val old = connections.open(null)
        assertTrue(connections.bind(old, { store.invalidate(session) }, {}))
        val epoch = store.epoch(session)!!
        assertNotNull(store.requestIfCurrent(session, snapshot, epoch))
        val fresh = connections.open(null)
        assertNull(store.epoch(session))
        assertFalse(connections.current(old))
        assertTrue(connections.current(fresh))
        assertNull(store.requestIfCurrent(session, snapshot, epoch))
        assertFalse(connections.close(old))
        assertTrue(connections.current(fresh))
    }

    @Test fun lockedCallbackCannotBeReplayedAfterUnlock() = runBlocking {
        val session = java.util.UUID.randomUUID().toString()
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val output = SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("legacy") }
        val id = output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        val owner = Any()
        val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
        ApprovalUiLease.resume(owner)
        try {
            val tap = ApprovalUiLease.tap(owner)
            shadowOf(keyguard).setIsDeviceLocked(true)
            assertTrue(SensitiveActionGuard.executeApproved(app, id, shown, tap).startsWith("BLOCKED"))
            shadowOf(keyguard).setIsDeviceLocked(false)
            assertNull(SensitiveActionGuard.store.pending(id))
            assertTrue(SensitiveActionGuard.executeApproved(app, id, shown, tap).startsWith("BLOCKED"))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            shadowOf(keyguard).setIsDeviceLocked(false)
            ApprovalUiLease.pause(owner)
            SensitiveActionGuard.invalidate(session)
        }
    }
}
