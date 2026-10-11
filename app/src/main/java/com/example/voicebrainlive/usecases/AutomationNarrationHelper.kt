package com.example.voicebrainlive.usecases

/**
 * Gemini narration is only for legacy flows. Scheduled agent automations execute directly.
 */
object AutomationNarrationHelper {

    fun buildPrompt(payload: AssistantAutomationPayload): String {
        if (payload.isPreReminder || payload.alreadySpoken) return ""
        return when (payload.type.lowercase()) {
            "weather", "music", "command", "reminder", "daily_log", "play_music", "play_spotify", "get_weather" -> ""
            else -> ""
        }
    }

    fun usesDirectExecution(type: String): Boolean {
        return type.lowercase() in DIRECT_EXECUTION_TYPES
    }

    private val DIRECT_EXECUTION_TYPES = setOf(
        "weather",
        "get_weather",
        "music",
        "play_music",
        "play_spotify",
        "command",
        "reminder",
        "daily_log"
    )
}
