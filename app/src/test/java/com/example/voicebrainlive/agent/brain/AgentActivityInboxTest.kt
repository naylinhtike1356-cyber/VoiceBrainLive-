package com.example.voicebrainlive.agent.brain

import android.app.Application
import android.app.AlertDialog
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.persistence.*
import com.example.voicebrainlive.agent.notion.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.example.voicebrainlive.agent.proactive.*
import com.example.voicebrainlive.commands.CommandPlan
import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

class InboxTestActivity : AgentActivityInboxActivity() {
    override suspend fun readRows() = fixture
    override suspend fun mutate(row: AgentInboxRow, pause: Boolean) { mutations++ }
    companion object { var fixture = AgentActivityInboxState(emptyList()); var mutations = 0 }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentActivityInboxTest {
    private fun task(status: AgentStatus = AgentStatus.DRAFT, effect: Boolean = false): BrainTask {
        val command = if (effect) CommandPlan("set_reminder", "မနက်ဖြန် 공부", "19:00", requiresConfirmation = false)
            else CommandPlan("get_current_time", null, null)
        val step = AgentStep("one", "Review one step", command, deadlineMillis = 60_000, idempotencyKey = "task_one")
        val plan = AgentPlan("task", objective = "Private Burmese မြန်မာ / 한국어 / English goal", createdAtMillis = 1_000,
            deadlineMillis = 60_000, idempotencyKey = "task_key", steps = listOf(step))
        val definition = BrainPlan(plan, "", "0".repeat(64), BrainBudget())
        return BrainTask(1, "old_session", definition, AgentTaskSnapshot("task", null, plan.objective, status,
            1_000, 1_000, 60_000, "task_key", 0, emptyList()))
    }
    private fun project(vararg tasks: BrainTask, now: Long = 2_000) = AgentActivityProjection.project(tasks.toList(), emptyList(), ProactiveSnapshot(), now)
    @Before fun reset() { InboxTestActivity.fixture = AgentActivityInboxState(emptyList()); InboxTestActivity.mutations = 0 }
    @Test fun proposedActionIgnoresForgedConfirmationFlagAndPreservesExactPreview() {
        val row = project(task(AgentStatus.WAITING_FOR_APPROVAL, true)).rows.single()
        assertTrue(row.requiresFreshApproval); assertEquals(PersistentTaskState.BLOCKED, row.state)
        assertTrue(row.detail.contains("မနက်ဖြန် 공부")); assertTrue(row.detail.contains("19:00"))
        assertTrue(row.detail.contains("NOT APPROVED")); assertTrue(row.blockedReason!!.contains("Fresh approval"))
    }
    @Test fun restoredAndExpiredTasksCannotInheritApproval() {
        val expired = project(task(effect = true), now = 70_000).rows.single()
        assertEquals(PersistentTaskState.BLOCKED, expired.state); assertTrue(expired.blockedReason!!.contains("expired"))
        val restored = project(task(AgentStatus.WAITING_FOR_APPROVAL, true)).rows.single()
        assertTrue(restored.requiresFreshApproval); assertEquals(PersistentTaskState.BLOCKED, restored.state)
    }
    @Test fun completedFailedAndPendingTasksRemainDistinct() {
        assertEquals(PersistentTaskState.COMPLETED, project(task(AgentStatus.SUCCEEDED), now = 80_000).rows.single().state)
        assertEquals(PersistentTaskState.FAILED, project(task(AgentStatus.FAILED)).rows.single().state)
        assertEquals(PersistentTaskState.DRAFT, project(task()).rows.single().state)
        assertFalse(project(task(AgentStatus.RUNNING)).rows.single().canCancel)
    }
    @Test fun cancellationIsExactRevisionBoundAndNeverExecutesThePlan() {
        var stored: ByteArray? = null
        val bytes = object : AtomicMemoryBytes {
            override fun readOrNull() = stored?.copyOf()
            override fun replaceAtomically(bytes: ByteArray) { stored = bytes.copyOf() }
        }
        val store = EncryptedBrainStore(bytes) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        val initial = store.create(task())
        val cancelled = AgentActivityProjection.cancelPending(store, "task", initial.revision)
        assertEquals(PersistentTaskState.CANCELLED, cancelled.state); assertEquals(0, cancelled.totalCalls)
        assertEquals(initial.definition, cancelled.definition)
        assertThrows(BrainFailure::class.java) { AgentActivityProjection.cancelPending(store, "task", initial.revision) }
    }
    @Test fun proactiveHistoryShowsSourceBlockedReasonAndNoRestoredGrant() {
        val config = DailySimulationConfig("UTC", 1140, 1200, monitor = MockTaskReviewConfig("Review study tasks", "profile", 2, 100_000))
        val rule = ProactiveRule("rule", 2, 3, RuleLifecycle.ACTIVE, config, 2, 1_000, 2_000,
            highestConsumedDay = 0, lastCheckAt = 2_000, lastAttemptAt = 2_000, lastResultAt = 2_000, nextAdvisoryAt = 50_000)
        val execution = MockReviewExecution(PersistentRunOwner("old_process", "attempt"), 1, PersistentTaskState.WAITING_FOR_APPROVAL,
            2_000, MockReviewReason.APPROVAL_UNAVAILABLE, sourceId = "source")
        val occurrence = RuleOccurrence("occurrence", "rule", 2, 0, 2_000, RuleDecision.RESULT_READY,
            GenericRuleResult.SYNTHETIC_CHECK_IN_READY, execution = execution)
        val state = AgentActivityProjection.project(emptyList(), emptyList(), ProactiveSnapshot(1, 2, 2_000,
            2_000, listOf(rule), listOf(occurrence)), 3_000)
        val history = state.rows.single { it.source == InboxSource.OCCURRENCE }
        assertEquals(PersistentTaskState.BLOCKED, history.state); assertTrue(history.requiresFreshApproval)
        assertTrue(history.attribution.contains("source")); assertTrue(history.detail.contains("no approval is restored"))
        assertTrue(state.rows.single { it.source == InboxSource.RULE }.canPause)
        assertFalse(state.rows.single { it.source == InboxSource.RULE }.canCancel) // preserve config; permanent deletion uses existing confirmed Rules UI
    }
    @Test fun localScheduledTaskStateAndCancellationAreOnlyPresentation() {
        val identity = PersistentTaskIdentity("scheduled", null, "scheduled_key", 1_000, 60_000,
            listOf(PersistentStepIdentity("clock", "clock_key"), PersistentStepIdentity("battery", "battery_key"), PersistentStepIdentity("report", "report_key")))
        val scheduled = PersistentTaskRecord(identity, PersistentTaskRecipe.LOCAL_STATUS, PersistentTaskState.SCHEDULED,
            2, 2_000, 80_000, 50_000, 0, null, PersistentTaskReason.NONE)
        val row = AgentActivityProjection.project(emptyList(), listOf(scheduled), ProactiveSnapshot(), 3_000).rows.single()
        assertEquals(PersistentTaskState.SCHEDULED, row.state); assertTrue(row.canCancel); assertTrue(row.canPause)
        assertFalse(row.requiresFreshApproval)
    }
    @Test fun proactiveMockProposalFlowsToExactInboxPreviewButNotIntoRestartedStorage() = runBlocking {
        val notion = NotionFixture().apply { now = java.time.Instant.parse("2026-10-20T18:55:00Z").toEpochMilli() }
        val profile = notion.profile("study"); notion.repo.select(profile.profileId, profile.revision)
        var saved = ProactiveRuleCodec.encode(ProactiveSnapshot()); var httpCalls = 0
        val persistence = object : ProactiveRulePersistence {
            override fun read() = ProactiveRuleCodec.decode(saved)
            override fun compareAndReplace(expectedRevision: Long, next: ProactiveSnapshot): Boolean {
                if (read().revision != expectedRevision) return false
                saved = ProactiveRuleCodec.encode(next); return true
            }
        }
        val rules = ProactiveRuleRepository(persistence, { notion.now }, io = Dispatchers.Unconfined)
        val work = MockReviewWork { config, pin, owner ->
            val adapter = NotionReadOnlyAdapter(notion.repo, MockNotionReadHttp { request, _ ->
                httpCalls++; assertEquals(NotionReadOperation.QUERY_TASKS, request.operation)
                val body = """{"object":"list","has_more":false,"next_cursor":null,"results":[{"object":"page","id":"task","parent":{"type":"data_source_id","data_source_id":"${pin.resource.dataSourceId}"},"properties":{"Title":{"id":"title","type":"title","title":[{"plain_text":"Study Korean"}]},"State":{"id":"business","type":"status","status":{"name":"Todo"}},"Sync":{"id":"sync","type":"select","select":{"name":"Synced"}},"Priority":{"id":"priority","type":"number","number":90}}}]}"""
                MockNotionHttpReply(pin.profileId, pin.profileRevision, 200, body.toByteArray())
            })
            ReadOnlyTaskReview(notion.repo, adapter, BrainMemoryAccess({ false }, { emptyList() }, { notion.now }), { notion.now }, owner)
                .review(config.objective, pin, CommandPlan("send_to_notion", "Exact Notion target", "Exact proposed content"))
        }
        val queue = object : MockReviewWakeups {
            override suspend fun enqueue(ruleId: String, configRevision: Long, eligibleAt: Long) { }
            override suspend fun cancel(ruleId: String) { }
        }
        val coordinator = MockReviewCoordinator(rules, notion.repo, work, queue, { notion.now }, "old_process")
        val draft = rules.createDisabled(DailySimulationConfig("UTC", 1140, 1200, monitor = MockTaskReviewConfig(
            "နေ့စဉ် task review", profile.profileId, profile.revision, notion.now + 86_400_000)))
        val enabled = coordinator.enableReviewed(draft.ruleId, draft.configRevision); notion.now += 300_000
        val dispatch = coordinator.trigger(enabled.ruleId, enabled.configRevision)
        val current = AgentActivityProjection.project(emptyList(), emptyList(), rules.history(), notion.now, dispatch.briefing)
        val preview = current.rows.single { it.source == InboxSource.MOCK_PREVIEW }
        assertTrue(preview.detail.contains("Exact Notion target")); assertTrue(preview.detail.contains("Exact proposed content"))
        assertTrue(preview.requiresFreshApproval); assertEquals(PersistentTaskState.BLOCKED, preview.state)
        assertFalse(preview.canCancel); assertFalse(preview.canPause); assertEquals(1, httpCalls)
        assertFalse(String(saved, Charsets.ISO_8859_1).contains("Exact proposed content"))
        val restartedRules = ProactiveRuleRepository(persistence, { notion.now }, io = Dispatchers.Unconfined)
        val restarted = MockReviewCoordinator(restartedRules, notion.repo, work, queue, { notion.now }, "new_process")
        restarted.recoverInterrupted()
        val afterRestart = AgentActivityProjection.project(emptyList(), emptyList(), restartedRules.history(), notion.now)
        assertFalse(afterRestart.rows.any { it.source == InboxSource.MOCK_PREVIEW })
        assertEquals(RuleDecision.DUPLICATE, restarted.trigger(enabled.ruleId, enabled.configRevision).decision)
        assertEquals(1, httpCalls) // no remote mutation or replayed read/approval
    }
    @Test fun privateDetailsAreSecureAndDroppedWhenActivityPauses() {
        InboxTestActivity.fixture = project(task(AgentStatus.WAITING_FOR_APPROVAL, true))
        val controller = Robolectric.buildActivity(InboxTestActivity::class.java).setup(); val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<Button>("inbox_details_brain:task").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val text = dialog.window!!.decorView.findViewWithTag<TextView>("inbox_details_text")
        assertTrue(text.text.contains("NOT APPROVED")); assertFalse(text.isSaveEnabled)
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        // Android inflates hidden buttons; only Close may be visible, never approval or execution.
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility != android.view.View.VISIBLE)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility != android.view.View.VISIBLE)
        assertEquals("ပိတ်ရန်", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        controller.pause(); assertEquals("", text.text.toString()); assertFalse(dialog.isShowing)
        assertEquals(0, InboxTestActivity.mutations); controller.stop().destroy()
    }
    @Test fun obsoleteUiCallbackCannotCancelAfterPauseAndReplacement() {
        InboxTestActivity.fixture = project(task())
        val controller = Robolectric.buildActivity(InboxTestActivity::class.java).setup(); val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val old = activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<Button>("inbox_cancel_brain:task")
        controller.pause(); old.performClick(); assertEquals(0, InboxTestActivity.mutations)
        InboxTestActivity.fixture = project(task().copy(revision = 2)); controller.resume(); shadowOf(Looper.getMainLooper()).idle()
        old.performClick(); assertEquals(0, InboxTestActivity.mutations)
        activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<Button>("inbox_cancel_brain:task").performClick()
        assertEquals(1, InboxTestActivity.mutations); controller.pause().stop().destroy()
    }
}
