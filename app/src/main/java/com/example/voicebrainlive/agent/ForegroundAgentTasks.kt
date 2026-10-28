// DOCS-ONLY REPLACEMENT CANDIDATE. Requires reviewed Stage8 full-binding core proposal.
package com.example.voicebrainlive.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.voicebrainlive.agent.notion.CapturedStudyReadContext
import com.example.voicebrainlive.agent.notion.ConnectionReadPin
import com.example.voicebrainlive.agent.notion.NotionProfilesRepository
import com.example.voicebrainlive.agent.notion.SyntheticNotionPort
import com.example.voicebrainlive.agent.notion.SyntheticStudyReadPort
import com.example.voicebrainlive.agent.notion.agentPin
import com.example.voicebrainlive.agent.notion.freezeReadPin
import com.example.voicebrainlive.agent.notion.fullAgentBinding
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One process-local foreground facade. View leases are cancellation ownership, never approval. */
class ForegroundAgentTasks(
    reads: AgentReadPort,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val now: () -> Long = System::currentTimeMillis,
    private val studyProfiles: NotionProfilesRepository? = null,
    private val syntheticNotionPort: SyntheticNotionPort? = null
) {
    class ViewLease internal constructor()
    data class State(val preview: AgentPlan? = null, val snapshot: AgentTaskSnapshot? = null,
        val message: String = "Choose a template and review its exact steps.", val running: Boolean = false, val runAllowed: Boolean = false)
    private data class Run(val lease: ViewLease, val taskId: String, val job: Job)
    private val lock = Any()
    private var owner: ViewLease? = null
    private var active: Run? = null
    private var inFlight: Job? = null
    private var executingTaskId: String? = null
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val routedReads: AgentReadPort = if (studyProfiles != null && syntheticNotionPort != null)
        SyntheticStudyReadPort(reads, studyProfiles, syntheticNotionPort, ::captureStudyContext, now)
        else reads
    private val runner: AgentRunner = AgentRunner(routedReads,
        sessionCurrent = { synchronized(lock) { active?.let { it.lease === owner && it.taskId == executingTaskId && previewBindingsCurrentLocked(it.taskId) } == true } },
        connectionCurrent = { pin ->
            // Atomic metadata cache only: never IO under the facade/runner callbacks.
            // The read adapter authenticates current storage before/after every await.
            try { syntheticNotionPort != null && studyProfiles?.isCurrentCached(fullAgentBinding(pin)) == true }
            catch (_: Exception) { false }
        },
        claims = AgentRunClaims(), wallClock = now,
        journal = AgentMetadataJournal { event ->
            synchronized(lock) {
                if (active?.let { it.lease === owner && it.taskId == event.taskId && it.taskId == executingTaskId && previewBindingsCurrentLocked(it.taskId) } == true) {
                    val snapshot = runnerSnapshot(event.taskId)
                    if (snapshot != null) mutableState.value = mutableState.value.copy(snapshot = snapshot, running = true)
                }
            }
        }, approval = AgentApprovalPreparation { AgentPreparationStatus.NOT_CONNECTED })

    private fun runnerSnapshot(taskId: String): AgentTaskSnapshot? = runner.snapshot()?.takeIf { it.taskId == taskId }

    fun openView(): ViewLease = synchronized(lock) {
        revokeLocked()
        ViewLease().also { owner = it }
    }
    fun isCurrent(lease: ViewLease): Boolean = synchronized(lock) { owner === lease }

    fun previewLocalStatus(lease: ViewLease): Boolean = synchronized(lock) {
        if (owner !== lease || active != null) return false
        val time = now()
        val taskId = UUID.randomUUID().toString()
        return try {
            val frozen = AgentPlanAdmission.admit(AgentGoalTemplates.interpret(AgentGoal.LocalStatus,
                taskId, UUID.randomUUID().toString(), time), time).plan
            mutableState.value = State(preview = frozen, message = "Preview only. Nothing has executed. Run starts these three local read steps; no provider or external write.", runAllowed = true)
            true
        } catch (_: Exception) { mutableState.value = State(message = "Preview unavailable. No work started."); false }
    }

    /** Closed fake-only template. A view lease is cancellation ownership, never an approval. */
    fun previewStudyDraft(lease: ViewLease, capturedFullPin: ConnectionReadPin, minutes: Int): Boolean = synchronized(lock) {
        if (owner !== lease || active != null || inFlight != null) return false
        return try {
            check(syntheticNotionPort != null && studyProfiles != null)
            val full = freezeReadPin(capturedFullPin)
            check(studyProfiles.isCurrentCached(full))
            val time = now()
            val frozen = AgentPlanAdmission.admit(AgentGoalTemplates.interpret(AgentGoal.StudyDraft(agentPin(full), minutes),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), time), time).plan
            mutableState.value = State(preview = frozen,
                message = "SYNTHETIC sample preview. No Notion account is connected. Run reads only fake task properties, ranks unfinished business tasks and prepares a local study draft. No remote write or reminder.", runAllowed = true)
            true
        } catch (_: Exception) {
            mutableState.value = State(message = "Synthetic study preview unavailable or stale. No account accessed or work started.")
            false
        }
    }

    private fun isSyntheticStudy(plan: AgentPlan): Boolean = plan.steps.any { it.connection?.notionReadBinding != null }

    private fun previewBindingsCurrentLocked(taskId: String): Boolean {
        val plan = mutableState.value.preview?.takeIf { it.taskId == taskId } ?: return false
        return plan.steps.all { step -> step.connection?.let { pin ->
            try { syntheticNotionPort != null && studyProfiles?.isCurrentCached(fullAgentBinding(pin)) == true }
            catch (_: Exception) { false }
        } != false }
    }

    /** Derive context from the exact admitted active run, never from selection or any later run. */
    private fun captureStudyContext(): CapturedStudyReadContext? = synchronized(lock) {
        val capturedRun = active ?: return null
        if (capturedRun.lease !== owner || capturedRun.taskId != executingTaskId) return null
        val plan = mutableState.value.preview?.takeIf { it.taskId == capturedRun.taskId } ?: return null
        val step = plan.steps.singleOrNull { it.command.intent == "agent_read_tasks" && it.connection?.notionReadBinding != null } ?: return null
        CapturedStudyReadContext(capturedRun.taskId, step.stepId, minOf(plan.deadlineMillis, step.deadlineMillis)) {
            synchronized(lock) { active === capturedRun && owner === capturedRun.lease && executingTaskId == capturedRun.taskId }
        }
    }

    fun runPreview(lease: ViewLease, taskId: String): Boolean {
        val job = synchronized(lock) {
            val preview = mutableState.value.preview
            if (owner !== lease || active != null || inFlight != null || preview?.taskId != taskId || !mutableState.value.runAllowed) return false
            val work = scope.launch(start = CoroutineStart.LAZY) {
                synchronized(lock) { if (owner === lease && active?.taskId == taskId) executingTaskId = taskId }
                try {
                    val result = runner.run(preview)
                    synchronized(lock) {
                        if (owner === lease && active?.taskId == taskId && !previewBindingsCurrentLocked(taskId)) {
                            runner.clearResultPayloads(taskId)
                            mutableState.value = State(preview, result.copy(status = AgentStatus.CANCELLED,
                                steps = java.util.Collections.unmodifiableList(result.steps.map { it.copy(result = null) })),
                                "Synthetic connection changed; late result cleared. No account accessed or retry.")
                        } else if (owner === lease && active?.taskId == taskId) mutableState.value = State(preview, result,
                            if (isSyntheticStudy(preview)) {
                                if (result.status == AgentStatus.SUCCEEDED) "SYNTHETIC study draft prepared locally. No connected account, remote write, or scheduled reminder."
                                else "Synthetic task ended: ${result.status}. No automatic retry or connected account."
                            } else if (result.status == AgentStatus.SUCCEEDED) "Local report completed." else "Task ended: ${result.status}. No automatic retry.")
                    }
                } catch (_: CancellationException) {
                    // The owner already published cancellation and revoked result admission.
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (owner === lease && active?.taskId == taskId) mutableState.value = State(preview,
                            message = "Task could not start or validate. No automatic retry.")
                    }
                } finally {
                    synchronized(lock) {
                        if (owner !== lease || active?.taskId != taskId || active?.lease !== lease)
                            runner.clearResultPayloads(taskId)
                        if (active?.taskId == taskId && active?.lease === lease) active = null
                        if (executingTaskId == taskId) executingTaskId = null
                    }
                }
            }
            active = Run(lease, taskId, work)
            inFlight = work
            work.invokeOnCompletion { synchronized(lock) { if (inFlight === work) inFlight = null } }
            mutableState.value = State(preview = preview, message = if (isSyntheticStudy(preview)) "Running SYNTHETIC sample steps; no connected account…" else "Running local read steps…", running = true)
            work
        }
        job.start()
        return true
    }

    fun cancel(lease: ViewLease, taskId: String): Boolean = synchronized(lock) {
        if (owner !== lease || active?.taskId != taskId || active?.lease !== lease || !mutableState.value.running) return false
        revokeRunLocked("Cancelled. No external effects were requested.")
        true
    }
    fun stop(lease: ViewLease) = synchronized(lock) {
        if (owner === lease) { revokeLocked(); owner = null }
    }

    private fun revokeRunLocked(message: String) {
        val old = active
        val state = mutableState.value
        active = null // Revoke before cooperative cancellation can run any callback.
        executingTaskId = null
        old?.taskId?.let { runner.clearResultPayloads(it) }
        old?.job?.cancel()
        val snapshot = state.snapshot?.copy(status = if (state.running) AgentStatus.CANCELLED else state.snapshot.status,
            steps = state.snapshot.steps.map { it.copy(result = null) })
        mutableState.value = State(state.preview, snapshot, if (state.running) message else "Task view closed; terminal status kept. Current result references dropped.")
    }
    private fun revokeLocked() {
        if (active != null) revokeRunLocked("Cancelled when the foreground task view left. Results cleared.")
        else {
            val old = mutableState.value
            old.preview?.taskId?.let { runner.clearResultPayloads(it) }
            mutableState.value = old.copy(snapshot = old.snapshot?.copy(steps = old.snapshot.steps.map { it.copy(result = null) }),
                message = if (old.preview != null) "Previous task view closed; results cleared. Preview a new task to run." else old.message, runAllowed = false)
        }
    }
}

/** Genuine local platform reads only; no secure preferences, microphone, provider or legacy Notion. */
class AndroidAgentReadPort(context: Context) : AgentReadPort {
    private val app = context.applicationContext
    override suspend fun clock(): AgentObservation = try {
        AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock(DateFormat.getDateTimeInstance().format(Date())))
    } catch (_: Exception) { AgentObservation(AgentStatus.FAILED, errorCode = "LOCAL_CLOCK_UNAVAILABLE") }
    override suspend fun battery(): AgentObservation = try {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (scale !in 1..10_000 || level !in 0..scale) AgentObservation(AgentStatus.FAILED, errorCode = "LOCAL_BATTERY_UNAVAILABLE")
        else AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery((level.toLong() * 100 / scale).toInt()))
    } catch (_: Exception) { AgentObservation(AgentStatus.FAILED, errorCode = "LOCAL_BATTERY_UNAVAILABLE") }
    override suspend fun tasks(connection: AgentConnectionPin): AgentObservation =
        AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_CONNECTED")
}
