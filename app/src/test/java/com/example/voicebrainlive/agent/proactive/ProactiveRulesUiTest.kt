package com.example.voicebrainlive.agent.proactive

import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.example.voicebrainlive.VoiceBrainApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

class ProactiveUiTestApplication : VoiceBrainApplication() {
    override val proactiveRules get() = fake
    override fun onCreate() { /* No real key, task scheduler, provider or service. */ }
    companion object { lateinit var fake: ProactiveRules }
}

class FakeProactiveRules : ProactiveRules {
    var data = ProactiveSnapshot()
    var unreadable = false
    var createCalls = 0; var enableCalls = 0; var evaluationCalls = 0; var deleteCalls = 0
    var lastEnabled: Pair<String, Long>? = null
    var historyGate: CompletableDeferred<Unit>? = null
    override suspend fun history(): ProactiveSnapshot {
        historyGate?.await()
        if (unreadable) throw RuleStoreException(RuleFailure.DATA_UNREADABLE)
        return data
    }
    override suspend fun createDisabled(config: DailySimulationConfig): ProactiveRule {
        createCalls++
        return ProactiveRule("rule_${createCalls}", 1, 1, RuleLifecycle.DISABLED, config, null, 1000, 1000).also {
            data = data.copy(revision = data.revision + 1, updatedAt = 1000, rules = data.rules + it)
        }
    }
    private fun exact(id: String, revision: Long) = data.rules.singleOrNull { it.ruleId == id && it.configRevision == revision }
        ?: throw RuleStoreException(RuleFailure.STALE_REVISION)
    private fun replace(rule: ProactiveRule): ProactiveRule {
        data = data.copy(revision = data.revision + 1, rules = data.rules.map { if (it.ruleId == rule.ruleId) rule else it })
        return rule
    }
    override suspend fun updateDisabled(id: String, revision: Long, config: DailySimulationConfig) =
        replace(exact(id, revision).let { it.copy(config = config, configRevision = revision + 1, stateRevision = it.stateRevision + 1, lifecycle = RuleLifecycle.DISABLED, reviewedRevision = null) })
    override suspend fun enableReviewed(id: String, revision: Long): ProactiveRule {
        val old = exact(id, revision); enableCalls++; lastEnabled = id to revision
        return replace(old.copy(configRevision = revision + 1, stateRevision = old.stateRevision + 1, lifecycle = RuleLifecycle.ACTIVE, reviewedRevision = revision + 1))
    }
    override suspend fun pause(id: String, revision: Long) = replace(exact(id, revision).let { it.copy(configRevision = revision + 1, stateRevision = it.stateRevision + 1, lifecycle = RuleLifecycle.PAUSED, reviewedRevision = null) })
    override suspend fun delete(id: String, revision: Long): ProactiveRule {
        deleteCalls++; return replace(exact(id, revision).let { it.copy(configRevision = revision + 1, stateRevision = it.stateRevision + 1, lifecycle = RuleLifecycle.DELETED, config = null, reviewedRevision = null) })
    }
    override suspend fun evaluateSynthetic(id: String, revision: Long, stateRevision: Long, signal: ClosedSyntheticSignal): EvaluationView {
        val rule = exact(id, revision); require(rule.stateRevision == stateRevision)
        evaluationCalls++
        return EvaluationView(rule, if (signal.ready) RuleDecision.RESULT_READY else RuleDecision.CONDITION_FALSE)
    }
}

