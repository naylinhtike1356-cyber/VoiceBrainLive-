package com.example.voicebrainlive.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.voicebrainlive.VoiceLiveService

/**
 * Phase 4: Proactive Assistant Features
 * Listens to system events and triggers the VoiceLiveService to proactively help the user.
 */
class ProactiveContextReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("ProactiveContext", "Received action: $action")
        
        var proactiveMessage: String? = null

        when (action) {
            Intent.ACTION_BATTERY_LOW -> {
                proactiveMessage = "Your battery is getting low. Would you like me to turn on battery saver or reduce screen brightness?"
            }
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) { // Plugged in
                    proactiveMessage = "Headphones connected. Would you like me to play your Spotify playlist or read your unread messages?"
                }
            }
            Intent.ACTION_POWER_CONNECTED -> {
                proactiveMessage = "Power connected. Should I summarize your day's remaining events while you charge?"
            }
        }

        if (proactiveMessage != null) {
            triggerProactiveAssistant(context, proactiveMessage)
        }
    }

    private fun triggerProactiveAssistant(context: Context, message: String) {
        // Start VoiceLiveService with the proactive message
        val serviceIntent = Intent(context, VoiceLiveService::class.java).apply {
            putExtra("proactive_message", message)
            putExtra("auto_start_mic", false)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
