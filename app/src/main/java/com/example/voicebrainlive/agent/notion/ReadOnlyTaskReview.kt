package com.example.voicebrainlive.agent.notion

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.BrainMemoryAccess
import com.example.voicebrainlive.commands.CommandPlan
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ReadOnlyReviewResult(val snapshot: AgentTaskSnapshot, val provenance: NotionReadProvenance,
    val proposedAction: CommandPlan? = null) {
    override fun toString() = "ReadOnlyReviewResult(status=${snapshot.status}, content=<redacted>, approval=NONE)"
}

/** Mock readiness workflow over existing template admission/runner. No second executor or saved-memory write.
 * Not registered with the conversation bridge or WorkManager; production HTTP stays disabled.
 */
class ReadOnlyTaskReview(private val profiles: NotionProfilesRepository, private val adapter: NotionReadOnlyAdapter,
    private val memory: BrainMemoryAccess, private val clock: () -> Long = System::currentTimeMillis,
    private val ownerCurrent: () -> Boolean = { true }) {
    suspend fun review(goal: String, input: ConnectionReadPin, proposedAction: CommandPlan? = null): ReadOnlyReviewResult {
        val pin = freezeReadPin(input); val now = clock()
        profileCheck(goal.isNotBlank() && goal.length <= 2_000 && now in 0..Long.MAX_VALUE - 60_000)
        suspend fun guard() {
            currentCoroutineContext().ensureActive()
            profileCheck(ownerCurrent(), ProfileFailure.CANCELLED)
            profileCheck(profiles.views().selectedProfileId == pin.profileId && profiles.isCurrent(pin), ProfileFailure.STALE_BINDING)
        }
        guard()
        val memoryView = memory.capture("study")
        val id = UUID.randomUUID().toString(); val key = UUID.randomUUID().toString()
        val binding = agentPin(pin)
        val base = AgentGoalTemplates.interpret(AgentGoal.StudyDraft(binding,
            if (memoryView.context.contains("short sessions", true)) 20 else 30), id, key, now).copy(objective = goal)
        val plan = if (proposedAction == null) base else {
            profileCheck(proposedAction.intent in setOf("set_reminder", "send_to_notion", "send_sms") &&
                proposedAction.target?.length in 1..2_000 && proposedAction.value?.length in 1..8_000)
            base.copy(steps = base.steps + AgentStep("proposal", "Review proposed external action",
                proposedAction.copy(requiresConfirmation = true), dependencies = setOf(base.steps.last().stepId),
                connection = if (proposedAction.intent == "send_to_notion") binding else null,
                deadlineMillis = base.deadlineMillis, idempotencyKey = "${id}_proposal"))
        }
        val noDeviceReads = object : AgentReadPort {
            override suspend fun clock() = AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_SELECTED")
            override suspend fun battery() = AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_SELECTED")
            override suspend fun tasks(connection: AgentConnectionPin) = AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_CONNECTED")
        }
        fun current() = ownerCurrent() && memory.capture("study").fingerprint == memoryView.fingerprint
        val reads = SyntheticStudyReadPort(noDeviceReads, profiles, adapter,
            { CapturedStudyReadContext(id, "tasks", base.deadlineMillis, ::current) }, clock)
        val runner = AgentRunner(reads, ::current, { it == binding && profiles.isCurrentCached(pin) }, wallClock = clock,
            // Existing common approval preparation boundary. No guard grant or effect execution is connected.
            approval = AgentApprovalPreparation { AgentPreparationStatus.NOT_CONNECTED })
        val result = runner.run(plan)
        guard(); profileCheck(current(), ProfileFailure.CANCELLED)
        return ReadOnlyReviewResult(result, NotionReadProvenance(pin, pin.resource.dataSourceId),
            plan.steps.lastOrNull { AgentCapabilities.get(it.command.intent)?.effect != AgentEffect.READ_ONLY }?.command?.copy())
    }
}
