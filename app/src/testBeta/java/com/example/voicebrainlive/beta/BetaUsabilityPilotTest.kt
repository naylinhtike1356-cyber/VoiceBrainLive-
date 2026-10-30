package com.example.voicebrainlive.beta

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.Rect
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import javax.crypto.KeyGenerator
import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=BetaHostTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BetaUsabilityPilotTest {
    @Before fun clock(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private val app get()=RuntimeEnvironment.getApplication() as BetaHostTestApplication
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun tag(a:android.app.Activity,t:String)=a.findViewById<View>(android.R.id.content).findViewWithTag<View>(t)
    private fun <T> rejects(block:()->T){assertTrue(runCatching(block).isFailure)}
    private val goal="မြန်မာ 한국어 English task"
    private fun wire(g:String=goal,tool:String="get_current_time",extra:String="")="""{"version":1,"goal":"$g","steps":[{"id":"time","tool":"$tool","dependencies":[],"arguments":{},"expected":"CLOCK"$extra}]}"""
    private fun envelope(w:String=wire(),finish:String="STOP",tokens:Int=21):String {
        val p=JsonObject().apply{addProperty("text",w)}
        val parts=com.google.gson.JsonArray().apply{add(p)}
        val candidate=JsonObject().apply{addProperty("finishReason",finish);add("content",JsonObject().apply{add("parts",parts)})}
        return JsonObject().apply{add("candidates",com.google.gson.JsonArray().apply{add(candidate)});add("usageMetadata",JsonObject().apply{addProperty("totalTokenCount",tokens)})}.toString()
    }
    @Test fun insetsUseSystemAndImeAndNeverAccumulate(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get();BetaUi.installInsets(a)
        val root=a.findViewById<View>(android.R.id.content)
        val i=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(5,32,7,24)).setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,260)).build()
        assertEquals(260,i.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        val bars=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(5,32,7,24)).build()
        ViewCompat.dispatchApplyWindowInsets(root,bars)
        assertEquals(32,root.paddingTop);assertEquals(24,root.paddingBottom)
        // API28 platform WindowInsets cannot retain Builder's synthetic per-type IME map.
        // Exercise the ACTUAL registered compat callback, not a duplicate implementation.
        // Installed core1.17 bytecode inspection is retained with this test checkpoint.
        val wrapper=root.getTag(androidx.core.R.id.tag_on_apply_window_listener)
        assertNotNull(wrapper)
        val callbackField=wrapper.javaClass.declaredFields.single {it.type==androidx.core.view.OnApplyWindowInsetsListener::class.java}
        callbackField.isAccessible=true
        val registered=callbackField.get(wrapper) as androidx.core.view.OnApplyWindowInsetsListener
        repeat(2){registered.onApplyWindowInsets(root,i)}
        assertEquals(32,root.paddingTop);assertEquals(260,root.paddingBottom);assertEquals(5,root.paddingLeft);assertEquals(7,root.paddingRight)
        val small=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,20,0,12)).build()
        ViewCompat.dispatchApplyWindowInsets(root,small);assertEquals(20,root.paddingTop);assertEquals(12,root.paddingBottom)
        c.pause().stop().destroy()
    }
    @Test fun settingsHasShortProviderStateAndRealSetupNotionAndDiagnosticsDestinations(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get()
        assertTrue((tag(a,"beta_provider_status") as TextView).text.length<100)
        listOf("beta_pilot" to BetaPilotActivity::class.java,"beta_notion" to BetaNotionActivity::class.java,"beta_diagnostics" to BetaDiagnosticsActivity::class.java).forEach{(t,type)->
            tag(a,t).performClick();assertEquals(type.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        }
        assertNull(tag(a,"beta_sample_rules"));c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w360dp-h800dp") fun largeFontsHaveScrollableSettingsAndWrappingTouchTargets(){
        RuntimeEnvironment.setFontScale(2f)
        assertEquals(2f,app.resources.configuration.fontScale,0.01f)
        assertEquals(2f,app.resources.displayMetrics.scaledDensity/app.resources.displayMetrics.density,0.01f)
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get()
        val root=a.findViewById<ViewGroup>(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));root.layout(0,0,360,700)
        val b=tag(a,"beta_diagnostics") as Button
        assertTrue(b.minHeight>=BetaUi.dp(a,48));assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,b.layoutParams.height)
        assertTrue((b.parent!!.parent as ScrollView).getChildAt(0).measuredHeight>0)
        c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w1000dp-h700dp-land") fun tabletColumnIsBoundedAndSettingsRemainReachable(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get();val root=a.findViewById<ViewGroup>(android.R.id.content)
        val frame=root.getChildAt(0) as FrameLayout;assertTrue(frame.getChildAt(0).layoutParams.width<=BetaUi.dp(a,760));assertTrue(tag(a,"beta_pilot").isEnabled);c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w640dp-h360dp-land") fun shortScreenControlsUseWeightedScrollingInsteadOfFixedHeight(){
        RuntimeEnvironment.setFontScale(2f)
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get()
        a.findViewById<View>(com.example.voicebrainlive.R.id.navChat).performClick();idle()
        val main=a.supportFragmentManager.findFragmentById(com.example.voicebrainlive.R.id.mainContentFrame) as com.example.voicebrainlive.ui.MainFragment
        main.childFragmentManager.executePendingTransactions();idle()
        val scroll=tag(a,"beta_action_scroll") as ScrollView
        assertEquals(View.GONE,scroll.visibility) // Plan controls no longer consume an empty conversation.
        val root=a.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(280,View.MeasureSpec.EXACTLY));root.layout(0,0,640,280)
        assertTrue(a.findViewById<EditText>(com.example.voicebrainlive.R.id.etMessageInput).measuredHeight>=BetaUi.dp(a,48))
        assertTrue(tag(a,"beta_local_actions").isEnabled)
        c.pause().stop().destroy()
    }
    private fun verifyTheme(mode:String){
        com.example.voicebrainlive.ThemeUtils.setTheme(app,mode)
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle();val a=c.get()
        val foreground=android.util.TypedValue();val surface=android.util.TypedValue()
        a.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface,foreground,true)
        a.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface,surface,true)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground.data,surface.data)>=4.5)
        assertEquals(foreground.data,a.findViewById<TextView>(com.example.voicebrainlive.R.id.tvAppTitle).currentTextColor)
        a.findViewById<View>(com.example.voicebrainlive.R.id.navChat).performClick();idle()
        val main=a.supportFragmentManager.findFragmentById(com.example.voicebrainlive.R.id.mainContentFrame) as com.example.voicebrainlive.ui.MainFragment
        main.childFragmentManager.executePendingTransactions()
        assertTrue(main.childFragmentManager.findFragmentById(com.example.voicebrainlive.R.id.fragmentHost) is BetaConversationFragment)
        assertEquals(foreground.data,a.findViewById<TextView>(com.example.voicebrainlive.R.id.tvNavHome).currentTextColor)
        val input=a.findViewById<EditText>(com.example.voicebrainlive.R.id.etMessageInput)
        assertEquals(foreground.data,input.currentTextColor)
        assertEquals(foreground.data,input.currentHintTextColor)
        assertEquals(surface.data,(input.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        val bubble=a.layoutInflater.inflate(com.example.voicebrainlive.R.layout.item_chat_assistant_message,null)
        assertEquals(foreground.data,bubble.findViewById<TextView>(com.example.voicebrainlive.R.id.tvAssistantMessage).currentTextColor)
        c.pause().stop().destroy()
    }
    @Test fun lightThemeHasReadableHeadersInputsNavigationAndAssistantText(){verifyTheme("light")}
    @Test fun darkThemeHasReadableHeadersInputsNavigationAndAssistantText(){verifyTheme("dark")}
    @Test fun livePilotStartsOffAndDoesNotCollectOrReadExistingKeysOnOpen(){
        val c=Robolectric.buildActivity(BetaPilotActivity::class.java).setup();idle();val a=c.get()
        assertNull(tag(a,"pilot_key"));assertNull(tag(a,"pilot_consent"))
        assertTrue((tag(a,"pilot_status") as TextView).text.contains("OFF"))
        assertFalse(tag(a,"pilot_enable").isEnabled)
        assertTrue(a.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
        c.pause().stop().destroy()
    }
    @Test fun invalidPilotSetupNeverTouchesSecureFactory(){
        var calls=0;val p=BetaPilotSettings{calls++;error("unavailable")}
        rejects{p.save("bad/path","sample-key-not-real",true)};rejects{p.save("test-model","sample-key-not-real",false)};assertEquals(0,calls)
    }
    @Test fun keyFailureDoesNotWriteFallbackPreferences(){
        val p=BetaPilotSettings{error("key unavailable")};rejects{p.save("test-model","sample-key-not-real",true)}
        assertFalse(app.getSharedPreferences("beta_options",0).contains("beta_pilot_key"))
    }
    @Test fun reviewedSetupReadBackCanBeVerifiedWithHostStoragePort(){
        // The injected host port is not the APK encrypted factory. Native Keystore is a separate device check.
        val prefs=app.getSharedPreferences("host-test-pilot-only",0);val p=BetaPilotSettings{prefs}
        assertFalse(p.configured());assertTrue(p.save("fixture-model","host-test-placeholder-key",true));assertTrue(p.configured())
    }
    @Test fun defaultPilotCannotRequestNetwork()=runBlocking<Unit>{
        val failure=runCatching{BetaTextPort("fixture-model",{true}).propose(goal,"DO NOT SEND MEMORY")}.exceptionOrNull() as BrainFailure
        assertEquals("PILOT_LIVE_APPROVAL_REQUIRED",failure.code)
    }
    @Test fun requestContainsOnlyReviewedGoalAndNoToolsOrCredentials(){
        val p=BetaTextProtocol.request("fixture-model",goal);val root=com.google.gson.JsonParser.parseString(p.body).asJsonObject
        assertFalse(root.has("tools"));assertFalse(root.has("toolConfig"));assertTrue(p.body.contains(goal));assertFalse(p.toString().contains(goal))
        assertEquals(1024,root.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt)
    }
    @Test fun validCompletedResponseIsParsedWithUnknownCost(){val out=BetaTextProtocol.response(200,envelope(),goal);assertEquals(goal,BrainProposalSchema.decode(out.json).goal);assertNull(out.reportedCostMicros)}
    @Test fun partialMalformedAndMissingUsageAreRejected(){
        rejects{BetaTextProtocol.response(200,envelope(finish="MAX_TOKENS"),goal)};rejects{BetaTextProtocol.response(200,"{}",goal)};rejects{BetaTextProtocol.response(200,envelope(tokens=0),goal)}
    }
    @Test fun providerErrorsRemainSanitized(){
        for((status,code) in listOf(403 to "PILOT_AUTHENTICATION",429 to "PILOT_RATE_LIMIT_OR_QUOTA",500 to "PILOT_PROVIDER_UNAVAILABLE")){
            val error=runCatching{BetaTextProtocol.response(status,"SECRET exception body",goal)}.exceptionOrNull() as BrainFailure
            assertEquals(code,error.code);assertFalse(error.message!!.contains("SECRET"))
        }
    }
    @Test fun sideEffectUnknownToolAndModelApprovalsCannotEnterPilot(){
        rejects{BetaTextProtocol.response(200,envelope(wire(tool="send_sms")),goal)}
        rejects{BetaTextProtocol.response(200,envelope(wire(tool="shell")),goal)}
        rejects{BetaTextProtocol.response(200,envelope(wire(extra=",\"approvalToken\":\"invented\"")),goal)}
        rejects{BetaTextProtocol.response(200,envelope(wire(g="changed")),goal)}
    }
    @Test fun cancelledAndReplacedPilotCallbacksCannotReturnPlans()=runBlocking<Unit>{
        var owned=true;var calls=0
        val p=BetaTextPort("fixture-model",{owned},BetaTextExchange{calls++;owned=false;200 to envelope()})
        assertTrue(runCatching{p.propose(goal,"")}.isFailure);assertEquals(1,calls)
        val slow=BetaTextPort("fixture-model",{true},BetaTextExchange{delay(500);200 to envelope()},1)
        assertEquals("PILOT_TIMEOUT_NO_RETRY",(runCatching{slow.propose(goal,"")}.exceptionOrNull() as BrainFailure).code)
    }
    @Test fun oneCallBudgetDisallowsUnboundedAutomaticRetry()=runBlocking<Unit>{
        var calls=0;val p=BetaTextPort("fixture-model",{true},BetaTextExchange{calls++;200 to envelope()})
        p.propose(goal,"");assertTrue(runCatching{p.propose(goal,"")}.isFailure);assertEquals(1,calls)
    }
    @Test fun providerProposalUsesCanonicalPreviewSaveExecuteAndRestoreBoundary()=runBlocking<Unit>{
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();var data:ByteArray?=null
        val store=EncryptedBrainStore(object:AtomicMemoryBytes{override fun readOrNull()=data?.copyOf();override fun replaceAtomically(bytes:ByteArray){data=bytes.copyOf()}}){key}
        var effects=0;val reads=object:AgentReadPort{
            override suspend fun clock():AgentObservation{effects++;return AgentObservation(AgentStatus.SUCCEEDED,AgentData.Clock("actual injected local clock"))}
            override suspend fun battery()=error("not selected")
            override suspend fun tasks(connection:AgentConnectionPin)=error("not selected")
        }
        val a=BetaLocalAgent(store,reads,BrainMemoryAccess({false},{emptyList()},System::currentTimeMillis),{true})
        val port=BetaTextPort("fixture-model",{true},BetaTextExchange{200 to envelope()})
        val preview=a.previewTextPilot(goal,port);assertTrue(store.list().isEmpty());assertEquals(0,effects)
        val saved=a.saveReviewed(preview.definition.plan.taskId,preview.definition.digest)
        assertEquals(AgentStatus.SUCCEEDED,a.runReviewed(saved).snapshot.status);assertEquals(1,effects)
        assertEquals(1,store.list().size);assertFalse(data!!.toString(Charsets.UTF_8).contains(goal))
    }
    @Test fun diskSelfTestRunsCanonicalWorkflowsWithoutUserRecords()=runBlocking<Unit>{
        val dir=java.io.File(app.noBackupFilesDir,"host-scratch-self-test")
        val before=app.bytes?.copyOf();val out=BetaSelfTest.run(dir)
        assertEquals(5,out.count{it.startsWith("PASS")});assertFalse(out.any{it.startsWith("FAIL")});assertArrayEquals(before,app.bytes)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
