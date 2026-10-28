package com.example.voicebrainlive.agent

import com.example.voicebrainlive.agent.notion.ConnectionReadPin
import com.example.voicebrainlive.agent.notion.freezeReadPin

import com.example.voicebrainlive.commands.CommandPlan
import java.util.Collections

enum class AgentEffect { READ_ONLY, LOCAL_WRITE, EXTERNAL_WRITE, DEVICE_ACTION }
enum class AgentRisk { LOW, SENSITIVE }
enum class AgentRetry { NONE }
enum class AgentStatus { DRAFT, RUNNING, SUCCEEDED, REPORTED, WAITING_FOR_APPROVAL, BLOCKED, FAILED, OUTCOME_UNKNOWN, CANCELLED, SKIPPED }

/** Metadata only: no token, secret, authorization flag or executable callback. */
data class AgentConnectionPin(val connectionId: String, val revision: Long,
    val workspaceId: String, val resourceId: String, val resourceKind: String,
    val schemaRevision: Long, val businessStatusPropertyId: String, val unfinishedValues: Set<String>,
    val notionReadBinding: ConnectionReadPin? = null)

data class AgentStep(val stepId: String, val objective: String, val command: CommandPlan,
    val dependencies: Set<String> = emptySet(), val parameters: Map<String, String> = emptyMap(),
    val connection: AgentConnectionPin? = null, val timeoutMillis: Long = 5_000,
    val deadlineMillis: Long, val retry: AgentRetry = AgentRetry.NONE, val idempotencyKey: String)
data class AgentPlan(val taskId: String, val parentTaskId: String? = null, val objective: String,
    val createdAtMillis: Long, val deadlineMillis: Long, val idempotencyKey: String, val steps: List<AgentStep>)
data class AgentCapability(val name: String, val effect: AgentEffect, val risk: AgentRisk,
    val parameterKeys: Set<String>, val exactCommandPayload: Boolean = false)
data class AdmittedAgentStep(val step: AgentStep, val capability: AgentCapability)
data class AdmittedAgentPlan(val plan: AgentPlan, val ordered: List<AdmittedAgentStep>)

/** Closed authoritative inventory. SAFE in the legacy policy never means read-only here. */
object AgentCapabilities {
    private val entries = listOf(
        AgentCapability("get_current_time", AgentEffect.READ_ONLY, AgentRisk.LOW, emptySet()),
        AgentCapability("get_battery_status", AgentEffect.READ_ONLY, AgentRisk.LOW, emptySet()),
        AgentCapability("agent_local_report", AgentEffect.READ_ONLY, AgentRisk.LOW, setOf("clockStep", "batteryStep")),
        AgentCapability("agent_read_tasks", AgentEffect.READ_ONLY, AgentRisk.LOW, emptySet()),
        AgentCapability("agent_prioritize_tasks", AgentEffect.READ_ONLY, AgentRisk.LOW, setOf("sourceStep")),
        AgentCapability("agent_study_draft", AgentEffect.READ_ONLY, AgentRisk.LOW, setOf("sourceStep", "minutes")),
        AgentCapability("send_to_notion", AgentEffect.EXTERNAL_WRITE, AgentRisk.SENSITIVE, emptySet(), true),
        AgentCapability("send_sms", AgentEffect.DEVICE_ACTION, AgentRisk.SENSITIVE, emptySet(), true),
        // Legacy SAFE reminder is still an effect; candidate cannot execute it or mint approval.
        AgentCapability("set_reminder", AgentEffect.DEVICE_ACTION, AgentRisk.LOW, emptySet(), true)
    ).associateBy { it.name }
    fun get(name: String): AgentCapability? = entries[name]?.let {
        it.copy(parameterKeys = Collections.unmodifiableSet(LinkedHashSet(it.parameterKeys)))
    }
}

