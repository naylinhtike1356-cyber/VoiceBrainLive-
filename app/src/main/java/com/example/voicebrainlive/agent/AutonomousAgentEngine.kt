package com.example.voicebrainlive.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Autonomous multi-step AI Agent orchestrator.
 * Executes bounded Plan -> Observe -> Act -> Verify workflows across VoiceBrainLive's
 * ToolRegistry & Accessibility Screen Co-Pilot while publishing live progress to [AgentProgressBus].
 */
class AutonomousAgentEngine(
    private val executeTool: suspend (commandType: String, target: String?, value: String?) -> String?,
    private val publishProgress: Boolean = true,
    private val stepDelayMillis: Long = 350L
) {
    companion object {
        const val MAX_STEPS = 8
    }

    data class PlannedStep(
        val title: String,
        val commandType: String,
        val target: String? = null,
        val value: String? = null,
        val optional: Boolean = false
    )

    data class AgentOutcome(
        val success: Boolean,
        val summary: String,
        val stepOutputs: List<String>
    )

    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        if (publishProgress) {
            AgentProgressBus.finish(AgentProgressBus.AgentPhase.CANCELLED, "Agent task cancelled by user.")
        }
    }

    suspend fun runGoal(goal: String, explicitStepsSpec: String? = null): AgentOutcome {
        cancelled.set(false)
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank() && explicitStepsSpec.isNullOrBlank()) {
            return AgentOutcome(false, "လုပ်ဆောင်ရမည့် AI Agent အလုပ်ကို ပြောပေးပါ။", emptyList())
        }

        if (publishProgress) {
            AgentProgressBus.beginPlanning(cleanGoal.ifBlank { "Autonomous Task" })
        }

        val lower = cleanGoal.lowercase()
        return when {
            !explicitStepsSpec.isNullOrBlank() -> {
                val parsed = parseExplicitSteps(explicitStepsSpec)
                executePlan(cleanGoal.ifBlank { "Custom Agent Plan" }, parsed)
            }
            isMorningBriefGoal(lower) -> runMorningBrief(cleanGoal)
            isResearchToNotionGoal(lower) -> runResearchToNotion(cleanGoal)
            isScreenCopilotGoal(lower) -> runScreenCopilot(cleanGoal)
            else -> {
                val inferred = inferMultiStepPlan(cleanGoal)
                if (inferred.isNotEmpty()) {
                    executePlan(cleanGoal, inferred)
                } else {
                    // Fallback: single-step screen observation if asking about screen, or web search
                    val fallbackSteps = listOf(
                        PlannedStep("Checking current context", "read_screen_contents", optional = true),
                        PlannedStep("Searching web info", "web_search", target = cleanGoal)
                    )
                    executePlan(cleanGoal, fallbackSteps)
                }
            }
        }
    }

    suspend fun runMorningBrief(goalLabel: String = "Morning Executive Brief"): AgentOutcome {
        val steps = listOf(
            PlannedStep("Checking Weather", "get_weather", value = "today", optional = true),
            PlannedStep("Reading Today's Calendar", "get_calendar_events", value = "today", optional = true),
            PlannedStep("Checking Pending Reminders", "list_reminders", optional = true),
            PlannedStep("Reading Health Summary", "get_health_summary", optional = true)
        )
        return executePlan(goalLabel, steps, aggregateBrief = true)
    }

    suspend fun runResearchToNotion(topicQuery: String): AgentOutcome {
        val cleanedTopic = extractTopic(topicQuery)
        if (publishProgress) {
            AgentProgressBus.startSteps(
                topicQuery,
                listOf(
                    AgentProgressBus.AgentStepProgress(0, "Searching: $cleanedTopic", "search_news"),
                    AgentProgressBus.AgentStepProgress(1, "Saving summary to Notion / Notes", "send_to_notion")
                )
            )
        }
        val outputs = mutableListOf<String>()
        try {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return cancelledOutcome(outputs)

            if (publishProgress) AgentProgressBus.updateStep(0, AgentProgressBus.StepStatus.RUNNING)
            val searchTool = if (topicQuery.contains("သတင်း") || topicQuery.contains("news", true)) "search_news" else "web_search"
            val searchResult = executeTool(searchTool, cleanedTopic, null).orEmpty()
            if (searchResult.isBlank() || isBlockedOrFailed(searchResult)) {
                if (publishProgress) {
                    AgentProgressBus.updateStep(0, AgentProgressBus.StepStatus.FAILED, searchResult)
                    AgentProgressBus.finish(AgentProgressBus.AgentPhase.FAILED, searchResult.ifBlank { "Search failed" })
                }
                return AgentOutcome(false, searchResult.ifBlank { "အချက်အလက် ရှာမတွေ့ပါ။" }, outputs)
            }
            outputs += searchResult
            if (publishProgress) AgentProgressBus.updateStep(0, AgentProgressBus.StepStatus.DONE, searchResult.take(120))

            if (stepDelayMillis > 0) delay(stepDelayMillis)
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return cancelledOutcome(outputs)

            if (publishProgress) AgentProgressBus.updateStep(1, AgentProgressBus.StepStatus.RUNNING)
            val title = "AI Research: ${cleanedTopic.take(40)}"
            val notionResult = executeTool("send_to_notion", title, searchResult).orEmpty()
            val savedMsg = if (notionResult.isNotBlank() && !isBlockedOrFailed(notionResult) && !notionResult.contains("Token", true)) {
                notionResult
            } else {
                // Graceful fallback to local Notes if Notion isn't connected
                executeTool("save_note", title, searchResult).orEmpty()
            }
            outputs += savedMsg
            if (publishProgress) {
                AgentProgressBus.updateStep(1, AgentProgressBus.StepStatus.DONE, savedMsg.take(120))
                val summary = "[$cleanedTopic] အချက်အလက်များကို ရှာဖွေပြီး သိမ်းဆည်းလိုက်ပါပြီ။\n$savedMsg"
                AgentProgressBus.finish(AgentProgressBus.AgentPhase.COMPLETED, summary)
            }
            return AgentOutcome(true, "[$cleanedTopic] ရှာဖွေပြီး သိမ်းဆည်းလိုက်ပါပြီ။\n${searchResult.take(240)}", outputs)
        } catch (ce: CancellationException) {
            if (publishProgress) AgentProgressBus.finish(AgentProgressBus.AgentPhase.CANCELLED, "Cancelled")
            throw ce
        }
    }

    suspend fun runScreenCopilot(goal: String): AgentOutcome {
        val steps = mutableListOf(
            PlannedStep("Inspecting active screen", "read_screen_contents")
        )
        val lower = goal.lowercase()
        when {
            lower.contains("scroll down") || lower.contains("အောက်") ->
                steps += PlannedStep("Scrolling down", "scroll_screen", target = "down")
            lower.contains("scroll up") || lower.contains("အပေါ်") ->
                steps += PlannedStep("Scrolling up", "scroll_screen", target = "up")
            lower.contains("back") || lower.contains("နောက်ဆုတ်") ->
                steps += PlannedStep("Navigating back", "perform_system_action", target = "back")
            lower.contains("home") || lower.contains("မူလ") ->
                steps += PlannedStep("Going Home", "perform_system_action", target = "home")
            lower.contains("notion") || lower.contains("သိမ်း") || lower.contains("save") -> {
                if (publishProgress) {
                    AgentProgressBus.startSteps(
                        goal,
                        listOf(
                            AgentProgressBus.AgentStepProgress(0, "Reading screen contents", "read_screen_contents"),
                            AgentProgressBus.AgentStepProgress(1, "Saving screen summary", "send_to_notion")
                        )
                    )
                }
                if (publishProgress) AgentProgressBus.updateStep(0, AgentProgressBus.StepStatus.RUNNING)
                val screenText = executeTool("read_screen_contents", null, null).orEmpty()
                if (screenText.isBlank() || screenText.contains("Accessibility", true)) {
                    if (publishProgress) {
                        AgentProgressBus.updateStep(0, AgentProgressBus.StepStatus.BLOCKED, screenText)
                        AgentProgressBus.finish(AgentProgressBus.AgentPhase.FAILED, screenText)
                    }
                    return AgentOutcome(false, screenText.ifBlank { "Accessibility Service ဖွင့်ရန် လိုအပ်ပါသည်။" }, listOf(screenText))
                }
                if (publishProgress) AgentProgressBus.updateStep(0, AgentProgressBus.StepStatus.DONE, "Screen captured")
                if (publishProgress) AgentProgressBus.updateStep(1, AgentProgressBus.StepStatus.RUNNING)
                val title = "Screen Capture Note"
                val notionRes = executeTool("send_to_notion", title, screenText.take(1500)).orEmpty()
                val saved = if (notionRes.isNotBlank() && !isBlockedOrFailed(notionRes) && !notionRes.contains("Token", true)) {
                    notionRes
                } else {
                    executeTool("save_note", title, screenText.take(1500)).orEmpty()
                }
                if (publishProgress) {
                    AgentProgressBus.updateStep(1, AgentProgressBus.StepStatus.DONE, saved.take(120))
                    AgentProgressBus.finish(AgentProgressBus.AgentPhase.COMPLETED, saved)
                }
                return AgentOutcome(true, "စခရင်ပေါ်ရှိ အချက်အလက်များကို သိမ်းဆည်းပြီးပါပြီ။\n$saved", listOf(screenText, saved))
            }
        }
        return executePlan(goal, steps)
    }

    suspend fun executePlan(
        goal: String,
        steps: List<PlannedStep>,
        aggregateBrief: Boolean = false
    ): AgentOutcome {
        val bounded = steps.take(MAX_STEPS)
        if (bounded.isEmpty()) {
            if (publishProgress) AgentProgressBus.finish(AgentProgressBus.AgentPhase.FAILED, "No steps planned")
            return AgentOutcome(false, "လုပ်ဆောင်ရန် အဆင့်များ မရှိပါ။", emptyList())
        }

        if (publishProgress) {
            AgentProgressBus.startSteps(
                goal,
                bounded.mapIndexed { idx, s ->
                    AgentProgressBus.AgentStepProgress(idx, s.title, s.commandType)
                }
            )
        }

        val outputs = mutableListOf<String>()
        val summaryLines = mutableListOf<String>()
        var allOk = true

        try {
            for ((idx, step) in bounded.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) return cancelledOutcome(outputs)

                if (publishProgress) {
                    AgentProgressBus.updateStep(idx, AgentProgressBus.StepStatus.RUNNING)
                }

                val raw = executeTool(step.commandType, step.target, step.value).orEmpty()
                val blocked = isApprovalOrBlocked(raw)
                val failed = !blocked && isBlockedOrFailed(raw)

                if (blocked) {
                    if (publishProgress) {
                        AgentProgressBus.updateStep(idx, AgentProgressBus.StepStatus.BLOCKED, raw.take(140))
                        AgentProgressBus.finish(AgentProgressBus.AgentPhase.FAILED, raw)
                    }
                    outputs += raw
                    return AgentOutcome(false, raw, outputs)
                }

                if (failed && !step.optional) {
                    if (publishProgress) {
                        AgentProgressBus.updateStep(idx, AgentProgressBus.StepStatus.FAILED, raw.take(140))
                        AgentProgressBus.finish(AgentProgressBus.AgentPhase.FAILED, raw)
                    }
                    outputs += raw
                    return AgentOutcome(false, raw.ifBlank { "${step.title} မအောင်မြင်ပါ။" }, outputs)
                }

                if (raw.isNotBlank()) {
                    outputs += raw
                    summaryLines += "• ${step.title}: ${raw.take(220)}"
                } else {
                    allOk = allOk && step.optional
                }

                if (publishProgress) {
                    AgentProgressBus.updateStep(
                        idx,
                        if (failed) AgentProgressBus.StepStatus.FAILED else AgentProgressBus.StepStatus.DONE,
                        raw.take(120)
                    )
                }

                if (stepDelayMillis > 0 && idx < bounded.lastIndex) {
                    delay(stepDelayMillis)
                }
            }

            val finalSummary = if (aggregateBrief) {
                buildExecutiveSummary(summaryLines)
            } else {
                summaryLines.joinToString("\n").ifBlank { "AI Agent လုပ်ငန်းစဉ် ပြီးဆုံးပါပြီ။" }
            }

            if (publishProgress) {
                AgentProgressBus.finish(AgentProgressBus.AgentPhase.COMPLETED, finalSummary)
            }
            return AgentOutcome(allOk || outputs.isNotEmpty(), finalSummary, outputs)
        } catch (ce: CancellationException) {
            if (publishProgress) {
                AgentProgressBus.finish(AgentProgressBus.AgentPhase.CANCELLED, "Cancelled")
            }
            throw ce
        }
    }

    internal fun parseExplicitSteps(spec: String): List<PlannedStep> {
        return spec.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_STEPS)
            .map { entry ->
                val parts = entry.split(":", limit = 3).map { it.trim() }
                val cmd = parts[0]
                val target = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                val value = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                PlannedStep(
                    title = cmd.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    commandType = cmd,
                    target = target,
                    value = value
                )
            }
    }

    internal fun inferMultiStepPlan(goal: String): List<PlannedStep> {
        val lower = goal.lowercase()
        val steps = mutableListOf<PlannedStep>()
        // Example: "YouTube ဖွင့်ပြီး Myanmar lo-fi ရှာ" or "open youtube and search lofi"
        val youtubeMatch = Regex("""(?:youtube|ယူကျု)\s*(?:ဖွင့်(?:ပေး)?(?:ပြီး|ပီး)?|open)?\s*(?:and\s*)?(?:search|play|ရှာ)?\s*(.+)""", RegexOption.IGNORE_CASE).find(goal)
        if (youtubeMatch != null && (lower.contains("youtube") || lower.contains("ယူကျု"))) {
            val query = youtubeMatch.groupValues[1].trim()
            if (query.isNotBlank() && query.length > 2) {
                steps += PlannedStep("Opening YouTube & searching", "play_youtube", target = query)
                return steps
            }
        }
        return steps
    }

    private fun isMorningBriefGoal(lower: String): Boolean =
        lower.contains("morning brief") ||
            lower.contains("daily brief") ||
            lower.contains("brief me") ||
            lower.contains("executive brief") ||
            lower.contains("မနက်ခင်း အစီအစဉ်") ||
            lower.contains("ဒီနေ့ အစီအစဉ်") ||
            lower.contains("အစီရင်ခံ")

    private fun isResearchToNotionGoal(lower: String): Boolean =
        (lower.contains("notion") || lower.contains("မှတ်စု") || lower.contains("save")) &&
            (lower.contains("search") || lower.contains("news") || lower.contains("research") ||
                lower.contains("ရှာ") || lower.contains("သတင်း"))

    private fun isScreenCopilotGoal(lower: String): Boolean =
        lower.contains("screen") || lower.contains("စခရင်") || lower.contains("ဖန်သားပြင်")

    private fun extractTopic(goal: String): String {
        return goal
            .replace(Regex("""(?i)\b(search|news|research|and|save|to|notion|please|about|for)\b"""), " ")
            .replace(Regex("""(သတင်း|ရှာပြီး|ရှာပီး|ရှာပေး|မှတ်စု|သိမ်းပေး|သိမ်း|ထဲ|ကို|အကြောင်း)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "today world technology news" }
    }

    private fun buildExecutiveSummary(lines: List<String>): String {
        if (lines.isEmpty()) return "လက်ရှိ အစီအစဉ် အချက်အလက် မရှိသေးပါ။"
        return "📋 J.A.R.V.I.S. Executive Brief:\n" + lines.joinToString("\n")
    }

    private fun isApprovalOrBlocked(output: String): Boolean =
        output.startsWith("NEEDS_APPROVAL", ignoreCase = true) ||
            output.startsWith("BLOCKED", ignoreCase = true)

    private fun isBlockedOrFailed(output: String): Boolean =
        output.startsWith("FAILED", ignoreCase = true) ||
            output.startsWith("Error", ignoreCase = true)

    private fun cancelledOutcome(outputs: List<String>) =
        AgentOutcome(false, "AI Agent လုပ်ငန်းစဉ်ကို ရပ်တန့်လိုက်ပါပြီ။", outputs)
}
