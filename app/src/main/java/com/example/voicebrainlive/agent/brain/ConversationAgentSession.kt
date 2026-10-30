package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Foreground conversation adapter. Only direct local user input may consume a review ticket.
 * Model tools can propose, never review, restore, approve or execute actions.
 */
class ConversationAgentSession(private val sessionId: String, private val current: () -> Boolean,
    private val store: () -> EncryptedBrainStore, private val memory: BrainMemoryAccess,
    private val reads: AgentReadPort, private val connection: AgentConnectionPin? = null,
    private val connectionCurrent: (AgentConnectionPin) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis, private val display: (String) -> Unit = {}) {
    private data class Review(val definition: BrainPlan, val generation: Long)
    private val generation = AtomicLong()
    private val runningGeneration = AtomicLong(-1)
    private val active = AtomicReference<Job?>()
    private val review = AtomicReference<Review?>()
    private val lock = Mutex()
    private val wire = AtomicReference<String?>()
    private val calls = linkedMapOf<String, String>()
    private var engine: AgentBrain? = null
    private val planner = BrainPlanner(StructuredBrainModel(BrainStructuredPort { _, _ ->
        BrainModelOutput(wire.get() ?: throw BrainFailure("MODEL_OUTPUT_UNAVAILABLE"))
    }), now)

    /** No file IO or blocking join on audio/provider/UI callback threads. */
    fun interrupt() {
        generation.incrementAndGet(); review.set(null)
        active.get()?.cancel(CancellationException("Conversation interrupted"))
    }
    internal fun publicationGuard(): () -> Boolean {
        // Capture the producing operation; later work must never revive a queued private preview.
        val owner = runningGeneration.get()
        return { owner >= 0 && current() && generation.get() == owner }
    }

    private fun engine(): AgentBrain = engine ?: AgentBrain(planner, memory, store(), reads, sessionId,
        { current() && generation.get() == runningGeneration.get() && active.get()?.isActive == true }, connectionCurrent,
        // Pending proposal only. There is deliberately NO approved-action execution adapter.
        AgentApprovalPreparation { AgentPreparationStatus.REQUESTED }, now,
        onProgress = { task -> if (current() && generation.get() == runningGeneration.get() && active.get()?.isActive == true) display(describe(task)) }).also { engine = it }

    suspend fun propose(callId: String, raw: String, exactUserGoal: String): String = operation {
        brainCheck(callId.matches(Regex("[A-Za-z0-9_-]{1,256}")), "INVALID_MODEL_CALL_ID")
        val hash = brainHash(raw.toByteArray(Charsets.UTF_8))
        val previous = calls[callId]
        brainCheck(previous == null, if (previous == hash) "DUPLICATE_MODEL_CALL" else "MODEL_CALL_ARGUMENTS_CHANGED")
        brainCheck(calls.size < 16, "CONVERSATION_PLAN_BUDGET")
        calls[callId] = hash // Keep failed/cancelled requests tombstoned. No automatic retry.
        val decoded = BrainProposalSchema.decode(raw)
        brainCheck(exactUserGoal.isNotBlank() && decoded.goal == exactUserGoal, "EXPLICIT_GOAL_REQUIRED")
        brainCheck(connection != null || decoded.candidate.steps.none { it.tool in setOf("agent_read_tasks", "send_to_notion") }, "TASK_CONNECTOR_NOT_CONNECTED")
        val epoch = generation.get()
        review.set(null); wire.set(raw)
        val query = if (decoded.goal.contains("study", true) || decoded.goal.contains("စာလေ့လာ") || decoded.goal.contains("공부")) "study" else ""
        val view = memory.capture(query)
        val definition = planner.plan(BrainGoalRequest(UUID.randomUUID().toString(), exactUserGoal, sessionId, connection, query), view)
        brainCheck(memory.capture(query).fingerprint == view.fingerprint, "MEMORY_OR_OWNER_CHANGED")
        ensureOwner(epoch)
        val plan = definition.plan
        val task = BrainTask(1, sessionId, definition, AgentTaskSnapshot(plan.taskId, plan.parentTaskId, plan.objective,
            AgentStatus.DRAFT, plan.createdAtMillis, now(), plan.deadlineMillis, plan.idempotencyKey, 0, emptyList()))
        review.set(Review(definition, epoch))
        display(describe(task) + "\nPreview is RAM-only. Run read steps saves an encrypted task checkpoint with these exact parameters/results. This is not action approval.")
        metadata(task)
    }

    suspend fun runFromUser(taskId: String): String = operation {
        val ticket = review.getAndSet(null) ?: throw BrainFailure("FRESH_LOCAL_REVIEW_REQUIRED")
        brainCheck(ticket.definition.plan.taskId == taskId && ticket.generation == generation.get(), "STALE_LOCAL_REVIEW")
        val saved = engine().saveReviewedPlan(ticket.definition)
        val task = engine().runReviewed(taskId, saved.revision, saved.definition.digest)
        ensureOwner(ticket.generation)
        display(describe(task)); metadata(task)
    }

    /** Restore only displays state. No review ticket/grant is restored and no port is called. */
    suspend fun restoreFromUser(taskId: String): String = operation {
        review.set(null)
        val task = engine().restore(taskId)
        display(describe(task) + "\nRestored for inspection only. No approval reused; create a new plan to run.")
        metadata(task)
    }

    private fun ensureOwner(epoch: Long) { brainCheck(current() && generation.get() == epoch, "STALE_CONVERSATION"); }
    private suspend fun operation(action: suspend () -> String): String = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext "AGENT_ERROR:BRAIN_BUSY"
        val epoch = generation.get()
        try {
            ensureOwner(epoch); currentCoroutineContext().ensureActive()
            runningGeneration.set(epoch)
            active.set(currentCoroutineContext()[Job])
            withTimeout(30_000) { action() }.also { ensureOwner(epoch) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: BrainFailure) { "AGENT_ERROR:${safe.code}" }
        catch (_: Exception) { "AGENT_ERROR:SECURE_AGENT_UNAVAILABLE" }
        finally { wire.set(null); active.set(null); lock.unlock() }
    }

    /** Provider gets metadata only, never local memory, goal, targets, arguments, task rows or drafts. */
    private fun metadata(task: BrainTask) = "AGENT:${task.snapshot.status};task=${task.definition.plan.taskId};" +
        task.snapshot.steps.joinToString(",") { "${it.stepId}:${it.status}" } + ";actions=NOT_CONNECTED;review=LOCAL_TEXT_ONLY"

    private fun describe(task: BrainTask): String {
        val records = task.snapshot.steps.associateBy { it.stepId }
        return ("Agent plan ${task.definition.plan.taskId} — ${task.snapshot.status}\n" +
            "Local reads only; task connector and action execution are NOT CONNECTED.\n" +
            task.definition.plan.steps.joinToString("\n") { step ->
                val record = records[step.stepId]
                val result = when (val data = record?.result) {
                    is AgentData.Draft -> data.exactText
                    is AgentData.Report -> data.exactText
                    else -> ""
                }
                val error = if (record?.errorCode == "READ_NOT_PROVEN_SUCCEEDED")
                    "Read could not be verified; check the source connection and permissions. No automatic retry." else ""
                "${step.stepId}: ${step.command.intent} [${record?.status ?: AgentStatus.DRAFT}] " +
                    "${step.command.target.orEmpty()} ${step.command.value.orEmpty()} ${step.parameters} $result $error"
            }).take(32_768)
    }
}
