package com.example.voicebrainlive

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.example.voicebrainlive.routines.RoutineScheduler
import com.example.voicebrainlive.routines.RoutineStorage
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
