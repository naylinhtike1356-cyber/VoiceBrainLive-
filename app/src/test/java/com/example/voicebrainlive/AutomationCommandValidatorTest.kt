package com.example.voicebrainlive

import com.example.voicebrainlive.commands.AutomationCommandValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCommandValidatorTest {
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
