package com.example.voicebrainlive

import com.example.voicebrainlive.memory.SmartContinuationEngine
import org.junit.Assert.*
import org.junit.Test

class LearnedActionAuthorizationTest {
    @Test fun highConfidenceLearnedAliases_areNotActionSpecificApproval() {
        val analysis = SmartContinuationEngine.ContinuationResult(
            "previously confirmed phrase", "get_time", "Alice", 100,
            SmartContinuationEngine.ContinuationSource.INTENT_PATTERN, "suggestion")
        assertTrue(analysis.shouldAutoExecute)
        for (command in listOf("call_quick_contact", "dial", "reply_messenger", "compose_email", "notion_delete", "forget_fact", "input_text", "unclassified_action")) {
            assertFalse(command, analysis.copy(suggestedCommand = command).shouldAutoExecute)
        }
    }
}
