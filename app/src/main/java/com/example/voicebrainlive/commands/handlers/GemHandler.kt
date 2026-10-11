package com.example.voicebrainlive.commands.handlers

import android.content.Context
import com.example.voicebrainlive.GemManager

class GemHandler(context: Context) : CommandHandler {

    private val gemManager = GemManager(context)

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "switch_gem", "change_gem", "use_gem" -> switchGem(target ?: "assistant")
            "list_gems", "show_gems" -> "Available gems: ${gemManager.getAllGems().joinToString(", ") { it.name }}"
            "current_gem", "active_gem" -> {
                val gem = gemManager.getActiveGem()
                "Current gem: ${gem.name} ${gem.avatarEmoji}. ${gem.description}"
            }
            else -> null
        }
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "switch_gem",
        "change_gem",
        "use_gem",
        "list_gems",
        "show_gems",
        "current_gem",
        "active_gem"
    )

    private fun switchGem(gemName: String): String {
        val raw = gemName.lowercase().replace(" ", "_")
        val gemId = when (raw) {
            "translation", "translate" -> "translator"
            "english", "english_teacher" -> "teacher"
            "korean_tutor", "korean_learning" -> "korean"
            "burmese", "myanmar_helper", "burmese_helper" -> "myanmar"
            "life_coach", "motivation" -> "coach"
            else -> raw
        }
        return if (gemManager.setActiveGem(gemId)) {
            val gem = gemManager.getActiveGem()
            "Switched to ${gem.name}. ${gem.description}"
        } else {
            "Gem not found: $gemName. Say 'list gems' to see available gems."
        }
    }
}
