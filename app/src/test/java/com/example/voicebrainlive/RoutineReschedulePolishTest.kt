package com.example.voicebrainlive

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.example.voicebrainlive.routines.RoutineScheduler
import com.example.voicebrainlive.routines.RoutineStorage
import com.example.voicebrainlive.routines.RoutineReceiver
import com.example.voicebrainlive.routines.AutomationExecutionLedger
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class RoutineReschedulePolishTest {
    @Test fun blockedOccurrenceIsRecordedAndDuplicateDoesNotRestartIt() {
        val app = RuntimeEnvironment.getApplication()
        val item = RoutineStorage.add(app, "reminder", 23, 0)
        RoutineScheduler.schedule(app, item)
        val scheduled = RoutineScheduler.currentSchedule(app, item.id)!!
        var attempts = 0
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): android.content.ComponentName? {
                attempts++
                throw IllegalStateException("background start blocked")
            }
        }
        val trigger = Intent(RoutineReceiver.ACTION_ROUTINE)
            .putExtra(MainActivity.EXTRA_AUTOMATION_ID, item.id)
            .putExtra(RoutineReceiver.EXTRA_OCCURRENCE, scheduled.at)
        RoutineReceiver().onReceive(context, trigger)
        RoutineReceiver().onReceive(context, trigger)
        assertEquals(1, attempts)
        assertEquals(AutomationExecutionLedger.Result.BLOCKED, AutomationExecutionLedger.lastResult(app, item.id))
        assertEquals(RoutineScheduler.Timing.EXACT, RoutineScheduler.currentSchedule(app, item.id)?.timing)
    }

    @Test fun removingPreReminderRetiresItsExistingAlarm() {
        val app=RuntimeEnvironment.getApplication()
        val alarms=shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val routine=RoutineStorage.RoutineItem(id=19,type="reminder",hour=23,minute=0,message="existing",reminderMinutesBefore=5,reminderText="soon")
        RoutineScheduler.schedule(app,routine)
        assertEquals(2,alarms.scheduledAlarms.size)
        RoutineScheduler.schedule(app,routine.copy(reminderMinutesBefore=0,reminderText=""))
        assertEquals(1,alarms.scheduledAlarms.size)
        RoutineScheduler.schedule(app,routine.copy(enabled=false))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
