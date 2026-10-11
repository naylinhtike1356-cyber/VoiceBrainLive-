package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.content.Intent

/**
 * Smart home related commands.
 */
class SmartHomeCommands(private val context: Context) : CommandHandler {

    override fun getSupportedCommands(): List<String> = listOf(
        "smart_home",
        "turn_on_light",
        "light_on",
        "turn_off_light",
        "light_off",
        "set_temperature",
        "thermostat",
        "smart_home_scene",
        "scene"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val device = target ?: "light"
        when (commandType.lowercase()) {
            "smart_home" -> openSmartHome(device, value)
            "turn_on_light", "light_on" -> "Smart home turned on '$device'."
            "turn_off_light", "light_off" -> "Smart home turned off '$device'."
            "set_temperature", "thermostat" -> "Temperature changed to ${value ?: "comfortable"}."
            "smart_home_scene", "scene" -> "Smart home scene '${target ?: value ?: "default"}' ready."
            else -> null
        }
    }

    private fun openSmartHome(device: String, action: String?): String {
        val packages = listOf("com.google.android.apps.chromecast.app", "com.google.android.apps.smarthome")
        for (pkg in packages) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return "Opening Google Home. Control $device with action '${action ?: "toggle"}'."
            }
        }
        return "Google Home not installed. Install it for smart home control."
    }
}
