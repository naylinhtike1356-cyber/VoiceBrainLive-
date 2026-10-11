package com.example.voicebrainlive

import com.example.voicebrainlive.commands.ActionApprovalStore
import com.example.voicebrainlive.commands.ActionSnapshot
import com.example.voicebrainlive.commands.CommandSafetyPolicy
import com.example.voicebrainlive.commands.AutomationCommandValidator
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class ActionApprovalStoreTest {
    private val action = ActionSnapshot("send_sms", "Alice", "Android SMS", "tel:01012345678",
        "  မြန်မာ၊\n한국어!\r\nEnglish: MiXeD ‘quotes’; 🙂  ", "one recipient", "version1")

    @Test fun noApproval_hasNoSideEffect() {
        val store = ActionApprovalStore()
        val request = store.request("session", action)
        var effects = 0
        if (store.consume(request.id, "session", action)) effects++
        assertEquals(0, effects)
    }

    @Test fun approvedAction_runsOnce_andRejectsReplay() {
        val store = ActionApprovalStore()
        val request = store.request("session", action)
        assertTrue(store.approve(request.id, action))
        var effects = 0
        repeat(3) { if (store.consume(request.id, "session", action)) effects++ }
        assertEquals(1, effects)
        assertFalse(store.approve(request.id, action))
    }

    @Test fun cancelledOrDeniedRequest_cannotBeApprovedOrConsumed() {
        val store = ActionApprovalStore()
        val request = store.request("session", action)
        store.cancel(request.id)
        assertFalse(store.approve(request.id, action))
        assertFalse(store.consume(request.id, "session", action))
    }

    @Test fun cancellationAfterApproval_alsoRevokesGrant() {
        val store = ActionApprovalStore()
        val request = store.request("session", action)
        assertTrue(store.approve(request.id, action))
        store.cancel(request.id)
        assertFalse(store.consume(request.id, "session", action))
    }

    @Test fun expiryBeforeApproval_andBeforeConsumption_denies() {
        var now = 100L
        val store = ActionApprovalStore({ now }, 50)
        val first = store.request("session", action)
        now = 150
        assertNull(store.pending(first.id))
        assertFalse(store.approve(first.id, action))
        val second = store.request("session", action)
        assertTrue(store.approve(second.id, action))
        now = 200
        assertFalse(store.consume(second.id, "session", action))
    }

    @Test fun everyScopeField_mustMatchExactly() {
        val changes = listOf(action.copy(command = "send_email"), action.copy(recipient = "Bob"),
            action.copy(destination = "Other app"), action.copy(targetId = "tel:01099999999"),
            action.copy(payload = action.payload!!.lowercase()), action.copy(payload = action.payload!!.trim()),
            action.copy(payload = action.payload!!.replace("\r\n", "\n")), action.copy(scope = "all recipients"),
            action.copy(revision = "version2"))
        for (changed in changes) {
            val store = ActionApprovalStore()
            val request = store.request("session", action)
            assertFalse("Changed preview must not mint a grant: $changed", store.approve(request.id, changed))
            assertTrue(store.approve(request.id, action))
            assertFalse("Changed effect must not consume a grant: $changed", store.consume(request.id, "session", changed))
        }
    }

    @Test fun sessionMismatchAndInvalidation_rejectExecution() {
        val store = ActionApprovalStore()
        val request = store.request("session", action)
        assertTrue(store.approve(request.id, action))
        assertFalse(store.consume(request.id, "other session", action))
        store.invalidate("session")
        assertFalse(store.consume(request.id, "session", action))
    }

    @Test fun concurrentConsumption_claimsBeforeSideEffect_exactlyOnce() {
        val store = ActionApprovalStore()
        val request = store.request("session", action)
        assertTrue(store.approve(request.id, action))
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val effects = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(16) { pool.execute {
                start.await()
                if (store.consume(request.id, "session", action)) effects.incrementAndGet()
                done.countDown()
            } }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, effects.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun fullPreview_preservesBurmeseKoreanEnglish_andWhitespace() {
        assertTrue(action.preview().endsWith(action.payload!!))
        assertTrue(action.preview().contains(action.targetId))
        assertTrue(action.preview().contains(action.destination))
        assertTrue(action.preview().contains(action.scope))
    }

    @Test fun aliasesAndUnknownActions_neverInheritSafePermission() {
        val sensitive = listOf("CALL", " dial ", "call_contact", "call_quick_contact", "compose_email",
            "messenger", "reply_message", "whatsapp", "notion_delete", "archive_notion_note", "notion_append",
            "sync_notion", "delete_all_notes", "delete_fact", "remove_memory", "remove_routine", "reset_memory",
            "click_at", "input_text", "copy_yesterday")
        sensitive.forEach { assertEquals(it, CommandSafetyPolicy.Classification.SENSITIVE, CommandSafetyPolicy.classify(it)) }
        assertEquals(CommandSafetyPolicy.Classification.UNKNOWN, CommandSafetyPolicy.classify("send_new_service"))
        assertTrue(CommandSafetyPolicy.requiresConfirmation("unknown"))
        assertFalse(CommandSafetyPolicy.requiresConfirmation("get_current_time"))
    }

    @Test fun automationParser_preservesExactPayload_evenWithSeparatorsAndAliases() {
        for (command in listOf("send_sms", "messenger", "whatsapp", "compose_email")) {
            val sequence = "$command:Alice:${action.payload}"
            assertEquals(listOf(sequence), AutomationCommandValidator.extractSteps(sequence))
            assertEquals(action.payload, AutomationCommandValidator.extractSteps(sequence).single().split(":", limit = 3)[2])
        }
        assertEquals(listOf("get_time", "send_sms:Alice:${action.payload}"),
            AutomationCommandValidator.extractSteps("get_time;send_sms:Alice:${action.payload}"))
    }

    @Test fun offlineRawCommandParser_keepsContentSeparateFromCommandNormalization() {
        val resolved = OfflineCommandMatcher.match(" SEND_SMS :01012345678:${action.payload}")!!
        assertEquals("send_sms", resolved.first)
        assertEquals("01012345678", resolved.second)
        assertEquals(action.payload, resolved.third)
    }
}
