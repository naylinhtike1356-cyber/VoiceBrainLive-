package com.example.voicebrainlive.beta

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Presentation bridge to the existing engine/store. No model/network, new schema or effect executor. */
enum class BetaRecipe(val title: String) { STATUS("Device status"), STUDY("Plan my saved tasks"), REMINDER("Reminder draft (not scheduled)") }
internal class BetaGoalModel(private val recipe: BetaRecipe) : BrainGoalModel {
    override suspend fun propose(request: BrainGoalRequest, confirmedContext: String): BrainInterpretation {
        fun step(id: String, tool: String, out: BrainOutput, deps: Set<String> = emptySet(), args: Map<String,String> = emptyMap()) =
            BrainProposedStep(id, tool, deps, args, expected = out)
        val steps = if (recipe == BetaRecipe.STATUS) listOf(step("clock", "get_current_time", BrainOutput.CLOCK), step("battery", "get_battery_status", BrainOutput.BATTERY),
            step("report", "agent_local_report", BrainOutput.REPORT, setOf("clock", "battery"), mapOf("clockStep" to "clock", "batteryStep" to "battery")))
        else listOf(step("tasks", "agent_read_tasks", BrainOutput.TASKS), step("priorities", "agent_prioritize_tasks", BrainOutput.PRIORITIES, setOf("tasks"), mapOf("sourceStep" to "tasks")),
            step("draft", "agent_study_draft", BrainOutput.DRAFT, setOf("priorities"), mapOf("sourceStep" to "priorities", "minutes" to if (confirmedContext.contains("short sessions", true)) "20" else "30")))
        return BrainInterpretation.Candidate(steps + if (recipe == BetaRecipe.REMINDER) listOf(BrainProposedStep("reminder", "set_reminder", setOf("draft"),
            target = "Local study reminder proposal", value = "User must separately review an exact time; no reminder is scheduled.", expected = BrainOutput.APPROVAL_PROPOSAL)) else emptyList())
    }
}
class BetaLocalAgent(private val store: EncryptedBrainStore, private val device: AgentReadPort,
    private val memory: BrainMemoryAccess, private val owned: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis, progress: (BrainTask) -> Unit = {},
    private val taskConnection: AgentConnectionPin = LOCAL,
    private val externalTasks: (suspend (AgentConnectionPin) -> AgentObservation)? = null,
    private val sourceCurrent: () -> Boolean = { true }) {
    companion object {
        val LOCAL = AgentConnectionPin("beta_local", 1, "BETA_USER_DATA", "saved_goals", "database", 1, "task_state", setOf("To do"))
        fun describe(task: BrainTask): String {
            val records = task.snapshot.steps.associateBy { it.stepId }
            return "${task.definition.plan.objective}\n${task.state} · revision ${task.revision}\n" + task.definition.contracts().joinToString("\n\n") { c ->
                val s = task.definition.plan.steps.single { it.stepId == c.stepId }; val record = records[c.stepId]
                val output = when (val d = record?.result) {
                    is AgentData.Clock -> d.exactDisplay
                    is AgentData.Battery -> "Device battery: ${d.percent}%"
                    is AgentData.Report -> d.exactText
                    is AgentData.Tasks -> "${d.rows.size} actual user-created BETA goals (LOCAL_ONLY)"
                    is AgentData.Priorities -> d.rows.joinToString("\n") { it.title }
                    is AgentData.Draft -> d.exactText
                    else -> "No completed output"
                }
                "${c.stepId}: ${record?.status ?: "PENDING"}\n${c.tool}; ${c.effect}; approval required=${c.requiresApproval}\nArguments: ${s.parameters}; target: ${s.command.target.orEmpty()}; value: ${s.command.value.orEmpty()}\n$output" + record?.errorCode?.let { "\n$it" }.orEmpty()
            } + "\n${task.reason.orEmpty()}\nSource: local device / your encrypted BETA goals. Network and side effects OFF."
        }
    }
    private val session = UUID.randomUUID().toString()
    private val pending = AtomicReference<BrainPlan?>()
    @Volatile private var runningId: String? = null
    private val reads = object : AgentReadPort {
        override suspend fun clock() = device.clock()
        override suspend fun battery() = device.battery()
        override suspend fun tasks(connection: AgentConnectionPin): AgentObservation {
            if (!owned() || !sourceCurrent() || connection != taskConnection) return AgentObservation(AgentStatus.BLOCKED, errorCode = "LOCAL_PROFILE_REQUIRED")
            externalTasks?.let { return it(connection) }
            val rows = store.list().filter { it.definition.plan.taskId != runningId && it.snapshot.status !in setOf(AgentStatus.SUCCEEDED, AgentStatus.CANCELLED, AgentStatus.REPORTED) }
                .sortedByDescending { it.definition.plan.createdAtMillis }.mapIndexed { i, t ->
                    AgentTaskRow(t.definition.plan.taskId, t.definition.plan.objective, "To do", "LOCAL_ONLY", 100-i)
                }
            return AgentObservation(AgentStatus.SUCCEEDED, AgentData.Tasks(LOCAL, rows, true))
        }
    }
    private val engine = AgentBrain(BrainPlanner(BetaGoalModel(BetaRecipe.STATUS), now), memory, store, reads, session, owned, { it == taskConnection && sourceCurrent() },
        approval = AgentApprovalPreparation { AgentPreparationStatus.REQUESTED }, now = now, onProgress = progress)
    private fun check(value: Boolean, code: String) { if (!value) throw BrainFailure(code) }
    suspend fun preview(goal: String, recipe: BetaRecipe): BrainTask {
        pending.set(null); check(owned(), "STALE_BETA_VIEW")
        val query = if (recipe == BetaRecipe.STATUS) "" else "study"
        val view = memory.capture(query)
        val definition = BrainPlanner(BetaGoalModel(recipe), now).plan(BrainGoalRequest(UUID.randomUUID().toString(), goal, session, taskConnection, query), view)
        currentCoroutineContext().ensureActive(); check(owned() && memory.capture(query).fingerprint == view.fingerprint, "OWNER_OR_MEMORY_CHANGED")
        pending.set(definition)
        val p = definition.plan
        return BrainTask(1, session, definition, AgentTaskSnapshot(p.taskId,p.parentTaskId,p.objective,AgentStatus.DRAFT,p.createdAtMillis,now(),p.deadlineMillis,p.idempotencyKey,0,emptyList()))
    }
    /** Separate text-pilot proposal path. No confirmed memory/history leaves the device. */
    suspend fun previewTextPilot(goal:String,port:BrainStructuredPort):BrainTask {
        pending.set(null);check(owned(),"STALE_BETA_VIEW")
        val view=memory.capture("")
        val strict=BrainGoalModel { request,_ ->
            val output=port.propose(request.text,"")
            val proposal=BrainProposalSchema.decode(output.json)
            check(proposal.goal==request.text && proposal.candidate.steps.all{it.tool in BetaTextProtocol.allowed && it.target==null && it.value==null},"PILOT_TOOL_FORBIDDEN")
            check(output.reportedTokens!=null && output.reportedTokens in 1..4096,"PILOT_USAGE_REQUIRED")
            proposal.candidate
        }
        val definition=BrainPlanner(strict,now,{it in BetaTextProtocol.allowed}).plan(BrainGoalRequest(UUID.randomUUID().toString(),goal,session,taskConnection,""),view)
        currentCoroutineContext().ensureActive();check(owned()&&memory.capture("").fingerprint==view.fingerprint,"OWNER_OR_MEMORY_CHANGED")
        pending.set(definition);val p=definition.plan
        return BrainTask(1,session,definition,AgentTaskSnapshot(p.taskId,p.parentTaskId,p.objective,AgentStatus.DRAFT,p.createdAtMillis,now(),p.deadlineMillis,p.idempotencyKey,0,emptyList()))
    }
    suspend fun saveReviewed(id: String, digest: String): BrainTask {
        check(owned(), "STALE_BETA_VIEW")
        val p = pending.getAndSet(null) ?: throw BrainFailure("PREVIEW_FIRST")
        check(p.plan.taskId == id && p.digest == digest, "CHANGED_REVIEW")
        return engine.saveReviewedPlan(p)
    }
    suspend fun runReviewed(task: BrainTask): BrainTask {
        check(owned(), "STALE_BETA_VIEW"); check(!LocalUserTaskRecord.matches(task),"LOCAL_TASK_USE_TASKS")
        pending.set(null); runningId = task.definition.plan.taskId
        try { return engine.runReviewed(task.definition.plan.taskId, task.revision, task.definition.digest) }
        finally { runningId = null }
    }
    fun saved(): List<BrainTask> { check(owned(), "STALE_BETA_VIEW"); return store.list().filterNot(LocalUserTaskRecord::matches) }
    fun restoreForReview(id: String): BrainTask {
        pending.set(null); check(owned(), "STALE_BETA_VIEW")
        check(store.list().none{it.definition.plan.taskId==id&&LocalUserTaskRecord.matches(it)},"LOCAL_TASK_USE_TASKS")
        return engine.restore(id)
    }
    fun cancelReviewed(task: BrainTask): BrainTask {
        pending.set(null); check(owned(), "STALE_BETA_VIEW")
        check(!LocalUserTaskRecord.matches(task),"LOCAL_TASK_USE_TASKS")
        val old = store.list().singleOrNull { it.definition.plan.taskId == task.definition.plan.taskId } ?: throw BrainFailure("TASK_NOT_FOUND")
        check(old.revision == task.revision && old.definition.digest == task.definition.digest, "CHANGED_REVIEW")
        engine.restore(old.definition.plan.taskId)
        return engine.cancel(old.definition.plan.taskId)
    }
    fun discardPreview() { pending.set(null) }
}