private class UiRuleMemory : ProactiveRulePersistence {
    var state = ProactiveSnapshot()
    override fun read() = freezeRules(state)
    override fun compareAndReplace(expectedRevision: Long, next: ProactiveSnapshot): Boolean {
        if (state.revision != expectedRevision) return false
        state = freezeRules(next); return true
    }
}
/** Actual policy/repository with an in-memory snapshot CAS restart seam; no disk/key/provider IO. */
private class RepositoryUiRules(memory: UiRuleMemory) : ProactiveRules {
    private val repository = ProactiveRuleRepository(memory,
        clock = { java.time.Instant.parse("2026-10-15T12:00:00Z").toEpochMilli() },
        ids = { "rule_persisted" }, io = Dispatchers.Unconfined)
    var evaluationCalls = 0
    override suspend fun history() = repository.history()
    override suspend fun createDisabled(config: DailySimulationConfig) = repository.createDisabled(config)
    override suspend fun updateDisabled(id: String, revision: Long, config: DailySimulationConfig) = repository.updateDisabled(id, revision, config)
    override suspend fun enableReviewed(id: String, revision: Long) = repository.enableReviewed(id, revision)
    override suspend fun pause(id: String, revision: Long) = repository.pause(id, revision)
    override suspend fun delete(id: String, revision: Long) = repository.delete(id, revision)
    override suspend fun evaluateSynthetic(id: String, revision: Long, stateRevision: Long, signal: ClosedSyntheticSignal): EvaluationView {
        evaluationCalls++; return repository.evaluateSynthetic(id, revision, stateRevision, signal)
    }
}

