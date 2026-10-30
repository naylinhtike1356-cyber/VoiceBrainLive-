package com.example.voicebrainlive.agent

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

data class AgentTaskRow(val id: String, val title: String, val businessStatus: String,
    val syncStatus: String, val priority: Int)
sealed interface AgentData {
    data class Clock(val exactDisplay: String) : AgentData
    data class Battery(val percent: Int) : AgentData
    data class Tasks(val connection: AgentConnectionPin, val rows: List<AgentTaskRow>, val complete: Boolean) : AgentData
    data class Priorities(val connection: AgentConnectionPin, val rows: List<AgentTaskRow>) : AgentData
    data class Draft(val exactText: String, val connection: AgentConnectionPin?, val sourceRowIds: List<String>) : AgentData
    data class Report(val exactText: String) : AgentData
}
data class AgentObservation(val status: AgentStatus, val data: AgentData? = null, val errorCode: String? = null)
interface AgentReadPort {
    suspend fun clock(): AgentObservation
    suspend fun battery(): AgentObservation
    suspend fun tasks(connection: AgentConnectionPin): AgentObservation
}
enum class AgentPreparationStatus { REQUESTED, NOT_CONNECTED, BLOCKED, OUTCOME_UNKNOWN }
fun interface AgentApprovalPreparation {
    /** Trusted adapter may prepare through the captured common guard, NEVER execute effects. */
    suspend fun prepare(step: AdmittedAgentStep): AgentPreparationStatus
}
data class AgentStepRecord(val stepId: String, val status: AgentStatus,
    val startedAtMillis: Long?, val updatedAtMillis: Long, val result: AgentData? = null, val errorCode: String? = null)
data class AgentTaskSnapshot(val taskId: String, val parentTaskId: String?, val objective: String,
    val status: AgentStatus, val createdAtMillis: Long, val updatedAtMillis: Long,
    val deadlineMillis: Long, val idempotencyKey: String, val calls: Int, val steps: List<AgentStepRecord>)
data class AgentJournalEvent(val taskId: String, val stepId: String?, val status: AgentStatus,
    val timestampMillis: Long, val calls: Int, val errorCode: String?)
fun interface AgentMetadataJournal { fun record(event: AgentJournalEvent) }

/** Process-local bounded claims; no restart persistence or exactly-once provider guarantee. */
class AgentRunClaims(private val capacity: Int = 64) {
    private val tasks = mutableSetOf<String>()
    private val keys = mutableSetOf<String>()
    init { require(capacity in 1..100) }
    @Synchronized fun claim(task: String, key: String): Boolean {
        if (tasks.size >= capacity || task in tasks || key in keys) return false
        tasks += task; keys += key
        return true
    }
}

