package com.example.voicebrainlive.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import android.content.Intent
import android.os.Looper
import android.util.Base64
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.agent.nativeapps.NativeAgentSettings
import com.example.voicebrainlive.security.ShadowSecureSettingsStorage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

internal class RecordedNarrationVoice:NarrationVoice {
    override var onReady:(()->Unit)?=null;override var onAudio:((String)->Unit)?=null
    override var onTurn:(()->Unit)?=null;override var onFailure:(()->Unit)?=null
    var connections=0;var closes=0;val sent=mutableListOf<String>()
    override fun connect(){connections++;onReady?.invoke()};override fun send(text:String){sent.add(text)};override fun close(){closes++}
}
internal class NarrationTestApplication:Application(),NarrationHost {
    val recorded=RecordedNarrationVoice();var notificationPresent=true
    override fun narrationVoice(scope:CoroutineScope)=recorded
    override fun notificationCurrent(a:Announcement)=notificationPresent&&a.key=="source-key"&&a.packageName=="selected.app"
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=NarrationTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentNotificationAudioServiceTest {
    private val app get()=RuntimeEnvironment.getApplication() as NarrationTestApplication
    private var controller:ServiceController<AgentNotificationAudioService>?=null
    @Before fun setup(){
        ShadowSecureSettingsStorage.reset();AgentNotificationAudio.queue.clear()
        SettingsActivity.getSecurePrefs(app).edit().clear().putString(SettingsActivity.KEY_GEMINI_API,"host-only-synthetic-key")
            .putString(SettingsActivity.KEY_GEMINI_MODEL,"models/recorded-test").putStringSet(SettingsActivity.KEY_SELECTED_NOTIFICATION_APPS,setOf("selected.app")).commit()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        Shadows.shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(NativeAgentSettings.setEnabled(app,true,NativeAgentSettings.binding(app)))
    }
    @After fun cleanup(){controller?.destroy();AgentNotificationAudio.queue.clear();ShadowSecureSettingsStorage.reset();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun start():AgentNotificationAudioService {
        val a=AgentNotificationAudio.queue.offer("source-key","selected.app","Test App","Meeting","မြန်မာ စမ်းသပ်စာ",setOf("selected.app"))!!
        return Robolectric.buildService(AgentNotificationAudioService::class.java).create().also{controller=it}
            .get().also{it.onStartCommand(Intent(app,AgentNotificationAudioService::class.java).putExtra("ticket",a.id),0,1)}
    }
    private suspend fun until(done:()->Boolean){withTimeout(20_000){while(!done()){Shadows.shadowOf(Looper.getMainLooper()).idle();delay(10)}}}
    @Test fun recordedAnnouncementUsesNoMicrophoneAndStopsWithoutFollowup()=runBlocking{
        val service=start();until{app.recorded.sent.isNotEmpty()}
        assertEquals(1,app.recorded.connections);assertTrue(app.recorded.sent.single().contains("မြန်မာ စမ်းသပ်စာ"))
        app.recorded.onAudio?.invoke(Base64.encodeToString(ByteArray(2400),Base64.NO_WRAP))
        until{NativeAgentSettings.status(app)=="PLAYBACK_REQUESTED"};app.recorded.onTurn?.invoke()
        service.onStartCommand(Intent().setAction("com.example.voicebrainlive.STOP_NOTIFICATION_AUDIO"),0,2)
        assertEquals("CANCELLED",NativeAgentSettings.status(app));assertEquals(1,app.recorded.closes)
        app.recorded.onAudio?.invoke("late invalid data");Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,app.recorded.sent.size);assertEquals(1,app.recorded.closes)
    }
    @Test fun revocationBeforeDeliveryCannotContactProvider()=runBlocking{
        app.notificationPresent=false;start();Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,app.recorded.connections);assertTrue(app.recorded.sent.isEmpty());assertEquals("REVOKED",NativeAgentSettings.status(app))
    }
    @Test fun providerFailureDoesNotRetryOrReportSpeechSuccess()=runBlocking{
        start();until{app.recorded.sent.isNotEmpty()};app.recorded.onFailure?.invoke();until{app.recorded.closes>0}
        assertEquals("PROVIDER_FAILED",NativeAgentSettings.status(app));assertEquals(1,app.recorded.connections)
    }
    @Test fun noAudioExpiresWithoutPretendingCompletion()=runBlocking{
        start();until{app.recorded.sent.isNotEmpty()};ShadowSystemClock.advanceBy(Duration.ofSeconds(21));until{app.recorded.closes>0}
        assertEquals("NO_AUDIO",NativeAgentSettings.status(app));assertEquals(1,app.recorded.connections)
    }
    @Test fun duplicateProviderReadyCannotSendPrivatePromptAgain()=runBlocking{
        start();until{app.recorded.sent.isNotEmpty()};app.recorded.onReady?.invoke();app.recorded.onReady?.invoke()
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,app.recorded.sent.size)
    }
    @Test fun missingVisibleNotificationBlocksProvider()=runBlocking{
        Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        start();Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,app.recorded.connections);assertTrue(app.recorded.sent.isEmpty());assertEquals("NOTIFICATIONS_UNAVAILABLE",NativeAgentSettings.status(app))
    }
    @Test fun consentRevocationClosesProviderWithoutRetry()=runBlocking{
        start();until{app.recorded.sent.isNotEmpty()};assertTrue(NativeAgentSettings.setEnabled(app,false))
        // PAUSED Robolectric does not advance the Android clock during coroutine wall-clock waits.
        ShadowSystemClock.advanceBy(Duration.ofMillis(600));until{app.recorded.closes>0}
        assertEquals("REVOKED",NativeAgentSettings.status(app));assertEquals(1,app.recorded.connections)
    }
}
