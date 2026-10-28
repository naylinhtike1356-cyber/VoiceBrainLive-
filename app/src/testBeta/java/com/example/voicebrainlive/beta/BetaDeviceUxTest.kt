package com.example.voicebrainlive.beta

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.*
import android.widget.*
import com.example.voicebrainlive.*
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import com.example.voicebrainlive.agent.proactive.*
import com.example.voicebrainlive.memory.confirmed.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import java.io.File
import java.time.Duration
import javax.crypto.KeyGenerator

/** Actual production AtomicFile/encryption/codec with host-only AES key, never native Keystore proof. */
class BetaDeviceTestApplication:BetaApplication(){
    val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    val taskFile get()=File(noBackupFilesDir,"device-ux-tests/tasks.enc")
    val memoryFile get()=File(noBackupFilesDir,"device-ux-tests/memory.enc")
    var reads=0
    val voice=BetaHostTestApplication.FakeSpeech()
    override fun speech(activity:BetaMainActivity)=voice
    override fun betaStore()=EncryptedBrainStore(AndroidAtomicMemoryBytes(taskFile){}){key}
    private fun repo()=ConfirmedMemoryRepository(EncryptedConfirmedMemoryPersistence(AndroidAtomicMemoryBytes(memoryFile){}){key},System::currentTimeMillis){java.util.UUID.randomUUID().toString()}
    override val confirmedMemoryStore:ConfirmedMemoryStore by lazy{
        val r=repo()
        object:ConfirmedMemoryStore{
            override fun list(includeExpired:Boolean)=r.list(includeExpired)
            override fun save(draft:MemoryDraft,confirmedExactPreview:Boolean)=r.save(draft,confirmedExactPreview)
            override fun correct(id:String,revision:Long,draft:MemoryDraft,confirmedExactPreview:Boolean)=r.correct(id,revision,draft,confirmedExactPreview)
            override fun forget(id:String,revision:Long,confirmedExactPreview:Boolean)=r.forget(id,revision,confirmedExactPreview)
        }
    }
    override fun betaReads()=object:AgentReadPort{
        override suspend fun clock():AgentObservation{reads++;return AgentObservation(AgentStatus.SUCCEEDED,AgentData.Clock("HOST_CLOCK_ONLY"))}
        override suspend fun battery():AgentObservation{reads++;return AgentObservation(AgentStatus.SUCCEEDED,AgentData.Battery(61))}
        override suspend fun tasks(connection:AgentConnectionPin)=error("No external connector")
    }
    override val proactiveRules=object:ProactiveRules{
        override suspend fun history()=ProactiveSnapshot()
        override suspend fun createDisabled(config:DailySimulationConfig):ProactiveRule=error("No rule activation")
        override suspend fun updateDisabled(id:String,revision:Long,config:DailySimulationConfig):ProactiveRule=error("No rule activation")
        override suspend fun enableReviewed(id:String,revision:Long):ProactiveRule=error("No rule activation")
        override suspend fun pause(id:String,revision:Long):ProactiveRule=error("No rule activation")
        override suspend fun delete(id:String,revision:Long):ProactiveRule=error("No rule activation")
        override suspend fun evaluateSynthetic(id:String,revision:Long,stateRevision:Long,signal:ClosedSyntheticSignal):EvaluationView=error("No rule activation")
    }
}

/** Host platform workaround: genuine configuration-driven destruction/restoration, never authorization. */
private fun <T:Activity> configurationRecreate(c:org.robolectric.android.controller.ActivityController<T>){
    val previous=c.get()
    val config=android.content.res.Configuration(previous.resources.configuration).apply{fontScale=if(fontScale==1f)1.01f else 1f}
    c.configurationChange(config).visible()
    check(c.get()!==previous){"Host lifecycle must construct a replacement Activity"}
}

