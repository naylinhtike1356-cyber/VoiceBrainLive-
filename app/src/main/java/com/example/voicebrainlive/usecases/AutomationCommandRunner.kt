package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.OfflineCommandMatcher
import com.example.voicebrainlive.SalaryDataReader
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.AutomationCommandValidator
import com.example.voicebrainlive.commands.BoundedMacroRunner
import com.example.voicebrainlive.commands.CommandSafetyPolicy
import com.example.voicebrainlive.commands.MacroDeviceVerification
import com.example.voicebrainlive.commands.MacroLeaf
import com.example.voicebrainlive.commands.MacroLeafRisk
import com.example.voicebrainlive.commands.MacroOutcomeStatus
import com.example.voicebrainlive.routines.RoutineStorage
import com.example.voicebrainlive.routines.AutomationExecutionLedger
import com.example.voicebrainlive.commands.renderLegacy
import com.example.voicebrainlive.weather.WeatherNarrative

/**
 * Executes scheduled automation actions (agent mode) without conversational filler.
 * Enhanced for Samsung Modes & Routines: multi-action pipelines (Ringer, Volume, Music, Sleep Timer, Macros).
 */
class AutomationCommandRunner(private val context: Context) {

    private val executor by lazy { PhoneCommandExecutor(context) }
    private val verification by lazy { MacroDeviceVerification(context) }

    suspend fun run(item: RoutineStorage.RoutineItem): String = run(
        type = item.type, message = item.message, musicQuery = item.musicQuery,
        ringerMode = item.ringerMode, volumePercent = item.volumePercent,
        sleepTimerMinutes = item.sleepTimerMinutes, actionsSequence = item.actionsSequence
    ).also { AutomationExecutionLedger.record(context, item.id, AutomationExecutionLedger.classify(it)) }

    suspend fun run(
        type: String,
        message: String,
        musicQuery: String = "",
        isPreReminder: Boolean = false,
        ringerMode: String = "",
        volumePercent: Int = -1,
        sleepTimerMinutes: Int = 0,
        actionsSequence: String = ""
    ): String {
        if (isPreReminder || type.equals("pre_reminder", ignoreCase = true)) {
            return ""
        }

        return try {
            val results = mutableListOf<String>()
            val kind = type.lowercase(java.util.Locale.ROOT)
            val sequence = actionsSequence.ifBlank { if (kind in setOf("macro", "command")) message else "" }
            if (sequence.isNotBlank() || kind in setOf("macro", "command")) {
                val validation = AutomationCommandValidator.validateSequence(sequence, allowApprovalRequired = true)
                if (!validation.valid) return "BLOCKED: ${validation.message}"
            }

            suspend fun applyStep(command: String, label: String, target: String? = null, value: String? = null): Boolean {
                val leaf = MacroLeaf(command, target, value, MacroLeafRisk.SAFE)
                val outcome = verification.dispatch(leaf) { executor.executeAsync(command, target, value) }
                val verified = outcome.status == MacroOutcomeStatus.VERIFIED
                val detail = when {
                    verified -> "အတည်ပြုပြီး"
                    outcome.status == MacroOutcomeStatus.UNVERIFIED -> "ရလဒ် အတည်မပြုနိုင်သေးပါ"
                    else -> com.example.voicebrainlive.commands.CommandResultFormatter.format(outcome.message)
                }
                results += "$label · $detail"
                return verified
            }

            fun incomplete() = "PARTIAL: ${results.joinToString("\n")}\nကျန်အဆင့်များ မလုပ်ဆောင်ရသေးပါ။"

            // 1. Device Sound / Ringer Mode (e.g. silent, vibrate, normal)
            if (ringerMode.isNotBlank()) {
                val ringerCmd = when (ringerMode.lowercase()) {
                    "silent" -> "set_ringer_silent"
                    "vibrate" -> "set_ringer_vibrate"
                    "normal" -> "set_ringer_normal"
                    else -> null
                }
                if (ringerCmd != null && !applyStep(ringerCmd, "ဖုန်းမြည်သံ")) return incomplete()
            }

            // 2. Media Volume Setting (0 - 100%)
            if (volumePercent in 0..100) {
                // Routine controls express a percentage of the device maximum. The media command uses 0–150.
                if (!applyStep("set_volume", "မီဒီယာအသံ $volumePercent%", value = (volumePercent * 150 / 100).toString())) {
                    return incomplete()
                }
            }

            // 3. Main Routine Type or Mode Execution
            val mainResult = if (sequence.isNotBlank()) runCommandSequence(sequence) else when (kind) {
                "bedtime", "sleep" -> {
                    val q = musicQuery.ifBlank { message }.ifBlank { "အိပ်ရာဝင်သီချင်း" }
                    val timerMin = if (sleepTimerMinutes > 0) sleepTimerMinutes else 30
                    if (!applyStep("play_music", "သီချင်းဖွင့်ခြင်း", target = q) ||
                        !applyStep("set_music_sleep_timer", "သီချင်းပိတ်ချိန် $timerMin မိနစ်", value = timerMin.toString())) {
                        return incomplete()
                    }
                    ""
                }
                "work" -> {
                    val q = musicQuery.ifBlank { message }.ifBlank { "အလုပ်ထဲမှာ ပျော်ရွှင်စေမယ့် သီချင်း" }
                    if (!applyStep("play_music", "သီချင်းဖွင့်ခြင်း", target = q)) return incomplete()
                    ""
                }
                "focus" -> {
                    if (!applyStep("set_focus_mode", "အာရုံစိုက်မုဒ်", value = "30")) return incomplete()
                    if (musicQuery.isNotBlank() && !applyStep("play_music", "သီချင်းဖွင့်ခြင်း", target = musicQuery)) return incomplete()
                    ""
                }
                "music", "play_music", "play_spotify" -> {
                    val query = musicQuery.ifBlank { message }.ifBlank { null }
                    if (!applyStep("play_music", "သီချင်းဖွင့်ခြင်း", target = query)) return incomplete()
                    ""
                }
                "weather", "get_weather" -> {
                    formatResult(executor.executeAsync("get_weather", null, null))
                }
                "health", "routine_health", "get_health_summary" -> {
                    formatResult(executor.executeAsync("get_health_summary", "all", null))
                }
                "reminder" -> {
                    val match = OfflineCommandMatcher.match(message)
                    when {
                        match == null -> ""
                        CommandSafetyPolicy.classify(match.first) != CommandSafetyPolicy.Classification.SAFE ->
                            "BLOCKED: ဒီသတိပေးချက်က လုပ်ဆောင်ရန် အတည်ပြုချက်လိုပါသည်။"
                        else -> formatResult(executor.executeAsync(match.first, match.second, match.third))
                    }
                }
                "daily_log" -> {
                    val res = SalaryDataReader.getSalaryAnswer(context, "today")
                    if (res.contains("မရှိသေးပါ") || res.contains("မရှိပါ")) {
                        "ယနေ့ လစာမှတ်တမ်း မရှိသေးပါ။ မှတ်တမ်းတင်ပေးပါ။"
                    } else {
                        res
                    }
                }
                else -> {
                    if (musicQuery.isNotBlank()) {
                        formatResult(executor.executeAsync("play_music", musicQuery, null))
                    } else {
                        "Automation type '$type' is not supported for automatic execution."
                    }
                }
            }
            if (mainResult.isNotBlank()) {
                if (sequence.isNotBlank()) {
                    return if (results.isEmpty()) mainResult else
                        "$mainResult\nကြိုတင်လုပ်ဆောင်ချက်များ\n${results.joinToString("\n")}"
                }
                results.add(mainResult)
            }

            // Presets arm at most one timer. A custom sequence owns all of its steps.
            if (sleepTimerMinutes > 0 && sequence.isBlank() && kind in setOf("work", "focus", "music", "play_music", "play_spotify") &&
                (kind != "focus" || musicQuery.isNotBlank())) {
                if (!applyStep("set_music_sleep_timer", "သီချင်းပိတ်ချိန် $sleepTimerMinutes မိနစ်", value = sleepTimerMinutes.toString())) return incomplete()
            }

            results.joinToString("\n")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.e("AutomationCommandRunner", "automation execution failed", e)
            "FAILED: Automation ကို ယခုအချိန်တွင် မလုပ်ဆောင်နိုင်ပါ။"
        }
    }

