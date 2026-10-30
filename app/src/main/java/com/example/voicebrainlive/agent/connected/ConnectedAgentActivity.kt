package com.example.voicebrainlive.agent.connected

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.agent.AgentData
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.brain.*
import kotlinx.coroutines.*

/** Real foreground requests from the original app. No automatic requests/history/voice/background work. */
class ConnectedAgentActivity:AppCompatActivity(){
    @Volatile private var owner:Any?=null
    private var sequence=0L
    private var workspace:ConnectedWorkspace?=null
    private var work:Job?=null
    private var dialog:AlertDialog?=null
    private var task:BrainTask?=null
    private var previewOnly=false
    private lateinit var status:TextView
    private lateinit var composer:EditText
    private lateinit var send:Button
    private lateinit var stop:Button
    private lateinit var chat:RadioButton
    private lateinit var plan:RadioButton
    private lateinit var save:Button
    private lateinit var run:Button
    private lateinit var reject:Button
    private lateinit var planText:TextView
    private lateinit var planArea:LinearLayout
    private lateinit var messages:ConnectedMessages
    private lateinit var list:RecyclerView
    private var scheduledDraftLoaded=false
    override fun onCreate(saved:Bundle?){ConnectedUi.theme(this);super.onCreate(saved)
        // This workspace may contain real private Notion records; ordinary offline BETA policy is unchanged.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        bar.addView(ConnectedUi.button(this,"‹","connected_chat_back"){finish()}.apply{contentDescription="နောက်သို့"})
        bar.addView(ConnectedUi.text(this,"ကိုယ်ပိုင် AI အကူအညီ",18f),LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(ConnectedUi.button(this,"⋮","connected_menu"){menu()}.apply{contentDescription="အကူအညီ မီနူး"});root.addView(bar)
        status=ConnectedUi.text(this,"AI မချိတ်ဆက်ရသေးပါ",13f).apply{tag="connected_status"};root.addView(status)
        val modes=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL}
        chat=RadioButton(this).apply{id=View.generateViewId();text="စကားပြောမည်";tag="connected_chat_mode";isChecked=true}
        plan=RadioButton(this).apply{id=View.generateViewId();text="အလုပ်စီစဉ်မည်";tag="connected_plan_mode"}
        modes.addView(chat);modes.addView(plan);root.addView(modes)
        modes.setOnCheckedChangeListener{_,_->invalidatePreview()}
        list=RecyclerView(this).apply{layoutManager=LinearLayoutManager(this@ConnectedAgentActivity).apply{stackFromEnd=true}}
        messages=ConnectedMessages();list.adapter=messages;root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        planArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        planText=ConnectedUi.text(this,"",13f).apply{tag="connected_plan_preview";maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END};planArea.addView(planText)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        save=ConnectedUi.button(this,"အစီအစဉ် သိမ်းရန်","connected_save_plan"){task?.let{t->operate({it.savePreview(t)}){shown->showTask(shown,false)}}}
        run=ConnectedUi.button(this,"ဖတ်ရှုအလုပ်များ လုပ်ရန်","connected_run_plan"){task?.let{t->operate({it.runSaved(t)}){shown->showTask(shown,false)}}}
        reject=ConnectedUi.button(this,"ပယ်ဖျက်ရန်","connected_reject_plan"){task?.let{t->
            if(previewOnly){invalidatePreview();message("မူကြမ်း ပယ်ဖျက်ပြီးပါပြီ။ မလုပ်ဆောင်ရသေးပါ။")}
            else confirm("သိမ်းထားသော အစီအစဉ်ကို ပယ်ဖျက်မလား",t.definition.plan.objective){operate({it.cancelSaved(t)}){shown->showTask(shown,false)}}
        }}
        actions.addView(save);actions.addView(run);actions.addView(reject)
        actions.addView(ConnectedUi.button(this,"အသေးစိတ်","connected_plan_details"){task?.let{t->
            dialog=AlertDialog.Builder(this).setTitle("အစီအစဉ်အတိအကျနှင့် ရလဒ်မှတ်တမ်း")
                .setView(ScrollView(this).apply{addView(ConnectedUi.text(this@ConnectedAgentActivity,connectedTaskDescription(t)))})
                .setPositiveButton("ပိတ်ရန်",null).show().also(ConnectedUi::protect)
        }})
        val horizontal=HorizontalScrollView(this).apply{addView(actions)};planArea.addView(horizontal)
        root.addView(planArea)
        stop=ConnectedUi.button(this,"ရပ်ရန်","connected_stop"){cancelWork()}.apply{visibility=View.GONE};root.addView(stop)
        val inputRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        composer=ConnectedUi.input(this,"စာရေးပါ","connected_composer",2000)
        composer.maxLines=4;inputRow.addView(composer,LinearLayout.LayoutParams(0,-2,1f))
        send=ConnectedUi.button(this,"ပို့ရန်","connected_send"){reviewSend()};inputRow.addView(send);root.addView(inputRow)
        composer.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){invalidatePreview()}
            override fun afterTextChanged(s:Editable?){}
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,i->val b=i.getInsets(WindowInsetsCompat.Type.systemBars());val keyboard=i.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(ConnectedUi.dp(this,8)+b.left,b.top,ConnectedUi.dp(this,8)+b.right,maxOf(b.bottom,keyboard.bottom));i}
        ViewCompat.requestApplyInsets(root)
    }
    override fun onResume(){super.onResume();newSession();refreshStatus();refresh();loadScheduledDraft()}
    private fun loadScheduledDraft(){
        if(scheduledDraftLoaded)return
        val id=intent.getStringExtra("scheduled_draft_id")?:return
        scheduledDraftLoaded=true;val lease=owner
        lifecycleScope.launch{
            val row=withContext(Dispatchers.IO){runCatching{
                com.example.voicebrainlive.agent.background.ScheduledRequestGraph.repository(this@ConnectedAgentActivity).history().singleOrNull{
                    it.id==id&&it.kind==com.example.voicebrainlive.agent.background.RequestKind.AI_GOAL&&
                    it.state==com.example.voicebrainlive.agent.background.RequestState.DRAFT_READY&&System.currentTimeMillis()<it.expiresAt
                }
            }.getOrNull()}
            if(owner!==lease)return@launch
            if(row==null){status.text="အချိန်သတ်မှတ် မူကြမ်း မရနိုင်ပါ။ မည်သည့်စာသားမျှ မပို့ပါ။";return@launch}
            composer.setText(row.objective);plan.isChecked=true
            status.text="အချိန်သတ်မှတ် အလုပ်မူကြမ်း ဖြစ်သည်။ Gemini ထံ မပို့ရသေးပါ။ စာသားနှင့် ကုန်ကျစရိတ်ကို ပြန်စစ်ပြီးမှ ပို့ပါ။"
        }
    }
    override fun onPause(){owner=null;sequence++;dialog?.dismiss();dialog=null;work?.cancel();work=null;workspace?.discard();workspace=null
        composer.text.clear();task=null;messages.clear();super.onPause()}
    override fun onDestroy(){list.adapter=null;super.onDestroy()}
    private fun newSession(){val lease=Any();owner=lease
        try{workspace=ConnectedGraph.workspace(this){owner===lease}}catch(e:Exception){workspace=null;status.text=ConnectedUi.error(e)}
    }
    private fun refreshStatus(){try{val w=workspace?:return;val model=w.settings.textModel()
        status.text=if(model.isBlank()||!w.settings.hasGeminiKey())"AI ချိတ်ဆက်ရန် လိုပါသည်။ စက်တွင်းဒေတာ မပြောင်းပါ။" else "ရွေးထားသော မော်ဒယ်: $model · မပို့မီ ပြန်စစ်ရန်\n${w.sourceDescription()}"
    }catch(e:Exception){status.text=ConnectedUi.error(e)}}
    private fun refresh(){val ready=owner!=null&&workspace!=null&&work==null
        send.isEnabled=ready;composer.isEnabled=work==null;chat.isEnabled=work==null;plan.isEnabled=work==null
        stop.visibility=if(work==null)View.GONE else View.VISIBLE
        planArea.visibility=if(task==null)View.GONE else View.VISIBLE
        save.visibility=if(previewOnly)View.VISIBLE else View.GONE;save.isEnabled=ready&&previewOnly
        run.visibility=if(previewOnly)View.GONE else View.VISIBLE;run.isEnabled=ready&&task?.snapshot?.status==AgentStatus.DRAFT
        reject.isEnabled=ready&&task?.snapshot?.status !in setOf(AgentStatus.SUCCEEDED,AgentStatus.REPORTED,AgentStatus.CANCELLED)
    }
    private fun invalidatePreview(){if(!::save.isInitialized)return;task=null;previewOnly=false;workspace?.discardPreview();refresh()}
    private fun message(s:String,user:Boolean=false){messages.add(s,user);list.scrollToPosition((messages.itemCount-1).coerceAtLeast(0))}
    private fun confirm(title:String,body:String,action:()->Unit){
        val lease=owner?:return
        dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply{addView(ConnectedUi.text(this@ConnectedAgentActivity,body))})
            .setNegativeButton("မလုပ်တော့ပါ",null).setPositiveButton("အတည်ပြုရန်",null).create().also{d->d.show();ConnectedUi.protect(d)
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(owner===lease&&dialog===d&&d.isShowing){d.dismiss();dialog=null;action()}}}
    }
    private fun reviewSend(){
        if(work!=null)return
        val w=workspace?:return;val text=composer.text.toString().trim()
        if(text.isBlank()){composer.error="ပို့မည့်စာ ရေးပါ";return}
        try{
            val action=w.reviewText(text,if(plan.isChecked)ConnectedTextMode.PLAN else ConnectedTextMode.CHAT)
            confirm("ဤစာကို Gemini ထံ တစ်ကြိမ် ပို့မလား","မော်ဒယ်: ${action.model}\n\n$text\n\nဤစာသားနှင့် ပုံသေညွှန်ကြားချက်များသာ ပို့မည်။ မှတ်ဉာဏ်၊ အလုပ်မှတ်တမ်း၊ Notion ဒေတာ၊ အသံ မပို့ပါ။ အဖြေ အများဆုံး ${if(action.mode==ConnectedTextMode.PLAN)"၁၀၂၄" else "၅၁၂"} တိုကင်။ အဝင်စာသားနှင့် စဉ်းစားမှုကိုလည်း ငွေကောက်ခံနိုင်သည်။ Gemini quota ကို စစ်ပါ။ အလိုအလျောက် ထပ်မပို့ပါ။\n\nစကားပြောမှ ကိရိယာ မလုပ်ဆောင်ပါ။ အစီအစဉ်ကို သီးခြား ပြန်စစ်/သိမ်း/လုပ်ဆောင်ရမည်။\nနောက်ပိုင်း စက်တွင်းအစီအစဉ်အတွက် ရင်းမြစ်: ${w.sourceDescription()}"){
                if(composer.text.toString().trim()!=action.text||plan.isChecked!=(action.mode==ConnectedTextMode.PLAN)){
                    status.text="စာသား သို့မဟုတ် ပုံစံ ပြောင်းသွားပါပြီ။ လက်ရှိတောင်းဆိုချက်ကို ပြန်စစ်ပါ။";return@confirm
                }
                message(text,true);operate({it.sendReviewed(action)}){reply->
                    status.text="Gemini အဖြေ ရပြီးပါပြီ · ${reply.tokens?:"မသိရသေး"} တိုကင်စုစုပေါင်း · ကုန်ကျစရိတ် မတွက်ထားပါ"
                    if(reply.plan==null)message("AI အဖြေ · မည်သည့်အလုပ်မျှ မလုပ်ဆောင်ရသေးပါ\n"+reply.text) else showTask(requireNotNull(reply.plan),true)
                }
            }
        }catch(e:Exception){status.text=ConnectedUi.error(e)}
    }
    private fun showTask(t:BrainTask,isPreview:Boolean){
        task=t;previewOnly=isPreview
        val summary=t.definition.plan.steps.joinToString(" → "){it.command.intent}
        planText.text="${if(isPreview)"မူကြမ်းသာ ဖြစ်သည် · မသိမ်း/မလုပ်ဆောင်ရသေး" else ConnectedUi.state(t.snapshot.status)}\n$summary"
        val output=t.snapshot.steps.mapNotNull{when(val r=it.result){is AgentData.Draft->r.exactText;is AgentData.Report->r.exactText;is AgentData.Clock->r.exactDisplay;is AgentData.Battery->"Battery ${r.percent}%";is AgentData.Tasks->"Read ${r.rows.size} mapped tasks";else->null}}.joinToString("\n")
        message("${t.definition.plan.objective}\n${if(isPreview)"စစ်ဆေးပြီးသော မူကြမ်းသာ ဖြစ်သည်" else "သိမ်းထားသော အခြေအနေ: ${ConnectedUi.state(t.snapshot.status)}"}\n$summary"+if(output.isBlank())"" else "\n$output")
        refresh()
    }
    private fun menu(){
        if(work!=null)return
        val lease=owner?:return
        val items=arrayOf("AI နှင့် Notion ချိတ်ဆက်မှု","Notion မှတ်စုများ ဖတ်ရန်","သတ်မှတ်ထားသော မပြီးသေးသည့် Notion အလုပ်များ ဖတ်ရန်","Notion အလုပ်ပြီး/မပြီး အဓိပ္ပာယ် သတ်မှတ်ရန်","စက်တွင်းသိမ်းထားသော အလုပ်များ သုံးရန်","စက်တွင်း အလုပ်များ","အတည်ပြု မှတ်ဉာဏ်","လုပ်ဆောင်မှုနှင့် ခွင့်ပြုချက်များ","သိမ်းထားသော အစီအစဉ်နှင့် ရလဒ်များ","အချိန်သတ်မှတ် အလုပ်များ")
        dialog=AlertDialog.Builder(this).setTitle("ကိုယ်ပိုင် အကူအညီ").setItems(items){_,i->if(owner===lease)when(i){
            0->ConnectedUi.open(this,ConnectedSettingsActivity::class.java)
            1->reviewNotion(false,false)
            2->reviewNotion(true,false)
            3->reviewNotion(false,true)
            4->{workspace?.clearSource();task=null;refreshStatus();refresh()}
            5->ConnectedUi.open(this,ConnectedTasksActivity::class.java)
            6->ConnectedUi.open(this,com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryActivity::class.java)
            7->ConnectedUi.open(this,AgentActivityInboxActivity::class.java)
            8->savedPlans()
            9->ConnectedUi.open(this,com.example.voicebrainlive.agent.background.ScheduledRequestsActivity::class.java)
        }}.setNegativeButton("ပိတ်ရန်",null).show().also(ConnectedUi::protect)
    }
    private fun savedPlans(){operate({it.savedPlans()}){rows->
        if(rows.isEmpty()){message("သိမ်းထားသော အစီအစဉ် မရှိသေးပါ။ အလုပ်စီစဉ်မည်ကို ရွေးပြီး မူကြမ်းကို ပြန်စစ်ကာ သိမ်းပါ။");return@operate}
        val lease=owner
        dialog=AlertDialog.Builder(this).setTitle("သိမ်းထားသော အစီအစဉ်များ").setItems(rows.map{"${it.state}: ${it.definition.plan.objective}"}.toTypedArray()){_,i->
            if(owner===lease)operate({it.restoreSaved(rows[i].definition.plan.taskId)}){t->showTask(t,false)}
        }.setNegativeButton("ပိတ်ရန်",null).show().also(ConnectedUi::protect)
    }}
    private fun reviewNotion(tasksOnly:Boolean,schemaOnly:Boolean){
        val w=workspace?:return
        try{
            val action=w.reviewNotion(tasksOnly=tasksOnly)
            confirm("ရွေးထားသော Notion ဒေတာဘေ့စ်ကို ဖတ်မလား","ဒေတာဘေ့စ်: ${action.databaseId}\n\n${if(schemaOnly)"အကွက်ပုံစံကိုသာ ဖတ်မည် (တောင်းဆိုချက် ၁ ခု)။" else "အကွက်ပုံစံနှင့် မှတ်တမ်း အများဆုံး ၇၅ ခု ဖတ်မည် (တောင်းဆိုချက် အများဆုံး ၄ ခု)။"}\nရေးခြင်း၊ ဖျက်ခြင်း၊ ဒေတာကူးယူပြောင်းခြင်း မလုပ်ပါ။ မှတ်တမ်းများ စက်ထဲမှာသာ ရှိမည်။ Gemini ထံ မပို့ပါ။ ခွင့်ပြုချက်နှင့် အကြိမ်နှုန်းအမှားများကို ပြမည်။ အလိုအလျောက် ထပ်မကြိုးစားပါ။"){
                if(schemaOnly)operate({it.readSchema(action)}){schema->mappingDialog(action,schema)}
                else operate({it.readNotion(action)}){snapshot->
                    message("${snapshot.evidence} · ${snapshot.records.size} records · ${if(snapshot.complete)"complete selected query" else "partial; not usable as an exhaustive task source"}\n"+
                        snapshot.records.take(25).joinToString("\n\n"){r->"${r.title}\n${r.content}\nStatus: ${r.rawStatus?:"မသိရသေး"}; completion: ${r.unfinished?.let{if(it)"unfinished" else "not unfinished"}?:"not mapped"}"+if(r.truncated)"\nText truncated" else ""})
                    task=null;refreshStatus();refresh()
                }
            }
        }catch(e:Exception){status.text=ConnectedUi.error(e)}
    }
    private fun mappingDialog(action:ReviewedNotionRead,schema:LegacyNotionSchema){
        val statuses=schema.fields.filter{it.type in setOf("status","select")}
        if(statuses.isEmpty()){message("အလုပ်ပြီး/မပြီး သတ်မှတ်နိုင်သော အကွက် မတွေ့ပါ။ မှတ်စုများ ဖတ်နိုင်သေးသော်လည်း မပြီးသေးဟု ခန့်မှန်းမထားပါ။");return}
        val lease=owner?:return;val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(ConnectedUi.text(this,"အလုပ်ပြီး/မပြီးကို အမှန်တကယ် ဆိုလိုသောအကွက်ကို ရွေးပါ။ Pending/Synced/Failed သည် ဒေတာကူးယူမှု အခြေအနေ ဖြစ်နိုင်သဖြင့် အလုပ်ပြီး/မပြီးဟု မယူဆပါ။"))
        val field=Spinner(this).apply{tag="connected_business_field";adapter=ArrayAdapter(this@ConnectedAgentActivity,android.R.layout.simple_spinner_dropdown_item,statuses.map{it.name})};body.addView(field)
        val choices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(choices)
        val checks=mutableListOf<Pair<String,CheckBox>>()
        fun options(i:Int){checks.clear();choices.removeAllViews();statuses[i].options.forEach{s->val c=CheckBox(this).apply{text=s};checks+=s to c;choices.addView(c)}}
        field.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){options(i)}}
        options(0)
        val numbers=schema.fields.filter{it.type=="number"}
        val priority=Spinner(this).apply{adapter=ArrayAdapter(this@ConnectedAgentActivity,android.R.layout.simple_spinner_dropdown_item,listOf("ဦးစားပေးအကွက် မသတ်မှတ်ပါ")+numbers.map{it.name})};body.addView(priority)
        val consent=CheckBox(this).apply{text="ဤအကွက်သည် ဒေတာကူးယူမှုအခြေအနေ မဟုတ်ဘဲ အလုပ်ပြီး/မပြီးကို ဆိုလိုသည်။ အမှန်ခြစ်ထားသော တန်ဖိုးများသည် မပြီးသေးဟု ဆိုလိုသည်။";tag="connected_mapping_consent"};body.addView(consent)
        dialog=AlertDialog.Builder(this).setTitle("Notion အလုပ်အခြေအနေ သတ်မှတ်ရန်").setView(ScrollView(this).apply{addView(body)})
            .setNegativeButton("မလုပ်တော့ပါ",null).setPositiveButton("သတ်မှတ်ချက် သိမ်းရန်",null).create().also{d->d.show();ConnectedUi.protect(d)
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                    if(owner!==lease||dialog!==d||!d.isShowing)return@setOnClickListener
                    if(!consent.isChecked){consent.error="အဓိပ္ပာယ်ကို အရင်အတည်ပြုပါ";return@setOnClickListener}
                    val mapping=LegacyTaskMapping(schema.databaseId,schema.title.id,statuses[field.selectedItemPosition].id,
                        checks.filter{it.second.isChecked}.map{it.first}.toSet(),numbers.getOrNull(priority.selectedItemPosition-1)?.id)
                    d.dismiss();dialog=null
                    operate({it.saveMapping(action,schema,mapping,true)}){ok->message(if(ok)"သတ်မှတ်ချက် သိမ်းပြီး စစ်ဆေးပြီးပါပြီ။ ဤရင်းမြစ်သုံးရန် အလုပ်များကို ပြန်ဖတ်ပါ။" else "သတ်မှတ်ချက် သိမ်းဆည်းမှုကို အတည်မပြုနိုင်ပါ။");refreshStatus()}
                }
            }
    }
    private fun cancelWork(){
        owner=null;sequence++;work?.cancel();work=null;workspace?.discard();workspace=null;task=null;previewOnly=false
        newSession();message("စက်တွင်း လုပ်ဆောင်မှု ရပ်ပြီးပါပြီ။ ပို့ပြီးသော တောင်းဆိုချက်ကို ဝန်ဆောင်မှုပေးသူက ငွေကောက်ခံနိုင်သေးသည်။ သိမ်းထားသော မှတ်တမ်းများ မပြောင်းပါ။");refreshStatus();refresh()
    }
    private fun <T> operate(action:suspend(ConnectedWorkspace)->T,display:(T)->Unit){
        if(work!=null)return;val w=workspace?:return;val lease=owner?:return;val stamp=++sequence
        lateinit var launched:Job
        launched=lifecycleScope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){
            try{val result=action(w);withContext(Dispatchers.Main){if(owner===lease&&sequence==stamp){if(work===launched)work=null;display(result);refresh()}}}
            catch(c:CancellationException){
                if(c is TimeoutCancellationException)withContext(NonCancellable+Dispatchers.Main){if(owner===lease&&sequence==stamp){status.text=ConnectedUi.error(c);task=null;previewOnly=false}} else throw c
            }
            catch(e:Exception){withContext(Dispatchers.Main){if(owner===lease&&sequence==stamp){status.text=ConnectedUi.error(e);task=null;previewOnly=false;refresh()}}}
            finally{withContext(NonCancellable+Dispatchers.Main){if(work===launched)work=null;if(owner===lease)refresh()}}
        };work=launched;status.text="လုပ်ဆောင်နေသည်…";refresh();launched.start()
    }
}

private class ConnectedMessages:RecyclerView.Adapter<ConnectedMessages.Holder>(){
    private val rows=mutableListOf<Pair<String,Boolean>>()
    class Holder(val text:TextView):RecyclerView.ViewHolder(text)
    fun add(s:String,user:Boolean){rows.add((s.take(10_000)+if(s.length>10_000)"\n[Display truncated; original source is unchanged.]"else "") to user);if(rows.size>30)rows.removeAt(0);notifyDataSetChanged()}
    fun clear(){rows.clear();notifyDataSetChanged()}
    override fun getItemCount()=rows.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(ConnectedUi.text(parent.context,"",15f).apply{
        layoutParams=RecyclerView.LayoutParams(-1,-2);setPadding(ConnectedUi.dp(context,12),ConnectedUi.dp(context,10),ConnectedUi.dp(context,12),ConnectedUi.dp(context,10));isSaveEnabled=false
    })
    override fun onBindViewHolder(h:Holder,i:Int){val row=rows[i];h.text.text=(if(row.second)"သင်\n" else "အကူအညီ\n")+row.first;h.text.contentDescription=h.text.text}
}
