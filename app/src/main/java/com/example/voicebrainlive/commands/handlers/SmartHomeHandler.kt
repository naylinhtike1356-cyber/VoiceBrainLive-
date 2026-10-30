package com.example.voicebrainlive.commands.handlers

import android.content.Context

/** Unconfigured direct controls fail closed. No private target/action logging or simulated success. */
class SmartHomeHandler(private val context: Context) : CommandHandler {
    override fun getSupportedCommands(): List<String> = listOf(
        "smart_home", "toggle_light", "dim_light", "set_ac", "turn_off_all_lights")

    override suspend fun execute(commandType: String, target: String?, value: String?): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (commandType.lowercase() in getSupportedCommands())
                "NOT_CONFIGURED: Direct smart-home control is unavailable. No device action was performed. Open Google Home to control your devices."
            else null
        }
}
