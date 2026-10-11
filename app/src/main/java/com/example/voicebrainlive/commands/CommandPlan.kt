package com.example.voicebrainlive.commands

import java.text.Normalizer

/**
 * Canonical representation of a command after model/tool argument resolution.
 * Memory may help resolve a target, but it never grants permission to execute.
 */
data class CommandPlan(
    val intent: String,
    val target: String?,
    val value: String?,
    val confidence: Int = 100,
    val source: String = "gemini_function_call",
    val requiresConfirmation: Boolean = CommandSafetyPolicy.requiresConfirmation(intent)
) {
    companion object {
        fun from(resolved: ActionDispatcher.ResolvedCommand): CommandPlan = CommandPlan(
            intent = normalizeCommand(resolved.commandType),
            target = resolved.target,
            value = resolved.value
        )

        private fun normalizeCommand(value: String): String =
            normalizeUserText(value).replace(' ', '_')
    }
}

object CommandPlanValidator {
    data class Result(val valid: Boolean, val clarification: String? = null)

    fun validate(plan: CommandPlan): Result {
        if (plan.intent.isBlank() || !plan.intent.matches(Regex("[a-z0-9_]+"))) {
            return Result(false, "ဘယ်လုပ်ဆောင်ချက်ကို လုပ်ပေးရမလဲ ပြန်ပြောပေးပါ။")
        }
        if (plan.confidence < 50) {
            return Result(false, "ခိုင်းစေချက်ကို သေချာမနားလည်သေးပါ။ နည်းနည်းပိုရှင်းပြပေးပါ။")
        }
        if (plan.requiresConfirmation && plan.target.isNullOrBlank() && CommandSafetyPolicy.canonical(plan.intent) in TARGET_REQUIRED_COMMANDS) {
            return Result(false, clarificationFor(plan.intent))
        }
        return Result(true)
    }

    private fun clarificationFor(intent: String): String = when (intent) {
        "make_call", "call", "call_contact" -> "ဘယ်သူ့ကို ဖုန်းခေါ်ပေးရမလဲ?"
        "send_sms", "send_messenger", "send_whatsapp", "send_facebook_message" -> "ဘယ်သူ့ကို စာပို့ရမလဲ?"
        "create_calendar_event", "add_calendar_event", "add_meeting", "create_meeting" -> "ဘယ်အကြောင်းအရာနဲ့ ဘယ်အချိန်မှာ ထည့်ပေးရမလဲ?"
        else -> "လိုအပ်တဲ့ အချက်အလက်တစ်ခု မပါသေးပါ။ နည်းနည်းပိုရှင်းပြပေးပါ။"
    }

    private val TARGET_REQUIRED_COMMANDS = setOf(
        "make_call", "call", "call_contact",
        "send_sms", "send_messenger", "send_whatsapp", "send_facebook_message",
        "create_calendar_event", "add_calendar_event", "add_meeting", "create_meeting"
    )
}

/** Normalizes common speech-to-text variation without changing the user's meaning. */
fun normalizeUserText(value: String): String {
    val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val digits = buildString(nfkc.length) {
        nfkc.forEach { ch ->
            append(
                when (ch) {
                    '၀' -> '0'; '၁' -> '1'; '၂' -> '2'; '၃' -> '3'; '၄' -> '4'
                    '၅' -> '5'; '၆' -> '6'; '၇' -> '7'; '၈' -> '8'; '၉' -> '9'
                    else -> ch
                }
            )
        }
    }
    return digits
        .replace(Regex("[၊,]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
}
