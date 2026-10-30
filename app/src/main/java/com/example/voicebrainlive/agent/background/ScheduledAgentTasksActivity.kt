package com.example.voicebrainlive.agent.background

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.persistence.PersistentTaskRecord
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import com.example.voicebrainlive.agent.persistence.TaskStoreException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit one-time closed local recipe only. No general task/provider/approval continuation. */
class ScheduledAgentTasksActivity : AppCompatActivity() {
    private var tasks: BackgroundTasks? = null
    private var uiEpoch = 0L
    private var busy = false
    private var readable = false
    private var notices = false
    private var records: List<PersistentTaskRecord> = emptyList()
    private var activePreview: AlertDialog? = null
    private lateinit var status: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var preview: Button
    private lateinit var refresh: Button
    private lateinit var permission: Button
    private lateinit var rows: LinearLayout
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (visible()) reload() }
    private data class Update(val message: String, val preview: PersistentTaskRecord? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        tasks = (application as? VoiceBrainApplication)?.backgroundTasks
        val page = vertical()
        page.addView(button("Back", "scheduled_back") { finish() })
        page.addView(label("Scheduled local agent tasks", "scheduled_title"))
        page.addView(label("One-time local status report: read device time and battery, then combine a report. Review and explicitly schedule it for about 15 minutes from now, with a 24-hour expiry. Android timing is inexact and requires battery not low. No network, microphone, provider, Notion or sensitive tools. Only task metadata is persisted; private result text stays ephemeral.", "scheduled_explanation"))
        notificationStatus = label("Checking notifications…", "scheduled_notifications")
        page.addView(notificationStatus)
        permission = button("Enable task notifications", "scheduled_permission") { openNotificationPermission() }
        page.addView(permission)
        status = label("Loading task history…", "scheduled_status").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        page.addView(status)
        preview = button("Preview a one-time local report", "scheduled_preview") {
            perform { Update("Draft metadata saved. No work has been enqueued yet.", it.previewLocalStatus()) }
        }
        refresh = button("Refresh history and recovery status", "scheduled_refresh") { reload() }
        page.addView(preview); page.addView(refresh)
        rows = vertical(); page.addView(rows)
        setContentView(ScrollView(this).apply { addView(page); isFillViewport = true })
        controls()
    }

    override fun onStart() { super.onStart(); uiEpoch++; busy = false }
    // ON_START can be dispatched after onStart returns. Refresh once STARTED admission exists.
    override fun onResume() { super.onResume(); reload() }
    override fun onStop() { uiEpoch++; activePreview?.dismiss(); activePreview = null; super.onStop() }
    private fun visible() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun reload() = perform { it.reconcile(); Update("History refreshed. Native execution and notification delivery still require device validation.") }

    /** Always reload durable state after errors: schedule/cancel may already have committed. */
    private fun perform(action: suspend (BackgroundTasks) -> Update) {
        val service = tasks ?: run { status.text = "Scheduled agent tasks are unavailable."; controls(); return }
        if (busy || !visible()) return
        val epoch = uiEpoch
        busy = true; controls()
        lifecycleScope.launch {
            var outcome = Update("Request could not finish. Review refreshed history before trying again.")
            try { outcome = action(service) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (safe: TaskStoreException) { outcome = Update("Request could not finish (${safe.failure.name}). Review refreshed history for any committed change.") }
            catch (_: Exception) { outcome = Update("Request could not finish. Review refreshed history before trying again.") }
            var loaded: List<PersistentTaskRecord>? = null
            var available = false
            try { loaded = service.history() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { outcome = Update("Task history could not be read. Scheduling and history actions are disabled; existing data is preserved.") }
            if (loaded != null) try { available = service.notificationsAvailable() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { available = false }
            if (uiEpoch != epoch || !visible()) return@launch
            busy = false; readable = loaded != null; notices = available
            records = loaded.orEmpty()
            status.text = outcome.message
            renderRows(); controls()
            outcome.preview?.takeIf { readable && records.any { current -> current.identity.taskId == it.identity.taskId && current.revision == it.revision } }
                ?.let { showPreview(it) }
        }
    }
    private fun controls() {
        if (!::preview.isInitialized) return
        preview.isEnabled = tasks != null && readable && !busy
        refresh.isEnabled = tasks != null && !busy
        permission.isEnabled = !busy
        permission.visibility = if (notices) View.GONE else View.VISIBLE
        notificationStatus.text = if (notices) "Notifications available for submission. Actual device delivery is unverified."
            else "Notifications unavailable or blocked. Scheduling requires notification availability; a draft alone does not schedule work."
    }
    private fun renderRows() {
        rows.removeAllViews()
        if (!readable) { rows.addView(label("History unavailable; this is not an empty writable task store.")); return }
        if (records.isEmpty()) { rows.addView(label("No scheduled local task metadata yet.")); return }
        records.asReversed().forEach { record ->
            val row = vertical()
            val details = "Local status report\nID: ${record.identity.taskId}\nState: ${record.state}; revision: ${record.revision}; attempts: ${record.attempts}\nDue: ${record.dueAt?.let(::date) ?: "not scheduled"}\nExpires: ${date(record.deadlineAt)}\nReason: ${record.reason}"
            row.addView(label(details))
            record.notificationFailureAt?.let { row.addView(label("Prior notification not accepted at ${date(it)}. This does not establish device delivery or change the recorded task outcome.")) }
            if (record.queueCancellationPending) row.addView(label("Queue cancellation is pending or uncertain. Same-task scheduling is disabled; no owner/result authority is restored."))
            val now = System.currentTimeMillis()
            if (record.state in setOf(PersistentTaskState.DRAFT, PersistentTaskState.PAUSED) && record.attempts == 0) {
                row.addView(button("Review schedule", "scheduled_review_${record.identity.taskId}") { if (current(record)) showPreview(record) }.apply {
                    isEnabled = !busy && !record.queueCancellationPending && (record.dueAt ?: -1) >= now && now < record.deadlineAt
                })
            }
            if (record.state in setOf(PersistentTaskState.DRAFT, PersistentTaskState.SCHEDULED, PersistentTaskState.RUNNING))
                row.addView(button("Pause this task", "scheduled_pause_${record.identity.taskId}") { if (current(record)) perform { it.pause(record.identity.taskId, record.revision); Update("Pause requested. Durable history shows the current state and queue acknowledgement.") } }.apply { isEnabled = !busy && now < record.deadlineAt })
            if (record.state !in setOf(PersistentTaskState.COMPLETED, PersistentTaskState.CANCELLED, PersistentTaskState.FAILED))
                row.addView(button("Cancel this task", "scheduled_cancel_${record.identity.taskId}") { if (current(record)) perform { it.cancel(record.identity.taskId, record.revision); Update("Cancellation requested. Durable history shows whether native queue cancellation was acknowledged.") } }.apply { isEnabled = !busy })
            rows.addView(row)
        }
    }
    private fun current(record: PersistentTaskRecord) = visible() && !busy && readable && records.any {
        it.identity.taskId == record.identity.taskId && it.revision == record.revision
    }
    private fun showPreview(record: PersistentTaskRecord) {
        if (!current(record) || record.queueCancellationPending) return
        activePreview?.dismiss()
        val epoch = uiEpoch
        var consumed = false
        val dialog = AlertDialog.Builder(this).setTitle("Review one local report")
            .setMessage("1. Read device time\n2. Read battery level\n3. Combine local report\n\nDue: ${record.dueAt?.let(::date)} (inexact)\nExpires: ${date(record.deadlineAt)}\nRequires battery not low and notifications. No network or sensitive tools. Only metadata is persisted. Leaving this preview does not schedule anything.\n\nTask: ${record.identity.taskId}\nRevision: ${record.revision}")
            .setNegativeButton("Keep draft", null).setPositiveButton("Schedule one report", null).create()
        activePreview = dialog
        dialog.setOnDismissListener { if (activePreview === dialog) activePreview = null }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = notices
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!consumed && activePreview === dialog && uiEpoch == epoch && current(record) && notices) {
                    consumed = true; activePreview = null; dialog.dismiss()
                    perform { it.schedule(record.identity.taskId, record.revision); Update("Schedule request finished. Read the persisted state below; queue acceptance is not proof that the report ran.") }
                }
            }
        }
        dialog.show()
    }
    private fun openNotificationPermission() {
        if (!visible()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        else try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        catch (_: Exception) { status.text = "Notification settings could not be opened. Enable them from Android app settings." }
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private fun label(text: String, tag: String? = null) = TextView(this).apply { this.text = text; this.tag = tag; textSize = 17f; setTextIsSelectable(true) }
    private fun button(text: String, tag: String, action: () -> Unit) = Button(this).apply { this.text = text; this.tag = tag; minHeight = dp(48); minimumHeight = dp(48); contentDescription = text; setOnClickListener { action() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun date(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))
}