    private suspend fun runCommandSequence(sequence: String): String {
        val validation = AutomationCommandValidator.validateSequence(sequence, allowApprovalRequired = true)
        if (!validation.valid) return validation.message
        val root = "automation-${java.util.UUID.randomUUID()}"
        val macros = context.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)
        return BoundedMacroRunner(
            lookupDefinition = { id -> if (id == root) sequence else macros.getString(id, null) },
            classifyRisk = { command -> when (CommandSafetyPolicy.classify(command)) {
                CommandSafetyPolicy.Classification.SAFE -> MacroLeafRisk.SAFE
                CommandSafetyPolicy.Classification.SENSITIVE -> MacroLeafRisk.SENSITIVE
                CommandSafetyPolicy.Classification.UNKNOWN -> MacroLeafRisk.UNKNOWN
            } },
            dispatchThroughCommonGuard = { leaf ->
                verification.dispatch(leaf) { executor.executeAsync(leaf.command, leaf.target, leaf.value) }
            },
            monotonicMillis = { android.os.SystemClock.elapsedRealtime() },
            fallbackValueToTarget = false,
            allowNumericShorthand = true
        ).run(root).renderLegacy()
    }

    private fun formatResult(result: String): String {
        if (!result.startsWith("WEATHER_DATA|")) return result
        val parts = result.split("|")
        if (parts.size < 9) return result
        return WeatherNarrative.current(
            location = parts.getOrNull(1).orEmpty(),
            temperature = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            minTemp = parts.getOrNull(3)?.toIntOrNull() ?: 0,
            maxTemp = parts.getOrNull(4)?.toIntOrNull() ?: 0,
            weatherCode = parts.getOrNull(5)?.toIntOrNull() ?: 0,
            isRainExpected = parts.getOrNull(6)?.toBooleanStrictOrNull() ?: false,
            humidity = parts.getOrNull(7)?.toIntOrNull() ?: 0,
            windSpeed = parts.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
            rainSummary = parts.getOrNull(10).orEmpty()
        )
    }
}
