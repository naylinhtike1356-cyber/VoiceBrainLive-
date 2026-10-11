package com.example.voicebrainlive

import com.example.voicebrainlive.commands.CommandSafetyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSafetyPolicyTest {
    @Test
    fun externalSideEffectsRequireConfirmation() {
        assertTrue(CommandSafetyPolicy.requiresConfirmation("make_call"))
        assertTrue(CommandSafetyPolicy.requiresConfirmation("send_sms"))
        assertTrue(CommandSafetyPolicy.requiresConfirmation("delete_note"))
        assertTrue(CommandSafetyPolicy.requiresConfirmation("create_meeting"))
    }

    @Test
    fun readOnlyCommandsDoNotRequireConfirmation() {
        assertFalse(CommandSafetyPolicy.requiresConfirmation("get_weather"))
        assertFalse(CommandSafetyPolicy.requiresConfirmation("get_battery_status"))
        assertFalse(CommandSafetyPolicy.requiresConfirmation("open_app"))
    }

    @Test
    fun commandMatchingIsTrimmedAndCaseInsensitive() {
        assertTrue(CommandSafetyPolicy.requiresConfirmation("  SEND_WHATSAPP "))
    }
}
