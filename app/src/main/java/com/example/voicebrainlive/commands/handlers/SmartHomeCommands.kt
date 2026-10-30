package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.content.Intent

/** Unconfigured direct controls fail closed. Google Home launcher is UI-only; no direct control configured. */
class SmartHomeCommands(private val context: Context) : CommandHandler {
    override fun getSupportedCommands(): List<String> = listOf(
        "smart_home", "turn_on_light", "light_on", "turn_off_light", "light_off",
        "set_temperature", "thermostat", "smart_home_scene", "scene")

    override suspend fun execute(commandType: String, target: String?, value: String?): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (commandType.lowercase()) {
                "smart_home" -> openSmartHome()
                "turn_on_light", "light_on", "turn_off_light", "light_off", "set_temperature", "thermostat",
                "smart_home_scene", "scene" -> "NOT_CONFIGURED: Direct smart-home control is unavailable. Open Google Home to control your devices. No device action was performed."
                else -> null
            }
        }

    private fun openSmartHome(): String {
        val packages = listOf("com.google.android.apps.chromecast.app", "com.google.android.apps.smarthome")
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            return try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Google Home opened. Choose and control a device there; no device setting was changed by VoiceBrainLive."
            } catch (_: Exception) { "FAILED: Google Home could not be opened. No device action was performed." }
        }
        return "NOT_CONFIGURED: Google Home is not installed and direct smart-home control is unavailable. No device action was performed."
    }
}
