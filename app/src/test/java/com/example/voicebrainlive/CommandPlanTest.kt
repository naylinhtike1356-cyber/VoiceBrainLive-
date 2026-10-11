package com.example.voicebrainlive

import com.example.voicebrainlive.commands.ActionDispatcher
import com.example.voicebrainlive.commands.CommandPlan
import com.example.voicebrainlive.commands.CommandPlanValidator
import com.example.voicebrainlive.commands.normalizeUserText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandPlanTest {
    @Test
    fun normalizeUserText_convertsBurmeseDigitsAndWhitespace() {
        assertEquals("မနက် 7 နာရီ", normalizeUserText("  မနက်   ၇   နာရီ၊  "))
    }

    @Test
    fun missingCallTarget_requiresClarification() {
        val resolved = ActionDispatcher.resolve(JSONObject().put("command_type", "make_call"))
        val result = CommandPlanValidator.validate(CommandPlan.from(resolved))

        assertFalse(result.valid)
        assertEquals("ဘယ်သူ့ကို ဖုန်းခေါ်ပေးရမလဲ?", result.clarification)
    }

    @Test
    fun resolvedCallTarget_isValidButStillRequiresConfirmation() {
        val resolved = ActionDispatcher.resolve(
            JSONObject().put("command_type", "make_call").put("target", "မမ")
        )
        val plan = CommandPlan.from(resolved)

        assertTrue(CommandPlanValidator.validate(plan).valid)
        assertTrue(plan.requiresConfirmation)
        assertEquals("မမ", plan.target)
    }

    @Test
    fun informationalCommandWithoutTarget_isValid() {
        val resolved = ActionDispatcher.resolve(JSONObject().put("command_type", "get_current_time"))
        assertTrue(CommandPlanValidator.validate(CommandPlan.from(resolved)).valid)
    }

    @Test
    fun missingMessagingTarget_withActiveNotification_stillRequiresExplicitRecipient() {
        com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender = "Aung Aung"
        try {
            val resolved = ActionDispatcher.resolve(
                JSONObject().put("command_type", "send_messenger").put("value", "အခုလာနေပြီ")
            )
            val plan = CommandPlan.from(resolved)
            val result = CommandPlanValidator.validate(plan)
            assertFalse(result.valid)
        } finally {
            com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender = null
        }
    }

    @Test
    fun missingMessagingTarget_withoutActiveNotification_requiresClarification() {
        com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender = null
        val resolved = ActionDispatcher.resolve(
            JSONObject().put("command_type", "send_messenger").put("value", "အခုလာနေပြီ")
        )
        val plan = CommandPlan.from(resolved)
        val result = CommandPlanValidator.validate(plan)
        assertFalse(result.valid)
        assertEquals("ဘယ်သူ့ကို စာပို့ရမလဲ?", result.clarification)
    }
}
