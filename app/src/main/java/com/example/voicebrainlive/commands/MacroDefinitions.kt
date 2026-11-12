package com.example.voicebrainlive.commands

import java.util.Locale

/** Keep legacy mixed-case names callable without rewriting or discarding stored definitions. */
internal object MacroDefinitions {
    const val GUIDE_KEY = "show_macro_guide"
    fun normalize(name: String) = name.trim().lowercase(Locale.ROOT)
    fun entries(values: Map<String, *>): Map<String, String> = values.mapNotNull { (key, value) ->
        (value as? String)?.takeIf { key != GUIDE_KEY }?.let { key to it }
    }.toMap()
    fun key(values: Map<String, *>, name: String): String? = entries(values).keys
        .filter { normalize(it) == normalize(name) }.singleOrNull()
    fun lookup(values: Map<String, *>, name: String): String? = key(values, name)?.let { values[it] as? String }

    fun preview(sequence: String): String = AutomationCommandValidator.extractSteps(sequence).mapIndexed { index, step ->
        val parts = step.split(':', limit = 3)
        val command = normalize(parts[0])
        val label = when (command) {
            "set_focus_mode" -> "အာရုံစိုက်ချိန် သတ်မှတ်ရန်"
            "end_focus_mode" -> "အာရုံစိုက်ချိန် ရပ်ရန်"
            "set_ringer_silent", "silent_mode", "mute_ringer" -> "ဖုန်းသံ ပိတ်ရန်"
            "set_ringer_normal", "normal_mode", "unmute_ringer" -> "ဖုန်းသံ ပြန်ဖွင့်ရန်"
            "set_ringer_vibrate", "vibrate_mode", "vibration_on" -> "တုန်ခါမှု သတ်မှတ်ရန်"
            "mute" -> "မီဒီယာအသံ ပိတ်ရန်"
            "unmute" -> "မီဒီယာအသံ ပြန်ဖွင့်ရန်"
            "volume_up" -> "အသံ တိုးရန်"
            "volume_down" -> "အသံ လျှော့ရန်"
            "set_volume" -> "အသံအတိုးအကျယ် သတ်မှတ်ရန်"
            "turn_on_flashlight", "flashlight_on" -> "ဓာတ်မီး ဖွင့်ရန်"
            "turn_off_flashlight", "flashlight_off" -> "ဓာတ်မီး ပိတ်ရန်"
            "get_weather", "weather" -> "မိုးလေဝသ ကြည့်ရန်"
            "my_schedule", "schedule_today" -> "ယနေ့ အချိန်ဇယား ကြည့်ရန်"
            "get_time", "get_current_time" -> "လက်ရှိအချိန် ကြည့်ရန်"
            "get_date", "get_current_date" -> "ယနေ့ ရက်စွဲ ကြည့်ရန်"
            "open_app" -> "App ဖွင့်ရန်"
            "run_macro" -> "အခြား မက်ခရို ခေါ်ရန်"
            else -> parts[0].trim().replace('_', ' ')
        }
        val arguments = parts.drop(1).joinToString(" · ").trim()
        "${index + 1}. $label" + if (arguments.isBlank()) "" else " · $arguments"
    }.joinToString("\n")
}
