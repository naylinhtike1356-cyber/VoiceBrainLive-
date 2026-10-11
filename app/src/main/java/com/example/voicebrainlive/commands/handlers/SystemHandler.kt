package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraManager
import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.example.voicebrainlive.AppLog

/**
 * Handler for system-related commands (WiFi, Bluetooth, Flashlight, Volume, Ringer).
 * Migrated logic from PhoneCommandExecutor.
 */
class SystemHandler(private val context: Context) : CommandHandler {

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var isFlashlightOn = false

    override fun getSupportedCommands(): List<String> = listOf(
        "volume_up", "volume_down", "set_volume", "mute", "unmute",
        "set_ringer_silent", "silent_mode", "mute_ringer",
        "set_ringer_normal", "normal_mode", "unmute_ringer",
        "set_ringer_vibrate", "vibrate_mode", "vibration_on",
        "turn_on_wifi", "wifi_on", "turn_off_wifi", "wifi_off",
        "turn_on_bluetooth", "bluetooth_on", "turn_off_bluetooth", "bluetooth_off",
        "turn_on_flashlight", "flashlight_on", "turn_off_flashlight", "flashlight_off", "toggle_flashlight"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "volume_up" -> volumeUp()
            "volume_down" -> volumeDown()
            "set_volume" -> setVolume(parseVolumePercent(value))
            "mute" -> mute()
            "unmute" -> unmute()
            "set_ringer_silent", "silent_mode", "mute_ringer" -> setRingerSilent()
            "set_ringer_normal", "normal_mode", "unmute_ringer" -> setRingerNormal()
            "set_ringer_vibrate", "vibrate_mode", "vibration_on" -> setRingerVibrate()
            "turn_on_wifi", "wifi_on" -> turnOnWifi()
            "turn_off_wifi", "wifi_off" -> turnOffWifi()
            "turn_on_bluetooth", "bluetooth_on" -> turnOnBluetooth()
            "turn_off_bluetooth", "bluetooth_off" -> turnOffBluetooth()
            "turn_on_flashlight", "flashlight_on" -> setFlashlight(true)
            "turn_off_flashlight", "flashlight_off" -> setFlashlight(false)
            "toggle_flashlight" -> toggleFlashlight()
            else -> null
        }
    }

    private fun volumeUp(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        return "Volume increased"
    }

    private fun volumeDown(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return "Volume decreased"
    }

    private fun setVolume(percent: Int): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $percent%"
    }

    private fun parseVolumePercent(value: String?): Int {
        if (value == null) return 50
        val v = value.replace(Regex("[^0-9]"), "")
        return v.toIntOrNull() ?: 50
    }

    private fun mute(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Muted"
    }

    private fun unmute(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return "Unmuted"
    }

    private fun setRingerSilent(): String {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return "Ringer set to silent"
    }

    private fun setRingerNormal(): String {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        return "Ringer set to normal"
    }

    private fun setRingerVibrate(): String {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        return "Ringer set to vibrate"
    }

    private fun turnOnWifi(): String {
        return "WiFi ကို Settings တွင် ကိုယ်တိုင်ဖွင့်ပေးပါ။ (Security restrictions on Android 10+)"
    }

    private fun turnOffWifi(): String {
        return "WiFi ကို Settings တွင် ကိုယ်တိုင်ပိတ်ပေးပါ။"
    }

    private fun turnOnBluetooth(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return if (adapter == null) "Bluetooth not supported"
        else if (adapter.isEnabled) "Bluetooth is already on"
        else "Bluetooth ဖွင့်ရန် Settings သို့သွားပါ။ (Security restrictions)"
    }

    private fun turnOffBluetooth(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return if (adapter == null) "Bluetooth not supported"
        else if (!adapter.isEnabled) "Bluetooth is already off"
        else "Bluetooth ပိတ်ရန် Settings သို့သွားပါ။"
    }

    private fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
            isFlashlightOn = enabled
            if (enabled) "Flashlight ဖွင့်လိုက်ပါပြီ" else "Flashlight ပိတ်လိုက်ပါပြီ"
        } catch (e: Exception) {
            "Flashlight error: ${e.message}"
        }
    }

    private fun toggleFlashlight(): String = setFlashlight(!isFlashlightOn)
}
