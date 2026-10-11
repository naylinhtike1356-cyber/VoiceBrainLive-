package com.example.voicebrainlive.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.voicebrainlive.CalendarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Event-Driven: Meeting မစခင် ၁၅ မိနစ်အလို သတိပေးခြင်း
 */
object CalendarEventReminderHelper {

    private const val TAG = "CalEventReminder"
    private const val REMIND_MINUTES = 15
    private const val REQUEST_CODE_BASE = 70000  // Avoid collision with routines
    private const val LOOKAHEAD_MS = 48 * 60 * 60 * 1000L  // 48 hours

    fun scheduleUpcoming(context: Context) {
        runBlocking(Dispatchers.IO) {
            try {
                val ch = CalendarHelper(context)
                val today = ch.getTodayEvents()
                val tomorrow = ch.getTomorrowEvents()
                val allEvents = today + tomorrow
                val now = System.currentTimeMillis()
                val remindThreshold = now + REMIND_MINUTES * 60 * 1000L
                val maxStart = now + LOOKAHEAD_MS

                val toSchedule = allEvents.filter { event ->
                    event.startTime > remindThreshold && event.startTime < maxStart
                }

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                for (event in toSchedule) {
                    val remindAt = event.startTime - REMIND_MINUTES * 60 * 1000L
                    val requestCode = REQUEST_CODE_BASE + (event.id % 50000).toInt()
                    val intent = Intent(context, CalendarEventReminderReceiver::class.java).apply {
                        action = CalendarEventReminderReceiver.ACTION_CAL_REMINDER
                        putExtra(CalendarEventReminderReceiver.EXTRA_EVENT_ID, event.id)
                        putExtra(CalendarEventReminderReceiver.EXTRA_TITLE, event.title)
                    }
                    val pending = PendingIntent.getBroadcast(
                        context, requestCode, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (alarmManager.canScheduleExactAlarms()) {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pending)
                            } else {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pending)
                            }
                        } else {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pending)
                        }
                        Log.d(TAG, "scheduled reminder for '${event.title}' at ${remindAt}")
                    } catch (e: Exception) {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, remindAt, pending)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleUpcoming: ${e.message}", e)
            }
        }
    }
}
