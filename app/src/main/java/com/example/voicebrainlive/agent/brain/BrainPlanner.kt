package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.commands.CommandPlan
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemory
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class BrainFailure(val code: String) : IllegalStateException(code)
internal fun brainCheck(ok: Boolean, code: String) { if (!ok) throw BrainFailure(code) }
internal fun brainHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class BrainBudget(val steps: Int = 8, val depth: Int = 8, val calls: Int = 16,
    val costUnits: Int = 17, val durationMillis: Long = 60_000, val modelMillis: Long = 2_000) {
    init { require(steps in 1..8 && depth in 1..8 && calls in 1..16 && costUnits in 2..17 &&
        durationMillis in 1..60_000 && modelMillis in 1..5_000 && modelMillis <= durationMillis) }
}
data class BrainGoalRequest(val id: String, val text: String, val sessionId: String,
    val connection: AgentConnectionPin? = null, val memoryQuery: String = "", val budget: BrainBudget = BrainBudget())
enum class BrainOutput { CLOCK, BATTERY, TASKS, PRIORITIES, DRAFT, REPORT, APPROVAL_PROPOSAL }
data class BrainProposedStep(val id: String, val tool: String, val dependencies: Set<String> = emptySet(),
    val arguments: Map<String, String> = emptyMap(), val target: String? = null, val value: String? = null,
    val expected: BrainOutput)
sealed interface BrainInterpretation {
    data class Candidate(val steps: List<BrainProposedStep>) : BrainInterpretation
    data class Unsupported(val reason: String = "UNSUPPORTED_LOCAL_GOAL") : BrainInterpretation
}
fun interface BrainGoalModel { suspend fun propose(request: BrainGoalRequest, confirmedContext: String): BrainInterpretation }

/** A callback transport cannot deliver twice or resume interpretation after cancellation. */
fun interface BrainCallbackPort {
    fun request(goal: BrainGoalRequest, confirmedContext: String, reply: (BrainInterpretation) -> Unit): () -> Unit
}
class CallbackBrainModel(private val port: BrainCallbackPort) : BrainGoalModel {
    override suspend fun propose(request: BrainGoalRequest, confirmedContext: String): BrainInterpretation = suspendCancellableCoroutine { continuation ->
        val delivered = AtomicBoolean(false)
        val dispose = AtomicReference<(() -> Unit)?>(null)
        continuation.invokeOnCancellation { delivered.set(true); dispose.getAndSet(null)?.invoke() }
        val stop = port.request(request, confirmedContext) { value ->
            if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
        }
        dispose.set(stop)
        if (!continuation.isActive) dispose.getAndSet(null)?.invoke()
    }
}

data class BrainMemoryView(val context: String, val fingerprint: String)
/** No save/update/delete API. The canonical renderer enforces per-record consent, privacy and retention. */
class BrainMemoryAccess(private val enabled: () -> Boolean, private val records: () -> List<ConfirmedMemory>,
    private val now: () -> Long) {
    fun capture(query: String): BrainMemoryView {
        brainCheck(query.length <= 80, "MEMORY_QUERY_BUDGET")
        val consent = enabled()
        val text = if (!consent || query.isBlank()) "" else try { ConfirmedMemoryContext.render(records(), now(), query) }
            catch (_: Exception) { throw BrainFailure("MEMORY_UNAVAILABLE") }
        return BrainMemoryView(text, brainHash(("$consent\n$text").toByteArray(Charsets.UTF_8)))
    }
}
data class BrainPlan(val plan: AgentPlan, val memoryQuery: String, val memoryFingerprint: String, val budget: BrainBudget) {
    val digest: String get() = brainHash(BrainCodec.planBytes(plan))
    fun contracts(): List<BrainStepContract> = plan.steps.map { step ->
        val capability = requireNotNull(AgentCapabilities.get(step.command.intent))
        BrainStepContract(step.stepId, capability.name, capability.effect, capability.risk,
            capability.effect != AgentEffect.READ_ONLY, outputFor(capability.name), AgentRetry.NONE)
    }
}
data class BrainStepContract(val stepId: String, val tool: String, val effect: AgentEffect, val risk: AgentRisk,
    val requiresApproval: Boolean, val expected: BrainOutput, val retry: AgentRetry)
internal fun outputFor(tool: String): BrainOutput = when (tool) {
    "get_current_time" -> BrainOutput.CLOCK
    "get_battery_status" -> BrainOutput.BATTERY
    "agent_read_tasks" -> BrainOutput.TASKS
    "agent_prioritize_tasks" -> BrainOutput.PRIORITIES
    "agent_study_draft" -> BrainOutput.DRAFT
    "agent_local_report" -> BrainOutput.REPORT
    "set_reminder", "send_sms", "send_to_notion" -> BrainOutput.APPROVAL_PROPOSAL
    else -> throw BrainFailure("UNSUPPORTED_TOOL")
}

