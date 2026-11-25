package com.example.voicebrainlive.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live telemetry bus broadcasting autonomous AI Agent step execution to HUDs and Dashboards.
 */
object AgentProgressBus {

    enum class StepStatus { PENDING, RUNNING, DONE, BLOCKED, FAILED }

    enum class AgentPhase { IDLE, PLANNING, EXECUTING, COMPLETED, FAILED, CANCELLED }

    data class AgentStepProgress(
        val index: Int,
        val title: String,
        val tool: String,
        val status: StepStatus = StepStatus.PENDING,
        val detail: String = ""
    )

    data class AgentExecutionState(
        val phase: AgentPhase = AgentPhase.IDLE,
        val goal: String = "",
        val steps: List<AgentStepProgress> = emptyList(),
        val summary: String = "",
        val updatedAtMillis: Long = System.currentTimeMillis()
    ) {
        val isRunning: Boolean
            get() = phase == AgentPhase.PLANNING || phase == AgentPhase.EXECUTING
    }

    private val _state = MutableStateFlow(AgentExecutionState())
    val state: StateFlow<AgentExecutionState> = _state.asStateFlow()

    fun beginPlanning(goal: String) {
        _state.value = AgentExecutionState(
            phase = AgentPhase.PLANNING,
            goal = goal,
            steps = emptyList(),
            summary = "Analyzing goal...",
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun startSteps(goal: String, steps: List<AgentStepProgress>) {
        _state.value = AgentExecutionState(
            phase = AgentPhase.EXECUTING,
            goal = goal,
            steps = steps,
            summary = "",
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun updateStep(index: Int, status: StepStatus, detail: String = "") {
        val current = _state.value
        val updated = current.steps.map { step ->
            if (step.index == index) step.copy(status = status, detail = detail) else step
        }
        _state.value = current.copy(
            steps = updated,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun finish(phase: AgentPhase, summary: String) {
        val current = _state.value
        _state.value = current.copy(
            phase = phase,
            summary = summary,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun reset() {
        _state.value = AgentExecutionState()
    }
}
