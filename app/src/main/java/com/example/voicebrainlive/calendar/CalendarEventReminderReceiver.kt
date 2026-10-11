package com.example.voicebrainlive.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * Event-Driven: Meeting မစခင် ၁၅ မိနစ်အလို alarm ဖြစ်ရင် CalendarReminderSpeakerService စတင်
 */
class CalendarEventReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAL_REMINDER) return

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Meeting"

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceBrain:CalRemindWakeLock"
        )
        wakeLock.acquire(30_000L)

        try {
            val serviceIntent = Intent(context, CalendarReminderSpeakerService::class.java).apply {
                putExtra(EXTRA_EVENT_ID, eventId)
                putExtra(EXTRA_TITLE, title)
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
        const val ACTION_CAL_REMINDER = "com.example.voicebrainlive.CAL_EVENT_REMINDER"
        const val EXTRA_EVENT_ID = "cal_event_id"
        const val EXTRA_TITLE = "cal_event_title"
    }
}
