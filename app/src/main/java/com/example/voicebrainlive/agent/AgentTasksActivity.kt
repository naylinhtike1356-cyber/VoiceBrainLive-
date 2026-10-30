package com.example.voicebrainlive.agent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.notion.DirectNotionProfiles
import com.example.voicebrainlive.agent.notion.ProfileStoreException
import com.example.voicebrainlive.agent.notion.fullAgentBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Foreground closed templates only. Synthetic task properties never imply a connected account. */
class AgentTasksActivity : AppCompatActivity() {
    private var facade: ForegroundAgentTasks? = null
    private var profiles: DirectNotionProfiles? = null
    private var lease: ForegroundAgentTasks.ViewLease? = null
    private var collector: Job? = null
    private var foreground = false
    private var brainAlpha: com.example.voicebrainlive.agent.brain.BrainAlphaPanel? = null
    private class Lookup(val generation: Long, val lease: ForegroundAgentTasks.ViewLease)
    private var sequence = 0L
    private var lookup: Lookup? = null
    private var lookupJob: Job? = null
    private var lookupMessage: String? = null
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var result: TextView
    private lateinit var choose: Button
    private lateinit var study: Button
    private lateinit var run: Button
    private lateinit var cancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        facade = (application as? VoiceBrainApplication)?.foregroundAgentTasks
        profiles = (application as? VoiceBrainApplication)?.directNotionProfiles
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        fun label(text: String, tag: String) = TextView(this).apply {
            this.text = text; this.tag = tag; textSize = 18f; setTextIsSelectable(true)
            isSaveEnabled = false; isSaveFromParentEnabled = false
        }
        fun button(text: String, tag: String, action: () -> Unit) = Button(this).apply {
            this.text = text; this.tag = tag; minHeight = dp(48); minimumHeight = dp(48); contentDescription = text; setOnClickListener { action() }
        }
        page.addView(button("Back", "agent_back") { finish() })
        page.addView(label("Agent Tasks", "agent_title"))
        page.addView(button("Activity & approval inbox", "agent_activity_inbox") {
            startActivity(Intent(this, com.example.voicebrainlive.agent.brain.AgentActivityInboxActivity::class.java))
        })
        page.addView(label("These template buttons read device time/battery or SYNTHETIC study properties. Their plans/results remain in process memory. Foreground only; leaving cancels work and drops result references. The separate local/mock Brain Alpha section below explicitly saves encrypted checkpoints; no provider receives these results.", "agent_explanation"))
        page.addView(label("Notion account: NOT CONNECTED. SYNTHETIC sample data only. No real account reads, remote writes or scheduled reminders. Configure a reviewed sample profile in Direct Notion profiles first.", "agent_notion_unavailable"))
        status = label("Choose a template.", "agent_status").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        preview = label("No plan preview.", "agent_preview")
        result = label("No execution results.", "agent_result")
        choose = button("Preview local status report", "agent_choose") {
            val owner = ownedLease() ?: return@button
            revokeLookup(); lookupMessage = null
            facade?.previewLocalStatus(owner)
            renderCurrent()
        }
        study = button("Preview SYNTHETIC 30-minute study sample", "agent_study") { previewStudy() }
        run = button("Run reviewed steps", "agent_run") {
            val owner = ownedLease() ?: return@button
            val task = facade?.state?.value?.preview
            if (lookup == null && task != null) {
                lookupMessage = null
                if (facade?.runPreview(owner, task.taskId) != true)
                    lookupMessage = "Run unavailable. The previous task may still be cancelling, or this preview expired. Preview a new task when cancellation finishes."
                renderCurrent()
            }
        }
        cancel = button("Cancel current task", "agent_cancel") {
            val owner = ownedLease() ?: return@button
            val taskId = facade?.state?.value?.preview?.taskId
            if (taskId != null) facade?.cancel(owner, taskId)
        }
        page.addView(choose); page.addView(study); page.addView(preview); page.addView(run); page.addView(cancel); page.addView(status); page.addView(result)
        brainAlpha = com.example.voicebrainlive.agent.brain.BrainAlphaPanel(this, lifecycleScope,
            { ownedLease() != null }, { facade?.state?.value?.running == true || lookup != null }, { renderCurrent() })
            .also { it.attach(page) }
        setContentView(ScrollView(this).apply { addView(page); isFillViewport = true })
        renderCurrent()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        val tasks = facade ?: return
        val current = lease?.takeIf { tasks.isCurrent(it) } ?: tasks.openView().also {
            lease = it; collector?.cancel(); collector = null
        }
        if (collector == null) collector = lifecycleScope.launch {
            tasks.state.collect { state -> if (foreground && lease === current && tasks.isCurrent(current)) render(state) }
        }
        renderCurrent()
    }

    override fun onPause() {
        foreground = false
        brainAlpha?.close()
        revokeLookup()
        choose.isEnabled = false; study.isEnabled = false; run.isEnabled = false; cancel.isEnabled = false
        super.onPause()
    }

    override fun onStop() {
        revokeLookup()
        lease?.let { facade?.stop(it) }
        lease = null
        collector?.cancel(); collector = null
        result.text = "No execution results."
        preview.text = "No plan preview."
        status.text = "Task view closed; result references cleared."
        super.onStop()
    }

    private fun revokeLookup() {
        lookup = null // Revoke admission before cancellation can resume a callback.
        lookupJob?.cancel(); lookupJob = null
    }

    private fun owns(captured: Lookup): Boolean = foreground && lookup === captured &&
        captured.generation == sequence && lease === captured.lease && facade?.isCurrent(captured.lease) == true

    private fun ownedLease(): ForegroundAgentTasks.ViewLease? = lease?.takeIf {
        foreground && facade?.isCurrent(it) == true
    }

    private fun previewStudy() {
        val owner = lease ?: return
        val tasks = facade ?: return
        val service = profiles ?: return
        if (!foreground || !tasks.isCurrent(owner) || tasks.state.value.running || lookup != null) return
        // Identity also guards the saturated generation; no stale owner can match a fresh one.
        sequence = if (sequence < Long.MAX_VALUE) sequence + 1 else sequence
        val captured = Lookup(sequence, owner)
        lookup = captured; lookupMessage = null
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Repository wrapper captures selected ID+revision once. Never select again on Run.
                val fullPin = service.captureSelectedPin()
                if (!owns(captured)) return@launch
                if (!tasks.previewStudyDraft(owner, fullPin, 30))
                    lookupMessage = "Synthetic preview unavailable or stale; a prior task may still be retiring. No account accessed or retry."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (safe: ProfileStoreException) {
                if (owns(captured)) lookupMessage = "Synthetic preview unavailable (${safe.failure.name}). No account accessed."
            } catch (_: Exception) {
                if (owns(captured)) lookupMessage = "Synthetic preview unavailable. No account accessed."
            } finally {
                if (owns(captured)) { lookup = null; lookupJob = null; renderCurrent() }
            }
        }
        lookupJob = job
        renderCurrent()
        job.start()
    }

    private fun renderCurrent() = render(facade?.state?.value ?: ForegroundAgentTasks.State(message = "Agent tasks are unavailable in this application."))

    private fun source(connection: AgentConnectionPin?): String = try {
        val pin = fullAgentBinding(requireNotNull(connection))
        "SYNTHETIC sample; no connected account. Profile ${pin.profileId} revision ${pin.profileRevision}; data source ${pin.resource.dataSourceId}; schema revision ${pin.schema.revision}."
    } catch (_: Exception) { "Source unavailable; no connected account." }

    private fun render(state: ForegroundAgentTasks.State) {
        status.text = (if (lookup != null) "Preparing selected SYNTHETIC sample preview…\n" else lookupMessage?.let { "$it\n" }.orEmpty()) +
            state.message + (state.snapshot?.let { "\nTask status: ${it.status}; tool calls: ${it.calls}" } ?: "")
        preview.text = state.preview?.let { plan ->
            val origin = plan.steps.firstOrNull { it.connection != null }?.connection?.let { source(it) + "\n" }.orEmpty()
            "Reviewed plan: ${plan.objective}\n${origin}Deadline: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(plan.deadlineMillis))}\n" +
                plan.steps.mapIndexed { i, step -> "${i + 1}. ${stepLabel(step.stepId)} — read-only; dependencies: ${step.dependencies.joinToString { stepLabel(it) }.ifBlank { "none" }}" }.joinToString("\n")
        } ?: "No plan preview."
        result.text = state.snapshot?.steps?.joinToString("\n\n") { record ->
            val display = when (val data = record.result) {
                is AgentData.Clock -> data.exactDisplay
                is AgentData.Battery -> "Battery: ${data.percent}%"
                is AgentData.Report -> data.exactText
                is AgentData.Tasks -> source(data.connection) + "\nSynthetic task-property rows: ${data.rows.size}; complete: ${data.complete}."
                is AgentData.Priorities -> source(data.connection) + "\nSynthetic unfinished priorities: ${data.rows.size}."
                is AgentData.Draft -> if (record.status == AgentStatus.SUCCEEDED && state.snapshot?.status == AgentStatus.SUCCEEDED)
                    source(data.connection) + "\n" + data.exactText else ""
                else -> ""
            }
            "${stepLabel(record.stepId)}: ${record.status}" + (record.errorCode?.let { " ($it)" } ?: "") + if (display.isBlank()) "" else "\n$display"
        }?.takeIf { it.isNotBlank() } ?: "No execution results."
        val owned = foreground && brainAlpha?.busy != true && lease?.let { facade?.isCurrent(it) } == true
        choose.isEnabled = owned && !state.running // A local preview may supersede an awaiting lookup.
        study.isEnabled = owned && profiles != null && !state.running && lookup == null
        run.isEnabled = owned && lookup == null && state.runAllowed && !state.running
        cancel.isEnabled = owned && state.running
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun stepLabel(id: String): String = when (id) {
        "clock" -> "Read device time"
        "battery" -> "Read battery level"
        "report" -> "Combine local report"
        "tasks" -> "Read SYNTHETIC task properties"
        "priorities" -> "Choose unfinished business priorities"
        "draft" -> "Prepare local SYNTHETIC study draft"
        else -> "Local step"
    }
}
