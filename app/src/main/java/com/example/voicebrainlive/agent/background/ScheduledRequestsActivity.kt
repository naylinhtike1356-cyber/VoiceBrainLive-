package com.example.voicebrainlive.agent.background

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.agent.connected.ConnectedAgentActivity
import com.example.voicebrainlive.agent.connected.ConnectedUi
import com.example.voicebrainlive.agent.connected.NotionMorningBrief
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.ReviewedAppTarget
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.Date

/** User-authored schedules. Only separately reviewed one-shot Notion scopes may use background text APIs. */
class ScheduledRequestsActivity : AppCompatActivity() {
    private lateinit var panel: ScheduledRequestsPanel
    override fun onCreate(saved: Bundle?) {
        ConnectedUi.theme(this)
        super.onCreate(saved)
        panel = ScheduledRequestsPanel(this)
        panel.attach(ConnectedUi.page(this, "အချိန်ဇယား"))
    }
    override fun onResume() { super.onResume(); panel.resume() }
    override fun onPause() { panel.pause(); super.onPause() }
}

/** Shared schedule UI; leaving a tab revokes its foreground review lease. */
internal class ScheduledRequestsPanel(private val activity: AppCompatActivity) : android.content.ContextWrapper(activity) {
    private lateinit var repo:ScheduledRequests
    private lateinit var status:TextView
    private lateinit var rows:LinearLayout
    @Volatile private var owner:Any?=null
    private var busy=false
    private var lastError:String?=null
    private var work:Job?=null
    private var dialog:android.app.AlertDialog?=null
    private val permission=activity.registerForActivityResult(ActivityResultContracts.RequestPermission()){reload()}
    fun attach(body: LinearLayout) {
        repo=ScheduledRequestGraph.repository(this)
        body.addView(button("+ အလုပ်သစ်","requests_add"){form()})
        body.addView(button("Notion အကျဉ်းချုပ်","requests_notion"){form(true)})
        status=ConnectedUi.text(this,"").apply{tag="requests_status"};body.addView(status)
        val tools = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=android.view.View.GONE }
        body.addView(button("သုံးနည်းနှင့် ချိတ်ဆက်မှုများ","requests_tools") {
            tools.visibility=if(tools.visibility==android.view.View.VISIBLE)android.view.View.GONE else android.view.View.VISIBLE
        })
        body.addView(tools)
        tools.addView(button("အသိပေးချက် ခွင့်ပြုရန်","requests_permission"){
            if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        })
        tools.addView(button("သုံးနည်း","requests_help"){
            dialog=android.app.AlertDialog.Builder(this).setTitle("အချိန်ဇယား သုံးနည်း")
                .setMessage("အလုပ်နှင့် အချိန်ကို ရွေးပြီး ပြန်စစ်ကာ သိမ်းပါ။ App ဖွင့်/သီချင်းဆက်ဖွင့်/AI မူကြမ်းသည် အချိန်ရောက် အသိပေးပြီး သင်ထပ်နှိပ်မှ ဆက်လုပ်မည်။\n\nNotion အကျဉ်းချုပ်ကို မနက် ၈ နာရီအတွက် ပြင်နိုင်သည်။ ရွေးထားသော ဒေတာနှင့် ကုန်ကျစရိတ်ကို ပြန်စစ်ပြီး ဤအလုပ်အတွက် နောက်ခံခွင့်ပြုမှသာ အလိုအလျောက် ဖတ်/အကျဉ်းချုပ်မည်။ မခွင့်ပြုလျှင် နှိပ်အတည်ပြုရန် စောင့်မည်။\n\nတစ်ကြိမ်စာသာ ဖြစ်သည်။ Android ကြောင့် အချိန် နောက်ကျနိုင်သည်။ မူလ Notion နှင့် အခြား app အတွင်း ဒေတာ မပြင်ပါ။")
                .setPositiveButton("ပိတ်ရန်",null).show()
        })
        tools.addView(button("မှတ်တမ်း ပြန်ကြည့်ရန်","requests_refresh"){reload()})
        tools.addView(button(getString(com.example.voicebrainlive.R.string.hub_connections),"requests_connections") {
            startActivity(Intent(activity,com.example.voicebrainlive.agent.connected.ConnectedSettingsActivity::class.java))
        })
        tools.addView(button(getString(com.example.voicebrainlive.R.string.hub_connected_tasks),"requests_connected_tasks") {
            startActivity(Intent(activity,com.example.voicebrainlive.agent.connected.ConnectedTasksActivity::class.java))
        })
        rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="requests_rows"};body.addView(rows)
    }
    fun resume(){if(owner==null)owner=Any();reload()}
    fun pause(){owner=null;work?.cancel();dialog?.dismiss();dialog=null}
    private fun dp(n:Int)=ConnectedUi.dp(this,n)
    private fun button(label:String,key:String,action:()->Unit)=com.google.android.material.button.MaterialButton(
        this,null,if(key=="requests_add")com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.borderlessButtonStyle
    ).apply {
        text=label;tag=key;contentDescription=label;isAllCaps=false
        if(key=="requests_add")contentDescription=getString(com.example.voicebrainlive.R.string.hub_schedule_description)
        minHeight=dp(48);cornerRadius=dp(16)
        layoutParams=LinearLayout.LayoutParams(-1,-2)
        setOnClickListener{action()}
    }
    private fun perform(action:suspend()->Unit){
        if(busy||owner==null)return
        val lease=owner;busy=true;lastError=null
        lateinit var launched:Job
        launched=activity.lifecycleScope.launch(start=CoroutineStart.LAZY){
            try {withContext(Dispatchers.IO){action()}}
            catch(c:CancellationException){throw c}
            catch(e:Exception){if(owner===lease)lastError=when((e as? ScheduledRequestFailure)?.code){
                "CAPACITY_REACHED"->"မှတ်တမ်း ၃၀ ခု ပြည့်နေသည်။ မူလမှတ်တမ်း မဖျက်ဘဲ အသစ်သိမ်းမှု ရပ်ထားသည်။"
                "CLOCK_CHANGED"->"စက်အချိန် ပြောင်းနေသည်။ အချိန်ကို စစ်ပြီး မှတ်တမ်း ပြန်ဖတ်ပါ။"
                "DAILY_CALL_BUDGET"->"အကျဉ်းချုပ်အတွက် AI တောင်းဆိုမှု ၂၄ နာရီအတွင်း တစ်ကြိမ်သာ ခွင့်ပြုထားသည်။"
                else->"မသိမ်းနိုင်ပါ သို့မဟုတ် အခြေအနေ ပြောင်းနေပါသည်။ မူလဒေတာ မဖျက်ပါ။ မှတ်တမ်း ပြန်ကြည့်ပါ။"
            }}
            finally{busy=false;if(work===launched)work=null;if(owner!=null)reload()}
        }
        work=launched;launched.start()
    }
    private fun reload(){
        if(!::rows.isInitialized||owner==null||busy)return
        val lease=owner
        activity.lifecycleScope.launch{
            try{
                val list=withContext(Dispatchers.IO){repo.recover()}
                if(owner!==lease)return@launch
                rows.removeAllViews()
                status.text=(lastError?.plus("\n").orEmpty())+if(list.isEmpty())"အလုပ်မရှိသေးပါ။ အလုပ်သစ်တစ်ခု သတ်မှတ်ပါ။" else "သိမ်းထားသော အလုပ် ${list.size} ခု။ နောက်ခံအချိန်သည် Android နှင့် ဘက်ထရီအခြေအနေကြောင့် နောက်ကျနိုင်သည်။"
                list.asReversed().forEach{r->
                    val card=LinearLayout(this@ScheduledRequestsPanel).apply{
                        orientation=LinearLayout.VERTICAL
                        setPadding(dp(16),dp(12),dp(16),dp(12))
                        setBackgroundResource(com.example.voicebrainlive.R.drawable.bg_card)
                        backgroundTintList=android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface))
                        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)}
                    }
                    card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,r.objective,17f))
                    card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,state(r.state),13f).apply {
                        setBackgroundResource(com.example.voicebrainlive.R.drawable.bg_hub_badge)
                        layoutParams=LinearLayout.LayoutParams(-2,-2)
                        setTextColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary))
                    })
                    card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,"${kind(r.kind)}\n${date(r.dueAt)}\n${r.packageName}"))
                    r.brief?.let{card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,if(it.automatic)"ဤအလုပ်အတွက်သာ နောက်ခံဖတ်/AI အကျဉ်းချုပ် ခွင့်ပြုထားသည်"else "အချိန်ရောက်လျှင် ပြန်အတည်ပြုရန် စောင့်မည်"))}
                    r.briefResult?.let{result->
                        card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,result.summary))
                        card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,"ဖတ်တွေ့သော အလုပ် ${result.matchedTasks} ခု · ${if(result.providerCalled)"AI အကြံပြုအကျဉ်းချုပ်သာ ဖြစ်သည်။ အလုပ်များကို ပြင်/ပြီးစီး မထားပါ။"else "AI မခေါ်ထားပါ။"}\nရင်းမြစ်: ${r.brief?.databaseId}\n${result.sourceIds.joinToString("\n")}",13f))
                    }
                    if(r.reason.isNotBlank())card.addView(ConnectedUi.text(this@ScheduledRequestsPanel,reason(r.reason)))
                    if(r.state==RequestState.READY)card.addView(button("ကြည့်ပြီး ဆက်လုပ်ရန်","requests_review_${r.id}"){review(r)})
                    if(r.state in setOf(RequestState.SCHEDULED,RequestState.READY))card.addView(button("ခဏရပ်ရန်","requests_pause_${r.id}"){
                        perform{repo.pause(r);ScheduledRequestGraph.cancel(this@ScheduledRequestsPanel,r.id)}
                    })
                    if(r.state==RequestState.PAUSED)card.addView(button("ပြန်စီစဉ်ရန်","requests_resume_${r.id}"){
                        perform{val next=repo.resume(r);if(next.state==RequestState.QUEUE_PENDING)enqueue(next)}
                    })
                    if(r.state in setOf(RequestState.SCHEDULED,RequestState.READY,RequestState.PAUSED,RequestState.BLOCKED,RequestState.EXPIRED)||r.kind==RequestKind.NOTION_BRIEF&&r.state==RequestState.SUBMITTING)
                        card.addView(button("ပယ်ဖျက်ရန်","requests_cancel_${r.id}"){
                            confirm("အလုပ် ပယ်ဖျက်မလား",r.objective){perform{repo.cancel(r);ScheduledRequestGraph.cancel(this@ScheduledRequestsPanel,r.id)}}
                        })
                    rows.addView(card)
                }
            }catch(c:CancellationException){throw c}catch(_:Exception){if(owner===lease){rows.removeAllViews();status.text="လုံခြုံသော သိမ်းဆည်းမှုကို ဖတ်မရပါ။ အလုပ်အသစ် မသိမ်းပါ။ မူလဒေတာ မပြောင်းပါ။"}}
        }
    }
    private suspend fun enqueue(r:ScheduledRequest){
        val ok=try{ScheduledRequestGraph.enqueue(this,r.copy(revision=r.revision+1,state=RequestState.SCHEDULED))}catch(c:CancellationException){throw c}catch(_:Exception){false}
        if(!ok)repo.fail(r,"QUEUE_UNAVAILABLE") else try{repo.queueAccepted(r)}catch(_:Exception){
            runCatching{repo.fail(r,"QUEUE_UNAVAILABLE")};ScheduledRequestGraph.cancel(this,r.id)
        }
    }
    private fun confirm(title:String,body:String,action:()->Unit){
        if(busy)return;val lease=owner?:return;var used=false
        dialog=android.app.AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply{addView(ConnectedUi.text(this@ScheduledRequestsPanel,body))})
            .setNegativeButton("မလုပ်တော့ပါ",null).setPositiveButton("ဤအတိုင်း ဆက်လုပ်ရန်",null).create().also{d->d.show()
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                    if(!used&&owner===lease&&dialog===d&&d.isShowing&&!busy){used=true;d.dismiss();action()}
                }
            }
    }
    private fun form(presetBrief:Boolean=false){
        if(busy)return;val lease=owner?:return
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val objective=ConnectedUi.input(this,"ဘာလုပ်ခိုင်းချင်ပါသလဲ","request_objective",1000)
        val kinds=Spinner(this).apply{tag="request_kind";adapter=ArrayAdapter(this@ScheduledRequestsPanel,android.R.layout.simple_spinner_dropdown_item,RequestKind.entries.map(::kind))}
        val app=Button(this).apply{text="App ရွေးရန်";tag="request_app";isAllCaps=false}
        val automatic=CheckBox(this).apply{text="ဤအလုပ်အတွက်သာ အချိန်ရောက်လျှင် နောက်ခံမှ ဖတ်/အကျဉ်းချုပ် ခွင့်ပြုမည်";tag="request_automatic";isChecked=false}
        val sharing=CheckBox(this).apply{text="ရွေးထားသော Notion မှ အလုပ်အမည်၊ ID၊ အခြေအနေနှင့် ဦးစားပေးကို Gemini ထံ ပို့မည်ကို သဘောတူသည်";tag="request_sharing_consent";isChecked=false}
        kinds.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(p:AdapterView<*>?){}
            override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,position:Int,id:Long){
                val k=RequestKind.entries[position]
                app.visibility=if(k in setOf(RequestKind.OPEN_APP,RequestKind.PLAY_MEDIA))android.view.View.VISIBLE else android.view.View.GONE
                automatic.visibility=if(k==RequestKind.NOTION_BRIEF)android.view.View.VISIBLE else android.view.View.GONE
                sharing.visibility=automatic.visibility
            }
        }
        var selected:Pair<String,String>?=null
        app.setOnClickListener{
            val apps=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                .map{it.activityInfo.packageName to it.loadLabel(packageManager).toString()}.distinctBy{it.first}.sortedBy{it.second}.take(500)
            android.app.AlertDialog.Builder(this).setTitle("ဖွင့်မည့် App အတိအကျ ရွေးပါ")
                .setItems(apps.map{it.second}.toTypedArray()){_,i->selected=apps[i];app.text=apps[i].second}.show()
        }
        val calendar=Calendar.getInstance().apply{add(Calendar.MINUTE,5);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}
        if(presetBrief){
            kinds.setSelection(RequestKind.NOTION_BRIEF.ordinal);objective.setText("မနက် ၈ နာရီ Notion အလုပ်များကို မြန်မာလို အကျဉ်းချုပ်ပြင်ရန်")
            calendar.set(Calendar.HOUR_OF_DAY,8);calendar.set(Calendar.MINUTE,0)
            if(calendar.timeInMillis<=System.currentTimeMillis()+60_000)calendar.add(Calendar.DAY_OF_YEAR,1)
        }
        val time=Button(this).apply{text=date(calendar.timeInMillis);tag="request_time";isAllCaps=false}
        time.setOnClickListener{
            DatePickerDialog(this,{_,y,m,d->calendar.set(y,m,d)
                TimePickerDialog(this,{_,h,min->calendar.set(Calendar.HOUR_OF_DAY,h);calendar.set(Calendar.MINUTE,min);time.text=date(calendar.timeInMillis)},calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),true).show()
            },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        body.addView(objective);body.addView(kinds);body.addView(app);body.addView(time);body.addView(sharing);body.addView(automatic)
        body.addView(ConnectedUi.text(this,"တစ်ကြိမ်စာ၊ ရက် ၃၀ အတွင်းသာ ရွေးနိုင်သည်။ အချိန်နောက်ကျနိုင်ပြီး ၂၄ နာရီအတွင်း မလုပ်လျှင် သက်တမ်းကုန်မည်။ Notion အကျဉ်းချုပ်အတွက် ရှိပြီးသား Gemini၊ Notion နှင့် အလုပ်ပြီး/မပြီး အကွက် သတ်မှတ်ချက် လိုသည်။ ကုန်ကျစရိတ်နှင့် ဒေတာအတိုင်းအတာကို နောက်စာမျက်နှာတွင် ပြန်စစ်ရမည်။"))
        dialog=android.app.AlertDialog.Builder(this).setTitle("အလုပ်သစ် အချိန်သတ်မှတ်ရန်").setView(ScrollView(this).apply{addView(body)})
            .setNegativeButton("မလုပ်တော့ပါ",null).setPositiveButton("ပြန်စစ်ရန်",null).create().also{d->d.show()
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                    if(owner!==lease||dialog!==d||busy)return@setOnClickListener
                    val text=objective.text.toString().trim();val selectedKind=RequestKind.entries[kinds.selectedItemPosition]
                    val needsApp=selectedKind in setOf(RequestKind.OPEN_APP,RequestKind.PLAY_MEDIA)
                    val pkg=if(needsApp)selected?.first.orEmpty()else ""
                    val at=calendar.timeInMillis;val now=System.currentTimeMillis()
                    if(text.isBlank()||(needsApp&&pkg.isBlank())||at<now+60_000||at-now>30L*86_400_000){objective.error="အလုပ်၊ App နှင့် အနည်းဆုံး တစ်မိနစ်နောက် အချိန်ကို ရွေးပါ။";return@setOnClickListener}
                    if(!RequestNotifications.available(activity)){objective.error="အချိန်ရောက် အသိပေးနိုင်ရန် အသိပေးချက်ကို အရင်ခွင့်ပြုပါ။";return@setOnClickListener}
                    val cert=try{if(pkg.isEmpty())""else ReviewedAppTarget.certificate(this,pkg)}catch(_:Exception){objective.error="App ကို အတည်မပြုနိုင်ပါ။ ပြန်ရွေးပါ။";return@setOnClickListener}
                    val scope=if(selectedKind==RequestKind.NOTION_BRIEF){
                        if(!sharing.isChecked){sharing.error="ဒေတာပို့မည့်အတိုင်းအတာကို သဘောတူမှ ဆက်လုပ်နိုင်သည်။";return@setOnClickListener}
                        try{NotionMorningBrief.android(this).reviewScope(automatic.isChecked)}catch(e:Exception){objective.error=ConnectedUi.error(e);return@setOnClickListener}
                    }else null
                    d.dismiss()
                    confirm("ဤအလုပ်ကို အချိန်သတ်မှတ်မလား","$text\n${kind(selectedKind)}\n$pkg\n${date(at)}\n\n"+(scope?.let(::briefDisclosure)?:"အချိန်ရောက် အသိပေးမည်။ App ဖွင့်ခြင်းနှင့် သီချင်းဆက်ဖွင့်ခြင်းကို ထပ်နှိပ်အတည်ပြုမှ လုပ်မည်။ AI မူကြမ်းစာသားကို အလိုအလျောက် မပို့ပါ။")){
                        perform{val r=if(scope==null)repo.create(selectedKind,text,pkg,cert,at)else repo.createBrief(text,scope,at,true);enqueue(r)}
                    }
                }
            }
    }
    private fun review(r:ScheduledRequest){
        confirm("လုပ်မည့်အရာ အတိအကျ", "${r.objective}\n${kind(r.kind)}\n${r.packageName}\n${date(r.dueAt)}\n\n"+(r.brief?.let(::briefDisclosure)?:if(r.kind==RequestKind.AI_GOAL)"AI စာမျက်နှာတွင် မူကြမ်းဖွင့်မည်။ သီးခြား ပြန်စစ်အတည်ပြုမှ Gemini ထံ ပို့မည်။"else "App က လက်ခံခြင်းကိုသာ မှတ်မည်။ သီချင်း တကယ်ကြားရခြင်းနှင့် app အတွင်း အလုပ်ပြီးခြင်းကို မအာမခံပါ။")){
            val lease=owner?:return@confirm
            perform{
                val claim=repo.claimReviewed(r){owner===lease}
                if(claim.kind==RequestKind.NOTION_BRIEF){ScheduledRequestGraph.briefRunner(this@ScheduledRequestsPanel).execute(claim);return@perform}
                // Once claimed, never replay on cancellation/process death. Settle the observed submission.
                withContext(NonCancellable){
                    val output=try{
                        if(claim.kind==RequestKind.AI_GOAL)"SUBMITTED:DRAFT" else {
                            if(owner!==lease)"BLOCKED:STALE_FOREGROUND" else PhoneCommandExecutor(activity).executeAsync(
                                if(claim.kind==RequestKind.OPEN_APP)"open_app"else "resume_music",claim.packageName,ReviewedAppTarget.PREFIX+claim.certificate)
                        }
                    }catch(_:Exception){"BLOCKED:EFFECT_UNAVAILABLE"}
                    val done=repo.finish(claim,output.startsWith("SUBMITTED:"))
                    ScheduledRequestGraph.cancel(this@ScheduledRequestsPanel,claim.id)
                    if(done.state==RequestState.DRAFT_READY)withContext(Dispatchers.Main){
                        if(owner===lease)startActivity(Intent(this@ScheduledRequestsPanel,ConnectedAgentActivity::class.java).putExtra("scheduled_draft_id",done.id))
                    }
                }
            }
        }
    }
    private fun date(at:Long)=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(Date(at))+" · "+java.util.TimeZone.getDefault().id
    private fun kind(k:RequestKind)=when(k){RequestKind.OPEN_APP->"ရွေးထားသော App ဖွင့်ရန်";RequestKind.PLAY_MEDIA->"ရွေးထားသော App မှ သီချင်းဆက်ဖွင့်ရန်";RequestKind.AI_GOAL->"AI အတွက် အလုပ်မူကြမ်း ပြင်ဆင်ရန်";RequestKind.NOTION_BRIEF->"Notion မှ မြန်မာ AI အကျဉ်းချုပ်"}
    private fun state(s:RequestState)=when(s){RequestState.QUEUE_PENDING->"အချိန်ဇယား အတည်မပြုရသေးပါ";RequestState.SCHEDULED->"အချိန်စောင့်နေသည်";RequestState.READY->"ကြည့်ပြီး ဆက်လုပ်ရန် စောင့်နေသည်";RequestState.PAUSED->"ခဏရပ်ထားသည်";RequestState.CANCELLED->"ပယ်ဖျက်ပြီး";RequestState.SUBMITTING->"တောင်းဆိုချက် ပို့နေသည်";RequestState.SUBMITTED->"App ထံ တောင်းဆိုချက် ပို့ပြီး";RequestState.DRAFT_READY->"AI မူကြမ်း ပြင်ဆင်ပြီး၊ မပို့ရသေး";RequestState.SUMMARY_READY->"Notion အကျဉ်းချုပ် သိမ်းပြီး";RequestState.BLOCKED->"လုပ်ဆောင်မှု ရပ်ထားသည်";RequestState.EXPIRED->"သက်တမ်းကုန်ပြီ"}
    private fun reason(code:String)=when(code){"QUEUE_UNAVAILABLE"->"Android အချိန်ဇယားကို အတည်မပြုနိုင်ပါ။ အလိုအလျောက် ထပ်မကြိုးစားပါ။";"NOTIFICATIONS_UNAVAILABLE"->"အသိပေးချက် ပို့မရပါ။ ဤနေရာမှ ပြန်စစ်နိုင်သည်။";"PROCESS_INTERRUPTED","BRIEF_INTERRUPTED"->"အက်ပ်ပိတ်သွားစဉ် ရလဒ်မသေချာပါ။ ထပ်မလုပ်ဆောင်ပါ။";"BRIEF_SCOPE_CHANGED"->"Gemini၊ Notion ချိတ်ဆက်မှု သို့မဟုတ် အကွက်သတ်မှတ်ချက် ပြောင်းနေသည်။ အသစ် ပြန်စစ်ရန် လိုသည်။";"BRIEF_AUTHENTICATION"->"Gemini/Notion key အသုံးပြုခွင့် မအောင်မြင်ပါ။ ချိတ်ဆက်ဆက်တင်ကို စစ်ပါ။";"BRIEF_RATE_LIMIT","DAILY_CALL_BUDGET"->"အသုံးပြုခွင့်ပမာဏ သို့မဟုတ် ၂၄ နာရီ AI တောင်းဆိုမှုကန့်သတ်ချက် ပြည့်နေသည်။ ထပ်မပို့ပါ။";"BRIEF_PERMISSION"->"Notion ဒေတာဘေ့စ်ကို integration သို့ မျှဝေထားခြင်း ရှိမရှိ စစ်ပါ။";"BRIEF_INCOMPLETE_SOURCE"->"Notion ရလဒ် မပြည့်စုံသဖြင့် အကျဉ်းချုပ် မပို့ပါ။";"BRIEF_NETWORK"->"ကွန်ရက် သို့မဟုတ် ဝန်ဆောင်မှုကို ဆက်သွယ်မရပါ။ အလိုအလျောက် ထပ်မပို့ပါ။";"BRIEF_INVALID_RESPONSE"->"အဖြေ မပြည့်စုံ သို့မဟုတ် စည်းမျဉ်းမကိုက်ပါ။ အောင်မြင်သည်ဟု မဖော်ပြပါ။";else->"ရွေးထားသော App၊ media session သို့မဟုတ် ခွင့်ပြုချက် မရနိုင်ပါ။ အခြား App ကို အစားမဖွင့်ပါ။"}
    private fun briefDisclosure(s:NotionBriefScope)="Notion ဒေတာဘေ့စ်: ${s.databaseId}\nGemini မော်ဒယ်: ${s.model}\n\n${if(s.automatic)"သတ်မှတ်ချိန်ရောက်လျှင် ဤအလုပ်တစ်ခုအတွက်သာ နောက်ခံမှ ဖတ်/AI ပို့မည်။ ထပ်နှိပ်အတည်ပြုရန် မစောင့်ပါ။"else "အချိန်ရောက် အသိပေးမည်။ သင်ပြန်နှိပ်အတည်ပြုမှ ဖတ်/AI ပို့မည်။"}\n\nဖတ်ရှုမှု အများဆုံး မှတ်တမ်း ၇၅ ခု/Notion တောင်းဆိုချက် ၄ ခု။ Gemini ထံ အလုပ်အမည်အစပိုင်း ၈၀ လုံး၊ ID၊ အခြေအနေအစပိုင်း ၄၀ လုံး၊ သတ်မှတ်ထားသော ဦးစားပေးသာ ပို့မည်။ အများဆုံး ၁၀ ခု/စာလုံး ၂၀၀၀ အတွင်း။ မှတ်စုအပြည့်၊ ကိုယ်ပိုင်မှတ်ဉာဏ်၊ အသံနှင့် key မပို့ပါ။\n\nဤအချိန်သတ်မှတ် Notion အကျဉ်းချုပ်များအတွက် AI တောင်းဆိုမှု ၂၄ နာရီအတွင်း တစ်ကြိမ်သာ။ သီးခြား Chat သုံးစွဲမှု မပါဝင်ပါ။ အဖြေ အများဆုံး ၅၁၂ တိုကင်။ အဝင်စာသား/စဉ်းစားမှုလည်း ငွေကောက်ခံနိုင်သည်။ ဤကန့်သတ်ချက်က ငွေပမာဏကန့်သတ်ချက် မဟုတ်ပါ။ မိမိ Gemini quota/ဈေးနှုန်းကို စစ်ပါ။ ပို့ပြီးသား တောင်းဆိုချက်ကို ရပ်ခြင်းက ကုန်ကျစရိတ်ကို ပြန်မဖျက်ပါ။\n\nရလဒ်ကို စက်ထဲ encrypted storage တွင် သိမ်းမည်။ Notion မပြင်/မဖျက်၊ အလုပ်များ ပြီးပြီဟု မမှတ်၊ ခေါ်ဆို/စာပို့ခြင်း မလုပ်ပါ။ ချိတ်ဆက်မှု/အကွက် ပြောင်းလျှင် ရပ်မည်။ ထပ်မကြိုးစားပါ။ တစ်ကြိမ်စာသာဖြစ်ပြီး နေ့စဉ်အလိုအလျောက် ထပ်မလုပ်ပါ။"
}
