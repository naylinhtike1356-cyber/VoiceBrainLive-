package com.example.voicebrainlive.routines

import android.app.AlarmManager
import android.provider.AlarmClock
import com.example.voicebrainlive.agent.nativeapps.NativeAgentSettings
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.security.ShadowSecureSettingsStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=SleepWakeTestApplication::class)
class NativeClockChoiceTest {
    private val app get()=RuntimeEnvironment.getApplication() as SleepWakeTestApplication
    @After fun cleanup(){ShadowSecureSettingsStorage.reset()}
    @Test fun nativeOnlyDoesNotCreateDuplicateLumaAlarm()=runBlocking{
        assertTrue(NativeAgentSettings.setPhoneClockOnly(app,true))
        val result=PhoneCommandExecutor(app).executeAsync("set_alarm","07:30",null)
        assertTrue(result.contains("Clock"));val intent=Shadows.shadowOf(app).nextStartedActivity!!
        assertEquals(AlarmClock.ACTION_SET_ALARM,intent.action);assertEquals(7,intent.getIntExtra(AlarmClock.EXTRA_HOUR,-1));assertEquals(30,intent.getIntExtra(AlarmClock.EXTRA_MINUTES,-1));assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true))
        assertTrue(app.sleepWakeJournal.history().isEmpty());assertTrue(Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
    @Test fun unavailableClockDoesNotPretendAlarmSaved()=runBlocking{
        NativeAgentSettings.setPhoneClockOnly(app,true);app.clockAvailable=false
        val result=PhoneCommandExecutor(app).executeAsync("set_alarm","07:30",null)
        assertTrue(result.contains("မထားခဲ့ပါ"));assertTrue(app.sleepWakeJournal.history().isEmpty())
    }
}
