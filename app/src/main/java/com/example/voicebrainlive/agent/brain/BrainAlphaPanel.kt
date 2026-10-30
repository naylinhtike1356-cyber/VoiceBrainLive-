package com.example.voicebrainlive.agent.brain

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent
import java.util.UUID
import kotlinx.coroutines.*

/** Explicit local/mock entry point. No provider, hardware actions, background service or biometric grant. */
internal class BrainAlphaPanel(private val context: Context, private val scope: CoroutineScope,
    private val ownsView: () -> Boolean, private val otherBusy: () -> Boolean, private val busyChanged: () -> Unit) {
    private val session = UUID.randomUUID().toString()
    private var job: Job? = null
    private var brain: AgentBrain? = null
    private var latest: BrainTask? = null
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var runningEpoch = -1L
    val busy get() = job != null
    private val input = EditText(context).apply {
        setText(BrainSamples.BURMESE_GOAL); hint = "Supported local goal"; contentDescription = hint
        isSaveEnabled = false; isSaveFromParentEnabled = false
        filters = arrayOf(android.text.InputFilter.LengthFilter(2_000))
    }
    private val output = TextView(context).apply {
        textSize = 17f; setTextIsSelectable(true); isSaveEnabled = false; isSaveFromParentEnabled = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun label(value: String) = TextView(context).apply { text = value; textSize = 17f }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; contentDescription = value; minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
    fun attach(page: LinearLayout) {
        page.addView(label("Agent Brain Alpha — LOCAL / MOCK"))
        page.addView(label("Finite offline goals; fake task/time/battery data. Relevant confirmed memory is included only when enabled. Explicit Run saves an encrypted foreground checkpoint. Effect steps create proposals only; common action approval is NOT CONNECTED. No SMS, reminder or Notion write executes. Leaving cancels active work. No background jobs."))
        page.addView(input)
        listOf(BrainSamples.BURMESE_GOAL, "local status", "draft sample sms").forEach { goal ->
            page.addView(button("Choose: $goal") { if (!busy) input.setText(goal) })
        }
        page.addView(button("Run local/mock goal and save checkpoint") {
            launch {
                val goal = withContext(Dispatchers.Main) { input.text.toString() }
                val engine = engine()
                val preview = engine.preview(BrainGoalRequest(UUID.randomUUID().toString(), goal, session,
                    BrainSamples.connection, memoryQuery = if (goal == BrainSamples.BURMESE_GOAL || goal == "study tonight") "study" else ""))
                latest = preview
                if (!ownsView()) throw CancellationException()
                latest = engine.runReviewed(preview.definition.plan.taskId, preview.revision, preview.definition.digest)
            }
        })
        page.addView(button("Restore saved local/mock tasks (no automatic resume)") {
            launch {
                val stored = AndroidBrainAlpha.store(context).list()
                val engine = engine()
                val restored = stored.map { engine.restore(it.definition.plan.taskId) }
                latest = restored.lastOrNull()
                withContext(Dispatchers.Main) { output.text = restored.joinToString("\n\n") { describe(it) }.ifEmpty { "No saved local/mock tasks." } }
            }
        })
        page.addView(button("Review and retry incomplete read steps once") {
            launch {
                val task = latest ?: throw BrainFailure("RESTORE_TASK_FIRST")
                latest = engine().runReviewed(task.definition.plan.taskId, task.revision, task.definition.digest, recover = true)
            }
        })
        page.addView(button("Cancel local/mock work") { close() })
        page.addView(output)
    }
    private fun engine(): AgentBrain = brain ?: run {
        val app = context.applicationContext as VoiceBrainApplication
        AgentBrain(BrainPlanner(LocalBrainModel(), System::currentTimeMillis), BrainMemoryAccess(
            { ConfirmedMemoryContextConsent.isEnabled(context) }, { app.confirmedMemoryStore.list() }, System::currentTimeMillis),
            AndroidBrainAlpha.store(context), BrainSamples.reads(), session, { ownsView() && epoch.get() == runningEpoch },
            { it == BrainSamples.connection },
            // Local pending proposal, NOT a SensitiveActionGuard approval or a biometric result.
            approval = AgentApprovalPreparation { AgentPreparationStatus.REQUESTED },
            onProgress = { task ->
                val captured = runningEpoch
                latest = task
                scope.launch(Dispatchers.Main) { if (ownsView() && epoch.get() == captured) output.text = describe(task) }
            }).also { brain = it }
    }
    private fun launch(action: suspend () -> Unit) {
        if (!ownsView() || busy || otherBusy()) return
        runningEpoch = epoch.get()
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                action()
                withContext(Dispatchers.Main) { if (ownsView() && epoch.get() == runningEpoch) latest?.let { output.text = describe(it) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (safe: BrainFailure) {
                withContext(Dispatchers.Main) { if (ownsView()) output.text = "Local/mock work stopped: ${safe.code}. No external action executed." }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { if (ownsView()) output.text = "Secure checkpoint unavailable or task invalid. No fallback, deletion, retry or external action." }
            } finally { withContext(NonCancellable + Dispatchers.Main) { job = null; busyChanged() } }
        }
        job?.start(); busyChanged()
    }
    fun close() {
        epoch.incrementAndGet()
        val running = job
        running?.cancel()
        // Coroutine cancellation checkpoints fail closed. Never perform Keystore/disk IO on the UI thread.
        latest = null; output.text = "Local/mock view closed. Restore explicitly to inspect saved tasks."
        busyChanged()
    }
    private fun describe(task: BrainTask): String {
        val records = task.snapshot.steps.associateBy { it.stepId }
        return "LOCAL / MOCK ${task.definition.plan.taskId}\n${task.state}; calls: ${task.totalCalls}; revision: ${task.revision}\n" +
            task.definition.contracts().joinToString("\n\n") { contract ->
                val step = task.definition.plan.steps.single { it.stepId == contract.stepId }
                val record = records[contract.stepId]
                val data = when (val value = record?.result) {
                    is AgentData.Draft -> value.exactText
                    is AgentData.Report -> value.exactText
                    is AgentData.Priorities -> value.rows.joinToString { it.id }
                    is AgentData.Tasks -> "${value.rows.size} fake task rows"
                    is AgentData.Clock -> value.exactDisplay
                    is AgentData.Battery -> "${value.percent}% (fake)"
                    else -> "No output"
                }
                "${contract.stepId}: ${record?.status ?: "PENDING"}\n${contract.tool}; ${contract.effect}; ${contract.risk}; approval: ${contract.requiresApproval}\n" +
                    "Arguments: ${step.parameters}; target: ${step.command.target.orEmpty()}; value: ${step.command.value.orEmpty()}\n" +
                    "Expected: ${contract.expected}; actual: $data" + (record?.errorCode?.let { "\n$it" } ?: "")
            } + "\nProposals require fresh common approval integration; nothing was sent or scheduled."
    }
}
