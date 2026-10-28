package com.example.voicebrainlive.agent.brain

import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.persistence.*
import com.example.voicebrainlive.agent.proactive.*
import java.util.Collections
import kotlinx.coroutines.*

enum class InboxSource { BRAIN, SCHEDULED_TASK, RULE, OCCURRENCE, MOCK_PREVIEW }
data class AgentInboxRow(val key: String, val id: String, val revision: Long, val source: InboxSource,
    val state: PersistentTaskState, val title: String, val detail: String, val attribution: String,
    val blockedReason: String?, val requiresFreshApproval: Boolean = false,
    val canCancel: Boolean = false, val canPause: Boolean = false) {
    override fun toString() = "AgentInboxRow(source=$source, state=$state, details=<redacted>)"
}
data class AgentActivityInboxState(val rows: List<AgentInboxRow>)

/** Presentation only: neither a status nor displaying an exact proposal is an execution grant. */
object AgentActivityProjection {
    fun project(brain: List<BrainTask>, scheduled: List<PersistentTaskRecord>, proactive: ProactiveSnapshot,
        now: Long, mockPreview: com.example.voicebrainlive.agent.notion.ReadOnlyReviewResult? = null): AgentActivityInboxState {
        val rows = mutableListOf<AgentInboxRow>()
        brain.forEach { task ->
            val plan = task.definition.plan
            if(LocalUserTaskRecord.matches(task)) {
                val note=when(task.snapshot.status){
                    AgentStatus.REPORTED->"Marked done by you. No automated action ran."
                    AgentStatus.CANCELLED->"Cancelled by you; history retained."
                    else->"Saved user task. No automated action has run."
                }
                rows += AgentInboxRow("brain:${plan.taskId}",plan.taskId,task.revision,InboxSource.BRAIN,
                    task.state,"Local task",plan.objective+"\n"+note,"Your local task",null,
                    canCancel=task.snapshot.status==AgentStatus.DRAFT)
                return@forEach
            }
            val proposals = plan.steps.filter { AgentCapabilities.get(it.command.intent)?.effect != AgentEffect.READ_ONLY }
            val terminal = task.state in setOf(PersistentTaskState.COMPLETED, PersistentTaskState.CANCELLED, PersistentTaskState.FAILED)
            val reason = when {
                !terminal && now >= plan.deadlineMillis -> "This plan expired. Prepare and review a new plan."
                task.state == PersistentTaskState.WAITING_FOR_APPROVAL -> "Fresh approval required. Saved approvals cannot be reused."
                task.reason != null -> task.reason
                proposals.isNotEmpty() -> "Action execution is unavailable here; a fresh common approval is required."
                else -> null
            }
            val state = if (!terminal && (now >= plan.deadlineMillis || task.state == PersistentTaskState.WAITING_FOR_APPROVAL))
                PersistentTaskState.BLOCKED else task.state
            val attribution = plan.steps.mapNotNull { it.connection }.distinct().joinToString("\n") {
                "Source reference ${it.connectionId} / ${it.resourceId}; provider access not verified"
            }.ifEmpty { "Local source" }
            val records = task.snapshot.steps.associateBy { it.stepId }
            val detail = "${plan.objective}\n$attribution\n" + plan.steps.joinToString("\n\n") { step ->
                val record = records[step.stepId]
                val output = when (val value = record?.result) {
                    is AgentData.Draft -> value.exactText
                    is AgentData.Report -> value.exactText
                    is AgentData.Priorities -> value.rows.joinToString("\n") { "${it.id}: ${it.title}" }
                    is AgentData.Tasks -> "${value.rows.size} source records; complete=${value.complete}"
                    is AgentData.Clock -> value.exactDisplay
                    is AgentData.Battery -> "${value.percent}%"
                    else -> "No completed result"
                }
                "${step.command.intent}: ${record?.status ?: AgentStatus.DRAFT}\n" +
                    (if (step in proposals) "Proposed target: ${step.command.target.orEmpty()}\nProposed content: ${step.command.value.orEmpty()}\nNOT APPROVED\n" else "") +
                    output + (record?.errorCode?.let { "\nBlocked reason: $it" } ?: "")
            } + "\n${reason.orEmpty()}\nThis view never approves or executes actions."
            rows += AgentInboxRow("brain:${plan.taskId}", plan.taskId, task.revision, InboxSource.BRAIN, state,
                "Saved agent plan", detail, attribution, reason, proposals.isNotEmpty(),
                canCancel = task.state !in setOf(PersistentTaskState.RUNNING, PersistentTaskState.COMPLETED, PersistentTaskState.CANCELLED))
        }
        scheduled.forEach { task ->
            rows += AgentInboxRow("scheduled:${task.identity.taskId}", task.identity.taskId, task.revision, InboxSource.SCHEDULED_TASK,
                task.state, "Local scheduled task", "${task.state}\nNext eligible: ${task.dueAt}\nDeadline: ${task.deadlineAt}\n${task.reason}\nQueue cancellation pending: ${task.queueCancellationPending}",
                "Local device status recipe", task.reason.takeIf { it != PersistentTaskReason.NONE }?.name,
                task.state == PersistentTaskState.WAITING_FOR_APPROVAL,
                canCancel = task.state !in setOf(PersistentTaskState.COMPLETED, PersistentTaskState.CANCELLED, PersistentTaskState.FAILED),
                canPause = now < task.deadlineAt && task.state in setOf(PersistentTaskState.DRAFT, PersistentTaskState.SCHEDULED, PersistentTaskState.RUNNING))
        }
        proactive.rules.forEach { rule ->
            val monitor = rule.config?.monitor
            val expired = monitor?.let { now >= it.expiresAt } == true
            val state = when (rule.lifecycle) {
                RuleLifecycle.DELETED -> PersistentTaskState.CANCELLED
                RuleLifecycle.PAUSED -> PersistentTaskState.PAUSED
                RuleLifecycle.DISABLED -> PersistentTaskState.DRAFT
                RuleLifecycle.ACTIVE -> if (expired) PersistentTaskState.BLOCKED else PersistentTaskState.SCHEDULED
            }
            val source = monitor?.let { "Synthetic Notion profile ${it.profileId}, revision ${it.profileRevision}" } ?: "Local manual simulation"
            rows += AgentInboxRow("rule:${rule.ruleId}", rule.ruleId, rule.configRevision, InboxSource.RULE, state,
                "Review rule · host simulation", "${monitor?.objective.orEmpty()}\n$source\n${rule.lifecycle}\nLast decision: ${rule.lastDecision}\nNext advisory: ${rule.nextAdvisoryAt}\nNo Android task-review job is registered.",
                source, if (expired) "Rule expired" else "Host simulation only; no live monitoring is enabled.",
                canCancel = false, canPause = rule.lifecycle == RuleLifecycle.ACTIVE)
        }
        proactive.occurrences.forEach { event ->
            val execution = event.execution ?: return@forEach
            val rule = proactive.rules.single { it.ruleId == event.ruleId }
            val source = "Synthetic source ${execution.sourceId ?: rule.config?.monitor?.profileId.orEmpty()}"
            val state = if (execution.state == PersistentTaskState.WAITING_FOR_APPROVAL) PersistentTaskState.BLOCKED else execution.state
            rows += AgentInboxRow("occurrence:${event.occurrenceId}", event.occurrenceId, rule.stateRevision, InboxSource.OCCURRENCE,
                state, "Task review history", "$source\n${execution.state}\n${execution.reason}\nAttempts: ${execution.attempts}\nNext retry: ${execution.retryAt}\nRevoked: ${event.revoked}\nPrivate briefing text is not stored; no approval is restored.",
                source, execution.reason.takeIf { it != MockReviewReason.NONE }?.name,
                execution.reason == MockReviewReason.APPROVAL_UNAVAILABLE || execution.state == PersistentTaskState.WAITING_FOR_APPROVAL)
        }
        mockPreview?.proposedAction?.let { action ->
            val source = "Synthetic Notion profile ${mockPreview.provenance.pin.profileId} / ${mockPreview.provenance.sourceId}"
            val reason = if (now >= mockPreview.snapshot.deadlineMillis) "Preview expired; prepare a new plan."
                else "Fresh common approval is required; action execution is unavailable."
            rows += AgentInboxRow("mock:${mockPreview.snapshot.taskId}", mockPreview.snapshot.taskId, 0, InboxSource.MOCK_PREVIEW,
                PersistentTaskState.BLOCKED, "Proposed action · host simulation",
                "${mockPreview.snapshot.objective}\n$source\n${action.intent}\nExact target: ${action.target.orEmpty()}\nExact content: ${action.value.orEmpty()}\nNOT APPROVED\n$reason\nRAM-only preview; no approval or action is restored.",
                source, reason, requiresFreshApproval = true)
        }
        return AgentActivityInboxState(Collections.unmodifiableList(rows))
    }
    /** Exact-revision cancellation only. Live RUNNING work is cancelled through its conversation owner. */
    fun cancelPending(store: EncryptedBrainStore, id: String, revision: Long): BrainTask {
        val old = store.list().singleOrNull { it.definition.plan.taskId == id } ?: throw BrainFailure("TASK_NOT_FOUND")
        brainCheck(old.revision == revision && old.state !in setOf(PersistentTaskState.RUNNING, PersistentTaskState.COMPLETED,
            PersistentTaskState.CANCELLED), "STALE_OR_ACTIVE_TASK")
        return store.update(id, revision, old.ownerSession) { it.copy(revision = it.revision + 1,
            snapshot = it.snapshot.copy(status = AgentStatus.CANCELLED, steps = it.snapshot.steps.map { step -> step.copy(result = null) }),
            reason = "USER_CANCELLED") }
    }
}

