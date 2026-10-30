package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Foreground coordinator. The existing runner remains the ONLY tool dispatcher.
 * An effect can only prepare a proposal through the existing approval interface; this class has no execute/grant API.
 */
class AgentBrain(private val planner: BrainPlanner, private val memory: BrainMemoryAccess,
    private val store: EncryptedBrainStore, private val reads: AgentReadPort,
    private val session: String, private val sessionCurrent: () -> Boolean,
    private val connectionCurrent: (AgentConnectionPin) -> Boolean,
    private val approval: AgentApprovalPreparation = AgentApprovalPreparation { AgentPreparationStatus.NOT_CONNECTED },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onProgress: (BrainTask) -> Unit = {}) {
    private val ownership = Mutex()
    private val active = AtomicReference<String?>()

    suspend fun preview(request: BrainGoalRequest): BrainTask {
        brainCheck(request.sessionId == session && sessionCurrent(), "STALE_SESSION")
        val view = memory.capture(request.memoryQuery)
        val definition = planner.plan(request, view)
        currentCoroutineContext().ensureActive()
        brainCheck(sessionCurrent() && memory.capture(request.memoryQuery).fingerprint == view.fingerprint, "MEMORY_OR_OWNER_CHANGED")
        return saveReviewedPlan(definition)
    }

    /** Trusted local review entry. Conversation proposals remain in RAM until the user elects to save/run.
     * No model tool exposes this method. Common admission/consent/deadline checks are repeated before storage.
     */
    internal suspend fun saveReviewedPlan(definition: BrainPlan): BrainTask {
        currentCoroutineContext().ensureActive()
        brainCheck(sessionCurrent() && memory.capture(definition.memoryQuery).fingerprint == definition.memoryFingerprint, "MEMORY_OR_OWNER_CHANGED")
        val plan = AgentPlanAdmission.admit(definition.plan, now()).plan
        val frozen = definition.copy(plan = plan)
        return store.create(BrainTask(1, session, frozen, AgentTaskSnapshot(plan.taskId, plan.parentTaskId,
            plan.objective, AgentStatus.DRAFT, plan.createdAtMillis, now(), plan.deadlineMillis, plan.idempotencyKey, 0, emptyList())))
    }

    /** Explicit foreground restore, never an automatic worker. Old-session proposals are display-only and blocked. */
    fun restore(taskId: String): BrainTask {
        brainCheck(active.get() == null && sessionCurrent(), "BUSY_OR_STALE_SESSION")
        val old = store.list().singleOrNull { it.definition.plan.taskId == taskId } ?: throw BrainFailure("TASK_NOT_FOUND")
        if (old.ownerSession == session) return old
        val terminal = old.snapshot.status in setOf(AgentStatus.SUCCEEDED, AgentStatus.CANCELLED, AgentStatus.REPORTED)
        return store.update(taskId, old.revision, old.ownerSession) {
            it.copy(revision = it.revision + 1, ownerSession = session,
                snapshot = if (terminal) it.snapshot else it.snapshot.copy(status = AgentStatus.BLOCKED,
                    steps = it.snapshot.steps.map { step ->
                        if (step.status in setOf(AgentStatus.RUNNING, AgentStatus.WAITING_FOR_APPROVAL))
                            step.copy(status = AgentStatus.BLOCKED, result = null, errorCode = "FRESH_REVIEW_REQUIRED") else step
                    }), reason = if (terminal) it.reason else "FRESH_REVIEW_REQUIRED")
        }
    }

    /** UI review binds this immutable plan digest AND storage revision; it is NOT action approval. */
    suspend fun runReviewed(taskId: String, revision: Long, digest: String, recover: Boolean = false): BrainTask {
        brainCheck(ownership.tryLock(), "BRAIN_BUSY")
        val token = UUID.randomUUID().toString()
        var current: BrainTask? = null
        var runner: AgentRunner? = null
        try {
            val saved = store.list().singleOrNull { it.definition.plan.taskId == taskId } ?: throw BrainFailure("TASK_NOT_FOUND")
            brainCheck(saved.ownerSession == session && saved.revision == revision && saved.definition.digest == digest &&
                sessionCurrent(), "STALE_REVIEW_OR_CHANGED_ARGUMENTS")
            val initial = saved.snapshot.status == AgentStatus.DRAFT
            brainCheck(initial || (recover && saved.snapshot.status in setOf(AgentStatus.FAILED, AgentStatus.BLOCKED,
                AgentStatus.WAITING_FOR_APPROVAL) && saved.recoveries == 0), "EXPLICIT_RECOVERY_REQUIRED_OR_TERMINAL")
            val definition = saved.definition
            val plan = AgentPlanAdmission.admit(definition.plan, now()).plan
            brainCheck(memory.capture(definition.memoryQuery).fingerprint == definition.memoryFingerprint, "MEMORY_CHANGED_REPLAN_REQUIRED")
            brainCheck(saved.totalCalls + plan.steps.size <= definition.budget.calls &&
                1 + saved.totalCalls + plan.steps.size <= definition.budget.costUnits, "RECOVERY_COST_BUDGET")
            // Durable claim BEFORE any port call. Another owner/process cannot silently claim this revision.
            current = store.update(taskId, saved.revision, session) {
                it.copy(revision = it.revision + 1, recoveries = it.recoveries + if (initial) 0 else 1,
                    snapshot = it.snapshot.copy(status = AgentStatus.RUNNING), reason = null)
            }
            active.set(token)
            val cached = if (initial) emptyMap() else saved.snapshot.steps.filter { it.status == AgentStatus.SUCCEEDED }
                .associate { it.stepId to it.result }
            var activeStep: String? = null
            val cachedReads = object : AgentReadPort {
                private fun prior(): AgentObservation? = cached[activeStep]?.let { AgentObservation(AgentStatus.SUCCEEDED, it) }
                override suspend fun clock() = prior() ?: reads.clock()
                override suspend fun battery() = prior() ?: reads.battery()
                override suspend fun tasks(connection: AgentConnectionPin) = prior() ?: reads.tasks(connection)
            }
            fun validOwner(): Boolean = active.get() == token && sessionCurrent() &&
                memory.capture(definition.memoryQuery).fingerprint == definition.memoryFingerprint
            runner = AgentRunner(cachedReads, ::validOwner, connectionCurrent, wallClock = now,
                journal = AgentMetadataJournal { event ->
                    if (event.status == AgentStatus.RUNNING) activeStep = event.stepId
                    brainCheck(validOwner(), "OWNER_OR_MEMORY_REVOKED")
                    val snapshot = requireNotNull(runner?.snapshot())
                    val used = saved.totalCalls + snapshot.calls + if (event.status == AgentStatus.RUNNING) 1 else 0
                    brainCheck(used <= definition.budget.calls, "CALL_BUDGET")
                    val owned = requireNotNull(current)
                    current = store.update(taskId, owned.revision, session) {
                        it.copy(revision = it.revision + 1, snapshot = snapshot, totalCalls = used)
                    }
                    onProgress(requireNotNull(current))
                }, approval = AgentApprovalPreparation { entry ->
                    brainCheck(validOwner(), "STALE_APPROVAL_OWNER")
                    // Recovery may inspect old exact proposals but can never reuse or recreate old-session grants.
                    if (!initial) AgentPreparationStatus.BLOCKED else approval.prepare(entry)
                })
            val outcome = requireNotNull(runner).run(plan)
            val owned = requireNotNull(current)
            brainCheck(active.get() == token, "CANCELLED_OWNER")
            val memoryCurrent = memory.capture(definition.memoryQuery).fingerprint == definition.memoryFingerprint
            val safeOutcome = if (memoryCurrent) outcome else outcome.copy(status = AgentStatus.CANCELLED,
                steps = outcome.steps.map { it.copy(result = null) })
            current = store.update(taskId, owned.revision, session) {
                it.copy(revision = it.revision + 1, snapshot = safeOutcome,
                    reason = if (!memoryCurrent) "MEMORY_REVOKED" else if (!initial) "FRESH_ACTION_APPROVAL_REQUIRED" else null)
            }
            return requireNotNull(current).also(onProgress)
        } catch (cancelled: CancellationException) {
            val owned = current
            if (owned != null && active.compareAndSet(token, null)) {
                try { store.update(taskId, owned.revision, session) {
                    it.copy(revision = it.revision + 1, snapshot = it.snapshot.copy(status = AgentStatus.CANCELLED,
                        steps = it.snapshot.steps.map { step -> step.copy(result = null) }), reason = "USER_CANCELLED")
                } } catch (_: Exception) { /* durable RUNNING is blocked on restore, never automatically resumed */ }
            }
            throw cancelled
        } finally {
            active.compareAndSet(token, null)
            runner?.clearResultPayloads(taskId)
            ownership.unlock()
        }
    }

    /** Revoke publication before coroutine cancellation; caller must cancel its running Job too. */
    fun cancel(taskId: String): BrainTask {
        active.set(null)
        val old = store.list().single { it.definition.plan.taskId == taskId }
        brainCheck(old.ownerSession == session, "STALE_TASK_OWNER")
        brainCheck(old.snapshot.status !in setOf(AgentStatus.SUCCEEDED, AgentStatus.REPORTED), "TASK_ALREADY_COMPLETED")
        return store.update(taskId, old.revision, session) {
            it.copy(revision = it.revision + 1, snapshot = it.snapshot.copy(status = AgentStatus.CANCELLED,
                steps = it.snapshot.steps.map { step -> step.copy(result = null) }), reason = "USER_CANCELLED")
        }
    }
}
