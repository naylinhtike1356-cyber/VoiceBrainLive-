package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.OfflineCommandMatcher
import com.example.voicebrainlive.SalaryDataReader
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.AutomationCommandValidator
import com.example.voicebrainlive.commands.BoundedMacroRunner
import com.example.voicebrainlive.commands.CommandSafetyPolicy
import com.example.voicebrainlive.commands.MacroDeviceVerification
import com.example.voicebrainlive.commands.MacroLeafRisk
import com.example.voicebrainlive.commands.OpaqueMacroResults
import com.example.voicebrainlive.commands.renderLegacy
import com.example.voicebrainlive.weather.WeatherNarrative

/**
 * Executes scheduled automation actions (agent mode) without conversational filler.
 * Enhanced for Samsung Modes & Routines: multi-action pipelines (Ringer, Volume, Music, Sleep Timer, Macros).
 */
class AutomationCommandRunner(private val context: Context) {

    private val executor by lazy { PhoneCommandExecutor(context) }

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

            // 1. Device Sound / Ringer Mode (e.g. silent, vibrate, normal)
            if (ringerMode.isNotBlank()) {
                val ringerCmd = when (ringerMode.lowercase()) {
                    "silent" -> "set_ringer_silent"
                    "vibrate" -> "set_ringer_vibrate"
                    "normal" -> "set_ringer_normal"
                    else -> null
                }
                if (ringerCmd != null) {
                    executor.executeAsync(ringerCmd, null, null)
                }
            }

            // 2. Media Volume Setting (0 - 100%)
            if (volumePercent in 0..100) {
                executor.executeAsync("set_volume", null, volumePercent.toString())
            }

            // 3. Main Routine Type or Mode Execution
            val mainResult = when (type.lowercase()) {
                "bedtime", "sleep" -> {
                    // Bedtime mode: play soothing bedtime music and set sleep timer
                    val q = musicQuery.ifBlank { message }.ifBlank { "အိပ်ရာဝင်သီချင်း" }
                    val playRes = formatResult(executor.executeAsync("play_music", q, null))
                    val timerMin = if (sleepTimerMinutes > 0) sleepTimerMinutes else 30
                    executor.executeAsync("set_music_sleep_timer", null, timerMin.toString())
                    "$playRes ($timerMin မိနစ်ကြာလျှင် သီချင်း အလိုအလျောက်ပိတ်မည်)"
                }
                "work" -> {
                    // Work mode: play cheerful upbeat music or execute actions
                    val q = musicQuery.ifBlank { message }.ifBlank { "အလုပ်ထဲမှာ ပျော်ရွှင်စေမယ့် သီချင်း" }
                    val playRes = formatResult(executor.executeAsync("play_music", q, null))
                    if (actionsSequence.isNotBlank()) {
                        runCommandSequence(actionsSequence)
                    }
                    playRes
                }
                "focus" -> {
                    executor.executeAsync("set_focus_mode", "30", null)
                    if (musicQuery.isNotBlank()) {
                        formatResult(executor.executeAsync("play_music", musicQuery, null))
                    } else {
                        "အာရုံစိုက်မုဒ် စတင်ပါပြီ။"
                    }
                }
                "music", "play_music", "play_spotify" -> {
                    val query = musicQuery.ifBlank { message }.ifBlank { "music" }
                    val playRes = formatResult(executor.executeAsync("play_music", query, null))
                    if (sleepTimerMinutes > 0) {
                        executor.executeAsync("set_music_sleep_timer", null, sleepTimerMinutes.toString())
                    }
                    playRes
                }
                "weather", "get_weather" -> {
                    formatResult(executor.executeAsync("get_weather", null, null))
                }
                "health", "routine_health", "get_health_summary" -> {
                    formatResult(executor.executeAsync("get_health_summary", "all", null))
                }
                "command", "macro" -> {
                    val seq = actionsSequence.ifBlank { message }
                    runCommandSequence(seq)
                }
                "reminder" -> {
                    val match = OfflineCommandMatcher.match(message)
                    if (match == null) "" else formatResult(executor.executeAsync(match.first, match.second, match.third))
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
                    if (actionsSequence.isNotBlank()) {
                        runCommandSequence(actionsSequence)
                    } else if (musicQuery.isNotBlank()) {
                        formatResult(executor.executeAsync("play_music", musicQuery, null))
                    } else {
                        "Automation type '$type' is not supported for automatic execution."
                    }
                }
            }
            if (mainResult.isNotBlank()) results.add(mainResult)

            // 4. Music Sleep Timer (if specified and not already applied by bedtime mode)
            if (sleepTimerMinutes > 0 && type.lowercase() !in setOf("bedtime", "sleep")) {
                executor.executeAsync("set_music_sleep_timer", null, sleepTimerMinutes.toString())
            }

            results.joinToString("\n")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.e("AutomationCommandRunner", "automation execution failed", e)
            "Automation ကို ယခုအချိန်တွင် မလုပ်ဆောင်နိုင်ပါ။"
        }
    }

    private suspend fun runCommandSequence(sequence: String): String {
        val validation = AutomationCommandValidator.validateSequence(sequence, allowApprovalRequired = true)
        if (!validation.valid) return validation.message
        val root = "automation-${java.util.UUID.randomUUID()}"
        val macros = context.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)
        val verification = MacroDeviceVerification(context)
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
            fallbackValueToTarget = false
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