/** Prepared candidate tests only; real execution must follow source adoption and current-source gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ProactiveUiTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ProactiveRulesUiTest {
    private lateinit var fake: FakeProactiveRules
    @Before fun fixture() { fake = FakeProactiveRules(); ProactiveUiTestApplication.fake = fake }
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun Button.click() { assertTrue(isEnabled); performClick(); idle() }
    private fun button(activity: ProactiveRulesActivity, tag: String) = activity.window.decorView.findViewWithTag<Button>(tag)!!
    private fun latest(): AlertDialog { idle(); return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) } }
    private fun open() = Robolectric.buildActivity(ProactiveRulesActivity::class.java).setup().visible().also { idle() }
    private fun text(view: View): String = (if (view is TextView) view.text.toString() else "") +
        (if (view is ViewGroup) (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) } else "")

    @Test fun initialAndRecreatedHistoryLoadEnablesDraftControlsWithoutEvaluating() {
        val controller = open()
        assertTrue(button(controller.get(), "proactive_save").isEnabled)
        controller.recreate(); idle()
        assertTrue(button(controller.get(), "proactive_save").isEnabled)
        assertEquals(0, fake.createCalls); assertEquals(0, fake.evaluationCalls)
        controller.pause().stop().destroy()
    }
    @Test fun savingDraftDoesNotEnableUntilExactCurrentReviewIsConfirmedOnce() {
        val controller = open(); val activity = controller.get()
        button(activity, "proactive_save").click()
        assertEquals(1, fake.createCalls); assertEquals(0, fake.enableCalls)
        val preview = latest(); val positive = preview.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.click(); positive.performClick(); idle()
        assertEquals(1, fake.enableCalls); assertEquals("rule_1" to 1L, fake.lastEnabled)
        assertEquals(0, fake.evaluationCalls)
        assertTrue(button(activity, "proactive_evaluate_rule_1").isEnabled)
        controller.pause().stop().destroy()
    }
    @Test fun hiddenOldPreviewCannotEnableAfterFreshActivityLoads() {
        val old = open(); button(old.get(), "proactive_save").click()
        val oldPositive = latest().getButton(AlertDialog.BUTTON_POSITIVE)
        old.pause().stop()
        val fresh = open(); oldPositive.performClick(); idle()
        assertEquals(0, fake.enableCalls)
        assertTrue(button(fresh.get(), "proactive_review_rule_1").isEnabled)
        fresh.pause().stop().destroy(); old.destroy()
    }
    @Test fun invalidConfigurationNeverReachesStorage() {
        val controller = open(); val activity = controller.get()
        activity.window.decorView.findViewWithTag<EditText>("proactive_zone").setText("invalid-zone")
        button(activity, "proactive_save").click()
        assertEquals(0, fake.createCalls); assertEquals(0, fake.enableCalls)
        controller.pause().stop().destroy()
    }
    @Test fun unreadableHistoryDisablesWritesRatherThanShowingWritableEmptyStore() {
        fake.unreadable = true
        val controller = open()
        assertFalse(button(controller.get(), "proactive_save").isEnabled)
        assertTrue(button(controller.get(), "proactive_refresh").isEnabled)
        assertEquals(0, fake.createCalls)
        controller.pause().stop().destroy()
    }
    @Test fun evaluatingRequiresExplicitButtonAndDoesNotRunOnRefreshOrRestart() {
        val controller = open(); val activity = controller.get()
        button(activity, "proactive_save").click(); latest().getButton(AlertDialog.BUTTON_POSITIVE).click()
        button(activity, "proactive_refresh").click(); assertEquals(0, fake.evaluationCalls)
        button(activity, "proactive_evaluate_rule_1").click(); assertEquals(1, fake.evaluationCalls)
        controller.recreate(); idle(); assertEquals(1, fake.evaluationCalls)
        controller.pause().stop().destroy()
    }
    @Test fun deleteRequiresExactConfirmationAndRetainsVisibleTombstone() {
        val controller = open(); val activity = controller.get()
        button(activity, "proactive_save").click(); latest().dismiss(); idle()
        button(activity, "proactive_delete_rule_1").click(); assertEquals(0, fake.deleteCalls)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).click()
        assertEquals(1, fake.deleteCalls); assertEquals(RuleLifecycle.DELETED, fake.data.rules.single().lifecycle)
        assertNull(activity.window.decorView.findViewWithTag<Button>("proactive_review_rule_1"))
        assertEquals(0, fake.evaluationCalls)
        controller.pause().stop().destroy()
    }
    @Test fun trueSyntheticResultPersistsThroughRepositoryAndActivityRecreationWithoutReevaluation() {
        val memory = UiRuleMemory(); val first = RepositoryUiRules(memory)
        ProactiveUiTestApplication.fake = first
        val controller = open(); val activity = controller.get(); val root = activity.window.decorView
        mapOf("proactive_zone" to "UTC", "proactive_trigger" to "00:00", "proactive_end" to "24:00",
            "proactive_quiet_start" to "", "proactive_quiet_end" to "").forEach { (tag, value) -> root.findViewWithTag<EditText>(tag).setText(value) }
        root.findViewWithTag<CheckBox>("proactive_signal").isChecked = true
        button(activity, "proactive_save").click(); latest().getButton(AlertDialog.BUTTON_POSITIVE).click()
        button(activity, "proactive_evaluate_rule_persisted").click()
        assertEquals(1, first.evaluationCalls)
        assertEquals(GenericRuleResult.SYNTHETIC_CHECK_IN_READY, memory.state.occurrences.single().result)
        assertTrue(text(root).contains("နမူနာအခြေအနေ ကိုက်ညီပါသည်။ သတ်မှတ်ထားသော စည်းမျဉ်းကို ပြန်ကြည့်ပါ။"))
        val restarted = RepositoryUiRules(memory); ProactiveUiTestApplication.fake = restarted
        controller.recreate(); idle()
        assertTrue(text(controller.get().window.decorView).contains("နမူနာအခြေအနေ ကိုက်ညီပါသည်။ သတ်မှတ်ထားသော စည်းမျဉ်းကို ပြန်ကြည့်ပါ။"))
        assertEquals(0, restarted.evaluationCalls); assertEquals(1, memory.state.occurrences.size)
        controller.pause().stop().destroy()
    }
    @Test fun busyHistoryDisablesRowActionsAndHiddenCompletionCannotUpdateItsOldView() {
        val controller = open(); val activity = controller.get()
        button(activity, "proactive_save").click(); latest().dismiss(); idle()
        val root = activity.window.decorView; val before = text(root)
        val gate = CompletableDeferred<Unit>(); fake.historyGate = gate
        button(activity, "proactive_refresh").click()
        assertFalse(button(activity, "proactive_save").isEnabled)
        assertFalse(button(activity, "proactive_review_rule_1").isEnabled)
        assertFalse(button(activity, "proactive_delete_rule_1").isEnabled)
        controller.pause().stop(); fake.unreadable = true; gate.complete(Unit); idle()
        assertEquals(before, text(root))
        fake.unreadable = false; fake.historyGate = null
        controller.start().resume().visible(); idle()
        assertTrue(button(activity, "proactive_save").isEnabled)
        assertTrue(button(activity, "proactive_review_rule_1").isEnabled)
        assertEquals(0, fake.enableCalls); assertEquals(0, fake.evaluationCalls)
        controller.pause().stop().destroy()
    }
}
