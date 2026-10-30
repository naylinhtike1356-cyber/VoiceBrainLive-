package com.example.voicebrainlive.routines

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.*
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.*
import android.view.KeyEvent
import android.widget.*
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.handlers.MusicCommands
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class SleepWakeTestApplication:VoiceBrainApplication(),SleepWakeHost {
    override val automaticStartupAllowed=false
    val disk=SleepMemory()
    var clock=SleepWakeNow(System.currentTimeMillis(),SystemClock.elapsedRealtime(),1)
    override val sleepWakeNow get()=clock
    override val sleepWakeJournal=SleepWakeJournal(disk,{clock})
    var clockAvailable=true
    override fun startActivity(intent:Intent){if(!clockAvailable&&intent.action==AlarmClock.ACTION_SET_ALARM)throw ActivityNotFoundException();super.startActivity(intent)}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=SleepWakeTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SleepWakeAndroidTest {
    private val app get()=RuntimeEnvironment.getApplication() as SleepWakeTestApplication
    private val alarms get()=Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
    private val audio get()=Shadows.shadowOf(app.getSystemService(AudioManager::class.java))
    private suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check()){Shadows.shadowOf(Looper.getMainLooper()).idle();delay(10)}}}
    private suspend fun execute(command:String,target:String?=null,value:String?=null):String=coroutineScope{
        val task=async(Dispatchers.Default){PhoneCommandExecutor(app).executeAsync(command,target,value)};until{task.isCompleted};task.await()
    }
    private suspend fun deliver(a:ShadowAlarmManager.ScheduledAlarm){
        val intent=Shadows.shadowOf(requireNotNull(a.operation)).savedIntent
        val component=ComponentName(app,SleepWakeReceiver::class.java)
        assertEquals(component,intent.component)
        assertFalse(app.packageManager.getReceiverInfo(component,0).exported)
        val receiver=SleepWakeReceiver()
        val factory=org.robolectric.shadows.ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create",Int::class.javaPrimitiveType!!,String::class.java,Bundle::class.java,Boolean::class.javaPrimitiveType!!).apply{isAccessible=true}
        val pending=factory.invoke(null,0,null,null,false) as android.content.BroadcastReceiver.PendingResult
        org.robolectric.util.ReflectionHelpers.setField(receiver,"mPendingResult",pending)
        receiver.onReceive(app,intent)
        assertTrue(Shadows.shadowOf(receiver).wentAsync())
        val completion=org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBroadcastPendingResult>(pending).future
        until{completion.isDone}
    }
    @After fun cleanup(){WakeAlarmService.stopOwned();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    @Test fun sleepTimerUsesSilentMediaKeyNotClockOrReminderAndDeduplicates()=runBlocking<Unit>{
        val result=execute("set_music_sleep_timer",null,"5")
        assertTrue(result.contains("တိတ်တဆိတ်"));assertNull(Shadows.shadowOf(app).nextStartedActivity)
        val entry=app.sleepWakeJournal.history().single();val a=alarms.scheduledAlarms.single()
        assertEquals(SleepWakeKind.MEDIA_STOP,entry.kind);assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP,a.type)
        assertTrue(audio.dispatchedMediaKeyEvents.isEmpty())
        app.clock=app.clock.copy(wall=app.clock.wall+300_000,elapsed=app.clock.elapsed+300_000)
        deliver(a);assertEquals(SleepWakeState.REQUESTED,app.sleepWakeJournal.history().single().state)
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_PAUSE),audio.dispatchedMediaKeyEvents.map{it.keyCode})
        deliver(a)
        assertEquals(2,audio.dispatchedMediaKeyEvents.size)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertTrue(Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test fun inaccessibleNotificationListenerFallsBackToMediaKeyInsteadOfPretendingPause()=runBlocking<Unit>{
        val c=ComponentName(app,com.example.voicebrainlive.MediaSessionAccessService::class.java).flattenToString()
        Settings.Secure.putString(app.contentResolver,"enabled_notification_listeners",c)
        com.example.voicebrainlive.MediaSessionHelper.onListenerDisconnected()
        assertEquals(MusicCommands.PAUSE_REQUESTED,execute("media_pause"))
        assertEquals(2,audio.dispatchedMediaKeyEvents.size)
    }
    @Test fun cancellationPreventsLaterMediaEffect()=runBlocking<Unit>{
        execute("set_music_sleep_timer",null,"5");val a=alarms.scheduledAlarms.single()
        execute("cancel_music_sleep_timer");app.clock=app.clock.copy(wall=app.clock.wall+300_000,elapsed=app.clock.elapsed+300_000)
        deliver(a)
        assertTrue(audio.dispatchedMediaKeyEvents.isEmpty());assertEquals(SleepWakeState.CANCELLED,app.sleepWakeJournal.history().single().state)
    }
    @Test fun dualWakeHandsExactTimeToClockAndStoresOnlyOwnMetadataOnce()=runBlocking<Unit>{
        val result=execute("set_alarm","07:30")
        assertTrue(result.contains("အတည်ပြု"));val native=Shadows.shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM,native.action)
        assertEquals(7,native.getIntExtra(AlarmClock.EXTRA_HOUR,-1));assertEquals(30,native.getIntExtra(AlarmClock.EXTRA_MINUTES,-1))
        assertFalse(native.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true))
        val row=app.sleepWakeJournal.history().single();assertEquals(SleepWakeKind.WAKE,row.kind);assertTrue(row.clockRequested)
        assertEquals(SleepWakeGraph.label(row.id),native.getStringExtra(AlarmClock.EXTRA_MESSAGE));assertNotNull(alarms.scheduledAlarms.single().alarmClockInfo)
        execute("set_alarm","07:30");assertEquals(1,app.sleepWakeJournal.history().size);assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun clockUnavailableCancelsLumaInsteadOfClaimingBothSet()=runBlocking<Unit>{
        app.clockAvailable=false;val r=execute("set_alarm","07:00")
        assertTrue(r.contains("ဖွင့်မရ"));assertEquals(SleepWakeState.CANCELLED,app.sleepWakeJournal.history().single().state);assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun secureWriteFailureHasNoAlarmOrPlayerEffect()=runBlocking<Unit>{
        app.disk.writable=false;execute("start_bedtime_music","bedtime","5")
        assertTrue(alarms.scheduledAlarms.isEmpty());assertNull(Shadows.shadowOf(app).nextStartedActivity);assertTrue(audio.dispatchedMediaKeyEvents.isEmpty())
    }
    @Test @Config(sdk=[34]) fun exactAlarmPermissionFailureNeverFallsBackToRingingClockTimer()=runBlocking<Unit>{
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try{val result=execute("set_music_sleep_timer",null,"5");assertTrue(result.contains("ခွင့်ပြု"));assertTrue(app.sleepWakeJournal.history().isEmpty());assertTrue(alarms.scheduledAlarms.isEmpty());assertNull(Shadows.shadowOf(app).nextStartedActivity)}
        finally{ShadowAlarmManager.setCanScheduleExactAlarms(true)}
    }
    @Test fun dismissUsesExactOwnedLabelAndCannotDeleteOrDismissFutureUnrelatedRecords()=runBlocking<Unit>{
        assertEquals(com.example.voicebrainlive.commands.CommandSafetyPolicy.Classification.SENSITIVE,
            com.example.voicebrainlive.commands.CommandSafetyPolicy.classify("dismiss_alarm"))
        val now=app.clock;val j=app.sleepWakeJournal
        val e=j.add(SleepWakeKind.WAKE,now.wall+60_000,0).first;j.clockRequested(e.id)
        app.clock=now.copy(wall=now.wall+60_000,elapsed=now.elapsed+60_000);j.claim(e.id)
        val future=j.add(SleepWakeKind.WAKE,app.clock.wall+120_000,0).first
        execute("stop_wake_alarm",e.id)
        val dismiss=Shadows.shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_DISMISS_ALARM,dismiss.action);assertEquals(AlarmClock.ALARM_SEARCH_MODE_LABEL,dismiss.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE))
        assertEquals(SleepWakeGraph.label(e.id),dismiss.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(SleepWakeState.SCHEDULED,j.history().single{it.id==future.id}.state);assertEquals(SleepWakeState.DISMISSED,j.history().single{it.id==e.id}.state)
    }
    @Test fun ownedCancelRejectsUnknownIdAndPreservesEveryOtherAlarm()=runBlocking<Unit>{
        val e=app.sleepWakeJournal.add(SleepWakeKind.WAKE,app.clock.wall+60_000,0).first
        val result=execute("cancel_wake_alarm","not-owned")
        assertTrue(result.contains("ဤအက်ပ်"));assertEquals(SleepWakeState.SCHEDULED,app.sleepWakeJournal.history().single().state);assertNull(Shadows.shadowOf(app).nextStartedActivity)
        execute("cancel_wake_alarm",e.id);assertEquals(SleepWakeState.CANCELLED,app.sleepWakeJournal.history().single().state)
    }
    @Test fun genericStopCannotDismissFutureOrUnrelatedClockAlarm()=runBlocking<Unit>{
        val e=app.sleepWakeJournal.add(SleepWakeKind.WAKE,app.clock.wall+60_000,0).first
        app.sleepWakeJournal.clockRequested(e.id);execute("stop_wake_alarm")
        assertEquals(SleepWakeState.SCHEDULED,app.sleepWakeJournal.history().single().state);assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun ordinarySleepWakeScreenNavigatesAndPersistsWithoutSecureWindowOrMicrophoneStartup()=runBlocking<Unit>{
        val controller=Robolectric.buildActivity(SleepWakeActivity::class.java).setup();val activity=controller.get()
        try{
            assertEquals(0,activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE)
            val input=activity.window.decorView.findViewWithTag<EditText>("sleep_minutes");input.setText("5")
            activity.window.decorView.findViewWithTag<Button>("sleep_set").performClick()
            until{app.sleepWakeJournal.history().isNotEmpty()}
            assertEquals(SleepWakeState.SCHEDULED,app.sleepWakeJournal.history().single().state)
            assertEquals(1,SleepWakeJournal(app.disk,{app.clock}).history().size)
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun alarmSpeechUsesNativeAgentWithoutCreatingAndroidTts(){
        val voice=TtsUnifiedInterface(app)
        try {
            assertTrue(voice.isAvailable())
            assertTrue(voice.speakLocalAlarm("နိုးချိန်ရောက်ပါပြီ"))
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            val intent=Shadows.shadowOf(app).nextStartedService
            assertEquals(com.example.voicebrainlive.VoiceLiveService.ACTION_START,intent.action)
            voice.stop()
            assertFalse(com.example.voicebrainlive.voice.AgentSpeech.isSpeaking)
        } finally { voice.shutdown() }
    }
    @Test fun installedAndroidVoiceIsNotUsedForAgentSpeech(){
        ShadowTextToSpeech.addVoice(Voice("local-my",Locale("my","MM"),300,300,false,emptySet()))
        val voice=TtsUnifiedInterface(app)
        try {
            voice.speak("ဖြည်းဖြည်းလေး အိပ်ရာထကြရအောင်")
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            assertTrue(com.example.voicebrainlive.voice.AgentSpeech.isSpeaking)
        } finally { voice.shutdown() }
    }
}