@Implements(Activity::class)
class BetaConfigurationRecreateShadow:ShadowActivity(){
    var requests=0
    @Implementation override fun recreate(){
        requests++
        val c=org.robolectric.util.ReflectionHelpers.getField<org.robolectric.android.controller.ActivityController<Activity>>(this,"controller")
        configurationRecreate(c)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=BetaDeviceTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BetaDeviceUxTest {
    private val app get()=RuntimeEnvironment.getApplication() as BetaDeviceTestApplication
    @Before fun setup(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));app.getSharedPreferences("beta_options",0).edit().putString("ui_language","en").commit()}
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))}
    private suspend fun until(test:()->Boolean){withTimeout(20000){while(!test()){idle();delay(20)}};idle()}
    private fun tag(a:Activity,key:String)=a.findViewById<View>(android.R.id.content).findViewWithTag<View>(key)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun texts(a:Activity)=all(a.findViewById(android.R.id.content)).filterIsInstance<TextView>().joinToString("\n"){it.text}
    private fun secure(a:Activity)=a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0
    private fun chat():org.robolectric.android.controller.ActivityController<BetaMainActivity>{
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup().visible();idle();c.get().findViewById<View>(R.id.navChat).performClick();idle();return c
    }
    private fun choose(a:Activity,index:Int){tag(a,"beta_local_actions").performClick();ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,index,index.toLong());idle()}
    private suspend fun form(title:String,existing:String?=null):BrainTask{
        val i=android.content.Intent(app,BetaTasksActivity::class.java).apply{if(existing!=null)putExtra("review_task_id",existing)}
        val c=Robolectric.buildActivity(BetaTasksActivity::class.java,i).setup();idle();val a=c.get()
        if(existing!=null)until{tag(a,"task_save").isEnabled}
        (tag(a,"task_title") as EditText).setText(title);(tag(a,"task_notes") as EditText).setText("ကိုယ်တိုင်ရေးထားသော အလုပ် · 한국어 English")
        tag(a,"task_save").performClick();until{a.isFinishing}
        val t=app.betaStore().list().single{BetaLocalTasks.title(it)==title};c.pause().stop().destroy();return t
    }
    @Test fun homeHasNoCompetingComposerAndDoesNotCreateTasks(){
        val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();idle()
        assertTrue(all(c.get().findViewById(android.R.id.content)).none{it is EditText});assertTrue(app.betaStore().list().isEmpty());assertEquals(0,app.reads)
        assertFalse(secure(c.get()));c.pause().stop().destroy()
    }
    @Test fun chatStartsCleanAndHasOneComposerAndExplicitLocalMenu(){
        val c=chat();val a=c.get();assertEquals(1,all(a.findViewById(android.R.id.content)).count{it is EditText})
        assertEquals(View.GONE,tag(a,"beta_action_scroll").visibility);assertTrue(all(a.findViewById(android.R.id.content)).none{it is Spinner})
        assertEquals(View.GONE,tag(a,"beta_app_toolbar").visibility);assertTrue(tag(a,"beta_local_actions").isShown);assertFalse(secure(a));c.pause().stop().destroy()
    }
    @Test fun unknownFreeTextNeverBecomesAnAutomaticStatusPlan()=runBlocking<Unit>{
        val c=chat();val a=c.get();a.findViewById<EditText>(R.id.etMessageInput).setText("မနက်ဖြန် အတွက် ရည်မှန်းချက်အသစ်")
        a.findViewById<View>(R.id.btnSendMessage).performClick();until{a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewChat).adapter!!.itemCount>=2}
        assertTrue(app.betaStore().list().isEmpty());assertEquals(0,app.reads);assertEquals(View.GONE,tag(a,"beta_action_scroll").visibility);c.pause().stop().destroy()
    }
    @Test fun chatRecreationKeepsCompactHeaderAndDropsUnsavedTextAndPreview(){
        val c=chat();c.get().findViewById<EditText>(R.id.etMessageInput).setText("PRIVATE unsaved text")
        configurationRecreate(c);idle();val a=c.get()
        assertNotNull(a.findViewById<EditText>(R.id.etMessageInput));assertEquals("",a.findViewById<EditText>(R.id.etMessageInput).text.toString())
        assertEquals(View.GONE,tag(a,"beta_app_toolbar").visibility);assertEquals(View.GONE,tag(a,"beta_action_scroll").visibility);assertTrue(app.betaStore().list().isEmpty());c.pause().stop().destroy()
    }
    @Test fun leavingChatStopsVoiceAndLateRecognitionCannotReopenConversation(){
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val c=chat();val a=c.get();choose(a,4)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertEquals(1,app.voice.starts);val stops=app.voice.stops
        a.findViewById<View>(R.id.navHome).performClick();idle();assertTrue(app.voice.stops>stops)
        app.voice.emit?.invoke("PRIVATE late result");idle()
        val main=a.supportFragmentManager.findFragmentById(R.id.mainContentFrame) as com.example.voicebrainlive.ui.MainFragment
        assertTrue(main.childFragmentManager.findFragmentById(R.id.fragmentHost) is com.example.voicebrainlive.ui.HomeFragment)
        assertNull(a.takePendingGoal());assertTrue(app.betaStore().list().isEmpty());c.pause().stop().destroy()
    }
    @Test fun explicitOfflinePlanProducesActualHostPortResultAndRestoresOnlySavedState()=runBlocking<Unit>{
        val c=chat();val a=c.get();choose(a,0);a.findViewById<EditText>(R.id.etMessageInput).setText("ဖုန်းအခြေအနေကို စစ်ပေးပါ")
        a.findViewById<View>(R.id.btnSendMessage).performClick();until{tag(a,"beta_save").isEnabled}
        assertEquals(View.VISIBLE,tag(a,"beta_action_scroll").visibility);assertEquals(0,app.reads);assertTrue(app.betaStore().list().isEmpty())
        tag(a,"beta_plan_details").performClick();val d=ShadowAlertDialog.getLatestAlertDialog();assertTrue(d.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0);d.dismiss()
        tag(a,"beta_save").performClick();until{tag(a,"beta_run").isEnabled};tag(a,"beta_run").performClick();until{app.betaStore().list().single().snapshot.status==AgentStatus.SUCCEEDED}
        assertEquals(2,app.reads);assertTrue(BetaLocalAgent.describe(app.betaStore().list().single()).contains("HOST_CLOCK_ONLY"));c.pause().stop().destroy()
        val reopened=app.betaStore().list().single();assertEquals(AgentStatus.SUCCEEDED,reopened.snapshot.status);assertFalse(app.taskFile.readBytes().toString(Charsets.UTF_8).contains("ဖုန်းအခြေအနေ"))
    }
    @Test fun realTaskFormSavesAndBoardShowsActualRecordAfterReopening()=runBlocking<Unit>{
        val board=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();until{texts(board.get()).contains("No tasks yet")}
        assertTrue(tag(board.get(),"beta_back") is ImageButton);tag(board.get(),"task_add").performClick()
        assertEquals(BetaTasksActivity::class.java.name,Shadows.shadowOf(board.get()).nextStartedActivity.component!!.className);board.pause().stop().destroy()
        val saved=form("ဒီည စာဖတ်ရန်")
        val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();until{texts(c.get()).contains("ဒီည စာဖတ်ရန်")}
        assertTrue(tag(c.get(),"task_done_${saved.definition.plan.taskId}").isEnabled);assertEquals(1,app.betaStore().list().size);assertEquals(0,app.reads);c.pause().stop().destroy()
    }
    @Test fun editIsAtomicHistoryRetainedAndManualDoneNeverClaimsAgentExecution()=runBlocking<Unit>{
        val old=form("Read chapter one");val newer=form("Read chapter two",old.definition.plan.taskId)
        assertEquals(2,app.betaStore().list().size);assertEquals(old.definition,app.betaStore().list().single{it.definition.plan.taskId==old.definition.plan.taskId}.definition)
        assertEquals(AgentStatus.CANCELLED,app.betaStore().list().single{it.definition.plan.taskId==old.definition.plan.taskId}.snapshot.status)
        assertEquals(old.definition.plan.taskId,newer.definition.plan.parentTaskId)
        val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();until{texts(c.get()).contains("Read chapter two")}
        assertFalse(texts(c.get()).contains("Read chapter one"));tag(c.get(),"task_done_${newer.definition.plan.taskId}").performClick()
        until{app.betaStore().list().single{it.definition.plan.taskId==newer.definition.plan.taskId}.snapshot.status==AgentStatus.REPORTED}
        val done=app.betaStore().list().single{it.definition.plan.taskId==newer.definition.plan.taskId};assertEquals(0,done.totalCalls);assertTrue(done.snapshot.steps.isEmpty());assertEquals(0,app.reads)
        until{tag(c.get(),"task_result_${newer.definition.plan.taskId}")!=null};tag(c.get(),"task_result_${newer.definition.plan.taskId}").performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().findViewById<TextView>(android.R.id.message).text.contains("No automated action"));c.pause().stop().destroy()
    }
    @Test fun cancelAndDuplicateTapsProduceOnePersistedTransition()=runBlocking<Unit>{
        val saved=form("Task to cancel");val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();until{texts(c.get()).contains("Task to cancel")}
        val action=tag(c.get(),"task_cancel_${saved.definition.plan.taskId}");action.performClick();action.performClick()
        until{app.betaStore().list().single().snapshot.status==AgentStatus.CANCELLED};assertEquals(2L,app.betaStore().list().single().revision);assertEquals(0,app.reads);c.pause().stop().destroy()
    }
    @Test fun keyAndAtomicWriteFailuresPreserveOldCiphertext()=runBlocking<Unit>{
        var bytes:ByteArray?=null;var keyFailure=false;var writeFailure=false
        val store=EncryptedBrainStore(object:AtomicMemoryBytes{override fun readOrNull()=bytes?.copyOf();override fun replaceAtomically(v:ByteArray){if(writeFailure)error("host failed atomic write");bytes=v.copyOf()}}){if(keyFailure)error("host unavailable key");app.key}
        val repo=BetaLocalTasks(store);val old=repo.create("Old record");val original=bytes!!.copyOf()
        keyFailure=true;assertTrue(runCatching{repo.edit(old,"Cannot save")}.isFailure);assertArrayEquals(original,bytes)
        keyFailure=false;writeFailure=true;assertTrue(runCatching{repo.edit(old,"Cannot save")}.isFailure);assertArrayEquals(original,bytes)
        writeFailure=false;assertEquals(old,store.list().single());assertFalse(bytes!!.toString(Charsets.UTF_8).contains("Old record"))
    }
    @Test fun fullHistoryRejectsEditWithoutDeletingOrCancellingOldRecords()=runBlocking<Unit>{
        val repo=BetaLocalTasks(app.betaStore());val rows=(1..8).map{repo.create("Task $it")};val before=app.taskFile.readBytes()
        assertTrue(runCatching{repo.edit(rows.first(),"New revision")}.isFailure);assertArrayEquals(before,app.taskFile.readBytes());assertEquals(8,app.betaStore().list().size)
    }
    @Test fun staleEditAndMarkDoNotOverwriteNewerUserDecision()=runBlocking<Unit>{
        val repo=BetaLocalTasks(app.betaStore());val old=repo.create("First");repo.mark(old,true);val bytes=app.taskFile.readBytes()
        assertTrue(runCatching{repo.edit(old,"Stale")}.isFailure);assertTrue(runCatching{repo.mark(old,false)}.isFailure);assertArrayEquals(bytes,app.taskFile.readBytes())
    }
    @Test fun localRevisionCannotModifyAnAgentOrSideEffectPlan()=runBlocking<Unit>{
        val a=app.localAgent({true}){};val p=a.preview("Legacy plan",BetaRecipe.STATUS);val old=a.saveReviewed(p.definition.plan.taskId,p.definition.digest)
        val manual=BetaLocalTasks(app.betaStore()).create("Manual");val before=app.taskFile.readBytes()
        assertTrue(runCatching{app.betaStore().reviseLocalTask(old,manual,System.currentTimeMillis())}.isFailure);assertArrayEquals(before,app.taskFile.readBytes())
    }
    @Test fun chatPlanPickerAndRunnerCannotInvalidateOrExecuteManualTasks()=runBlocking<Unit>{
        val t=BetaLocalTasks(app.betaStore()).create("Manual task must stay editable")
        val before=app.taskFile.readBytes();val agent=app.localAgent({true}){}
        assertTrue(agent.saved().isEmpty())
        assertEquals("LOCAL_TASK_USE_TASKS",(runCatching{agent.restoreForReview(t.definition.plan.taskId)}.exceptionOrNull() as BrainFailure).code)
        assertEquals("LOCAL_TASK_USE_TASKS",(runCatching{agent.runReviewed(t)}.exceptionOrNull() as BrainFailure).code)
        assertEquals("LOCAL_TASK_USE_TASKS",(runCatching{agent.cancelReviewed(t)}.exceptionOrNull() as BrainFailure).code)
        assertArrayEquals(before,app.taskFile.readBytes());assertEquals(AgentStatus.DRAFT,app.betaStore().list().single().snapshot.status);assertEquals(0,app.reads)
    }
    @Test fun diskV1ReopenPreservesLegacyShapedPlansAndNewUserTasks()=runBlocking<Unit>{
        val a=app.localAgent({true}){};val p=a.preview("Legacy-shaped v1 checkpoint",BetaRecipe.STATUS);val old=a.saveReviewed(p.definition.plan.taskId,p.definition.digest)
        val manual=BetaLocalTasks(app.betaStore()).create("User task");val encrypted=app.taskFile.readBytes()
        val reopened=EncryptedBrainStore(AndroidAtomicMemoryBytes(app.taskFile){}){app.key}.list()
        assertEquals(setOf(old.definition.digest,manual.definition.digest),reopened.map{it.definition.digest}.toSet());assertArrayEquals(encrypted,app.taskFile.readBytes())
        assertEquals(1,encrypted[0].toInt());assertFalse(encrypted.toString(Charsets.UTF_8).contains("User task"))
    }
    @Test fun manualTaskDoesNotExpireIntoAnExecutionBlockOrInventResults()=runBlocking<Unit>{
        var at=1000L;val repo=BetaLocalTasks(app.betaStore(),{at});val t=repo.create("Manual task");at=1000000
        val projected=AgentActivityProjection.project(listOf(t),emptyList(),ProactiveSnapshot(),at).rows.single()
        assertEquals(PersistentTaskState.DRAFT,projected.state);assertFalse(projected.requiresFreshApproval);assertFalse(projected.detail.contains("get_current_time"));assertTrue(projected.detail.contains("No automated action"))
    }
    @Test fun memoryUiRequiresExactConfirmationThenCorrectsDeletesAndReopens()=runBlocking<Unit>{
        ConfirmedMemoryContextConsent.setAfterReview(app,false)
        val c=Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup();until{all(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().any{it.text=="Add confirmed memory"&&it.isEnabled}}
        fun button(s:String)=all(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().first{it.text==s}
        button("Add confirmed memory").performClick();idle()
        var editor=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        var fields=all(editor.window!!.decorView).filterIsInstance<EditText>();fields[0].setText("Confirmed title");fields[1].setText("ကိုယ်တိုင် အတည်ပြုထားသည်")
        editor.getButton(-1).performClick();idle();assertTrue(app.confirmedMemoryStore.list().isEmpty())
        var preview=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog;assertTrue(preview.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        preview.getButton(-1).performClick();until{app.confirmedMemoryStore.list().size==1};until{all(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().any{it.text=="View / edit"}}
        assertFalse(app.confirmedMemoryStore.list().single().draft.includeInContext);assertFalse(ConfirmedMemoryContextConsent.isEnabled(app))
        button("View / edit").performClick();idle();editor=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        fields=all(editor.window!!.decorView).filterIsInstance<EditText>();fields[0].setText("Corrected title");editor.getButton(-1).performClick();idle();preview=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog;preview.getButton(-1).performClick()
        until{app.confirmedMemoryStore.list().single().revision==2L};until{texts(c.get()).contains("Saved memory list updated")}
        assertEquals("Corrected title",app.confirmedMemoryStore.list().single().draft.title)
        button("Delete").performClick();idle();assertEquals(1,app.confirmedMemoryStore.list().size)
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).getButton(-1).performClick();until{app.confirmedMemoryStore.list().isEmpty()};c.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup();until{texts(reopened.get()).contains("0 confirmed memories")};assertFalse(secure(reopened.get()));reopened.pause().stop().destroy()
    }
    @Test fun approvalInboxReviewsExactProposalWithoutAnyEffectOrReplayedGrant()=runBlocking<Unit>{
        val a=app.localAgent({true}){};val p=a.preview("Reminder draft",BetaRecipe.REMINDER);val saved=a.saveReviewed(p.definition.plan.taskId,p.definition.digest);a.runReviewed(saved)
        val c=Robolectric.buildActivity(AgentActivityInboxActivity::class.java).setup();until{texts(c.get()).contains("Saved activity loaded")}
        tag(c.get(),"inbox_details_brain:${saved.definition.plan.taskId}").performClick();idle()
        val dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.contains("NOT APPROVED")});assertEquals(0,app.reads)
        assertTrue(all(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>().none{it.text.toString().contains("Approve")});c.pause().stop().destroy()
    }
    @Test fun settingsAreGroupedSecondaryOptionsCollapsedAndSelectedSpinnerHasNoRadioGlyph(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();val a=c.get()
        assertEquals(View.GONE,tag(a,"beta_optional_settings").visibility)
        val root=a.findViewById<ViewGroup>(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);idle()
        assertFalse((tag(a,"beta_ui_language") as Spinner).selectedView is CheckedTextView)
        tag(a,"beta_settings_more").performClick();assertEquals(View.VISIBLE,tag(a,"beta_optional_settings").visibility);assertTrue(tag(a,"beta_language").isShown);c.pause().stop().destroy()
    }
    @Test @Config(shadows=[BetaConfigurationRecreateShadow::class]) fun themeAndLanguagePreferenceSurviveNewActivitiesWithoutEnablingNetwork(){
        val c=Robolectric.buildActivity(SettingsActivity::class.java).setup().visible();idle();val before=ThemeUtils.getTheme(app);val themeShadow=org.robolectric.shadow.api.Shadow.extract<BetaConfigurationRecreateShadow>(c.get());tag(c.get(),"beta_theme").performClick();idle();assertEquals(1,themeShadow.requests)
        assertNotEquals(before,ThemeUtils.getTheme(app));c.pause().stop().destroy()
        app.getSharedPreferences("beta_options",0).edit().putString("ui_language","my").commit();assertEquals("ပင်မ",BetaWords.get(app,"home"))
        val next=Robolectric.buildActivity(SettingsActivity::class.java).setup();idle();assertTrue(texts(next.get()).contains("အထွေထွေ"));assertTrue(texts(next.get()).contains("AI OFF"));next.pause().stop().destroy()
    }
    @Test fun ordinaryCaptureSurvivesAndCredentialWindowRemainsProtected(){
        for(type in listOf(BetaMainActivity::class.java,BetaTaskBoardActivity::class.java,BetaTasksActivity::class.java,SettingsActivity::class.java)){
            val c=Robolectric.buildActivity(type).setup();idle();assertFalse(secure(c.get()));c.pause();assertTrue(secure(c.get()));c.resume();idle();assertFalse(secure(c.get()));c.pause().stop().destroy()
        }
        val c=Robolectric.buildActivity(BetaPilotActivity::class.java).setup();idle();assertTrue(secure(c.get()));assertNull(tag(c.get(),"pilot_key"));assertFalse(tag(c.get(),"pilot_enable").isEnabled);c.pause().stop().destroy()
    }
    private fun capture(a:Activity,name:String,width:Int,height:Int){
        idle();val v=a.findViewById<ViewGroup>(android.R.id.content);v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,width,height);idle()
        val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);val color=android.util.TypedValue();a.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface,color,true);bitmap.eraseColor(color.data);v.draw(android.graphics.Canvas(bitmap))
        val folder=File("build/device-ux-captures");folder.mkdirs();File(folder,"$name.png").outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-mdpi") fun renderSimpleHome(){val c=Robolectric.buildActivity(BetaMainActivity::class.java).setup();capture(c.get(),"home",360,800);c.pause().stop().destroy()}
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-mdpi") fun renderCleanChat(){val c=chat();capture(c.get(),"chat",360,800);c.pause().stop().destroy()}
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w360dp-h800dp-mdpi") fun renderBurmeseTaskAndSettings()=runBlocking<Unit>{
        app.getSharedPreferences("beta_options",0).edit().putString("ui_language","my").commit();BetaLocalTasks(app.betaStore()).create("ဒီည စာအုပ်ဖတ်ရန်","မြန်မာစာ · ကိုယ်တိုင်စီစဉ်ထားသည်")
        val c=Robolectric.buildActivity(BetaTaskBoardActivity::class.java).setup();until{texts(c.get()).contains("ဒီည စာအုပ်ဖတ်ရန်")};capture(c.get(),"tasks-my",360,800);c.pause().stop().destroy()
        val s=Robolectric.buildActivity(SettingsActivity::class.java).setup();capture(s.get(),"settings-my",360,800);s.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w640dp-h360dp-land-mdpi") fun renderLandscapeChatAt200(){RuntimeEnvironment.setFontScale(2f);val c=chat();capture(c.get(),"chat-landscape-200",640,360);c.pause().stop().destroy()}
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w1000dp-h700dp-land-mdpi") fun renderTabletTaskFormAt200(){
        RuntimeEnvironment.setFontScale(2f);app.getSharedPreferences("beta_options",0).edit().putString("ui_language","my").commit()
        val c=Robolectric.buildActivity(BetaTasksActivity::class.java).setup();(tag(c.get(),"task_title") as EditText).setText("မြန်မာစာ ရေးပြီး အလုပ်သိမ်းရန်");capture(c.get(),"tablet-form-200",1000,700);c.pause().stop().destroy()
    }
}
