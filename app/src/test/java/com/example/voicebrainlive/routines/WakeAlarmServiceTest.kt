package com.example.voicebrainlive.routines

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.*
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=SleepWakeTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WakeAlarmServiceTest {
    private val app get()=RuntimeEnvironment.getApplication() as SleepWakeTestApplication
    private fun due():SleepWakeEntry {
        val e=app.sleepWakeJournal.add(SleepWakeKind.WAKE,app.clock.wall+60_000,0).first
        app.sleepWakeJournal.clockRequested(e.id);app.clock=app.clock.copy(wall=e.due,elapsed=app.clock.elapsed+60_000)
        app.sleepWakeJournal.claim(e.id);return e
    }
    @Test fun nativeAgentWakeIsStoppedTogetherWithExactClockDismissal()=runBlocking<Unit>{
        val e=due();ShadowTextToSpeech.addVoice(Voice("local-my",Locale("my","MM"),300,300,false,emptySet()))
        Shadows.shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val controller=Robolectric.buildService(WakeAlarmService::class.java).create();val service=controller.get()
        try{
            service.onStartCommand(Intent(app,WakeAlarmService::class.java).putExtra("id",e.id),0,1)
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(WakeAlarmService.active)
            val speech=Shadows.shadowOf(app).nextStartedService
            assertEquals(com.example.voicebrainlive.VoiceLiveService.ACTION_START,speech.action)
            assertTrue(speech.getStringExtra("proactive_message")!!.contains("အိပ်ရာ"))
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            val result=async(Dispatchers.Default){PhoneCommandExecutor(app).executeAsync("stop_wake_alarm",null,null)}
            withTimeout(20_000){while(!result.isCompleted){Shadows.shadowOf(Looper.getMainLooper()).idle();delay(10)}};result.await()
            assertFalse(WakeAlarmService.active);assertFalse(com.example.voicebrainlive.voice.AgentSpeech.isSpeaking)
            assertEquals(SleepWakeState.DISMISSED,app.sleepWakeJournal.history().single().state)
            val native=Shadows.shadowOf(app).nextStartedActivity;assertEquals(SleepWakeGraph.label(e.id),native.getStringExtra(android.provider.AlarmClock.EXTRA_MESSAGE))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(60,TimeUnit.SECONDS);assertFalse(com.example.voicebrainlive.voice.AgentSpeech.isSpeaking)
        }finally{WakeAlarmService.stopOwned();controller.destroy()}
    }
    @Test fun wakeSpeechHasHardTenMinuteBound(){
        val e=due();ShadowTextToSpeech.addVoice(Voice("local-my",Locale("my","MM"),300,300,false,emptySet()))
        Shadows.shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val c=Robolectric.buildService(WakeAlarmService::class.java).create()
        try{c.get().onStartCommand(Intent(app,WakeAlarmService::class.java).putExtra("id",e.id),0,1)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(11,TimeUnit.MINUTES)
            assertFalse(WakeAlarmService.active);assertEquals(SleepWakeState.EXPIRED,app.sleepWakeJournal.history().single().state)
        }finally{WakeAlarmService.stopOwned();c.destroy()}
    }
    @Test fun staleOrScheduledServiceIdCannotStartWakeSpeech(){
        val e=app.sleepWakeJournal.add(SleepWakeKind.WAKE,app.clock.wall+60_000,0).first
        val c=Robolectric.buildService(WakeAlarmService::class.java).create()
        try{c.get().onStartCommand(Intent(app,WakeAlarmService::class.java).putExtra("id",e.id),0,1);assertFalse(WakeAlarmService.active);assertEquals(SleepWakeState.SCHEDULED,app.sleepWakeJournal.history().single().state)}finally{c.destroy()}
    }
}
