package com.example.voicebrainlive.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.example.voicebrainlive.MainActivity

/**
 * When routine alarm fires: start RoutineSpeakerService to execute actions / speak message.
 */
class RoutineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ROUTINE && intent.action != ACTION_ROUTINE_PRE) return

        val id = intent.getIntExtra(MainActivity.EXTRA_AUTOMATION_ID, -1)
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE, 0L)
        val isPre = intent.action == ACTION_ROUTINE_PRE
        val routine = if (id >= 0) RoutineStorage.getById(context, id)?.takeIf { it.enabled } ?: return else null
        if (occurrence > 0 && RoutineScheduler.currentSchedule(context, id)?.at != occurrence) return
        val type = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_TYPE) ?: "reminder"
        val message = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE) ?: ""
        val musicQuery = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY) ?: ""
        val reminderText = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT) ?: ""
        val routineName = intent.getStringExtra("extra_automation_name") ?: ""
        val ringerMode = intent.getStringExtra("extra_automation_ringer_mode") ?: ""
        val volumePercent = intent.getIntExtra("extra_automation_volume_percent", -1)
        val sleepTimerMinutes = intent.getIntExtra("extra_automation_sleep_timer_minutes", 0)
        val actionsSequence = intent.getStringExtra("extra_automation_actions_sequence") ?: ""
        
        if (occurrence > 0 && !AutomationExecutionLedger.tryClaim(context, id, type, isPre, occurrence)) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceBrain:RoutineWakeLock"
        )
        wakeLock.acquire(30_000L)

        try {
            val shouldSpeak = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_SPEAK, true)
            val announceText = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_ANNOUNCE).orEmpty().ifBlank {
                com.example.voicebrainlive.usecases.AssistantAutomationPayload.buildCalmAnnounce(type, message, musicQuery, isPre)
            }

            // Speak in background via RoutineSpeakerService without forcing MainActivity to pop up full-screen
            val serviceIntent = Intent(context, RoutineSpeakerService::class.java).apply {
                putExtra(MainActivity.EXTRA_AUTOMATION_ID, id)
                putExtra(MainActivity.EXTRA_AUTOMATION_TYPE, type)
                putExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE, message)
                putExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY, musicQuery)
                putExtra(MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT, reminderText)
                putExtra(MainActivity.EXTRA_AUTOMATION_ANNOUNCE, announceText)
                putExtra(MainActivity.EXTRA_AUTOMATION_IS_PRE, isPre)
                putExtra(MainActivity.EXTRA_AUTOMATION_SPEAK, shouldSpeak)
                putExtra(EXTRA_OCCURRENCE, occurrence)
                putExtra("extra_automation_name", routineName)
                putExtra("extra_automation_ringer_mode", ringerMode)
                putExtra("extra_automation_volume_percent", volumePercent)
                putExtra("extra_automation_sleep_timer_minutes", sleepTimerMinutes)
                putExtra("extra_automation_actions_sequence", actionsSequence)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (blocked: IllegalStateException) {
            // Android 12+ may reject an inexact alarm's background FGS start.
            onStartBlocked(context, id, isPre)
        } catch (blocked: SecurityException) {
            onStartBlocked(context, id, isPre)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (routine != null && !isPre) {
                runCatching { RoutineScheduler.schedule(context, routine) }
                    .onFailure { android.util.Log.w("RoutineReceiver", "Could not schedule next routine occurrence", it) }
            }
        }
    }

    private fun onStartBlocked(context: Context, id: Int, isPre: Boolean) {
        android.util.Log.w("RoutineReceiver", "Routine start blocked; user review required")
        if (!isPre) AutomationExecutionLedger.record(context, id, AutomationExecutionLedger.Result.BLOCKED)
        com.example.voicebrainlive.voice.AgentSpeech.showOpenNotification(context)
    }

    companion object {
        const val ACTION_ROUTINE = "com.example.voicebrainlive.ROUTINE"
        const val ACTION_ROUTINE_PRE = "com.example.voicebrainlive.ROUTINE_PRE"
        const val EXTRA_OCCURRENCE = "routine_occurrence_at"
    }
}
