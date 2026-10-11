package com.example.voicebrainlive.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules routine alarms via AlarmManager. Daily recurring.
 */
object RoutineScheduler {

    private const val REQUEST_CODE_BASE = 90000  // Avoid collision with reminder IDs
    private const val REQUEST_CODE_PRE_BASE = 91000

    fun schedule(context: Context, item: RoutineStorage.RoutineItem) {
        if (!item.enabled) {
            cancel(context, item.id)
            return
        }
        val triggerTime = nextTriggerTime(item.hour, item.minute, item.dayOfWeek)
        val intent = Intent(context, RoutineReceiver::class.java).apply {
            action = RoutineReceiver.ACTION_ROUTINE
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ID, item.id)
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_TYPE, item.type)
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MESSAGE, item.message)
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY, item.musicQuery)
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT, item.reminderText)
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_SPEAK, item.useLiveAgent)
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE_BASE + item.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            }
            android.util.Log.d("RoutineScheduler", "scheduled id=${item.id} at ${item.hour}:${item.minute}")
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        }

        // Optional pre-reminder (verbal) before the main trigger.
        if (item.reminderMinutesBefore > 0 && item.reminderText.isNotBlank()) {
            val preTime = (triggerTime - item.reminderMinutesBefore * 60_000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
            val preIntent = Intent(context, RoutineReceiver::class.java).apply {
                action = RoutineReceiver.ACTION_ROUTINE_PRE
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ID, item.id)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_TYPE, item.type)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MESSAGE, item.message)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY, item.musicQuery)
                putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT, item.reminderText)
            }
            val prePending = PendingIntent.getBroadcast(
                context, REQUEST_CODE_PRE_BASE + item.id, preIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, prePending)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, prePending)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, prePending)
                }
            } catch (_: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, preTime, prePending)
            }
        }
    }

    fun cancel(context: Context, id: Int) {
        val intent = Intent(context, RoutineReceiver::class.java).apply {
            action = RoutineReceiver.ACTION_ROUTINE
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ID, id)
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE_BASE + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        alarmManager.cancel(pending)

        val preIntent = Intent(context, RoutineReceiver::class.java).apply {
            action = RoutineReceiver.ACTION_ROUTINE_PRE
            putExtra(com.example.voicebrainlive.MainActivity.EXTRA_AUTOMATION_ID, id)
        }
        val prePending = PendingIntent.getBroadcast(
            context, REQUEST_CODE_PRE_BASE + id, preIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(prePending)
    }

    /** dayOfWeek: 0 = daily, 1-7 = Calendar.SUNDAY..SATURDAY */
    fun nextTriggerTime(hour: Int, minute: Int, dayOfWeek: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (dayOfWeek in 1..7) {
            var currentDow = cal.get(Calendar.DAY_OF_WEEK)
            var daysToAdd = dayOfWeek - currentDow
            if (daysToAdd < 0 || (daysToAdd == 0 && cal.timeInMillis <= System.currentTimeMillis())) {
                daysToAdd += 7
            }
            cal.add(Calendar.DAY_OF_YEAR, daysToAdd)
        } else if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun rescheduleAll(context: Context) {
        RoutineStorage.getAll(context).forEach { schedule(context, it) }
    }
}
