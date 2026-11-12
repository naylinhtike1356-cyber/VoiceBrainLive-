package com.example.voicebrainlive.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.voicebrainlive.MainActivity
import java.util.Calendar

/**
 * Schedules routine alarms via AlarmManager.
 * Supports daily, weekly, and custom multi-day schedules (e.g. Weekdays, Weekends, Mon/Wed/Fri).
 */
object RoutineScheduler {

    private const val REQUEST_CODE_BASE = 90000  // Avoid collision with reminder IDs
    private const val REQUEST_CODE_PRE_BASE = 91000

    fun schedule(context: Context, item: RoutineStorage.RoutineItem) {
        if (!item.enabled) {
            cancel(context, item.id)
            return
        }
        // Replacing a schedule must retire its old pre-reminder, including when it was removed.
        cancel(context, item.id)
        val triggerTime = nextTriggerTime(item.hour, item.minute, item.repeatDays, item.dayOfWeek)
        val intent = Intent(context, RoutineReceiver::class.java).apply {
            action = RoutineReceiver.ACTION_ROUTINE
            putExtra(MainActivity.EXTRA_AUTOMATION_ID, item.id)
            putExtra(MainActivity.EXTRA_AUTOMATION_TYPE, item.type)
            putExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE, item.message)
            putExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY, item.musicQuery)
            putExtra(MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT, item.reminderText)
            putExtra(MainActivity.EXTRA_AUTOMATION_SPEAK, item.useLiveAgent)
            putExtra("extra_automation_name", item.name)
            putExtra("extra_automation_ringer_mode", item.ringerMode)
            putExtra("extra_automation_volume_percent", item.volumePercent)
            putExtra("extra_automation_sleep_timer_minutes", item.sleepTimerMinutes)
            putExtra("extra_automation_actions_sequence", item.actionsSequence)
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
            android.util.Log.d("RoutineScheduler", "scheduled id=${item.id} at ${item.hour}:${item.minute} trigger=${triggerTime}")
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        }

        // Optional pre-reminder (verbal) before the main trigger.
        if (item.reminderMinutesBefore > 0 && item.reminderText.isNotBlank()) {
            val preTime = (triggerTime - item.reminderMinutesBefore * 60_000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
            val preIntent = Intent(context, RoutineReceiver::class.java).apply {
                action = RoutineReceiver.ACTION_ROUTINE_PRE
                putExtra(MainActivity.EXTRA_AUTOMATION_ID, item.id)
                putExtra(MainActivity.EXTRA_AUTOMATION_TYPE, item.type)
                putExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE, item.message)
                putExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY, item.musicQuery)
                putExtra(MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT, item.reminderText)
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
            putExtra(MainActivity.EXTRA_AUTOMATION_ID, id)
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE_BASE + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        alarmManager.cancel(pending)

        val preIntent = Intent(context, RoutineReceiver::class.java).apply {
            action = RoutineReceiver.ACTION_ROUTINE_PRE
            putExtra(MainActivity.EXTRA_AUTOMATION_ID, id)
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
            val currentDow = cal.get(Calendar.DAY_OF_WEEK)
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

    /**
     * Calculates the nearest upcoming trigger time across multiple repeat days.
     * repeatDays: list of Calendar.SUNDAY..SATURDAY (1..7).
     * If repeatDays is empty, falls back to single dayOfWeek or daily.
     */
    fun nextTriggerTime(hour: Int, minute: Int, repeatDays: List<Int>, fallbackDow: Int = 0): Long {
        val validDays = repeatDays.filter { it in 1..7 }
        if (validDays.isEmpty()) {
            return nextTriggerTime(hour, minute, fallbackDow)
        }
        val now = System.currentTimeMillis()
        return validDays.map { dow -> nextTriggerTime(hour, minute, dow) }
            .filter { it > now }
            .minOrNull() ?: nextTriggerTime(hour, minute, validDays.first())
    }

    fun rescheduleAll(context: Context) {
        RoutineStorage.getAll(context).forEach { schedule(context, it) }
    }
}