object AgentPlanAdmission {
    const val MAX_STEPS = 8
    const val MAX_CALLS = 16
    const val MAX_DURATION_MILLIS = 60_000L
    private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,80}"))
    private fun validText(value: String, limit: Int) = value.isNotBlank() && value.length <= limit &&
        value.indices.all { i -> when {
            Character.isHighSurrogate(value[i]) -> i + 1 < value.length && Character.isLowSurrogate(value[i + 1])
            Character.isLowSurrogate(value[i]) -> i > 0 && Character.isHighSurrogate(value[i - 1])
            else -> true
        } }
    private fun <T> frozenSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
    private fun frozenMap(values: Map<String, String>): Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(values))
    private fun pin(pin: AgentConnectionPin): AgentConnectionPin {
        require(validId(pin.connectionId) && pin.revision > 0 && pin.schemaRevision > 0) { "INVALID_CONNECTION" }
        require(validId(pin.resourceId) && pin.resourceKind in setOf("database", "data_source")) { "INVALID_CONNECTION" }
        val full = pin.notionReadBinding?.let(::freezeReadPin)
        if (full == null) require(validText(pin.workspaceId, 80)) { "INVALID_CONNECTION" }
        else require(pin.connectionId == full.profileId && pin.revision == full.profileRevision &&
            pin.workspaceId.isEmpty() && full.namespace.providerWorkspaceId == null &&
            pin.resourceKind == "data_source" && pin.resourceId == full.resource.dataSourceId &&
            pin.schemaRevision == full.schema.revision &&
            pin.businessStatusPropertyId == full.schema.businessStatus.propertyId &&
            pin.unfinishedValues == full.schema.unfinishedValues) { "MISMATCHED_NOTION_BINDING" }
        require(validText(pin.businessStatusPropertyId, 80) && pin.unfinishedValues.size in 1..10 &&
            pin.unfinishedValues.all { validText(it, 80) }) { "UNMAPPED_BUSINESS_STATUS" }
        return pin.copy(unfinishedValues = frozenSet(pin.unfinishedValues), notionReadBinding = full)
    }

    /** Deep-copy caller collections, then validate the WHOLE graph before any adapter call. */
    fun admit(input: AgentPlan, now: Long): AdmittedAgentPlan {
        require(input.steps.size in 1..MAX_STEPS) { "PLAN_BUDGET" }
        require(input.steps.all { it.dependencies.size <= MAX_STEPS && it.parameters.size <= 4 }) { "INPUT_COLLECTION_BUDGET" }
        val copied = input.steps.map { step -> step.copy(command = step.command.copy(),
            dependencies = frozenSet(step.dependencies), parameters = frozenMap(step.parameters),
            connection = step.connection?.let(::pin)) }
        val plan = input.copy(steps = Collections.unmodifiableList(copied))
        require(validId(plan.taskId) && (plan.parentTaskId == null || validId(plan.parentTaskId)) &&
            plan.parentTaskId != plan.taskId && validId(plan.idempotencyKey)) { "INVALID_TASK_ID" }
        require(validText(plan.objective, 2_000)) { "INVALID_OBJECTIVE" }
        require(plan.createdAtMillis >= 0 && now >= plan.createdAtMillis && plan.deadlineMillis > now &&
            plan.deadlineMillis - plan.createdAtMillis in 1..MAX_DURATION_MILLIS) { "INVALID_DEADLINE" }
        require(plan.steps.size in 1..MAX_STEPS && plan.steps.size <= MAX_CALLS) { "PLAN_BUDGET" }
        val ids = plan.steps.map { it.stepId }
        require(ids.toSet().size == ids.size) { "DUPLICATE_STEP" }
        require(plan.steps.map { it.idempotencyKey }.toSet().size == plan.steps.size) { "DUPLICATE_STEP_KEY" }
        val admitted = plan.steps.map { step ->
            require(validId(step.stepId) && validId(step.idempotencyKey) && validText(step.objective, 500)) { "INVALID_STEP" }
            require(step.timeoutMillis in 1..30_000 && step.deadlineMillis > now &&
                step.deadlineMillis <= plan.deadlineMillis && step.retry == AgentRetry.NONE) { "INVALID_STEP_BUDGET" }
            require(step.stepId !in step.dependencies && ids.containsAll(step.dependencies)) { "INVALID_DEPENDENCY" }
            val capability = requireNotNull(AgentCapabilities.get(step.command.intent)) { "UNSUPPORTED_TOOL" }
            require(step.parameters.keys == capability.parameterKeys && step.parameters.values.all { validText(it, 100) }) { "INVALID_PARAMETERS" }
            // requiresConfirmation is user/model-supplied metadata and is never authorization.
            require(step.command.confidence in 50..100 && validText(step.command.source, 80)) { "INVALID_COMMAND" }
            if (capability.exactCommandPayload) {
                require(validText(step.command.target.orEmpty(), 2_000) && validText(step.command.value.orEmpty(), 8_000)) { "MISSING_EXACT_PAYLOAD" }
            } else require(step.command.target == null && step.command.value == null) { "UNEXPECTED_COMMAND_PAYLOAD" }
            when (capability.name) {
                "agent_read_tasks" -> require(step.connection != null && step.dependencies.isEmpty()) { "MISSING_CONNECTION" }
                "agent_prioritize_tasks", "agent_study_draft" -> {
                    require(step.connection == null && step.dependencies == setOf(step.parameters.getValue("sourceStep"))) { "INVALID_SOURCE_DEPENDENCY" }
                    if (capability.name == "agent_study_draft") require(step.parameters.getValue("minutes").toIntOrNull()?.let { it in 5..180 } == true) { "INVALID_DURATION" }
                }
                "agent_local_report" -> require(step.connection == null && step.dependencies ==
                    setOf(step.parameters.getValue("clockStep"), step.parameters.getValue("batteryStep"))) { "INVALID_REPORT_DEPENDENCIES" }
                "send_to_notion" -> require(step.connection != null) { "MISSING_CONNECTION" }
                else -> require(step.connection == null) { "UNEXPECTED_CONNECTION" }
            }
            AdmittedAgentStep(step, capability)
        }
        val byId = admitted.associateBy { it.step.stepId }
        admitted.forEach { entry ->
            val step = entry.step
            when (entry.capability.name) {
                "agent_prioritize_tasks" -> require(byId.getValue(step.parameters.getValue("sourceStep")).capability.name == "agent_read_tasks") { "WRONG_SOURCE_TYPE" }
                "agent_study_draft" -> require(byId.getValue(step.parameters.getValue("sourceStep")).capability.name == "agent_prioritize_tasks") { "WRONG_SOURCE_TYPE" }
                "agent_local_report" -> require(
                    byId.getValue(step.parameters.getValue("clockStep")).capability.name == "get_current_time" &&
                    byId.getValue(step.parameters.getValue("batteryStep")).capability.name == "get_battery_status") { "WRONG_SOURCE_TYPE" }
            }
        }
        val remaining = admitted.toMutableList()
        val ordered = mutableListOf<AdmittedAgentStep>()
        val done = mutableSetOf<String>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { done.containsAll(it.step.dependencies) } ?: error("CYCLIC_PLAN")
            remaining.remove(next); ordered += next; done += next.step.stepId
        }
        return AdmittedAgentPlan(plan, Collections.unmodifiableList(ordered))
    }
}
