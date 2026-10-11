package com.example.voicebrainlive

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.commands.*
import com.example.voicebrainlive.commands.handlers.CallCommands
import com.example.voicebrainlive.commands.handlers.PhoneHandler
import com.example.voicebrainlive.commands.handlers.ToolRegistryImpl
import com.example.voicebrainlive.routines.AutomationApprovalQueue
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Robolectric intents/preferences only: no real calls, SMS, remote APIs or device mutations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SensitiveActionGuardTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
    private val content = "  မြန်မာ။\n한국어!\r\nEnglish: MiXeD, and punctuation; 🙂  "
    private val session = java.util.UUID.randomUUID().toString()

    @Before fun setup() {
        SensitiveActionGuard.uiVisible = false
        prefs.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        while (shadowOf(app).nextStartedActivity != null) { /* drain shadow intents */ }
    }

    @After fun cleanup() {
        SensitiveActionGuard.invalidate(session)
        SensitiveActionGuard.uiVisible = false
    }

    private suspend fun executeFixture(context: Context, id: String, shown: ActionSnapshot): String {
        val owner = Any()
        ApprovalUiLease.resume(owner)
        return try { SensitiveActionGuard.executeApproved(context, id, shown, ApprovalUiLease.tap(owner)) }
        finally { ApprovalUiLease.pause(owner) }
    }

    private fun requestId(output: String): String {
        assertTrue(output, output.startsWith("NEEDS_APPROVAL:"))
        return output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
    }

    @Test fun safeDispatchRuns_butUnknownAndSensitiveDoNotInvokeLegacyEffect() = runBlocking {
        var effects = 0
        assertEquals("safe", SensitiveActionGuard.run(app, session, "get_time", null, null) { effects++; "safe" })
        assertTrue(SensitiveActionGuard.run(app, session, "unknown_action", null, null) { effects++; "unsafe" }.startsWith("BLOCKED"))
        requestId(SensitiveActionGuard.run(app, session, "send_sms", "01012345678", content) { effects++; "unsafe" })
        assertEquals(1, effects)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun approvedCall_submitsOneShadowIntent_replayDoesNothing() = runBlocking {
        val id = requestId(SensitiveActionGuard.run(app, session, "dial", "010-1234-5678", null) { error("legacy bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("Call requested.", executeFixture(app, id, shown))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CALL, intent.action)
        assertEquals("tel:01012345678", intent.data.toString())
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun concurrentBridgeRetries_submitOnlyOneShadowCall() = runBlocking {
        val id = requestId(SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        val results = coroutineScope {
            List(8) { async(kotlinx.coroutines.Dispatchers.Default) { executeFixture(app, id, shown) } }.map { it.await() }
        }
        assertEquals(1, results.count { it == "Call requested." })
        assertEquals(7, results.count { it.startsWith("BLOCKED") })
        assertNotNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun approvedSms_preservesExactMultilingualText_andReplayHasNoEffect() = runBlocking {
        val exact = "  မြန်မာ၊\n한국어!\r\nMiXeD 🙂  "
        val sms = shadowOf(android.telephony.SmsManager.getDefault())
        sms.clearLastSentTextMessageParams()
        sms.clearLastSentMultipartTextMessageParams()
        val id = requestId(SensitiveActionGuard.run(app, session, "send_sms", "01012345678", exact) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        assertNull(sms.lastSentTextMessageParams)
        assertNull(sms.lastSentMultipartTextMessageParams)
        assertEquals("SMS request submitted; delivery unverified.", executeFixture(app, id, shown))
        assertEquals("01012345678", sms.lastSentTextMessageParams.destinationAddress)
        assertEquals(exact, sms.lastSentTextMessageParams.text)
        sms.clearLastSentTextMessageParams()
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        assertNull(sms.lastSentTextMessageParams)
    }

    @Test fun emailDraft_hasOnlyReviewedRecipientAndBody_queryInjectionIsRejected() = runBlocking {
        for (recipient in listOf("Alice@example.com?bcc=Bob%40example.com", "Alice@example.com#hidden", "Alice@example.com,Bob@example.com")) {
            assertTrue(SensitiveActionGuard.run(app, session, "compose_email", recipient, content) { error("bypass") }.startsWith("BLOCKED"))
        }
        val recipient = "Alice+tag@example.com"
        val id = requestId(SensitiveActionGuard.run(app, session, "compose_email", recipient, content) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("Email draft opened; not sent.", executeFixture(app, id, shown))
        val draft = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, draft.action)
        assertEquals("com.google.android.gm", draft.`package`)
        assertEquals(recipient, draft.data!!.schemeSpecificPart)
        assertNull(draft.data!!.query)
        assertEquals(content, draft.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun unsafeUiAndDeletionAliases_areBlockedWithoutEffects() = runBlocking {
        for (command in listOf("click_at", "input_text", "notion_delete", "archive_notion_note", "clear_memory", "remove_routine")) {
            assertTrue(command, SensitiveActionGuard.run(app, session, command, "ambiguous", content) { error("legacy bypass") }.startsWith("BLOCKED"))
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun cancelledDeniedOrInvalidatedRequest_doesNotCall() = runBlocking {
        val id = requestId(SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        SensitiveActionGuard.cancel(app, id)
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        val other = requestId(SensitiveActionGuard.run(app, session, "call_contact", "01012345678", null) { error("bypass") })
        val otherShown = SensitiveActionGuard.store.pending(other)!!.snapshot
        SensitiveActionGuard.invalidate(session)
        assertTrue(executeFixture(app, other, otherShown).startsWith("BLOCKED"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun changedRecipientPayloadOrTarget_isRejectedWithoutSideEffect() = runBlocking {
        val id = requestId(SensitiveActionGuard.run(app, session, "send_sms", "01012345678", content) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        for (changed in listOf(shown.copy(recipient = "Bob"), shown.copy(targetId = "tel:01099999999"), shown.copy(payload = content.trim()))) {
            assertTrue(executeFixture(app, id, changed).startsWith("BLOCKED"))
        }
        assertEquals(content, shown.payload)
        assertTrue(shown.preview().endsWith(content))
    }

    @Test fun permissionsAreRechecked_afterPreview() = runBlocking {
        val id = requestId(SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun lockedDevice_cannotClaimApproval() = runBlocking {
        val id = requestId(SensitiveActionGuard.run(app, session, "make_call", "01012345678", null) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguard).setIsDeviceLocked(true)
        try {
            assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { shadowOf(keyguard).setIsDeviceLocked(false) }
    }

    @Test fun changedQuickContact_requiresFreshApproval() = runBlocking {
        prefs.edit().putString("alice", "01012345678").commit()
        val id = requestId(SensitiveActionGuard.run(app, session, "call", "Alice", null) { error("bypass") })
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        prefs.edit().putString("alice", "01099999999").commit()
        assertTrue(executeFixture(app, id, shown).startsWith("BLOCKED"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun ambiguousContact_andAmbientNotification_areBlocked() = runBlocking {
        prefs.edit().putString("Alice", "01012345678").putString("alice", "01099999999").commit()
        assertTrue(SensitiveActionGuard.run(app, session, "call", "ALICE", null) { error("bypass") }.startsWith("BLOCKED"))
        com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender = "Alice"
        try {
            assertTrue(SensitiveActionGuard.run(app, session, "reply_message", null, content) { error("bypass") }.startsWith("BLOCKED"))
        } finally { com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender = null }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun registryExecutorAndDirectHandlers_allRequireApproval_forAliases() = runBlocking {
        val registry = ToolRegistryImpl(app, prefs)
        val executor = PhoneCommandExecutor(app)
        val phone = PhoneHandler(app, prefs)
        val calls = CallCommands(app)
        requestId(registry.executeCommandAsync("call_contact", "01012345678", null)!!)
        requestId(registry.executeCommand("call", "01012345678", null)!!)
        requestId(executor.executeAsync("dial", "01012345678", null))
        requestId(phone.execute("call", "01012345678", null))
        requestId(calls.execute("make_call", "01012345678", null)!!)
        assertNull(shadowOf(app).nextStartedActivity)
        registry.invalidateApprovals()
        executor.invalidateApprovals()
    }

    @Test fun automationAndMacro_configurationIsNotApproval_exactPayloadRemainsPending() = runBlocking {
        val runner = AutomationCommandRunner(app)
        val output = runner.run("command", "send_sms:01012345678:$content")
        val id = requestId(output)
        assertEquals(content, SensitiveActionGuard.store.pending(id)!!.snapshot.payload)
        SensitiveActionGuard.cancel(app, id)
        app.getSharedPreferences("custom_macros", Context.MODE_PRIVATE).edit()
            .putString("pending_sms", "send_sms:01012345678:$content").commit()
        val executor = PhoneCommandExecutor(app)
        val macro = executor.executeAsync("run_macro", "pending_sms", null)
        assertTrue(macro, macro.contains("NEEDS_APPROVAL"))
        val macroId = macro.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        assertEquals(content, SensitiveActionGuard.store.pending(macroId)!!.snapshot.payload)
        executor.invalidateApprovals()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun modelApprovedTrue_andGeneralYes_doNotGrantAuthority() = runBlocking {
        val resolved = ActionDispatcher.resolve(JSONObject().put("command_type", "send_sms")
            .put("recipient", "01012345678").put("message", content).put("approved", true))
        assertEquals(content, CommandPlan.from(resolved).value)
        val executor = PhoneCommandExecutor(app)
        val id = requestId(executor.executeAsync(resolved.commandType, resolved.target, resolved.value))
        val shown = SensitiveActionGuard.store.pending(id)!!.snapshot
        assertFalse(SensitiveActionGuard.store.consume(id, "yes", shown))
        executor.invalidateApprovals()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun persistedQueueWithoutProcessCapability_cannotExecute() = runBlocking {
        val pending = AutomationApprovalQueue.enqueue(app, "make_call", "01012345678", null, "legacy approved=true")
        val forged = ActionSnapshot("make_call", "01012345678", "Phone", "tel:01012345678", null, "call")
        assertTrue(executeFixture(app, pending.id, forged).startsWith("BLOCKED"))
        AutomationApprovalQueue.remove(app, pending.id)
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
