package com.example.voicebrainlive.agent.connected

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.brain.LocalUserTaskRecord
import com.example.voicebrainlive.beta.BetaLocalTasks
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ConnectedHostApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectedUiTest {
    private val f get()=(RuntimeEnvironment.getApplication() as ConnectedHostApplication).fixture
    @Before fun clock(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private suspend fun waitFor(condition:()->Boolean){withTimeout(20_000){while(!condition()){idle();delay(20)}};idle()}
    private fun <T:View> tag(a:android.app.Activity,name:String):T {
        val v=a.findViewById<View>(android.R.id.content).findViewWithTag<View>(name)
        assertNotNull("Missing control $name",v)
        @Suppress("UNCHECKED_CAST") return v as T
    }
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog()
    private fun <T:View> dtag(d:AlertDialog,name:String):T {
        val v=d.window!!.decorView.findViewWithTag<View>(name);assertNotNull(name,v)
        @Suppress("UNCHECKED_CAST") return v as T
    }
    private fun children(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{children(v.getChildAt(it))}else emptyList()
    @Test fun openingConnectedWorkspaceDoesNotMakeRequestsOrClaimLiveConnection(){
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        assertTrue(f.http.requests.isEmpty());assertTrue(f.notionCalls.isEmpty());assertNull(f.bytes)
        assertTrue(tag<TextView>(a,"connected_status").text.contains("မပို့မီ ပြန်စစ်ရန်"))
        assertEquals(View.GONE,tag<View>(a,"connected_plan_preview").parent.let{it as View}.visibility)
        tag<View>(a,"connected_menu").performClick();dialog().listView.performItemClick(null,0,0)
        assertEquals(ConnectedSettingsActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }
    @Test fun oneReviewedTextSendShowsActualProviderResponseWithoutToolEffects()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<EditText>(a,"connected_composer").setText("မြန်မာ 한국어 hello")
        tag<View>(a,"connected_send").performClick();idle()
        assertTrue(f.http.requests.isEmpty());assertTrue(dialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{tag<TextView>(a,"connected_status").text.contains("Gemini အဖြေ ရပြီးပါပြီ")}
        assertEquals(1,f.http.requests.size);assertEquals(0,f.reads.get());assertTrue(f.store.list().isEmpty())
        c.pause().stop().destroy()
    }
    @Test fun changingComposerAfterReviewPreventsOldTextTransmission(){
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<EditText>(a,"connected_composer").setText("old text");tag<View>(a,"connected_send").performClick();val d=dialog()
        tag<EditText>(a,"connected_composer").setText("new text");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertTrue(f.http.requests.isEmpty());assertTrue(tag<TextView>(a,"connected_status").text.contains("စာသား သို့မဟုတ် ပုံစံ ပြောင်းသွားပါပြီ"))
        c.pause().stop().destroy()
    }
    @Test fun leavingCancelsProviderAndLateCallbackCannotReopenResults()=runBlocking<Unit>{
        f.http.responder={};val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<EditText>(a,"connected_composer").setText("private temporary text");tag<View>(a,"connected_send").performClick();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{f.http.calls.size==1};c.pause();idle();waitFor{f.http.calls.single().cancelled}
        assertEquals("",tag<EditText>(a,"connected_composer").text.toString())
        c.resume();idle();f.http.calls.single().respond(200,connectedEnvelope("STALE PRIVATE RESPONSE"));idle()
        assertFalse(tag<TextView>(a,"connected_status").text.contains("အဖြေ ရပြီးပါပြီ"));assertTrue(f.store.list().isEmpty())
        c.pause().stop().destroy()
    }
    @Test fun planUiRequiresPreviewThenVerifiedSaveThenRun()=runBlocking<Unit>{
        val goal="Read the device time";f.http.responder={it.respond(200,connectedEnvelope(connectedPlan(goal)))}
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<RadioButton>(a,"connected_plan_mode").performClick();tag<EditText>(a,"connected_composer").setText(goal)
        tag<View>(a,"connected_send").performClick();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{tag<View>(a,"connected_save_plan").isEnabled&&tag<View>(a,"connected_save_plan").visibility==View.VISIBLE}
        assertTrue(f.store.list().isEmpty());assertEquals(0,f.reads.get())
        tag<View>(a,"connected_save_plan").performClick();waitFor{f.store.list().size==1&&tag<View>(a,"connected_run_plan").isEnabled}
        assertEquals(0,f.reads.get());tag<View>(a,"connected_run_plan").performClick()
        waitFor{f.store.list().single().snapshot.status==AgentStatus.SUCCEEDED&&tag<View>(a,"connected_stop").visibility==View.GONE}
        assertEquals(1,f.reads.get());assertFalse(tag<View>(a,"connected_run_plan").isEnabled)
        c.pause().stop().destroy()
    }
    @Test fun existingCredentialsStayMaskedAndSecretInputIsDroppedOnPause(){
        val c=Robolectric.buildActivity(ConnectedSettingsActivity::class.java).setup();idle();val a=c.get()
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        assertTrue(children(a.findViewById(android.R.id.content)).filterIsInstance<TextView>().none{it.text.contains("host-connected-gemini-key")||it.text.contains("host-connected-notion-token")})
        tag<View>(a,"connected_key").performClick();idle();val d=dialog();val input=dtag<EditText>(d,"connected_key_input")
        assertTrue(input.text.isEmpty());assertFalse(input.isSaveEnabled);assertFalse(input.isSaveFromParentEnabled)
        input.setText("host-replacement-key");c.pause();idle()
        assertFalse(d.isShowing);assertTrue(input.text.isEmpty());assertEquals("host-connected-gemini-key",f.prefs.getString(com.example.voicebrainlive.SettingsActivity.KEY_GEMINI_API,null))
        c.stop().destroy()
    }
    @Test fun modelSettingPersistsWithoutChangingExistingVoiceModel()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ConnectedSettingsActivity::class.java).setup();idle();val a=c.get()
        tag<View>(a,"connected_model").performClick();idle();val d=dialog()
        dtag<EditText>(d,"connected_model_input").setText("host-new-text-model");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{f.settings.textModel()=="host-new-text-model"}
        assertEquals("models/existing-live-model",f.prefs.getString(com.example.voicebrainlive.SettingsActivity.KEY_GEMINI_MODEL,null))
        assertTrue(f.http.requests.isEmpty());c.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(ConnectedSettingsActivity::class.java).setup();idle()
        assertTrue(tag<TextView>(reopened.get(),"connected_settings_status").text.contains("host-new-text-model"));reopened.pause().stop().destroy()
    }
    @Test fun notionReadNeedsReviewAndNeverBecomesAnAiRequest()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<View>(a,"connected_menu").performClick();dialog().listView.performItemClick(null,1,1);idle()
        assertTrue(f.notionCalls.isEmpty());dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{f.notionCalls.size==2&&tag<View>(a,"connected_stop").visibility==View.GONE}
        assertEquals(listOf(LegacyReadOperation.SCHEMA,LegacyReadOperation.QUERY),f.notionCalls.map{it.first})
        assertTrue(f.http.requests.isEmpty());assertTrue(f.store.list().isEmpty())
        c.pause().stop().destroy()
    }
    @Test fun businessCompletionMappingRequiresRealSchemaReadAndUserConfirmation()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<View>(a,"connected_menu").performClick();dialog().listView.performItemClick(null,3,3);idle();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{f.notionCalls.size==1&&runCatching{dtag<CheckBox>(dialog(),"connected_mapping_consent")}.isSuccess}
        val d=dialog();val consent=dtag<CheckBox>(d,"connected_mapping_consent")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertNull(f.settings.mapping(ConnectedNotionFixture.DB))
        children(d.window!!.decorView).filterIsInstance<CheckBox>().single{it.text=="Todo"}.isChecked=true;consent.isChecked=true
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();waitFor{f.settings.mapping(ConnectedNotionFixture.DB)!=null}
        assertEquals("business",f.settings.mapping(ConnectedNotionFixture.DB)!!.businessStatusId)
        assertEquals(1,f.notionCalls.size);assertTrue(f.http.requests.isEmpty());c.pause().stop().destroy()
    }
    @Test fun localTaskCreateEditDoneCancelAndRestartUseActualCanonicalPersistence()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ConnectedTasksActivity::class.java).setup();idle();val a=c.get()
        waitFor{tag<TextView>(a,"connected_tasks_status").text.contains("အလုပ် မရှိသေးပါ")}
        tag<View>(a,"connected_add_task").performClick();idle();var d=dialog()
        dtag<EditText>(d,"connected_task_title").setText("ပထမအလုပ်");dtag<EditText>(d,"connected_task_notes").setText("local details")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();waitFor{f.store.list().size==1&&tag<TextView>(a,"connected_tasks_status").text.contains("သိမ်းထားသော မှတ်တမ်း")}
        assertFalse(f.bytes!!.decodeToString().contains("ပထမအလုပ်"));val first=f.store.list().single()
        tag<View>(a,"connected_edit_${first.definition.plan.taskId}").performClick();idle();d=dialog()
        dtag<EditText>(d,"connected_task_title").setText("ပြင်ထားသောအလုပ်");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{f.store.list().size==2&&tag<TextView>(a,"connected_tasks_status").text.contains("သိမ်းထားသော မှတ်တမ်း")}
        val edited=f.store.list().single{BetaLocalTasks.title(it)=="ပြင်ထားသောအလုပ်"}
        tag<View>(a,"connected_done_${edited.definition.plan.taskId}").performClick();waitFor{f.store.list().single{it.definition.plan.taskId==edited.definition.plan.taskId}.snapshot.status==AgentStatus.REPORTED}
        c.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(ConnectedTasksActivity::class.java).setup();idle();val b=reopened.get()
        waitFor{tag<TextView>(b,"connected_tasks_status").text.contains("သိမ်းထားသော မှတ်တမ်း")}
        assertEquals(AgentStatus.REPORTED,f.store.list().single{it.definition.plan.taskId==edited.definition.plan.taskId}.snapshot.status)
        tag<View>(b,"connected_add_task").performClick();idle();d=dialog();dtag<EditText>(d,"connected_task_title").setText("Cancel me")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();waitFor{f.store.list().size==3&&tag<TextView>(b,"connected_tasks_status").text.contains("သိမ်းထားသော မှတ်တမ်း")}
        val cancel=f.store.list().single{BetaLocalTasks.title(it)=="Cancel me"}
        val previousDialog=dialog()
        assertTrue(tag<View>(b,"connected_cancel_${cancel.definition.plan.taskId}").performClick())
        println("Cancellation fixture before idle: previous=${dialog()===previousDialog}; positive=${dialog().getButton(AlertDialog.BUTTON_POSITIVE).text}")
        waitFor{dialog()!==previousDialog&&dialog().isShowing&&dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString()=="အလုပ် ပယ်ဖျက်ရန်"}
        val confirmation=dialog();assertNotSame(previousDialog,confirmation)
        println("Cancellation fixture ready: showing=${confirmation.isShowing}; positive=${confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text}")
        assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
        try{waitFor{f.store.list().single{it.definition.plan.taskId==cancel.definition.plan.taskId}.snapshot.status==AgentStatus.CANCELLED}}
        catch(e:TimeoutCancellationException){
            val ui=tag<TextView>(b,"connected_tasks_status").text
            val saved=f.store.list().single{it.definition.plan.taskId==cancel.definition.plan.taskId}.snapshot.status
            throw AssertionError("Cancel did not persist; UI=$ui; saved=$saved; dialog=${dialog().isShowing}",e)
        }
        assertEquals(0,f.reads.get());assertTrue(f.http.requests.isEmpty());assertTrue(f.notionCalls.isEmpty())
        assertTrue(f.store.list().all(LocalUserTaskRecord::matches));reopened.pause().stop().destroy()
    }
    @Test fun dismissedOldCancellationDialogCannotChangeAStoredTask()=runBlocking<Unit>{
        val task=f.workspace().localTasks().create("Keep this task","host fixture")
        val c=Robolectric.buildActivity(ConnectedTasksActivity::class.java).setup();idle();val a=c.get()
        waitFor{tag<TextView>(a,"connected_tasks_status").text.contains("သိမ်းထားသော မှတ်တမ်း")}
        assertTrue(tag<View>(a,"connected_cancel_${task.definition.plan.taskId}").performClick());idle()
        val old=dialog();assertTrue(old.isShowing);val positive=old.getButton(AlertDialog.BUTTON_POSITIVE)
        c.pause().stop().destroy();assertFalse(old.isShowing)
        positive.performClick();idle()
        assertEquals(AgentStatus.DRAFT,f.store.list().single().snapshot.status)
        assertTrue(f.http.requests.isEmpty());assertTrue(f.notionCalls.isEmpty());assertEquals(0,f.reads.get())
    }
    @Test fun providerTimeoutShowsFailureAndReleasesTheForegroundControls()=runBlocking<Unit>{
        f.notionReply={_,_->runBlocking<LegacyHttpReply>{withTimeout(1){awaitCancellation()}}}
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        tag<View>(a,"connected_menu").performClick();dialog().listView.performItemClick(null,1,1);idle()
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick())
        waitFor{tag<TextView>(a,"connected_status").text.contains("စောင့်ဆိုင်းချိန် ကုန်သွားပါပြီ")&&tag<Button>(a,"connected_send").isEnabled}
        assertEquals(View.GONE,tag<View>(a,"connected_stop").visibility)
        assertEquals(1,f.notionCalls.size);assertTrue(f.http.requests.isEmpty());assertTrue(f.store.list().isEmpty())
        assertFalse(tag<TextView>(a,"connected_status").text.contains("အဖြေ ရပြီးပါပြီ"));c.pause().stop().destroy()
    }
    @Test @Config(qualifiers="w1000dp-h700dp-land-mdpi") fun largeFontsKeepComposerAndSendUsableOnTablet(){
        RuntimeEnvironment.setFontScale(2f)
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle();val a=c.get()
        val root=a.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));root.layout(0,0,1000,600)
        val input=tag<EditText>(a,"connected_composer");val send=tag<Button>(a,"connected_send")
        assertTrue(input.measuredHeight>=ConnectedUi.dp(a,48));assertTrue(send.measuredHeight>=ConnectedUi.dp(a,48))
        assertTrue(input.measuredWidth>0);assertTrue(send.measuredWidth>0);assertTrue(input.isEnabled);assertTrue(send.isEnabled)
        c.pause().stop().destroy()
    }
    private fun render(a:android.app.Activity,width:Int,height:Int,name:String){
        val root=a.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
        val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap)
        val background=requireNotNull(a.window.decorView.background)
        background.setBounds(0,0,width,height);background.draw(canvas)
        root.draw(canvas)
        val color=android.util.TypedValue();assertTrue(a.theme.resolveAttribute(android.R.attr.colorBackground,color,true))
        val text=tag<TextView>(a,"connected_status")
        assertTrue("Connected theme text/window contrast",androidx.core.graphics.ColorUtils.calculateContrast(text.currentTextColor,color.data)>=4.5)
        val folder=java.io.File("build/connected-captures").apply{mkdirs()}
        java.io.File(folder,name).outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-notnight-mdpi") fun renderPhoneChatWithNoCredentialsOrNetwork(){
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle()
        render(c.get(),360,800,"connected-phone-chat.png")
        assertTrue(f.http.requests.isEmpty());assertTrue(f.notionCalls.isEmpty())
        c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w1000dp-h700dp-land-notnight-mdpi") fun renderTabletChatAtTwoHundredPercentFont(){
        RuntimeEnvironment.setFontScale(2f)
        val c=Robolectric.buildActivity(ConnectedAgentActivity::class.java).setup();idle()
        render(c.get(),1000,700,"connected-tablet-chat-font200.png")
        assertTrue(f.http.requests.isEmpty());assertTrue(f.notionCalls.isEmpty())
        c.pause().stop().destroy()
    }
}
