package com.example.voicebrainlive.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * When routine alarm fires: start RoutineSpeakerService to fetch weather / speak message.
 */
class RoutineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ROUTINE && intent.action != ACTION_ROUTINE_PRE) return

        val id = intent.getIntExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ID, -1)
        val type = intent.getStringExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_TYPE) ?: "reminder"
        val message = intent.getStringExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MESSAGE) ?: ""
        val musicQuery = intent.getStringExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY) ?: ""
        val reminderText = intent.getStringExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT) ?: ""
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceBrain:RoutineWakeLock"
        )
        wakeLock.acquire(30_000L)

        try {
            val isPre = intent.action == ACTION_ROUTINE_PRE
            val shouldSpeak = intent.getBooleanExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_SPEAK, true)
            val announceText = intent.getStringExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ANNOUNCE).orEmpty().ifBlank {
                com.example.voicebrainlive.usecases.AssistantAutomationPayload.buildCalmAnnounce(type, message, musicQuery, isPre)
            }

            // Speak in background via RoutineSpeakerService without forcing MainActivity to pop up full-screen
            val serviceIntent = Intent(context, RoutineSpeakerService::class.java).apply {
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ID, id)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_TYPE, type)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MESSAGE, message)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY, musicQuery)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT, reminderText)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ANNOUNCE, announceText)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_IS_PRE, isPre)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_SPEAK, shouldSpeak)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        const val ACTION_ROUTINE = "com.example.voicebrainlive.ROUTINE"
        const val ACTION_ROUTINE_PRE = "com.example.voicebrainlive.ROUTINE_PRE"
    }
}
