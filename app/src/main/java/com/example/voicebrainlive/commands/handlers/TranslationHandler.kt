package com.example.voicebrainlive.commands.handlers

import android.content.Context
import com.example.voicebrainlive.VoiceBrainExtensions

class TranslationHandler(context: Context) : CommandHandler {

    private val voiceBrainExtensions = VoiceBrainExtensions(context)

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "translate" -> voiceBrainExtensions.translateText(target ?: "", value ?: "english")
            "translate_from" -> voiceBrainExtensions.translateFrom(target ?: "auto", value ?: "")
            "live_translate_call", "live_translate" -> voiceBrainExtensions.liveTranslate(target ?: "myanmar", value ?: "english")
            "detect_language" -> voiceBrainExtensions.detectLanguage(target ?: "")
            "supported_languages" -> voiceBrainExtensions.getSupportedLanguages()
            else -> null
        }
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "translate",
        "translate_from",
        "live_translate_call",
        "live_translate",
        "detect_language",
        "supported_languages"
    )
}
