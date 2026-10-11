package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.util.Log

/**
 * Phase 2.2: Smart Home Handler (Mocked/Simulated)
 * Handles lights, AC, TV, etc.
 */
class SmartHomeHandler(private val context: Context) : CommandHandler {

    override fun getSupportedCommands(): List<String> = listOf(
        "smart_home", "toggle_light", "dim_light", "set_ac", "turn_off_all_lights"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val device = target ?: value ?: "device"
        val action = if (target != null) value ?: "toggle" else "toggle"

        when (commandType.lowercase()) {
            "smart_home" -> handleSmartHome(device, action)
            "turn_off_all_lights" -> "အိမ်က မီးအားလုံးကို ပိတ်လိုက်ပါပြီ။"
            else -> handleSmartHome(commandType.replace("light", "မီး"), action)
        }
    }

    private fun handleSmartHome(device: String, action: String): String {
        Log.d("SmartHome", "Action: $action on Device: $device")
        
        val deviceMy = when (device.lowercase()) {
            "light", "lights" -> "မီး"
            "fan" -> "ပန်ကာ"
            "ac", "aircon" -> "အဲကွန်း"
            "tv" -> "တီဗီ"
            else -> device
        }

        val actionMy = when (action.lowercase()) {
            "on", "turn on", "start" -> "ဖွင့်"
            "off", "turn off", "stop" -> "ပိတ်"
            else -> action
        }

        return "$deviceMy ကို $actionMy လိုက်ပါပြီ။ (Smart Home connectivity simulation)"
    }
}
