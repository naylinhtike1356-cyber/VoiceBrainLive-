package com.example.voicebrainlive.commands.handlers

import android.content.Context
import com.example.voicebrainlive.health.HealthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HealthHandler(private val context: Context) {

    suspend fun execute(commandType: String, target: String?, value: String?): String {
        return withContext(Dispatchers.IO) {
            when (commandType.lowercase().trim()) {
                "get_health_summary", "health_summary", "get_health_data", "health" -> {
                    val queryTarget = target ?: value
                    HealthManager.getHealthSummaryLive(context, queryTarget)
                }
                "get_sleep_data", "sleep_summary", "sleep" -> {
                    HealthManager.getHealthSummaryLive(context, "sleep")
                }
                "get_activity_data", "steps_summary", "steps" -> {
                    HealthManager.getHealthSummaryLive(context, "activity")
                }
                "get_workout_data", "workout_summary", "workout" -> {
                    HealthManager.getHealthSummaryLive(context, "workout")
                }
                else -> {
                    HealthManager.getHealthSummaryLive(context, target ?: value)
                }
            }
        }
    }
}
