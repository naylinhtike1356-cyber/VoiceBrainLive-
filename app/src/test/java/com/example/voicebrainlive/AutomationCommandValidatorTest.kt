package com.example.voicebrainlive

import com.example.voicebrainlive.commands.AutomationCommandValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCommandValidatorTest {
    @Test
    fun directCallAndMessageExceptionsDoNotAuthorizeAutomaticMacros() {
        listOf("make_call", "dial", "call_quick_contact", "send_sms", "send_messenger", "reply_messenger", "whatsapp").forEach { command ->
            assertFalse(command, AutomationCommandValidator.validateSequence("$command:Mom:hello").valid)
        }
    }

    @Test
    fun reviewedMessageKeepsSemicolonsAsPayload() {
        val sequence = "send_sms:Mom:hello; do not split this"
        org.junit.Assert.assertEquals(listOf(sequence), AutomationCommandValidator.extractSteps(sequence))
        assertTrue(AutomationCommandValidator.validateSequence(sequence, allowApprovalRequired = true).valid)
        assertFalse(AutomationCommandValidator.validateSequence(sequence).valid)
    }

    @Test
    fun validSequence_isAccepted() {
        assertTrue(AutomationCommandValidator.validateSequence("set_focus_mode:30;set_ringer_silent").valid)
    }

    @Test
    fun confirmationRequiredCommand_isRejectedForAutomaticMacro() {
        val result = AutomationCommandValidator.validateSequence("make_call:Mom")
        assertFalse(result.valid)
    }

    @Test
    fun oversizedSequence_isRejected() {
        val result = AutomationCommandValidator.validateSequence("set_focus_mode:" + "x".repeat(1_100))
        assertFalse(result.valid)
    }

    @Test
    fun tooManySteps_areRejected() {
        val sequence = List(13) { "set_ringer_silent" }.joinToString(";")
        assertFalse(AutomationCommandValidator.validateSequence(sequence).valid)
    }
}