/** Reusable composition, not an executor or a source of action authority. Models return data only. */
class BrainPlanner(private val model: BrainGoalModel, private val now: () -> Long,
    private val available: (String) -> Boolean = { AgentCapabilities.get(it) != null }) {
    suspend fun plan(request: BrainGoalRequest, memory: BrainMemoryView): BrainPlan = prepare(request, memory, false)
    /** RAM-only selected synthetic-source preview. Never serialize, grant approval or resume it from disk. */
    internal suspend fun planReadOnlyTransient(request: BrainGoalRequest, memory: BrainMemoryView): AgentPlan =
        prepare(request, memory, true).plan
    private suspend fun prepare(request: BrainGoalRequest, memory: BrainMemoryView, transientReadOnly: Boolean): BrainPlan {
        brainCheck(request.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && request.sessionId.matches(Regex("[A-Za-z0-9_-]{1,80}")), "INVALID_OWNER_OR_GOAL")
        brainCheck(request.text.isNotBlank() && request.text.length <= 2_000, "GOAL_TEXT_BUDGET")
        // Alpha checkpoint attachments intentionally support mock/legacy typed pins only; no real connector is wired.
        brainCheck(transientReadOnly || request.connection?.notionReadBinding == null, "ALPHA_FULL_BINDING_NOT_SUPPORTED")
        val created = now()
        brainCheck(created >= 0 && created <= Long.MAX_VALUE - request.budget.durationMillis, "INVALID_CLOCK")
        val deadline = created + request.budget.durationMillis
        val interpretation = withTimeoutOrNull(request.budget.modelMillis) { model.propose(request, memory.context) }
            ?: throw BrainFailure("INTERPRETATION_TIMEOUT_NO_RETRY")
        currentCoroutineContext().ensureActive()
        val candidate = interpretation as? BrainInterpretation.Candidate ?: throw BrainFailure("UNSUPPORTED_GOAL")
        brainCheck(candidate.steps.size in 1..request.budget.steps && candidate.steps.size <= request.budget.calls &&
            1 + candidate.steps.size <= request.budget.costUnits, "PLAN_COST_OR_STEP_BUDGET")
        brainCheck(!transientReadOnly || candidate.steps.all { AgentCapabilities.get(it.tool)?.effect == AgentEffect.READ_ONLY }, "TRANSIENT_EFFECT_FORBIDDEN")
        val steps = candidate.steps.map { proposed ->
            brainCheck(available(proposed.tool), "TOOL_UNAVAILABLE")
            val capability = AgentCapabilities.get(proposed.tool) ?: throw BrainFailure("UNSUPPORTED_TOOL")
            brainCheck(proposed.expected == outputFor(proposed.tool), "WRONG_EXPECTED_OUTPUT")
            brainCheck(proposed.arguments.size <= 4 && proposed.dependencies.size <= 8, "SCHEMA_COLLECTION_BUDGET")
            AgentStep(proposed.id, "Run ${proposed.tool}", CommandPlan(proposed.tool, proposed.target, proposed.value,
                source = "agent_brain_candidate", requiresConfirmation = capability.effect != AgentEffect.READ_ONLY),
                proposed.dependencies, proposed.arguments,
                if (proposed.tool in setOf("agent_read_tasks", "send_to_notion")) request.connection else null,
                timeoutMillis = minOf(5_000, request.budget.durationMillis), deadlineMillis = deadline,
                idempotencyKey = "${request.id}_${proposed.id}".take(80))
        }
        val raw = AgentPlan(request.id, objective = request.text, createdAtMillis = created,
            deadlineMillis = deadline, idempotencyKey = request.id, steps = steps)
        val admitted = try { AgentPlanAdmission.admit(raw, now()) } catch (_: Exception) { throw BrainFailure("PLAN_ADMISSION_REJECTED") }
        val depths = mutableMapOf<String, Int>()
        admitted.ordered.forEach { entry ->
            val depth = 1 + (entry.step.dependencies.maxOfOrNull { depths.getValue(it) } ?: 0)
            brainCheck(depth <= request.budget.depth, "PLAN_DEPTH_BUDGET"); depths[entry.step.stepId] = depth
        }
        if (transientReadOnly) brainCheck(admitted.plan.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024, "PLAN_PREVIEW_BUDGET")
        else brainCheck(BrainCodec.planBytes(admitted.plan).size <= 32 * 1024, "PLAN_WIRE_BUDGET")
        return BrainPlan(admitted.plan, request.memoryQuery, memory.fingerprint, request.budget)
    }
}
