package com.example.voicebrainlive.agent.proactive

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.VoiceBrainApplication
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Manual synthetic simulation only. A visible persisted review result is not a scheduled task. */
class ProactiveRulesActivity : AppCompatActivity() {
    private var service: ProactiveRules? = null
    private var epoch = 0L
    private var busy = false
    private var snapshot: ProactiveSnapshot? = null
    private var editing: ProactiveRule? = null
    private var dialog: AlertDialog? = null
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var save: Button
    private lateinit var refresh: Button
    private lateinit var zone: EditText
    private lateinit var trigger: EditText
    private lateinit var end: EditText
    private lateinit var quietStart: EditText
    private lateinit var quietEnd: EditText
    private lateinit var quota: EditText
    private lateinit var monitorDraft: CheckBox
    private lateinit var objective: EditText
    private lateinit var expiryDays: EditText
    private lateinit var attempts: EditText
    private lateinit var backoff: EditText
    private lateinit var showResults: CheckBox
    private lateinit var matched: CheckBox
    private data class Update(val message: String, val preview: ProactiveRule? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this); super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        service = (application as? VoiceBrainApplication)?.proactiveRules
        val page = vertical()
        page.addView(button("နောက်သို့", "proactive_back") { finish() })
        page.addView(label("စမ်းသပ်ခန်း — အခြေအနေစစ် စည်းမျဉ်းများ", "proactive_title"))
        page.addView(label("ဤနေရာသည် စမ်းသပ်ခန်းသာ ဖြစ်သည်။ ပိတ်ထားသော စည်းမျဉ်းမူကြမ်းကို သိမ်း၊ ပြန်စစ်ပြီး စမ်းသပ်အခြေအနေကို ကိုယ်တိုင်စစ်နိုင်သည်။ အလိုအလျောက် အလုပ်အချိန် မသတ်မှတ်ပါ။ အသိပေးချက်၊ ကွန်ရက်၊ ဖုန်းခေါ်ခြင်း မလုပ်ပါ။ တကယ့် အချိန်သတ်မှတ်အလုပ်အတွက် ဆက်တင်မှ အချိန်သတ်မှတ် အလုပ်များကို သုံးပါ။"))
        zone = field(page, "အချိန်ဇုန် အမည်", "proactive_zone", ZoneId.systemDefault().id)
        trigger = field(page, "နေ့စဉ် စတင်ချိန် (နာရီ:မိနစ်)", "proactive_trigger", "08:00")
        end = field(page, "နေ့စဉ် အဆုံးချိန် (၂၄:၀၀ အထိ ရနိုင်သည်)", "proactive_end", "22:00")
        quietStart = field(page, "မနှောင့်ယှက်ချိန် အစ (မဖြည့်လျှင် ပိတ်ထားမည်)", "proactive_quiet_start", "22:00")
        quietEnd = field(page, "မနှောင့်ယှက်ချိန် အဆုံး", "proactive_quiet_end", "07:00")
        quota = field(page, "၇ ရက်အတွင်း ရလဒ်အများဆုံး (၁ မှ ၃)", "proactive_quota", "3")
        showResults = CheckBox(this).apply { text = "စမ်းသပ်ရလဒ်များကို ဤစာမျက်နှာတွင် ပြခွင့်ပြုမည်"; tag = "proactive_results"; isChecked = true }
        matched = CheckBox(this).apply { text = "ယခုစမ်းသပ်မှုတွင် အခြေအနေ ကိုက်ညီသည်ဟု သတ်မှတ်မည်"; tag = "proactive_signal"; isChecked = false }
        page.addView(showResults); page.addView(matched)
        monitorDraft = CheckBox(this).apply { text = "အလုပ်ပြန်စစ်ရန် နမူနာမူကြမ်း ပြင်မည် (အချိန်မသတ်မှတ်ပါ)"; tag = "proactive_monitor"; isChecked = false }
        page.addView(monitorDraft)
        objective = field(page, "အလုပ်ပြန်စစ်မည့် ရည်ရွယ်ချက် (နမူနာမူကြမ်း ရွေးမှ သိမ်းမည်)", "proactive_objective", "").apply {
            setSingleLine(false); minLines = 2; isSaveEnabled = false; isSaveFromParentEnabled = false
        }
        expiryDays = field(page, "မူကြမ်း သက်တမ်း ရက်အရေအတွက် (၁ မှ ၃၀)", "proactive_expiry_days", "7")
        attempts = field(page, "ဖတ်ရှုခွင့် အကြိမ်အများဆုံး (၁ မှ ၂)", "proactive_attempts", "1")
        backoff = field(page, "ပြန်ဖတ်ရန် စောင့်ချိန် မိနစ် (၁ မှ ၆၀)", "proactive_backoff", "1")
        page.addView(label("ရွေးထားသော နမူနာ Notion profile ကိုသာ သုံးသည်။ ပိတ်ထားသော နမူနာမူကြမ်းသာ သိမ်းသည်။ တကယ့် Notion ချိတ်ဆက်မှုနှင့် နောက်ခံလုပ်ဆောင်မှု မဖွင့်ပါ။"))
        save = button("ပိတ်ထားသော မူကြမ်း သိမ်းရန်", "proactive_save") { saveDraft() }
        refresh = button("သိမ်းထားသော စည်းမျဉ်းနှင့် မှတ်တမ်း ပြန်ကြည့်ရန်", "proactive_refresh") { reload() }
        page.addView(save); page.addView(refresh)
        status = label("စမ်းသပ်မှတ်တမ်း ဖတ်နေသည်…", "proactive_status").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        page.addView(status); rows = vertical(); page.addView(rows)
        setContentView(ScrollView(this).apply { addView(page); isFillViewport = true })
        controls()
    }
    override fun onStart() { super.onStart(); epoch++; busy = false }
    override fun onResume() { super.onResume(); reload() }
    override fun onStop() { epoch++; dialog?.dismiss(); dialog = null; super.onStop() }
    private fun visible() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun reload() = perform { Update("Persisted rules and history loaded. Evaluation requires its explicit button.") }
    private fun current(rule: ProactiveRule) = visible() && !busy && snapshot?.rules?.any {
        it.ruleId == rule.ruleId && it.configRevision == rule.configRevision && it.stateRevision == rule.stateRevision
    } == true
    private fun perform(action: suspend (ProactiveRules) -> Update) {
        val api = service ?: run { status.text = "စည်းမျဉ်းမှတ်တမ်းကို ဖတ်မရပါ။"; controls(); return }
        if (busy || !visible()) return
        val owner = epoch; busy = true; controls()
        lifecycleScope.launch {
            if (owner != epoch || !visible()) return@launch
            var update = Update("တောင်းဆိုချက် မပြီးပါ။ ထပ်မကြိုးစားမီ ပြန်ဖတ်ထားသော အခြေအနေကို စစ်ပါ။")
            try { update = action(api) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (safe: RuleStoreException) { update = Update("တောင်းဆိုချက် မပြီးပါ (${safe.failure.name})။ မူလဒေတာ မဖျက်ပါ။ လက်ရှိမှတ်တမ်း ပြန်စစ်ပါ။") }
            catch (_: Exception) { }
            var loaded: ProactiveSnapshot? = null
            try { loaded = api.history() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update = Update("သိမ်းထားသော မှတ်တမ်းကို ဖတ်မရပါ။ ပြင်ဆင်မှု ရပ်ထားပြီး မူလဒေတာ မဖျက်ပါ။") }
            if (owner != epoch || !visible()) return@launch
            busy = false; snapshot = loaded; status.text = update.message
            editing = editing?.takeIf { old -> loaded?.rules?.any { it.ruleId == old.ruleId && it.configRevision == old.configRevision && it.stateRevision == old.stateRevision } == true }
            render(); controls()
            update.preview?.takeIf(::current)?.let(::reviewEnable)
        }
    }
    private fun config(): DailySimulationConfig = DailySimulationConfig(zone.text.toString().trim(),
        minute(trigger.text.toString()), minute(end.text.toString(), allowEnd = true),
        quietStart.text.toString().trim().takeIf { it.isNotEmpty() }?.let { minute(it) },
        quietEnd.text.toString().trim().takeIf { it.isNotEmpty() }?.let { minute(it) },
        showResults.isChecked, quota.text.toString().trim().toInt()).also(::validateConfig)
    private fun minute(value: String, allowEnd: Boolean = false): Int {
        val text = value.trim(); require(text.matches(Regex("[0-9]{2}:[0-9]{2}")))
        if (allowEnd && text == "24:00") return 1440
        val h = text.take(2).toInt(); val m = text.takeLast(2).toInt()
        require(h in 0..23 && m in 0..59); return h * 60 + m
    }
    private fun saveDraft() {
        val exact = try { config() } catch (_: Exception) { status.text = "အချိန်ဇုန်၊ နာရီ:မိနစ်၊ မနှောင့်ယှက်ချိန် နှစ်ခုနှင့် အများဆုံး ၁ မှ ၃ ကို စစ်ပါ။"; return }
        val old = editing
        if (old != null && !current(old)) { status.text = "မူကြမ်း ပြောင်းသွားပါပြီ။ မပြင်မီ ပြန်ကြည့်ပါ။"; return }
        val wantMonitor = monitorDraft.isChecked
        val goal = objective.text.toString()
        val days = expiryDays.text.toString().toIntOrNull()
        val limit = attempts.text.toString().toIntOrNull()
        val delayMinutes = backoff.text.toString().toLongOrNull()
        if (wantMonitor && (goal.isBlank() || goal.length > 2_000 || days !in 1..30 || limit !in 1..2 || delayMinutes !in 1L..60L)) {
            status.text = "ရည်ရွယ်ချက်၊ သက်တမ်း၊ အကြိမ်ရေနှင့် ပြန်ကြိုးစားစောင့်ချိန်ကို စစ်ပါ။"; return
        }
        perform { api ->
            val reviewedConfig = if (!wantMonitor) exact else {
                val app = application as? VoiceBrainApplication ?: throw RuleStoreException(RuleFailure.STORAGE_UNAVAILABLE)
                val pin = app.directNotionProfiles.captureSelectedPin()
                val now = System.currentTimeMillis()
                ruleCheck(now in 0..Long.MAX_VALUE - RULE_DAY * 30)
                exact.copy(monitor = MockTaskReviewConfig(goal, pin.profileId, pin.profileRevision,
                    now + requireNotNull(days) * RULE_DAY, requireNotNull(limit), requireNotNull(delayMinutes) * 60_000))
            }
            val rule = if (old == null) api.createDisabled(reviewedConfig) else api.updateDisabled(old.ruleId, old.configRevision, reviewedConfig)
            Update(if (wantMonitor) "ပိတ်ထားသော နမူနာမူကြမ်း သိမ်းပြီးပါပြီ။ ဤစမ်းသပ်ခန်းမှ တကယ့်အချိန် မသတ်မှတ်ပါ။" else "ပိတ်ထားသော မူကြမ်း သိမ်းပြီးပါပြီ။ မဖွင့်မီ ပြန်စစ်အတည်ပြုရန် လိုသည်။", rule)
        }
    }
    private fun controls() {
        if (!::save.isInitialized) return
        val editable = service != null && snapshot != null && !busy
        listOf(zone, trigger, end, quietStart, quietEnd, quota, showResults, matched, monitorDraft, objective, expiryDays, attempts, backoff).forEach { it.isEnabled = editable }
        save.isEnabled = editable && (editing != null || (snapshot?.rules?.size ?: 20) < 20)
        save.text = if (editing == null) "ပိတ်ထားသော မူကြမ်း သိမ်းရန်" else "ပြင်ထားသော မူကြမ်း သိမ်းရန်"
        refresh.isEnabled = service != null && !busy
        if (::rows.isInitialized) for (i in 0 until rows.childCount) {
            (rows.getChildAt(i) as? Button)?.isEnabled = editable
        }
    }
    private fun render() {
        rows.removeAllViews()
        val data = snapshot ?: run { rows.addView(label("မှတ်တမ်း ဖတ်မရပါ။ ဒေတာမရှိဟု မယူဆဘဲ အသစ်မသိမ်းပါ။")); return }
        if (data.rules.isEmpty()) rows.addView(label("စမ်းသပ်စည်းမျဉ်း မရှိသေးပါ။ အသစ်ကို ပြန်စစ်မှ ဖွင့်မည်။"))
        data.rules.asReversed().forEach { rule ->
            rows.addView(label("Rule ${rule.ruleId}\n${rule.lifecycle}; config revision ${rule.configRevision}; state revision ${rule.stateRevision}\n${rule.config?.let(::describe) ?: "ပယ်ဖျက်ထားကြောင်း မှတ်တမ်း ဆက်သိမ်းထားသည်"}\nLast check: ${rule.lastCheckAt ?: "မရှိသေး"}; last decision: ${rule.lastDecision}\nNext advisory check: ${rule.nextAdvisoryAt ?: "မရှိသေး"}. No automatic delivery scheduled."))
            fun action(title: String, suffix: String, run: () -> Unit) {
                rows.addView(button(title, "proactive_${suffix}_${rule.ruleId}") { if (current(rule)) run() }.apply { isEnabled = !busy })
            }
            if (rule.lifecycle in setOf(RuleLifecycle.DISABLED, RuleLifecycle.PAUSED)) {
                if (rule.config?.monitor == null) action("ဤစမ်းသပ်မှုကို ပြန်စစ်ပြီး ဖွင့်ရန်", "review") { reviewEnable(rule) }
                else rows.addView(label("ကွန်ပျူတာစမ်းသပ် နမူနာသာ ဖြစ်သည်။ ဤနေရာမှ နောက်ခံအလုပ် မလုပ်ဆောင်ပါ။"))
                action("ပိတ်ထားသော စည်းမျဉ်း ပြင်ရန်", "edit") { loadEditor(rule) }
            }
            if (rule.lifecycle == RuleLifecycle.ACTIVE) {
                action("ဤစည်းမျဉ်း ခဏရပ်ရန်", "pause") { perform { it.pause(rule.ruleId, rule.configRevision); Update("ခဏရပ်ရန် တောင်းဆိုပြီးပါပြီ။ သိမ်းထားသော အခြေအနေကို အောက်တွင် ကြည့်ပါ။") } }
                if (rule.config?.monitor == null) action("နမူနာအခြေအနေ ယခုစစ်ရန်", "evaluate") {
                    val signal = ClosedSyntheticSignal(matched.isChecked)
                    perform { val result = it.evaluateSynthetic(rule.ruleId, rule.configRevision, rule.stateRevision, signal)
                        Update("နမူနာစစ်ဆေးမှု ရလဒ်: ${result.decision}။ သိမ်းထားသောမှတ်တမ်းကို ကြည့်ပါ။ တကယ့်နောက်ခံအလုပ် ပြီးစီးခြင်း မဟုတ်ပါ။") }
                }
            }
            if (rule.lifecycle != RuleLifecycle.DELETED) action("စည်းမျဉ်း ဖျက်ပြီး ထပ်မလုပ်အောင် မှတ်တမ်းထားရန်", "delete") {
                confirm(rule, "ဤစမ်းသပ်စည်းမျဉ်းကို ဖျက်မလား", "စည်းမျဉ်း ရပ်ပြီး သတ်မှတ်ချက်ကို ဖျက်မည်။ ထပ်မလုပ်အောင် အနည်းဆုံး ပယ်ဖျက်မှတ်တမ်းနှင့် အကြိမ်ရေမှတ်တမ်း ဆက်သိမ်းမည်။ ပြင်ပဝန်ဆောင်မှုကို မပြောင်းပါ။", "စည်းမျဉ်း ဖျက်ရန်") {
                    it.delete(rule.ruleId, rule.configRevision); Update("ဖျက်ရန် တောင်းဆိုပြီးပါပြီ။ လက်ရှိပယ်ဖျက်မှုနှင့် မှတ်တမ်းကို အောက်တွင် ကြည့်ပါ။")
                }
            }
        }
        rows.addView(label("သိမ်းထားသော စမ်းသပ်မှတ်တမ်း", "proactive_history"))
        data.occurrences.asReversed().forEach { event ->
            rows.addView(label("${event.occurrenceId}\nRule ${event.ruleId}; local epoch-day ${event.localEpochDay}; decision ${event.decision}; revoked ${event.revoked}\n${event.execution?.let { "Mock task review: ${it.state}; reason ${it.reason}; attempts ${it.attempts}; source ${it.sourceId ?: "မရှိသေး"}. Briefing text is not stored." } ?: if (event.result == GenericRuleResult.SYNTHETIC_CHECK_IN_READY) "နမူနာအခြေအနေ ကိုက်ညီပါသည်။ သတ်မှတ်ထားသော စည်းမျဉ်းကို ပြန်ကြည့်ပါ။" else "ပြသရန် ရလဒ် မရှိပါ။"}"))
        }
    }
    private fun loadEditor(rule: ProactiveRule) {
        if (!current(rule)) return
        val c = rule.config ?: return
        editing = rule; zone.setText(c.zoneId); trigger.setText(time(c.triggerMinute)); end.setText(time(c.windowEndMinute))
        quietStart.setText(c.quietStartMinute?.let(::time).orEmpty()); quietEnd.setText(c.quietEndMinute?.let(::time).orEmpty())
        quota.setText(c.maxResultsPerSevenDays.toString()); showResults.isChecked = c.showGenericResults
        monitorDraft.isChecked = c.monitor != null; objective.setText(c.monitor?.objective.orEmpty())
        c.monitor?.let { monitor ->
            expiryDays.setText(((monitor.expiresAt - System.currentTimeMillis()) / RULE_DAY).coerceIn(1, 30).toString())
            attempts.setText(monitor.maxAttempts.toString()); backoff.setText((monitor.backoffMillis / 60_000).toString())
        }
        controls()
        status.text = "Editing captured revision ${rule.configRevision}. Saving remains disabled until a separate exact enable review."
    }
    private fun reviewEnable(rule: ProactiveRule) {
        if (rule.config?.monitor != null) { status.text = "နမူနာမူကြမ်း သိမ်းပြီးပါပြီ။ ဤစမ်းသပ်ခန်းမှ အချိန် မသတ်မှတ်ပါ။"; return }
        if (!current(rule) || rule.lifecycle !in setOf(RuleLifecycle.DISABLED, RuleLifecycle.PAUSED)) return
        confirm(rule, "ဤနမူနာစည်းမျဉ်း အတိအကျကို ဖွင့်မလား", describe(rule.config ?: return) +
            "\nGlobal maximum: one result per rolling 24 hours. One slot per local day; skipped slots are not retried. No automatic scheduling or native notification.\nRule ${rule.ruleId}; revision ${rule.configRevision}", "နမူနာစမ်းသပ်မှု ဖွင့်ရန်") {
            it.enableReviewed(rule.ruleId, rule.configRevision); Update("ဖွင့်ရန် တောင်းဆိုပြီးပါပြီ။ နမူနာစစ်ဆေးရန် ခလုတ်ကို ကိုယ်တိုင် နှိပ်ပါ။ လက်ရှိအခြေအနေကို အောက်တွင် ကြည့်ပါ။")
        }
    }
    private fun confirm(rule: ProactiveRule, title: String, message: String, positive: String, action: suspend (ProactiveRules) -> Update) {
        if (!current(rule)) return
        dialog?.dismiss(); val owner = epoch; var consumed = false
        val preview = AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("မလုပ်တော့ပါ", null).setPositiveButton(positive, null).create()
        dialog = preview; preview.setOnDismissListener { if (dialog === preview) dialog = null }
        preview.setOnShowListener {
            preview.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!consumed && dialog === preview && owner == epoch && current(rule)) {
                    consumed = true; dialog = null; preview.dismiss(); perform(action)
                }
            }
        }
        preview.show()
    }
    private fun describe(c: DailySimulationConfig) = "MANUAL SYNTHETIC check-in; timezone ${c.zoneId}\nDaily window ${time(c.triggerMinute)}–${time(c.windowEndMinute)}\nQuiet ${c.quietStartMinute?.let(::time) ?: "off"}–${c.quietEndMinute?.let(::time) ?: "off"}\nGeneric on-screen results ${c.showGenericResults}; maximum ${c.maxResultsPerSevenDays} per rolling seven days" +
        c.monitor?.let { "\nTask-review draft: ${it.objective}\nSynthetic profile ${it.profileId}, revision ${it.profileRevision}; expiry ${it.expiresAt}; attempts ${it.maxAttempts}; backoff ${it.backoffMillis / 60_000} minutes. NOT SCHEDULED ON DEVICE." }.orEmpty()
    private fun time(minute: Int) = java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private fun label(text: String, tag: String? = null) = TextView(this).apply { this.text = text; this.tag = tag; textSize = 17f; setTextIsSelectable(true) }
    private fun field(page: LinearLayout, title: String, tag: String, value: String): EditText {
        page.addView(label(title))
        return EditText(this).apply { this.tag = tag; contentDescription = title; setText(value); setSingleLine(true); minHeight = dp(48); page.addView(this) }
    }
    private fun button(text: String, tag: String, run: () -> Unit) = Button(this).apply { this.text = text; this.tag = tag; contentDescription = text; minHeight = dp(48); minimumHeight = dp(48); setOnClickListener { run() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