/** Sequential candidate. No LLM planning, tool-output instruction parsing, retries or persisted grants. */
class AgentRunner(private val reads: AgentReadPort,
    private val sessionCurrent: () -> Boolean,
    private val connectionCurrent: (AgentConnectionPin) -> Boolean,
    private val claims: AgentRunClaims = AgentRunClaims(),
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val journal: AgentMetadataJournal = AgentMetadataJournal {},
    private val approval: AgentApprovalPreparation = AgentApprovalPreparation { AgentPreparationStatus.NOT_CONNECTED }) {
    private val latest = AtomicReference<AgentTaskSnapshot?>()
    private val runOwnership = Mutex()
    fun snapshot(): AgentTaskSnapshot? = latest.get()

    /** Best-effort redaction of the current owner's snapshot; retained caller copies are immutable.
     * An active publisher may publish again, so a cancelled owner must also clear in its finally.
     */
    fun clearResultPayloads(taskId: String) {
        while (true) {
            val current = latest.get() ?: return
            if (current.taskId != taskId) return
            if (current.steps.all { it.result == null }) return
            val redacted = current.copy(steps = Collections.unmodifiableList(
                current.steps.map { it.copy(result = null) }))
            if (latest.compareAndSet(current, redacted)) return
            // Retry only while this exact task still owns latest; never clear its replacement.
        }
    }

    suspend fun run(input: AgentPlan): AgentTaskSnapshot {
        // Mutex is non-reentrant and survives coroutine thread switches. Busy calls do not
        // consume claims or replace another task's latest snapshot, even via adapter callbacks.
        check(runOwnership.tryLock()) { "RUNNER_BUSY" }
        try {
            return runOwned(input)
        } finally {
            runOwnership.unlock()
        }
    }

    private suspend fun runOwned(input: AgentPlan): AgentTaskSnapshot {
        val admitted = AgentPlanAdmission.admit(input, wallClock())
        // Validate every connection before first call, including future effect destinations.
        require(admitted.ordered.all { it.step.connection?.let(connectionCurrent) != false }) { "STALE_CONNECTION" }
        require(claims.claim(admitted.plan.taskId, admitted.plan.idempotencyKey)) { "TASK_ALREADY_CLAIMED_OR_CAPACITY" }
        val plan = admitted.plan
        val startedElapsed = elapsedClock()
        val records = linkedMapOf<String, AgentStepRecord>()
        var calls = 0
        var lastWall = wallClock()
        var terminal: AgentStatus? = null
        var active: AdmittedAgentStep? = null
        fun publish(status: AgentStatus): AgentTaskSnapshot {
            val snapshot = AgentTaskSnapshot(plan.taskId, plan.parentTaskId, plan.objective, status,
                plan.createdAtMillis, lastWall, plan.deadlineMillis, plan.idempotencyKey, calls,
                Collections.unmodifiableList(records.values.toList()))
            latest.set(snapshot)
            return snapshot
        }
        fun record(record: AgentStepRecord) {
            records[record.stepId] = record
            publish(AgentStatus.RUNNING)
            journal.record(AgentJournalEvent(plan.taskId, record.stepId, record.status, record.updatedAtMillis, calls, record.errorCode))
        }
        publish(AgentStatus.RUNNING)
        try {
            for (entry in admitted.ordered) {
                currentCoroutineContext().ensureActive()
                val step = entry.step
                val now = wallClock()
                val elapsed = elapsedClock() - startedElapsed
                if (terminal != null || step.dependencies.any { records[it]?.status != AgentStatus.SUCCEEDED }) {
                    record(AgentStepRecord(step.stepId, AgentStatus.SKIPPED, null, lastWall, errorCode = "PREREQUISITE_NOT_SUCCEEDED"))
                    continue
                }
                if (now < lastWall || elapsed < 0 || !sessionCurrent() || step.connection?.let(connectionCurrent) == false) {
                    terminal = AgentStatus.CANCELLED
                    record(AgentStepRecord(step.stepId, terminal!!, null, lastWall, errorCode = "OWNER_CLOCK_OR_CONNECTION_CHANGED"))
                    continue
                }
                lastWall = now
                val timeout = minOf(step.timeoutMillis, step.deadlineMillis - now, plan.deadlineMillis - now,
                    AgentPlanAdmission.MAX_DURATION_MILLIS - elapsed)
                if (timeout <= 0 || calls >= AgentPlanAdmission.MAX_CALLS) {
                    terminal = AgentStatus.BLOCKED
                    record(AgentStepRecord(step.stepId, terminal!!, null, now, errorCode = "DEADLINE_OR_CALL_BUDGET"))
                    continue
                }
                active = entry
                record(AgentStepRecord(step.stepId, AgentStatus.RUNNING, now, now))
                calls++
                val outcome = try {
                    withTimeoutOrNull(timeout) {
                        currentCoroutineContext().ensureActive()
                        if (!sessionCurrent() || step.connection?.let(connectionCurrent) == false)
                            AgentObservation(AgentStatus.CANCELLED, errorCode = "OWNER_CHANGED")
                        else if (entry.capability.effect != AgentEffect.READ_ONLY) {
                            when (approval.prepare(entry)) {
                                AgentPreparationStatus.REQUESTED -> AgentObservation(AgentStatus.WAITING_FOR_APPROVAL)
                                AgentPreparationStatus.NOT_CONNECTED -> AgentObservation(AgentStatus.BLOCKED, errorCode = "APPROVAL_ADAPTER_NOT_CONNECTED")
                                AgentPreparationStatus.BLOCKED -> AgentObservation(AgentStatus.BLOCKED, errorCode = "PREPARATION_BLOCKED")
                                AgentPreparationStatus.OUTCOME_UNKNOWN -> AgentObservation(AgentStatus.OUTCOME_UNKNOWN, errorCode = "PREPARATION_UNKNOWN")
                            }
                        } else executeRead(entry, records)
                    } ?: AgentObservation(if (entry.capability.effect == AgentEffect.READ_ONLY) AgentStatus.FAILED else AgentStatus.OUTCOME_UNKNOWN,
                        errorCode = "TIMEOUT_NO_RETRY")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    AgentObservation(if (entry.capability.effect == AgentEffect.READ_ONLY) AgentStatus.FAILED else AgentStatus.OUTCOME_UNKNOWN,
                        errorCode = "ADAPTER_FAILURE_NO_RETRY")
                }
                val ended = wallClock()
                val stale = ended < lastWall || !sessionCurrent() || step.connection?.let(connectionCurrent) == false
                val late = ended >= step.deadlineMillis || ended >= plan.deadlineMillis ||
                    elapsedClock() - startedElapsed >= AgentPlanAdmission.MAX_DURATION_MILLIS
                lastWall = maxOf(lastWall, ended)
                val accepted = when {
                    stale -> AgentObservation(AgentStatus.CANCELLED, errorCode = "STALE_RESULT_REJECTED")
                    late -> AgentObservation(if (entry.capability.effect == AgentEffect.READ_ONLY) AgentStatus.FAILED else AgentStatus.OUTCOME_UNKNOWN,
                        errorCode = "LATE_RESULT_NO_RETRY")
                    else -> outcome
                }
                record(AgentStepRecord(step.stepId, accepted.status, now, lastWall, accepted.data, accepted.errorCode))
                active = null
                if (accepted.status != AgentStatus.SUCCEEDED) terminal = accepted.status
            }
            return publish(terminal ?: if (records.values.all { it.status == AgentStatus.SUCCEEDED }) AgentStatus.SUCCEEDED else AgentStatus.BLOCKED)
        } catch (cancelled: CancellationException) {
            active?.let { entry ->
                records[entry.step.stepId] = AgentStepRecord(entry.step.stepId,
                    if (entry.capability.effect == AgentEffect.READ_ONLY) AgentStatus.CANCELLED else AgentStatus.OUTCOME_UNKNOWN,
                    records[entry.step.stepId]?.startedAtMillis, lastWall, errorCode = "CANCELLED_NO_RETRY")
            }
            admitted.ordered.filter { it.step.stepId !in records }.forEach {
                records[it.step.stepId] = AgentStepRecord(it.step.stepId, AgentStatus.CANCELLED, null, lastWall, errorCode = "RUN_CANCELLED")
            }
            publish(AgentStatus.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            // Journal/storage callback failure cannot authorize continuation.
            active?.let { entry ->
                records[entry.step.stepId] = AgentStepRecord(entry.step.stepId,
                    if (entry.capability.effect == AgentEffect.READ_ONLY) AgentStatus.FAILED else AgentStatus.OUTCOME_UNKNOWN,
                    records[entry.step.stepId]?.startedAtMillis, lastWall, errorCode = "JOURNAL_FAILURE_NO_CONTINUATION")
            }
            admitted.ordered.filter { it.step.stepId !in records }.forEach {
                records[it.step.stepId] = AgentStepRecord(it.step.stepId, AgentStatus.SKIPPED, null, lastWall,
                    errorCode = "JOURNAL_FAILURE_NO_CONTINUATION")
            }
            return publish(AgentStatus.BLOCKED)
        }
    }

    private suspend fun executeRead(entry: AdmittedAgentStep, prior: Map<String, AgentStepRecord>): AgentObservation {
        val step = entry.step
        val observed = when (entry.capability.name) {
            "get_current_time" -> reads.clock()
            "get_battery_status" -> reads.battery()
            "agent_read_tasks" -> reads.tasks(step.connection!!)
            "agent_local_report" -> {
                val clock = prior.getValue(step.parameters.getValue("clockStep")).result as AgentData.Clock
                val battery = prior.getValue(step.parameters.getValue("batteryStep")).result as AgentData.Battery
                AgentObservation(AgentStatus.SUCCEEDED, AgentData.Report("${clock.exactDisplay}\nBattery: ${battery.percent}%"))
            }
            "agent_prioritize_tasks" -> {
                val tasks = prior.getValue(step.parameters.getValue("sourceStep")).result as AgentData.Tasks
                val rows = tasks.rows.filter { it.businessStatus in tasks.connection.unfinishedValues }
                    .sortedWith(compareByDescending<AgentTaskRow> { it.priority }.thenBy { it.id }).take(3)
                AgentObservation(AgentStatus.SUCCEEDED, AgentData.Priorities(tasks.connection, Collections.unmodifiableList(rows)))
            }
            "agent_study_draft" -> {
                val selected = prior.getValue(step.parameters.getValue("sourceStep")).result as AgentData.Priorities
                val minutes = step.parameters.getValue("minutes").toInt()
                val text = "Study draft — $minutes minutes\n" + selected.rows.mapIndexed { i, row -> "${i + 1}. ${row.title}" }.joinToString("\n")
                AgentObservation(AgentStatus.SUCCEEDED, AgentData.Draft(text, selected.connection,
                    Collections.unmodifiableList(selected.rows.map { it.id })))
            }
            else -> return AgentObservation(AgentStatus.BLOCKED, errorCode = "UNSUPPORTED_READ")
        }
        if (observed.status !in setOf(AgentStatus.SUCCEEDED, AgentStatus.REPORTED, AgentStatus.FAILED, AgentStatus.BLOCKED))
            return AgentObservation(AgentStatus.FAILED, errorCode = "INVALID_READ_RESULT_STATUS")
        if (observed.status != AgentStatus.SUCCEEDED) return observed.copy(data = null, errorCode = "READ_NOT_PROVEN_SUCCEEDED")
        val value = observed.data
        val valid = when (entry.capability.name) {
            "get_current_time" -> value is AgentData.Clock && value.exactDisplay.isNotBlank() && value.exactDisplay.length <= 200
            "get_battery_status" -> value is AgentData.Battery && value.percent in 0..100
            "agent_read_tasks" -> value is AgentData.Tasks && value.connection == step.connection && value.complete &&
                value.rows.size <= 100 && value.rows.map { it.id }.toSet().size == value.rows.size &&
                value.rows.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.title.isNotBlank() && it.title.length <= 2_000 &&
                    it.businessStatus.length <= 80 && it.syncStatus.length <= 80 && it.priority in 0..100 }
            "agent_local_report" -> value is AgentData.Report
            "agent_prioritize_tasks" -> value is AgentData.Priorities
            "agent_study_draft" -> value is AgentData.Draft && value.exactText.length <= 8_000
            else -> false
        }
        if (!valid) return AgentObservation(AgentStatus.FAILED, errorCode = "INVALID_PARTIAL_OR_CROSS_CONNECTION_RESULT")
        return if (value is AgentData.Tasks) observed.copy(data = value.copy(connection = step.connection!!,
            rows = Collections.unmodifiableList(value.rows.toList())), errorCode = null) else observed.copy(errorCode = null)
    }
}
