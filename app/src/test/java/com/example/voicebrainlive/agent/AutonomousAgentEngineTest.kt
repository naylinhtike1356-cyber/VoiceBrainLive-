package com.example.voicebrainlive.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutonomousAgentEngineTest {

    @Before
    fun setUp() {
        AgentProgressBus.reset()
    }

    @Test
    fun morningBriefAggregatesToolsAndPublishesCompletedState() = runBlocking {
        val called = mutableListOf<String>()
        val engine = AutonomousAgentEngine(
            executeTool = { cmd, _, _ ->
                called += cmd
                when (cmd) {
                    "get_weather" -> "28°C Sunny"
                    "get_calendar_events" -> "10:00 Design Review"
                    "list_reminders" -> "Buy coffee"
                    "get_health_summary" -> "6,200 steps"
                    else -> ""
                }
            },
            publishProgress = true,
            stepDelayMillis = 0L
        )

        val outcome = engine.runMorningBrief()
        assertTrue(outcome.success)
        assertEquals(listOf("get_weather", "get_calendar_events", "list_reminders", "get_health_summary"), called)
        assertTrue(outcome.summary.contains("28°C Sunny"))
        assertTrue(outcome.summary.contains("Design Review"))
        assertEquals(AgentProgressBus.AgentPhase.COMPLETED, AgentProgressBus.state.value.phase)
        assertTrue(AgentProgressBus.state.value.steps.all { it.status == AgentProgressBus.StepStatus.DONE })
    }

    @Test
    fun researchToNotionFallsBackToLocalNoteWhenNotionTokenMissing() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = AutonomousAgentEngine(
            executeTool = { cmd, target, _ ->
                calls += "$cmd:$target"
                when (cmd) {
                    "search_news" -> "AI agents advance rapidly in 2026."
                    "send_to_notion" -> "Notion Token မထည့်ရသေးပါ"
                    "save_note" -> "Saved locally"
                    else -> ""
                }
            },
            publishProgress = true,
            stepDelayMillis = 0L
        )

        val outcome = engine.runResearchToNotion("AI technology news")
        assertTrue(outcome.success)
        assertTrue(calls.any { it.startsWith("search_news:") })
        assertTrue(calls.any { it.startsWith("send_to_notion:") })
        assertTrue(calls.any { it.startsWith("save_note:") })
    }

    @Test
    fun blockedApprovalStopsAutonomousContinuation() = runBlocking {
        val executed = mutableListOf<String>()
        val engine = AutonomousAgentEngine(
            executeTool = { cmd, _, _ ->
                executed += cmd
                if (cmd == "send_sms") "NEEDS_APPROVAL: approval required" else "ok"
            },
            publishProgress = true,
            stepDelayMillis = 0L
        )

        val outcome = engine.runGoal("Custom", "get_current_time ; send_sms:Mom:Hi ; turn_on_flashlight")
        assertFalse(outcome.success)
        assertEquals(listOf("get_current_time", "send_sms"), executed)
        assertEquals(AgentProgressBus.AgentPhase.FAILED, AgentProgressBus.state.value.phase)
    }

    @Test
    fun explicitStepsBoundedToMaxSteps() = runBlocking {
        val executed = mutableListOf<String>()
        val engine = AutonomousAgentEngine(
            executeTool = { cmd, _, _ ->
                executed += cmd
                "done"
            },
            publishProgress = false,
            stepDelayMillis = 0L
        )

        val tenSteps = (1..10).joinToString(" ; ") { "get_current_time" }
        val outcome = engine.runGoal("Ten step cap", tenSteps)
        assertTrue(outcome.success)
        assertEquals(AutonomousAgentEngine.MAX_STEPS, executed.size)
    }

    @Test
    fun cancelHaltsBeforeNextStep() = runBlocking {
        val executed = mutableListOf<String>()
        lateinit var engine: AutonomousAgentEngine
        engine = AutonomousAgentEngine(
            executeTool = { cmd, _, _ ->
                executed += cmd
                engine.cancel()
                "ok"
            },
            publishProgress = true,
            stepDelayMillis = 0L
        )

        val outcome = engine.runGoal("Cancel mid-run", "get_current_time ; get_battery_status")
        assertFalse(outcome.success)
        assertEquals(listOf("get_current_time"), executed)
        assertEquals(AgentProgressBus.AgentPhase.CANCELLED, AgentProgressBus.state.value.phase)
    }
}
