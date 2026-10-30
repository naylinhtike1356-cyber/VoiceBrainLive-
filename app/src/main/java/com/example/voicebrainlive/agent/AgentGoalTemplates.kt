package com.example.voicebrainlive.agent

import com.example.voicebrainlive.commands.CommandPlan

/** Typed, explicitly selected templates; never claims general natural-language understanding. */
sealed interface AgentGoal {
    object LocalStatus : AgentGoal
    data class StudyDraft(val connection: AgentConnectionPin, val minutes: Int) : AgentGoal
}
object AgentGoalTemplates {
    fun interpret(goal: AgentGoal, taskId: String, idempotencyKey: String, now: Long): AgentPlan {
        require(now >= 0 && now <= Long.MAX_VALUE - AgentPlanAdmission.MAX_DURATION_MILLIS)
        val deadline = now + AgentPlanAdmission.MAX_DURATION_MILLIS
        fun step(id: String, tool: String, dependencies: Set<String> = emptySet(),
            parameters: Map<String, String> = emptyMap(), connection: AgentConnectionPin? = null) =
            AgentStep(id, "Run $tool", CommandPlan(tool, null, null, source = "explicit_goal_template"),
                dependencies, parameters, connection, deadlineMillis = deadline, idempotencyKey = "${taskId}_$id")
        return when (goal) {
            AgentGoal.LocalStatus -> AgentPlan(taskId, objective = "Prepare local status report", createdAtMillis = now,
                deadlineMillis = deadline, idempotencyKey = idempotencyKey, steps = listOf(
                    step("clock", "get_current_time"), step("battery", "get_battery_status"),
                    step("report", "agent_local_report", setOf("clock", "battery"), mapOf("clockStep" to "clock", "batteryStep" to "battery"))))
            is AgentGoal.StudyDraft -> {
                require(goal.minutes in 5..180)
                AgentPlan(taskId, objective = "Read explicitly mapped tasks and prepare local study draft", createdAtMillis = now,
                    deadlineMillis = deadline, idempotencyKey = idempotencyKey, steps = listOf(
                        step("tasks", "agent_read_tasks", connection = goal.connection),
                        step("priorities", "agent_prioritize_tasks", setOf("tasks"), mapOf("sourceStep" to "tasks")),
                        step("draft", "agent_study_draft", setOf("priorities"), mapOf("sourceStep" to "priorities", "minutes" to goal.minutes.toString()))))
            }
        }
    }
}
