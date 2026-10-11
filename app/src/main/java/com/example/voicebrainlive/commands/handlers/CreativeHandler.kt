package com.example.voicebrainlive.commands.handlers

import android.content.Context
import com.example.voicebrainlive.ImageGenerator
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.VoiceChanger

class CreativeHandler(private val context: Context) : CommandHandler {

    private val voiceChanger by lazy { VoiceChanger(context) }
    private val imageGenerator by lazy {
        ImageGenerator(
            context,
            SettingsActivity.getGeminiApiKey(context),
            SettingsActivity.getOpenAIApiKey(context)
        )
    }

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "voice_changer_on", "start_voice_changer" -> voiceChanger.handleVoiceCommand("start_voice_changer", target)
            "voice_changer_off", "stop_voice_changer" -> voiceChanger.handleVoiceCommand("stop_voice_changer", null)
            "change_voice", "set_voice" -> voiceChanger.handleVoiceCommand("change_voice", target)
            "list_voices", "voice_effects" -> voiceChanger.handleVoiceCommand("list_voices", null)
            "generate_image", "create_image", "draw" -> imageGenerator.handleVoiceCommand("generate_image", target, value)
            "open_image_generator" -> imageGenerator.handleVoiceCommand("open_image_generator", target, null)
            "image_models" -> imageGenerator.handleVoiceCommand("list_models", null, null)
            else -> null
        }
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "voice_changer_on",
        "start_voice_changer",
        "voice_changer_off",
        "stop_voice_changer",
        "change_voice",
        "set_voice",
        "list_voices",
        "voice_effects",
        "generate_image",
        "create_image",
        "draw",
        "open_image_generator",
        "image_models"
    )
}
