package com.example.voicebrainlive

import com.example.voicebrainlive.usecases.AssistantAutomationPayload
import com.example.voicebrainlive.usecases.AutomationNarrationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationNarrationHelperTest {

    @Test
    fun buildPrompt_isEmpty_forScheduledAgentTypes() {
        val payload = AssistantAutomationPayload(
            type = "weather",
            message = "",
            musicQuery = "",
            announceText = "test",
            isPreReminder = false,
            alreadySpoken = false
        )

        assertEquals("", AutomationNarrationHelper.buildPrompt(payload))
    }

    @Test
    fun usesDirectExecution_forMusicAndCommand() {
        assertTrue(AutomationNarrationHelper.usesDirectExecution("music"))
        assertTrue(AutomationNarrationHelper.usesDirectExecution("command"))
    }
}
