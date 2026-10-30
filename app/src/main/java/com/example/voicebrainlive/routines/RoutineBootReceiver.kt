package com.example.voicebrainlive.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.voicebrainlive.ReminderHelper
import com.example.voicebrainlive.calendar.CalendarEventReminderHelper
import com.example.voicebrainlive.location.GeofenceHelper
import com.example.voicebrainlive.proactive.ProactiveRainScheduler

/**
 * On boot: reschedule all saved routines (AlarmManager alarms are cleared on reboot).
 * Also schedules calendar event 15-min reminders, proactive rain check.
 * Phase 2.1: Re-register Home/Office geofences.
 */
class RoutineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            RoutineScheduler.rescheduleAll(context)
            SleepWakeGraph.restore(context)
            val reminderCount = ReminderHelper.rescheduleAllFuture(context)
            CalendarEventReminderHelper.scheduleUpcoming(context)
            ProactiveRainScheduler.schedule(context)
            try {
                GeofenceHelper(context).registerGeofences()
            } catch (_: Exception) { }
            android.util.Log.d("RoutineBoot", "Rescheduled ${RoutineStorage.getAll(context).size} routines + $reminderCount reminders + calendar reminders + proactive rain + geofences")
        }
    }
}
