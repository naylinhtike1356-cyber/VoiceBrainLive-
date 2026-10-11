package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.OfflineCommandMatcher
import com.example.voicebrainlive.SalaryDataReader
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.AutomationCommandValidator

import com.example.voicebrainlive.weather.WeatherNarrative

/**
 * Executes scheduled automation actions (agent mode) without conversational filler.
 */
class AutomationCommandRunner(private val context: Context) {

    private val executor by lazy { PhoneCommandExecutor(context) }

    suspend fun run(
        type: String,
        message: String,
        musicQuery: String = "",
        isPreReminder: Boolean = false
    ): String {
        if (isPreReminder || type.equals("pre_reminder", ignoreCase = true)) {
            return ""
        }

        return try {
            when (type.lowercase()) {
                "music", "play_music", "play_spotify" -> {
                    val query = musicQuery.ifBlank { message }.ifBlank { "music" }
                    formatResult(executor.executeAsync("play_music", query, null))
                }
                "weather", "get_weather" -> {
                    formatResult(executor.executeAsync("get_weather", null, null))
                }
                "health", "routine_health", "get_health_summary" -> {
                    formatResult(executor.executeAsync("get_health_summary", "all", null))
                }
                "command" -> {
                    val validation = AutomationCommandValidator.validateSequence(message, allowApprovalRequired = true)
                    if (!validation.valid) return validation.message
                    val results = mutableListOf<String>()
                    val commands = AutomationCommandValidator.extractSteps(message)
                    for (subCmd in commands) {
                        if (subCmd.contains(":")) {
                            val parts = subCmd.split(":", limit = 3)
                            results += formatResult(executor.executeAsync(parts[0].trim(), parts.getOrNull(1), parts.getOrNull(2)))
                        } else {
                            val match = OfflineCommandMatcher.match(subCmd)
                            if (match != null) results += formatResult(executor.executeAsync(match.first, match.second, match.third))
                            else results += "Unrecognized command."
                        }
                    }
                    results.joinToString("\n")
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
                else -> "Automation type '$type' is not supported for automatic execution."
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.e("AutomationCommandRunner", "automation execution failed", e)
            "Automation ကို ယခုအချိန်တွင် မလုပ်ဆောင်နိုင်ပါ။"
        }

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
