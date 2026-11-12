package com.example.voicebrainlive.commands

/**
 * Transforms internal technical command results, approval IDs, and security guard codes
 * into polished, user-friendly Burmese text suitable for chat display and TTS speech.
 */
object CommandResultFormatter {

    fun format(result: String): String {
        val trimmed = result.trim()
        if (trimmed.isBlank()) return ""

        if (trimmed.contains("No automatic retry or resume.") && Regex("\\b\\d+ macro step\\(s\\) dispatched\\.").containsMatchIn(trimmed)) {
            val summary = when {
                trimmed.startsWith("COMPLETED:") -> "မက်ခရို၏ အဆင့်အားလုံးကို လုပ်ဆောင်ပြီး စက်အခြေအနေ ပြန်စစ်ပြီးပါပြီ။"
                trimmed.startsWith("NEEDS_APPROVAL:") -> "မက်ခရို ရပ်စောင့်နေသည်။ မျက်နှာပြင်ပေါ်မှ အတည်ပြုပေးပါ။ ကျန်အဆင့်များ မလုပ်ဆောင်ရသေးပါ။"
                trimmed.startsWith("BLOCKED:") -> "မက်ခရို ရပ်ထားသည်။ အမည်နှင့် လုပ်မည့်အဆင့်များကို မက်ခရိုစာမျက်နှာတွင် ပြန်စစ်ပါ။"
                trimmed.startsWith("FAILED:") -> "မက်ခရိုအဆင့် မအောင်မြင်ပါ။ ကျန်အဆင့်များ မလုပ်ဆောင်ရသေးပါ။"
                else -> "မက်ခရို၏ နောက်ဆုံးအဆင့်ရလဒ်ကို အတည်မပြုနိုင်သေးပါ။ ကျန်အဆင့်များ ရပ်ထားသည်။ မပြီးသောအလုပ်ကို ပြန်စစ်ပေးပါ။"
            }
            val marker = "\nလုပ်ဆောင်ခဲ့သော အဆင့်များ\n"
            return summary + if (marker in trimmed) marker + trimmed.substringAfter(marker) else ""
        }

        if (trimmed.startsWith("NEEDS_APPROVAL:")) {
            return "📞 ဖုန်းခေါ်ဆိုရန် အတည်ပြုချက် တောင်းခံထားပါသည်။ မျက်နှာပြင်ပေါ်တွင် အတည်ပြုပေးပါ"
        }

        if (trimmed.startsWith("BLOCKED: Exact target, destination or scope မရှင်းလင်းပါ။")) {
            return "ဖုန်းခေါ်ဆိုမည့်သူ သို့မဟုတ် ဖုန်းနံပါတ်ကို ရှာမတွေ့ပါ။ အမည် သို့မဟုတ် ဖုန်းနံပါတ် အတိအကျ ပြောပေးပါ"
        }

        if (trimmed.startsWith("BLOCKED: STALE_CALL_INPUT")) {
            return "ခေါ်ဆိုရန် အချိန်ကျော်လွန်သွားပါပြီ။ ထပ်မံခိုင်းစေပေးပါ"
        }

        if (trimmed.startsWith("BLOCKED:")) {
            val reason = trimmed.removePrefix("BLOCKED:").trim()
            return when {
                reason.contains("revoked", ignoreCase = true) ->
                    "ခွင့်ပြုချက် မရှိသေးပါ။ ဖုန်းခေါ်ဆိုခွင့် ခွင့်ပြုချက် (Permission) ပေးပါ"
                reason.contains("Session closed", ignoreCase = true) ->
                    "လုပ်ဆောင်ချက် အချိန်ကျော်လွန်သွားပါပြီ။ ထပ်မံခိုင်းစေပေးပါ"
                reason.contains("Device locked", ignoreCase = true) ->
                    "ဖုန်း screen လော့ခ်ပွင့်နေမှသာ လုပ်ဆောင်နိုင်ပါမည်"
                else -> reason
            }
        }

        if (trimmed.startsWith("FAILED: CALL_REQUEST_UNAVAILABLE")) {
            return "ဖုန်းခေါ်ဆိုမှု ဝန်ဆောင်မှုကို လောလောဆယ် အသုံးပြု၍ မရသေးပါ"
        }

        when (trimmed) {
            "Flashlight on" -> return "ဓာတ်မီး ဖွင့်လိုက်ပါပြီ"
            "Flashlight off" -> return "ဓာတ်မီး ပိတ်လိုက်ပါပြီ"
        }

        return trimmed
    }
}
