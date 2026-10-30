package com.example.voicebrainlive.beta

import android.Manifest
import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.compose.ui.platform.ComposeView
import com.example.voicebrainlive.*
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.agent.persistence.*
import com.example.voicebrainlive.memory.confirmed.*
import com.example.voicebrainlive.ui.*
import com.example.voicebrainlive.updates.AppUpdateActivity
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.time.Duration
import org.robolectric.shadows.ShadowChoreographer
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

class BetaHostTestApplication:BetaApplication(){
    var bytes:ByteArray?=null
    private val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    private val store by lazy {EncryptedBrainStore(object:AtomicMemoryBytes{
        override fun readOrNull()=bytes?.copyOf()
        override fun replaceAtomically(bytes:ByteArray){this@BetaHostTestApplication.bytes=bytes.copyOf()}
    }){key}}
    override fun betaStore()=store
    override fun betaReads()=object:AgentReadPort{
        override suspend fun clock()=AgentObservation(AgentStatus.SUCCEEDED,AgentData.Clock("HOST_ACTUAL_PORT"))
        override suspend fun battery()=AgentObservation(AgentStatus.SUCCEEDED,AgentData.Battery(91))
        override suspend fun tasks(connection:AgentConnectionPin)=error("must use local goal store")
    }
    var lastProgress:((BrainTask)->Unit)?=null
    override fun localAgent(owner:()->Boolean,progress:(BrainTask)->Unit):BetaLocalAgent {lastProgress=progress;return super.localAgent(owner,progress)}
    val fakeSpeech=FakeSpeech()
    override fun speech(activity:BetaMainActivity)=fakeSpeech
    class FakeSpeech:BetaSpeechPort{
        var supported=true;var starts=0;var stops=0;var emit:((String)->Unit)?=null;var language:String?=null
        override fun available()=supported
        override fun start(language:String,text:(String)->Unit,state:(String)->Unit){starts++;this.language=language;emit=text;state("Listening on device")}
        override fun cancel(){stops++}
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=BetaHostTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BetaNavigationTest{
    @Before fun boundedVsyncClock(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private val app get()=RuntimeEnvironment.getApplication() as BetaHostTestApplication
    private fun <T:View> tagged(a:android.app.Activity,tag:String):T=a.findViewById<View>(android.R.id.content).findViewWithTag(tag)
    private suspend fun waitFor(check:()->Boolean){withTimeout(15000){while(!check()){shadowOf(Looper.getMainLooper()).idle();delay(20)}};shadowOf(Looper.getMainLooper()).idle()}
    private fun idle(){shadowOf(Looper.getMainLooper()).idle();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))}
    private fun chat():org.robolectric.android.controller.ActivityController<BetaMainActivity>{
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle()
        c.get().findViewById<View>(R.id.navChat).performClick();idle()
        tagged<View>(c.get(),"beta_local_actions").performClick()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0);idle()
        return c
    }
    @Test fun betaHasSeparateIdentityAndNoEmbeddedProviderKeys(){
        assertEquals("com.example.voicebrainlive.interactivebeta",app.packageName);assertEquals("VoiceBrainLive BETA",app.getString(R.string.app_name));assertEquals("beta",BuildConfig.BUILD_TYPE)
        listOf(BuildConfig.GEMINI_API_KEY,BuildConfig.OPENAI_API_KEY,BuildConfig.YOUTUBE_API_KEY,BuildConfig.SERPER_API_KEY,BuildConfig.SPOTIFY_CLIENT_ID,BuildConfig.UPDATE_REPOSITORY).forEach{assertEquals("",it)}
    }
    @Test fun onlyOptionalMicAndOwnSignaturePermissionAreRequested(){
        val p=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        assertEquals(setOf(Manifest.permission.RECORD_AUDIO,app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),p)
        assertFalse(p.contains(Manifest.permission.INTERNET));assertFalse(p.contains(Manifest.permission.CALL_PHONE));assertFalse(p.contains(Manifest.permission.SEND_SMS))
    }
    @Test fun graphHasNoServicesProvidersReceiversOrProductionLauncher(){
        val p=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
        assertTrue(p.services.isNullOrEmpty());assertTrue(p.receivers.isNullOrEmpty());assertTrue(p.providers.isNullOrEmpty())
        assertEquals(listOf(BetaMainActivity::class.java.name),p.activities!!.filter{it.exported}.map{it.name});assertFalse(p.activities!!.any{it.name==MainActivity::class.java.name})
    }
    @Test fun firstRunUsesActualMainAndHomeDashboardWithNoMicrophoneOrQueue(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get()
        val main=a.supportFragmentManager.findFragmentById(R.id.mainContentFrame) as MainFragment;main.childFragmentManager.executePendingTransactions()
        assertTrue(main.childFragmentManager.findFragmentById(R.id.fragmentHost) is HomeFragment)
        val home=main.childFragmentManager.findFragmentById(R.id.fragmentHost)!!.view
        assertTrue(home is ComposeView);assertTrue((home as ComposeView).childCount>0)
        assertEquals(0,app.fakeSpeech.starts);assertNull(app.bytes);assertFalse(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        c.pause().stop().destroy()
    }
    @Test fun realNavigationOpensConversationTaskInboxAndSettings(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get()
        a.findViewById<View>(R.id.navChat).performClick();idle();val main=a.supportFragmentManager.findFragmentById(R.id.mainContentFrame) as MainFragment
        assertTrue(main.childFragmentManager.findFragmentById(R.id.fragmentHost) is BetaConversationFragment)
        a.findViewById<View>(R.id.navAutomation).performClick();assertEquals(BetaTaskBoardActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        a.findViewById<View>(R.id.navCamera).performClick();assertEquals(ConfirmedMemoryActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        a.findViewById<View>(R.id.btnSettings).performClick();assertEquals(SettingsActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w900dp-h600dp-land") fun tabletLandscapeKeepsActualNavigation(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get();assertTrue(a.resources.configuration.screenWidthDp>=900)
        assertTrue(a.findViewById<View>(R.id.navHome).isEnabled);assertTrue(a.findViewById<View>(R.id.navChat).isEnabled);c.pause().stop().destroy()
    }
    @Test fun originalSettingsShowsFunctionalBetaSubsetWithoutCredentialsOrSwitches(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get()
        assertTrue(tagged<TextView>(a,"beta_provider_status").text.contains("OFF / UNAVAILABLE"))
        assertNull(a.findViewById<View>(R.id.etGeminiApiKey));assertNull(a.findViewById<View>(R.id.switchWakeWord))
        a.findViewById<Button>(R.id.btnAgentTasks).performClick();assertEquals(BetaTaskBoardActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        a.findViewById<Button>(R.id.btnAppUpdates).performClick();assertEquals(AppUpdateActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }
    @Test fun languageSettingPersistsWithoutEnablingProvider(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get();tagged<Spinner>(a,"beta_language").setSelection(1);idle()
        assertEquals("ko-KR",app.getSharedPreferences("beta_options",0).getString("speech_language",null));assertEquals(0,app.fakeSpeech.starts);c.pause().stop().destroy()
    }
    @Test fun staleConsentDialogCannotEnablePersonalization(){
        ConfirmedMemoryContextConsent.setAfterReview(app,false)
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get();tagged<Button>(a,"beta_consent_review").performClick()
        val d=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();val old=d.getButton(AlertDialog.BUTTON_POSITIVE);c.pause();c.resume();old.performClick();idle()
        assertFalse(ConfirmedMemoryContextConsent.isEnabled(app));c.pause().stop().destroy()
    }
    @Test fun unsupportedOnDeviceSpeechDoesNotAskPermissionOrFakeResponse(){
        app.fakeSpeech.supported=false;val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();c.get().requestMic();assertEquals(0,app.fakeSpeech.starts);assertNull(c.get().takePendingGoal());c.pause().stop().destroy()
    }
    @Test fun micPermissionReviewDoesNotStartCaptureWithoutGrant(){
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();c.get().requestMic();val d=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(d.isShowing);assertEquals(0,app.fakeSpeech.starts);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(0,app.fakeSpeech.starts);c.pause().stop().destroy()
    }
    @Test fun grantedMicStillRequiresExplicitReviewAndStopsOnPause(){
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get();assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(a,Manifest.permission.RECORD_AUDIO));assertEquals(0,app.fakeSpeech.starts)
        a.requestMic();org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertEquals(1,app.fakeSpeech.starts);c.pause();assertTrue(app.fakeSpeech.stops>0);app.fakeSpeech.emit?.invoke("STALE speech")
        assertNull(a.takePendingGoal());c.stop().destroy()
    }
    @Test fun staleMicReviewCannotStartAfterBackground(){
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get();a.requestMic();val button=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
        c.pause();c.resume();button.performClick();idle();assertEquals(0,app.fakeSpeech.starts);c.pause().stop().destroy()
    }
    @Test fun typedGoalPreviewSaveAndRealLocalExecutionWorkInChatUi()=runBlocking<Unit>{
        val c=chat();idle();val a=c.get();a.findViewById<EditText>(R.id.etMessageInput).setText("မြန်မာ 한국어 local goal")
        a.findViewById<View>(R.id.btnSendMessage).performClick();waitFor{tagged<Button>(a,"beta_save").isEnabled};assertNull(app.bytes)
        tagged<Button>(a,"beta_save").performClick();waitFor{tagged<Button>(a,"beta_run").isEnabled};assertEquals(1,app.betaStore().list().size)
        tagged<Button>(a,"beta_run").performClick();waitFor{app.betaStore().list().single().snapshot.status==AgentStatus.SUCCEEDED}
        assertTrue(BetaLocalAgent.describe(app.betaStore().list().single()).contains("HOST_ACTUAL_PORT"));assertFalse(tagged<Button>(a,"beta_run").isEnabled);c.pause().stop().destroy()
    }
    @Test fun changingGoalInvalidatesExactPreviewAndRun()=runBlocking<Unit>{
        val c=chat();idle();val a=c.get();val input=a.findViewById<EditText>(R.id.etMessageInput);input.setText("first")
        a.findViewById<View>(R.id.btnSendMessage).performClick();waitFor{tagged<Button>(a,"beta_save").isEnabled};input.setText("changed")
        assertFalse(tagged<Button>(a,"beta_save").isEnabled);assertFalse(tagged<Button>(a,"beta_run").isEnabled);assertNull(app.bytes);c.pause().stop().destroy()
    }
    @Test fun pauseDropsUnsavedInputAndRevokesRamPreview()=runBlocking<Unit>{
        val c=chat();idle();val a=c.get();val input=a.findViewById<EditText>(R.id.etMessageInput);input.setText("PRIVATE goal")
        a.findViewById<View>(R.id.btnSendMessage).performClick();waitFor{tagged<Button>(a,"beta_save").isEnabled};c.pause();idle();assertEquals("",input.text.toString());assertNull(app.bytes)
        c.resume();assertFalse(tagged<Button>(a,"beta_run").isEnabled);c.pause().stop().destroy()
    }
    @Test fun progressAfterDestroyedViewIsDiscarded()=runBlocking<Unit>{
        val c=chat();idle()
        val late=app.lastProgress!!
        val task=BetaLocalAgent(app.betaStore(),app.betaReads(),BrainMemoryAccess({false},{emptyList()},System::currentTimeMillis),{true}).preview("late local progress",BetaRecipe.STATUS)
        c.pause().stop().destroy();late(task);idle();assertNull(app.bytes)
    }
    @Test fun originalAboutShowsBetaVersionAndNotConfigured(){
        val c=Robolectric.buildActivity(AppUpdateActivity::class.java).setup();val a=c.get();assertTrue(a.findViewById<TextView>(R.id.tvInstalledVersion).text.contains("INTERACTIVE-BETA"));assertTrue(a.findViewById<TextView>(R.id.tvUpdateStatus).text.contains("NOT CONFIGURED"));c.pause().stop().destroy()
    }
}
