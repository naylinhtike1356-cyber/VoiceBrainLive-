package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.*

/** Deliberately finite, offline interpreter. A future model can implement the same typed contract.
 * Unknown requests return unsupported; no guessed tool names, destinations or executable text.
 */
class LocalBrainModel : BrainGoalModel {
    override suspend fun propose(request: BrainGoalRequest, confirmedContext: String): BrainInterpretation {
        val text = request.text.lowercase()
        fun step(id: String, tool: String, expected: BrainOutput, dependencies: Set<String> = emptySet(),
            arguments: Map<String, String> = emptyMap(), target: String? = null, value: String? = null) =
            BrainProposedStep(id, tool, dependencies, arguments, target, value, expected)
        val status = listOf(step("clock", "get_current_time", BrainOutput.CLOCK),
            step("battery", "get_battery_status", BrainOutput.BATTERY),
            step("report", "agent_local_report", BrainOutput.REPORT, setOf("clock", "battery"),
                mapOf("clockStep" to "clock", "batteryStep" to "battery")))
        return when {
            text == "local status" || text == "စက်အခြေအနေစစ်ပေး" || isStatusIntent(text) -> BrainInterpretation.Candidate(status)
            text == "draft sample sms" || isSmsDraftIntent(text) -> BrainInterpretation.Candidate(status + step("message", "send_sms",
                BrainOutput.APPROVAL_PROPOSAL, setOf("report"), target = "+12025550123", value = "SYNTHETIC status draft; do not send."))
            text == "study tonight" || text == "ဒီညအတွက် စာလေ့လာဖို့ အရေးကြီးဆုံးအလုပ်သုံးခု စီစဉ်ပေး" || isStudyIntent(text) -> {
                // Context is data, never instructions. Only this explicit preference adjusts this bounded draft.
                val minutes = if (confirmedContext.contains("short sessions", ignoreCase = true)) "20" else "30"
                BrainInterpretation.Candidate(listOf(
                    step("tasks", "agent_read_tasks", BrainOutput.TASKS),
                    step("priorities", "agent_prioritize_tasks", BrainOutput.PRIORITIES, setOf("tasks"), mapOf("sourceStep" to "tasks")),
                    step("draft", "agent_study_draft", BrainOutput.DRAFT, setOf("priorities"), mapOf("sourceStep" to "priorities", "minutes" to minutes)),
                    step("reminder", "set_reminder", BrainOutput.APPROVAL_PROPOSAL, setOf("draft"),
                        target = "SYNTHETIC tomorrow study reminder", value = "Review the study draft tomorrow; exact time requires user review."),
                    step("after_review", "get_current_time", BrainOutput.CLOCK, setOf("reminder"))))
            }
            else -> BrainInterpretation.Unsupported()
        }
    }

    private fun isStatusIntent(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("စက်အခြေအနေ") || t.contains("ဖုန်းအခြေအနေ") ||
                (t.contains("ဘက်ထရီ") && (t.contains("စစ်") || t.contains("ဘယ်လောက်"))) ||
                t.contains("device status") || t.contains("phone status") || t.contains("check battery"))
    }

    private fun isSmsDraftIntent(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("sms") && (t.contains("draft") || t.contains("မူကြမ်း"))) ||
               (t.contains("စာတို") && t.contains("မူကြမ်း"))
    }

    private fun isStudyIntent(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("စာလေ့လာ") || t.contains("စာကျက်") || t.contains("စာလုပ်") ||
                t.contains("study plan") || (t.contains("notion") && (t.contains("အလုပ်") || t.contains("tasks"))))
    }
}

/** Explicitly fake source; cannot access SmartNote, Notion, contacts, hardware or providers. */
object BrainSamples {
    const val BURMESE_GOAL = "ဒီညအတွက် စာလေ့လာဖို့ အရေးကြီးဆုံးအလုပ်သုံးခု စီစဉ်ပေး"
    val connection = AgentConnectionPin("sample", 1, "SYNTHETIC", "tasks", "database", 1, "business_status", setOf("To do", "Doing"))
    fun reads(): AgentReadPort = object : AgentReadPort {
        override suspend fun clock() = AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("SYNTHETIC 19:00"))
        override suspend fun battery() = AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery(80))
        override suspend fun tasks(connection: AgentConnectionPin): AgentObservation {
            if (connection != BrainSamples.connection) return AgentObservation(AgentStatus.BLOCKED)
            return AgentObservation(AgentStatus.SUCCEEDED, AgentData.Tasks(connection, listOf(
                AgentTaskRow("korean", "한국어 복습", "Doing", "Synced", 95),
                AgentTaskRow("math", "သင်္ချာလေ့ကျင့်ရန်", "To do", "Pending", 90),
                AgentTaskRow("english", "English reading", "To do", "Failed", 85),
                AgentTaskRow("done", "Completed lesson", "Done", "Pending", 100)), true))
        }
    }
}