/** Nonexported foreground view. No approve/run/credential/network surface. */
open class AgentActivityInboxActivity : AppCompatActivity() {
    private var generation = 0L
    private var visible = false
    private var job: Job? = null
    private var state = AgentActivityInboxState(emptyList())
    private var dialog: AlertDialog? = null
    private var privateText: TextView? = null
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this); super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); isSaveEnabled = false }
        val beta=application as? com.example.voicebrainlive.ui.InteractiveBetaSettings
        if(beta!=null)page.addView(beta.betaHeader(this,"Activity & Approvals"))
        else {page.addView(button("နောက်သို့", "inbox_back") { finish() });page.addView(label("လုပ်ဆောင်မှုနှင့် ခွင့်ပြုချက်များ"))}
        page.addView(label(if(application is com.example.voicebrainlive.ui.InteractiveBetaSettings) "သိမ်းထားသော ရလဒ်နှင့် အဆိုပြုထားသော အလုပ်များ ဖြစ်သည်။ ကြည့်ရုံဖြင့် မလုပ်ဆောင်ပါ။" else "သိမ်းထားသော လုပ်ဆောင်မှုများ ဖြစ်သည်။ အဆိုပြုအလုပ်များကို အသစ် အတည်ပြုရန် လိုသည်။ ဤနေရာမှ ကြည့်ရုံဖြင့် မလုပ်ဆောင်ပါ။ စမ်းသပ်စည်းမျဉ်းများနှင့် တကယ့်အချိန်ဇယားကို သီးခြားခွဲထားသည်။"))
        page.addView(button("လုပ်ဆောင်မှု မှတ်တမ်း ပြန်ကြည့်ရန်", "inbox_refresh") { refresh() })
        if(application !is com.example.voicebrainlive.ui.InteractiveBetaSettings) page.addView(button("စမ်းသပ် စည်းမျဉ်းများ စီမံရန်", "inbox_rules") {
            startActivity(android.content.Intent(this, ProactiveRulesActivity::class.java))
        })
        status = label("ဖတ်နေသည်…").apply { tag = "inbox_status" }; page.addView(status)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "inbox_rows"; isSaveEnabled = false }; page.addView(rows)
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(page) })
    }
    override fun onResume() { super.onResume(); visible = true; refresh() }
    override fun onPause() {
        visible = false; generation++; job?.cancel(); job = null; clearDetails()
        state = AgentActivityInboxState(emptyList()); rows.removeAllViews(); status.text = "မှတ်တမ်း ဖုံးကွယ်ထားသည်"; super.onPause()
    }
    protected open suspend fun readRows(): AgentActivityInboxState = withContext(Dispatchers.IO) {
        val app = application as? VoiceBrainApplication ?: throw BrainFailure("ACTIVITY_UNAVAILABLE")
        val store=(app as? com.example.voicebrainlive.ui.InteractiveBetaSettings)?.betaTaskStore()?:AndroidBrainAlpha.store(app)
        AgentActivityProjection.project(store.list(), app.backgroundTasks.history(), app.proactiveRules.history(), System.currentTimeMillis())
    }
    protected open suspend fun mutate(row: AgentInboxRow, pause: Boolean) = withContext(Dispatchers.IO) {
        val app = application as? VoiceBrainApplication ?: throw BrainFailure("ACTIVITY_UNAVAILABLE")
        when (row.source) {
            InboxSource.BRAIN -> { brainCheck(!pause, "UNSUPPORTED_PAUSE"); AgentActivityProjection.cancelPending((app as? com.example.voicebrainlive.ui.InteractiveBetaSettings)?.betaTaskStore()?:AndroidBrainAlpha.store(app), row.id, row.revision) }
            InboxSource.SCHEDULED_TASK -> if (pause) app.backgroundTasks.pause(row.id, row.revision) else app.backgroundTasks.cancel(row.id, row.revision)
            InboxSource.RULE -> { brainCheck(pause, "DELETE_REQUIRES_RULE_SETTINGS_CONFIRMATION"); app.proactiveRules.pause(row.id, row.revision) }
            InboxSource.OCCURRENCE, InboxSource.MOCK_PREVIEW -> throw BrainFailure("HISTORY_AND_PROPOSALS_ARE_READ_ONLY")
        }; Unit
    }
    private fun refresh() = launch(null, false)
    private fun launch(row: AgentInboxRow?, pause: Boolean) {
        if (!visible || (row != null && state.rows.none { it.key == row.key && it.revision == row.revision })) return
        val owner = ++generation; job?.cancel(); clearDetails(); state = AgentActivityInboxState(emptyList()); rows.removeAllViews()
        status.text = "သိမ်းထားသော အခြေအနေ ဖတ်နေသည်…"
        job = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (row != null) mutate(row, pause)
                val loaded = readRows()
                if (!visible || generation != owner) return@launch
                state = loaded; status.text = if (loaded.rows.isEmpty()) "လုပ်ဆောင်မှု မှတ်တမ်း မရှိသေးပါ။" else "မှတ်တမ်း ဖတ်ပြီးပါပြီ။ ယခင်ခွင့်ပြုချက်များ ပြန်မသုံးပါ။"
                render(owner)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (visible && generation == owner) status.text = "မှတ်တမ်း ဖတ်မရပါ။ မူလဒေတာ မဖျက်ပါ။ ပြန်ကြည့်ပြီးမှ ထပ်ကြိုးစားပါ။" }
        }
    }
    private fun render(owner: Long) {
        rows.removeAllViews()
        state.rows.forEach { row ->
            fun current() = visible && generation == owner && state.rows.any { it.key == row.key && it.revision == row.revision }
            rows.addView(label("${row.title}\n${row.state.name.replace('_', ' ')}\n${row.attribution}\n${row.blockedReason.orEmpty()}"))
            rows.addView(button("အသေးစိတ် ကြည့်ရန်", "inbox_details_${row.key}") { if (current()) showDetails(row) })
            if (row.canCancel) rows.addView(button("စောင့်နေသော အလုပ် ပယ်ဖျက်ရန်", "inbox_cancel_${row.key}") {
                if (current()) launch(row, false)
            })
            if (row.canPause) rows.addView(button("ဤအလုပ် သို့မဟုတ် စည်းမျဉ်း ခဏရပ်ရန်", "inbox_pause_${row.key}") { if (current()) launch(row, true) })
        }
    }
    private fun showDetails(row: AgentInboxRow) {
        clearDetails()
        val text = label(row.detail).apply { tag = "inbox_details_text" }; privateText = text
        dialog = AlertDialog.Builder(this).setTitle("လုပ်ဆောင်မှု အသေးစိတ် — ခွင့်ပြုချက် မဟုတ်ပါ")
            .setView(ScrollView(this).apply { isSaveEnabled = false; addView(text) }).setNegativeButton("ပိတ်ရန်", null).create().also {
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                it.setOnDismissListener { text.text = "" }; it.show()
            }
    }
    private fun clearDetails() { privateText?.text = ""; privateText = null; dialog?.dismiss(); dialog = null }
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 17f; setTextIsSelectable(true); isSaveEnabled = false; isSaveFromParentEnabled = false
    }
    private fun button(value: String, marker: String, click: () -> Unit) = Button(this).apply {
        text = value; tag = marker; contentDescription = value; isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { click() }
    }
}
