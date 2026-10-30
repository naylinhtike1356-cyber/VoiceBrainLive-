package com.example.voicebrainlive.agent.background

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.connected.*
import androidx.work.OneTimeWorkRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlertDialog

internal class ScheduledRequestTestApp:VoiceBrainApplication(),ScheduledRequestHost,ConnectedWorkspaceHost {
    override val automaticStartupAllowed=false
    val bytes=RequestBytes()
    var now=System.currentTimeMillis()
    override val scheduledRequests=ScheduledRequests(bytes,{now})
    val enqueued=mutableListOf<OneTimeWorkRequest>();val cancelled=mutableListOf<String>()
    override val scheduledRequestQueue=object:AgentWorkQueue {
        override suspend fun enqueue(uniqueName:String,request:OneTimeWorkRequest):Boolean{enqueued.add(request);return true}
        override suspend fun cancelTag(tag:String):Boolean{cancelled.add(tag);return true}
    }
    private val fixture by lazy{ConnectedFixture(this).also{f->
        check(f.settings.saveMapping(ConnectedNotionFixture.mapping,f.settings.notion(),true))
    }}
    override fun connectedWorkspace(current:()->Boolean)=fixture.workspace(current)
    override fun connectedSettings()=fixture.settings
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ScheduledRequestTestApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduledRequestsUiTest {
    private val app get()=RuntimeEnvironment.getApplication() as ScheduledRequestTestApp
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private suspend fun until(test:()->Boolean){withTimeout(15_000){while(!test()){idle();delay(20)}};idle()}
    private fun <T:View> tag(root:View,key:String):T {@Suppress("UNCHECKED_CAST")return requireNotNull(root.findViewWithTag<View>(key)){key} as T}
    private fun content(a:android.app.Activity)=a.findViewById<View>(android.R.id.content)
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog()
    private fun texts(v:View):String=(v as? TextView)?.text?.toString().orEmpty()+if(v is ViewGroup)(0 until v.childCount).joinToString(""){texts(v.getChildAt(it))}else ""
    @Test fun opensMyanmarScreenWithoutCreatingWorkOrNetwork()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();val a=c.get()
        until{texts(content(a)).contains("အလုပ်မရှိသေးပါ")}
        assertTrue(texts(content(a)).contains("အချိန်ဇယား"));assertTrue(app.enqueued.isEmpty());assertNull(app.bytes.value)
        assertEquals(0,a.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE)
        c.pause().stop().destroy()
    }
    @Test fun morningPresetDefaultsOffAndCannotSaveWithoutDataConsent()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();idle()
        tag<Button>(content(c.get()),"requests_notion").performClick();idle();val form=dialog()
        assertEquals(RequestKind.NOTION_BRIEF.ordinal,tag<Spinner>(form.window!!.decorView,"request_kind").selectedItemPosition)
        assertFalse(tag<CheckBox>(form.window!!.decorView,"request_automatic").isChecked)
        assertFalse(tag<CheckBox>(form.window!!.decorView,"request_sharing_consent").isChecked)
        assertTrue(tag<Button>(form.window!!.decorView,"request_time").text.contains("08:00"))
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertSame(form,dialog());assertTrue(form.isShowing);assertNull(app.bytes.value);assertTrue(app.enqueued.isEmpty())
        c.pause().stop().destroy()
    }
    @Test fun notionDataConsentStillNeedsFinalExactReviewBeforeSaving()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();idle()
        tag<Button>(content(c.get()),"requests_notion").performClick();idle();val form=dialog()
        tag<CheckBox>(form.window!!.decorView,"request_sharing_consent").isChecked=true
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();val preview=dialog()
        assertNotSame(form,preview);assertTrue(texts(preview.window!!.decorView).contains(ConnectedNotionFixture.DB))
        assertTrue(texts(preview.window!!.decorView).contains("host-text-model"));assertNull(app.bytes.value)
        preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle()
        assertNull(app.bytes.value);assertTrue(app.enqueued.isEmpty());c.pause().stop().destroy()
    }
    @Test fun exactBackgroundOptInPersistsPolicyAndConstrainedOpaqueWorker()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();idle()
        tag<Button>(content(c.get()),"requests_notion").performClick();idle();val form=dialog()
        tag<CheckBox>(form.window!!.decorView,"request_sharing_consent").isChecked=true
        tag<CheckBox>(form.window!!.decorView,"request_automatic").isChecked=true
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertTrue(texts(dialog().window!!.decorView).contains("ထပ်နှိပ်အတည်ပြုရန် မစောင့်ပါ"))
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until{app.scheduledRequests.history().singleOrNull()?.state==RequestState.SCHEDULED}
        val row=app.scheduledRequests.history().single();assertTrue(row.brief!!.automatic)
        assertEquals(ConnectedNotionFixture.DB,row.brief.databaseId);assertNull(row.briefResult);assertNull(row.geminiAttemptAt)
        val work=app.enqueued.single().workSpec
        assertEquals(androidx.work.NetworkType.CONNECTED,work.constraints.requiredNetworkType)
        assertTrue(work.constraints.requiresBatteryNotLow())
        assertEquals(setOf("request_id","request_revision"),work.input.keyValueMap.keys)
        c.pause().stop().destroy()
    }
    @Test fun aiScheduleNeedsReviewThenPersistsExactGoalWithPrimitiveQueueInput()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();val a=c.get();idle()
        tag<Button>(content(a),"requests_add").performClick();val form=dialog()
        tag<EditText>(form.window!!.decorView,"request_objective").setText("ည ၇ နာရီ စာလုပ်ရန် စီစဉ်ပေး")
        tag<Spinner>(form.window!!.decorView,"request_kind").setSelection(2)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertNull(app.bytes.value);assertTrue(app.enqueued.isEmpty())
        assertTrue(texts(dialog().window!!.decorView).contains("ည ၇ နာရီ"))
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until{app.enqueued.size==1&&texts(content(a)).contains("အချိန်စောင့်နေသည်")}
        val row=app.scheduledRequests.history().single()
        assertEquals("ည ၇ နာရီ စာလုပ်ရန် စီစဉ်ပေး",row.objective)
        val data=app.enqueued.single().workSpec.input
        assertEquals(setOf("request_id","request_revision"),data.keyValueMap.keys)
        assertFalse(data.toString().contains(row.objective))
        c.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup()
        until{texts(content(reopened.get())).contains(row.objective)}
        assertEquals(1,app.scheduledRequests.history().size);reopened.pause().stop().destroy()
    }
    @Test fun changedOrClosedPreviewCannotSchedule()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();val a=c.get();idle()
        tag<Button>(content(a),"requests_add").performClick();val form=dialog()
        tag<EditText>(form.window!!.decorView,"request_objective").setText("မပို့ပါနှင့်")
        tag<Spinner>(form.window!!.decorView,"request_kind").setSelection(2)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();val preview=dialog();c.pause();idle()
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertNull(app.bytes.value);assertTrue(app.enqueued.isEmpty());c.stop().destroy()
    }
    @Test fun dueRequestOpensDraftOnlyAfterFreshTapAndNeverSendsAi()=runBlocking<Unit>{
        val r=app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL,"မြန်မာ 한국어 study plan","","",app.now+60_000))
        app.now=r.dueAt;val q=app.scheduledRequests.due(r.id,r.revision)!!
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();val a=c.get()
        until{content(a).findViewWithTag<View>("requests_review_${q.id}")!=null}
        assertNull(Shadows.shadowOf(a).peekNextStartedActivity())
        tag<Button>(content(a),"requests_review_${q.id}").performClick();idle()
        assertEquals(RequestState.READY,app.scheduledRequests.history().single().state)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until{app.scheduledRequests.history().single().state==RequestState.DRAFT_READY}
        until{Shadows.shadowOf(a).peekNextStartedActivity()!=null}
        val intent=Shadows.shadowOf(a).nextStartedActivity
        assertEquals("com.example.voicebrainlive.agent.connected.ConnectedAgentActivity",intent.component!!.className)
        assertEquals(q.id,intent.getStringExtra("scheduled_draft_id"));assertEquals(1,intent.extras!!.size())
        assertEquals(0,app.enqueued.size);c.pause().stop().destroy()
    }
    @Test fun cancelAndPausePersistWithoutExecutingAnything()=runBlocking<Unit>{
        val r=app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL,"ခဏရပ်ရမည့် အလုပ်","","",app.now+60_000))
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();val a=c.get()
        until{content(a).findViewWithTag<View>("requests_pause_${r.id}")!=null}
        tag<Button>(content(a),"requests_pause_${r.id}").performClick()
        until{app.scheduledRequests.history().single().state==RequestState.PAUSED&&content(a).findViewWithTag<View>("requests_resume_${r.id}")!=null}
        tag<Button>(content(a),"requests_cancel_${r.id}").performClick();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until{app.scheduledRequests.history().single().state==RequestState.CANCELLED}
        assertEquals(2,app.cancelled.size);assertTrue(app.enqueued.isEmpty());c.pause().stop().destroy()
    }
    @Test fun keyFailureShowsMyanmarFailureAndCannotClaimSavedSuccess()=runBlocking<Unit>{
        app.bytes.readable=false
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup()
        until{texts(content(c.get())).contains("လုံခြုံသော သိမ်းဆည်းမှုကို ဖတ်မရပါ")}
        assertNull(app.bytes.value);assertTrue(app.enqueued.isEmpty());c.pause().stop().destroy()
    }
    private fun capture(width:Int,height:Int,name:String)=runBlocking<Unit>{
        RuntimeEnvironment.setFontScale(2f)
        val c=Robolectric.buildActivity(ScheduledRequestsActivity::class.java).setup();until{texts(content(c.get())).contains("အလုပ်မရှိသေးပါ")}
        val root=content(c.get());root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
        val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);c.get().window.decorView.background?.apply{setBounds(0,0,width,height);draw(canvas)};root.draw(canvas)
        val out=java.io.File("build/connected-captures").apply{mkdirs()}
        java.io.File(out,name).outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        val add=tag<Button>(root,"requests_add");assertTrue(add.measuredHeight>=48)
        val visible=android.graphics.Rect();assertTrue(add.getGlobalVisibleRect(visible));assertEquals(add.height,visible.height())
        assertTrue(visible.bottom<=height)
        c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-notnight-mdpi") fun renderMyanmarPhoneAtLargeFont()=capture(360,800,"scheduled-requests-mm-phone.png")
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w1000dp-h700dp-land-notnight-mdpi") fun renderMyanmarTabletAtLargeFont()=capture(1000,700,"scheduled-requests-mm-tablet.png")
}
