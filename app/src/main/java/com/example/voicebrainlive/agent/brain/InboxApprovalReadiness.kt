package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import com.example.voicebrainlive.commands.ActionApprovalStore
import com.example.voicebrainlive.commands.ActionSnapshot

internal enum class SimulatedBiometric { DENIED, HOST_FIXTURE_SUCCESS }
internal data class InboxMockTicket(val id: String, val taskId: String, val revision: Long, val digest: String,
    val action: ActionSnapshot) {
    override fun toString() = "InboxMockTicket(action=<redacted>, authorization=SIMULATION_ONLY)"
}
internal enum class InboxMockOutcome { BLOCKED, SIMULATED_COUNTER_ONLY }

/** Host readiness contract ONLY. No perform callback, native biometric result, M1 grant or production effect.
 * Uses a separate instance of the EXISTING ActionApprovalStore. Tickets cannot enter SensitiveActionGuard.
 * The only possible effect is incrementing this in-memory counter; all receipts say SIMULATED.
 * Actual Inbox Activity deliberately has no execution controls or registration for this class.
 */
internal class InboxApprovalReadiness(private val session: String, private val current: () -> Boolean,
    private val task: () -> BrainTask?, private val clock: () -> Long, private val hostOnly: Boolean = false) {
    private val store = ActionApprovalStore(clock, 60_000)
    private val tickets = linkedMapOf<String, InboxMockTicket>()
    var simulatedEffects: Int = 0; private set
    @Synchronized fun preview(row: AgentInboxRow, stepId: String): InboxMockTicket {
        brainCheck(hostOnly && current() && store.epoch(session) != null, "MOCK_APPROVAL_DISABLED_OR_STALE")
        val value = task() ?: throw BrainFailure("TASK_NOT_FOUND")
        brainCheck(row.source == InboxSource.BRAIN && row.key == "brain:${value.definition.plan.taskId}" && row.id == value.definition.plan.taskId &&
            row.revision == value.revision && row.requiresFreshApproval && value.ownerSession == session && tickets.size < 20 && simulatedEffects < 20 &&
            clock() >= 0 && clock() <= Long.MAX_VALUE - 60_000 && clock() < value.definition.plan.deadlineMillis &&
            value.state !in setOf(PersistentTaskState.CANCELLED, PersistentTaskState.COMPLETED, PersistentTaskState.RUNNING), "EXACT_FRESH_MOCK_REVIEW_REQUIRED")
        val step = value.definition.plan.steps.singleOrNull { it.stepId == stepId } ?: throw BrainFailure("STEP_NOT_FOUND")
        val target = step.command.target ?: throw BrainFailure("MOCK_DESTINATION_REQUIRED")
        brainCheck(AgentCapabilities.get(step.command.intent)?.effect?.let { it != AgentEffect.READ_ONLY } == true &&
            target.startsWith("mock:") && target.length <= 200 && step.command.value.orEmpty().length <= 8_000 &&
            step.connection == null, "MOCK_DESTINATION_REQUIRED")
        val snapshot = ActionSnapshot(step.command.intent, target, "mock:effect-counter", target,
            step.command.value, "HOST_SIMULATION_ONLY", "${value.definition.plan.taskId}:${value.revision}:${value.definition.digest}")
        val pending = store.request(session, snapshot)
        tickets.entries.removeAll { store.pending(it.key) == null }
        return InboxMockTicket(pending.id, value.definition.plan.taskId, value.revision, value.definition.digest, snapshot).also { tickets[it.id] = it }
    }
    @Synchronized fun simulateConfirmation(ticket: InboxMockTicket, shown: ActionSnapshot,
        biometric: SimulatedBiometric = SimulatedBiometric.DENIED, explicitTap: Boolean = false,
        permissionGranted: Boolean = false): InboxMockOutcome {
        val saved = task()
        val exact = hostOnly && current() && tickets[ticket.id] === ticket && saved != null &&
            saved.ownerSession == session && saved.definition.plan.taskId == ticket.taskId && saved.revision == ticket.revision &&
            saved.definition.digest == ticket.digest && clock() < saved.definition.plan.deadlineMillis &&
            saved.state !in setOf(PersistentTaskState.CANCELLED, PersistentTaskState.COMPLETED, PersistentTaskState.RUNNING)
        if (!exact || !explicitTap || !permissionGranted || biometric != SimulatedBiometric.HOST_FIXTURE_SUCCESS ||
            shown != ticket.action || !store.approve(ticket.id, shown) || !store.consume(ticket.id, session, shown)) {
            cancel(ticket); return InboxMockOutcome.BLOCKED
        }
        tickets.remove(ticket.id); simulatedEffects++
        return InboxMockOutcome.SIMULATED_COUNTER_ONLY
    }
    @Synchronized fun cancel(ticket: InboxMockTicket) { tickets.remove(ticket.id); store.cancel(ticket.id) }
    @Synchronized fun invalidate() { store.invalidate(session); tickets.clear() }
}
