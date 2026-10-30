package com.example.voicebrainlive.beta

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.fragment.app.FragmentActivity
import com.example.voicebrainlive.*
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.memory.confirmed.*
import com.example.voicebrainlive.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import javax.crypto.KeyGenerator

class BetaUxApplication:BetaApplication(){
    private val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    private var taskBytes:ByteArray?=null
    private var memoryBytes:ByteArray?=null
    private fun port(memory:Boolean)=object:AtomicMemoryBytes{
        override fun readOrNull()=(if(memory)memoryBytes else taskBytes)?.copyOf()
        override fun replaceAtomically(bytes:ByteArray){if(memory)memoryBytes=bytes.copyOf() else taskBytes=bytes.copyOf()}
    }
    override fun betaStore()=EncryptedBrainStore(port(false)){key}
    override val confirmedMemoryStore:ConfirmedMemoryStore by lazy{
        val repo=ConfirmedMemoryRepository(EncryptedConfirmedMemoryPersistence(port(true)){key},System::currentTimeMillis){java.util.UUID.randomUUID().toString()}
        object:ConfirmedMemoryStore{
            override fun list(includeExpired:Boolean)=repo.list(includeExpired)
            override fun save(draft:MemoryDraft,confirmedExactPreview:Boolean)=repo.save(draft,confirmedExactPreview)
            override fun correct(id:String,revision:Long,draft:MemoryDraft,confirmedExactPreview:Boolean)=repo.correct(id,revision,draft,confirmedExactPreview)
            override fun forget(id:String,revision:Long,confirmedExactPreview:Boolean)=repo.forget(id,revision,confirmedExactPreview)
        }
    }
    override fun betaReads()=object:AgentReadPort{
        override suspend fun clock()=AgentObservation(AgentStatus.SUCCEEDED,AgentData.Clock("HOST_SYNTHETIC_TIME"))
        override suspend fun battery()=AgentObservation(AgentStatus.SUCCEEDED,AgentData.Battery(52))
        override suspend fun tasks(connection:AgentConnectionPin)=error("external data not allowed")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=BetaUxApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BetaUxRebuildTest {
    @Before fun frames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private val app get()=RuntimeEnvironment.getApplication() as BetaUxApplication
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))}
    private fun tag(a:Activity,t:String)=a.findViewById<View>(android.R.id.content).findViewWithTag<View>(t)
    private fun secure(a:Activity)=a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0
    private fun children(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{children(v.getChildAt(it))}else emptyList()
    private fun text(a:Activity)=children(a.findViewById(android.R.id.content)).filterIsInstance<TextView>().joinToString("\n"){it.text}
    private suspend fun waitFor(test:()->Boolean){withTimeout(15000){while(!test()){idle();delay(20)}};idle()}
    private suspend fun seed():BrainTask{
        val a=BetaLocalAgent(app.betaStore(),app.betaReads(),BrainMemoryAccess({false},{emptyList()},System::currentTimeMillis),{true})
        val p=a.preview("မြန်မာ 한국어 user task",BetaRecipe.STATUS)
        return a.saveReviewed(p.definition.plan.taskId,p.definition.digest)
    }
    @Test fun unknownWindowsAndProviderStayProtected(){
        assertFalse(BetaCapturePolicy.permits(MainActivity::class.java.name));assertFalse(BetaCapturePolicy.permits("unknown.Window"))
        val c=Robolectric.buildActivity(BetaPilotActivity::class.java).setup();idle()
        assertTrue(secure(c.get()));assertNull(tag(c.get(),"pilot_key"));assertNull(tag(c.get(),"pilot_model"));assertFalse(tag(c.get(),"pilot_enable").isEnabled)
        c.pause().stop().destroy()
    }
    @Test fun homeAllowsCaptureAndReappliesPolicyAfterBackground(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();assertFalse(secure(c.get()))
        c.pause();assertTrue(secure(c.get()));c.resume();idle();assertFalse(secure(c.get()));c.pause().stop().destroy()
    }
    @Test fun taskBoardAllowsCaptureAndHasRealAddDestination(){
        val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();idle();assertFalse(secure(c.get()))
        tag(c.get(),"task_add").performClick();assertEquals(BetaTasksActivity::class.java.name,Shadows.shadowOf(c.get()).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }
    @Test fun chatScreenshotAndBackReturnHome(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get()
        a.findViewById<View>(R.id.navChat).performClick();idle();assertFalse(secure(a))
        a.onBackPressedDispatcher.onBackPressed();idle()
        val main=a.supportFragmentManager.findFragmentById(R.id.mainContentFrame) as MainFragment;main.childFragmentManager.executePendingTransactions()
        assertTrue(main.childFragmentManager.findFragmentById(R.id.fragmentHost) is HomeFragment);c.pause().stop().destroy()
    }
    @Test fun fivePrimaryDestinationsAndHelpAreReachable(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get()
        tag(a,"beta_nav_settings").performClick();assertEquals(SettingsActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        a.findViewById<View>(R.id.navCamera).performClick();assertEquals(ConfirmedMemoryActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        a.findViewById<View>(R.id.navAutomation).performClick();assertEquals(BetaTaskBoardActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        a.findViewById<View>(R.id.btnGuide).performClick();assertEquals(BetaHelpActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }
    @Test fun fourStepTutorialPersistsFinishWithoutCloudSetup(){
        val c=Robolectric.buildActivity(BetaHelpActivity::class.java).setup();idle();val a=c.get()
        assertTrue((tag(a,"tutorial_step") as TextView).text.startsWith("1/4"));assertFalse(secure(a))
        repeat(3){tag(a,"tutorial_next").performClick()};assertTrue((tag(a,"tutorial_step") as TextView).text.startsWith("4/4"))
        tag(a,"tutorial_next").performClick();assertTrue(app.getSharedPreferences("beta_options",0).getBoolean("ux_tutorial_seen",false));assertTrue(a.isFinishing)
        c.pause().stop().destroy()
    }
    @Test fun tutorialSkipAndActualTaskLinkWork(){
        val c=Robolectric.buildActivity(BetaHelpActivity::class.java).setup();idle()
        tag(c.get(),"help_task").performClick();assertEquals(BetaTasksActivity::class.java.name,Shadows.shadowOf(c.get()).nextStartedActivity.component!!.className)
        tag(c.get(),"tutorial_skip").performClick();assertTrue(app.getSharedPreferences("beta_options",0).getBoolean("ux_tutorial_seen",false));c.pause().stop().destroy()
    }
    @Test fun boardReadsPersistedTaskAndRestartsWithoutDuplicateWrites()=runBlocking<Unit>{
        val saved=seed();val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();waitFor{text(c.get()).contains(saved.definition.plan.objective)}
        assertEquals(1,app.betaStore().list().size);tag(c.get(),"task_open_${saved.definition.plan.taskId}").performClick()
        val i=Shadows.shadowOf(c.get()).nextStartedActivity;assertEquals(saved.definition.plan.taskId,i.getStringExtra("review_task_id"));assertEquals(BetaTasksActivity::class.java.name,i.component!!.className)
        c.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();waitFor{text(reopened.get()).contains(saved.definition.plan.objective)}
        assertEquals(1,app.betaStore().list().size);reopened.pause().stop().destroy()
    }
    @Test fun restoredEditorBlocksOldExecutionInsteadOfReusingApproval()=runBlocking<Unit>{
        val saved=seed();val i=android.content.Intent(app,BetaTasksActivity::class.java).putExtra("review_task_id",saved.definition.plan.taskId)
        val c=Robolectric.buildActivity(BetaTasksActivity::class.java,i).setup()
        waitFor{app.betaStore().list().single().state==com.example.voicebrainlive.agent.persistence.PersistentTaskState.BLOCKED}
        assertFalse(tag(c.get(),"beta_run").isEnabled);assertFalse(c.get().intent.hasExtra("review_task_id"));c.pause().stop().destroy()
    }
    @Test fun memoryListRedactsTitleAndEditorRemainsSecure()=runBlocking<Unit>{
        app.confirmedMemoryStore.save(MemoryDraft("PRIVATE_TITLE_SENTINEL","PRIVATE_CONTENT_SENTINEL",ConfirmedMemoryCategory.FACT,ConfirmedMemorySource.USER_ENTERED),true)
        val c=Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup();waitFor{text(c.get()).contains("private title hidden")}
        assertFalse(secure(c.get()));assertFalse(text(c.get()).contains("PRIVATE_TITLE_SENTINEL"));assertFalse(text(c.get()).contains("PRIVATE_CONTENT_SENTINEL"))
        children(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().first{it.text=="View / edit"}.performClick();idle()
        val d=ShadowDialog.getLatestDialog();assertNotNull(d);assertTrue(d.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        d.dismiss();assertFalse(secure(c.get()));c.pause().stop().destroy()
    }
    @Test fun settingsCaptureAllowedButConsentDialogProtected(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();assertFalse(secure(c.get()))
        tag(c.get(),"beta_consent_review").performClick();val d=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(d.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0);d.dismiss();assertFalse(secure(c.get()));c.pause().stop().destroy()
    }
    @Test fun diagnosticsShareRequiresReviewAndCancelSendsNothing(){
        val c=Robolectric.buildActivity(BetaDiagnosticsActivity::class.java).setup();idle();assertFalse(secure(c.get()))
        tag(c.get(),"beta_share_report").performClick();assertNull(Shadows.shadowOf(c.get()).nextStartedActivity)
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);c.pause().stop().destroy()
    }
    @Test fun staleDiagnosticReviewCannotOpenExternalChooser(){
        val c=Robolectric.buildActivity(BetaDiagnosticsActivity::class.java).setup();idle();tag(c.get(),"beta_share_report").performClick()
        val old=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
        c.pause();c.resume();old.performClick();assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);c.pause().stop().destroy()
    }
    @Test fun diagnosticSummaryRejectsPrivateInputInsteadOfEchoingIt(){
        val s=BetaDiagnosticSummary.create("PRIVATE_MEMORY_OR_CHAT","SECRET_TOKEN")
        assertFalse(s.contains("PRIVATE_MEMORY_OR_CHAT"));assertFalse(s.contains("SECRET_TOKEN"));assertTrue(s.contains("UNKNOWN"));assertTrue(s.contains("AI/network/background OFF"))
    }
    @Test fun primaryLabelsSupportThreeUnicodeLanguages(){
        for(code in listOf("my","en","ko")){
            app.getSharedPreferences("beta_options",0).edit().putString("ui_language",code).commit()
            val words=listOf("home","chat","tasks","memory","settings").map{BetaWords.get(app,it)}
            assertEquals(5,words.distinct().size);assertTrue(words.all{it.isNotBlank()&&!it.contains('\uFFFD')})
        }
    }
    private fun layout(scale:Float,width:Int,height:Int){
        RuntimeEnvironment.setFontScale(scale)
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val root=c.get().findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
        assertEquals(width,root.width);assertTrue(children(root).any{it is ScrollView})
        children(root).filterIsInstance<Button>().forEach{assertTrue(it.minimumHeight>=BetaUi.dp(c.get(),48));assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,it.layoutParams.height)}
        assertTrue((tag(c.get(),"beta_diagnostics") as Button).measuredHeight>0);c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w360dp-h800dp") fun phoneAt100Percent(){layout(1f,360,740)}
    @Test @Config(qualifiers="w360dp-h800dp") fun phoneAt150Percent(){layout(1.5f,360,740)}
    @Test @Config(qualifiers="w360dp-h800dp") fun phoneAt200Percent(){layout(2f,360,740)}
    @Test @Config(qualifiers="w1000dp-h700dp-land") fun tabletAt200Percent(){layout(2f,1000,640)}
    @Test fun versionIncreasedAndNetworkStillAbsent(){
        assertEquals(6,BuildConfig.VERSION_CODE);assertEquals("1.2.1-INTERACTIVE-BETA",BuildConfig.VERSION_NAME)
        val permissions=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET));assertFalse(permissions.contains(android.Manifest.permission.SEND_SMS));assertFalse(permissions.contains(android.Manifest.permission.CALL_PHONE))
    }
    /** Host native-graphics raster only; not proof of Samsung rendering or screenshot gestures. */
    private fun capture(a:Activity,name:String,width:Int,height:Int){
        idle();val root=a.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);idle()
        val image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
        val surface=android.util.TypedValue();a.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface,surface,true)
        image.eraseColor(surface.data)
        root.draw(android.graphics.Canvas(image))
        val pixels=IntArray(width*height);image.getPixels(pixels,0,width,0,0,width,height)
        assertTrue("Rendered view must contain more than a blank surface",pixels.toSet().size>10)
        val folder=java.io.File("build/ux-rebuild-captures");folder.mkdirs()
        java.io.File(folder,"$name.png").outputStream().use{assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        image.recycle()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-mdpi") fun renderPhoneSettingsLight(){
        ThemeUtils.setTheme(app,"light");val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();capture(c.get(),"phone-settings-light",360,800);c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-mdpi") fun renderPhoneSettingsDark(){
        ThemeUtils.setTheme(app,"dark");val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();capture(c.get(),"phone-settings-dark",360,800);c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w1000dp-h700dp-land-mdpi") fun renderTabletTaskBoardAt200Percent(){
        RuntimeEnvironment.setFontScale(2f);val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();capture(c.get(),"tablet-tasks-font200",1000,700);c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-mdpi") fun renderPhoneHome(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();capture(c.get(),"phone-home",360,800);c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w640dp-h360dp-land-mdpi") fun renderChatLandscapeAt200Percent(){
        RuntimeEnvironment.setFontScale(2f);val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();c.get().findViewById<View>(R.id.navChat).performClick();idle()
        capture(c.get(),"phone-chat-landscape-font200",640,360);c.pause().stop().destroy()
    }
    private fun onSurface(a:Activity):Int {val v=android.util.TypedValue();a.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface,v,true);return v.data}
    private fun readable(a:Activity,v:TextView){assertTrue("${v.javaClass.simpleName} must contrast against actual theme surface",androidx.core.graphics.ColorUtils.calculateContrast(v.currentTextColor,onSurface(a))>=4.5)}
    private fun nativeSettings(mode:String){
        ThemeUtils.setTheme(app,mode);val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get()
        tag(a,"beta_settings_more").performClick();idle()
        val root=a.findViewById<ViewGroup>(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);idle()
        children(root).filterIsInstance<TextView>().filter{it.isEnabled}.forEach{readable(a,it)}
        val spinner=tag(a,"beta_language") as Spinner
        readable(a,spinner.selectedView as TextView)
        val row=spinner.adapter.getDropDownView(0,null,FrameLayout(a)) as TextView;readable(a,row)
        assertTrue((tag(a,"beta_theme") as Button).background is android.graphics.drawable.RippleDrawable)
        c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="notnight-mdpi") fun nativeControlsDarkRemainReadableWhenSystemIsDay(){nativeSettings("dark")}
    @Test @Config(qualifiers="notnight-mdpi") fun nativeControlsLightHaveReadableTextAndDropdown(){nativeSettings("light")}
    @Test @Config(qualifiers="notnight-mdpi") fun taskBoardNativeTextHasDarkContrast()=runBlocking<Unit>{
        ThemeUtils.setTheme(app,"dark");val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();waitFor{text(c.get()).contains(BetaWords.get(app,"empty"))}
        children(c.get().findViewById(android.R.id.content)).filterIsInstance<TextView>().forEach{readable(c.get(),it)};c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="notnight-mdpi") fun protectedMemoryEditorKeepsReadableNativeInputs()=runBlocking<Unit>{
        ThemeUtils.setTheme(app,"dark");val c=Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup()
        waitFor{children(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().any{it.text=="Add confirmed memory"&&it.isEnabled}}
        children(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().first{it.text=="Add confirmed memory"}.performClick();idle()
        val d=ShadowDialog.getLatestDialog();assertTrue(d.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        children(d.window!!.decorView).filterIsInstance<EditText>().forEach{readable(c.get(),it)}
        d.dismiss();c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-notnight-mdpi") fun renderRedactedMemoryListDark()=runBlocking<Unit>{
        ThemeUtils.setTheme(app,"dark");app.confirmedMemoryStore.save(MemoryDraft("PRIVATE_RENDER_TITLE","PRIVATE_RENDER_CONTENT",ConfirmedMemoryCategory.FACT,ConfirmedMemorySource.USER_ENTERED),true)
        val c=Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup();waitFor{text(c.get()).contains("private title hidden")}
        assertFalse(text(c.get()).contains("PRIVATE_RENDER_TITLE"));assertFalse(text(c.get()).contains("PRIVATE_RENDER_CONTENT"))
        capture(c.get(),"phone-memory-redacted-dark",360,800);c.pause().stop().destroy()
    }
}
