package com.example.voicebrainlive.agent.connected

import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.agent.AgentData
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.beta.BetaLocalTasks
import kotlinx.coroutines.*

internal fun connectedTaskDescription(t:BrainTask):String {
    if(LocalUserTaskRecord.matches(t))return "${BetaLocalTasks.title(t)}\n${BetaLocalTasks.notes(t)}\n${t.snapshot.status}\n"+
        when(t.snapshot.status){AgentStatus.REPORTED->"သင်က ပြီးပြီဟု မှတ်ထားသော အလုပ်ဖြစ်သည်။ ကိရိယာဖြင့် မလုပ်ဆောင်ထားပါ။";AgentStatus.CANCELLED->"သင် ပယ်ဖျက်ထားသော အလုပ်ဖြစ်သည်။ မှတ်တမ်း ဆက်သိမ်းထားသည်။";else->"စက်တွင်း သိမ်းထားပါသည်။ အလိုအလျောက် မလုပ်ဆောင်ရသေးပါ။"}
    val records=t.snapshot.steps.associateBy{it.stepId}
    return "${t.definition.plan.objective}\n${t.state}\n"+t.definition.plan.steps.joinToString("\n\n"){s->
        val r=records[s.stepId]
        val output=when(val d=r?.result){is AgentData.Clock->d.exactDisplay;is AgentData.Battery->"Battery ${d.percent}%";is AgentData.Report->d.exactText
            is AgentData.Draft->d.exactText;is AgentData.Tasks->d.rows.take(25).joinToString("\n"){it.title}
            is AgentData.Priorities->d.rows.joinToString("\n"){it.title};else->"ပြီးစီးသော ရလဒ် မရှိသေးပါ"}
        "${s.stepId}: ${r?.status?:"PENDING"}\nTool: ${s.command.intent}\nArguments: ${s.parameters}\nSource: ${s.connection?.resourceId?:"local/no external source"}\n$output\n${r?.errorCode.orEmpty()}"
    }+"\n${t.reason.orEmpty()}"
}

/** Common production UI over the same encrypted v1 manual-task adapter used by BETA. */
class ConnectedTasksActivity:AppCompatActivity(){
    @Volatile private var owner:Any?=null
    private var workspace:ConnectedWorkspace?=null
    private var work:Job?=null
    private var dialog:AlertDialog?=null
    private val inputs=mutableListOf<EditText>()
    private lateinit var status:TextView
    private lateinit var rows:LinearLayout
    override fun onCreate(saved:Bundle?){ConnectedUi.theme(this);super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body=ConnectedUi.page(this,"ကျွန်ုပ်၏ အလုပ်များ")
        status=ConnectedUi.text(this,"").apply{tag="connected_tasks_status"};body.addView(status)
        body.addView(ConnectedUi.button(this,"+ အလုပ်သစ် ထည့်ရန်","connected_add_task"){form(null)})
        rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="connected_task_rows"};body.addView(rows)
    }
    override fun onResume(){super.onResume();val lease=Any();owner=lease
        try{workspace=ConnectedGraph.workspace(this){owner===lease};reload()}catch(e:Exception){status.text=ConnectedUi.error(e)}}
    override fun onPause(){owner=null;work?.cancel();work=null;clearInputs();dialog?.dismiss();dialog=null;workspace?.discard();workspace=null;rows.removeAllViews();super.onPause()}
    private fun clearInputs(){inputs.forEach{it.text.clear()};inputs.clear()}
    private fun reload(){operate({w->BetaLocalTasks.visible(w.store.list())}){list->
        rows.removeAllViews();status.text=if(list.isEmpty())"အလုပ် မရှိသေးပါ။ ပထမဆုံး အလုပ်တစ်ခု ထည့်ပါ။" else "သိမ်းထားသော မှတ်တမ်း ${list.size} ခု"
        for(t in list){
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,ConnectedUi.dp(context,8),0,ConnectedUi.dp(context,8))}
            card.addView(ConnectedUi.text(this,BetaLocalTasks.title(t),17f))
            card.addView(ConnectedUi.text(this,"${ConnectedUi.state(t.snapshot.status)}"+if(LocalUserTaskRecord.matches(t))" · မိမိထည့်ထားသော စက်တွင်းအလုပ်"else " · အကူအညီ၏ အစီအစဉ်",13f))
            val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(LocalUserTaskRecord.matches(t)&&t.snapshot.status==AgentStatus.DRAFT){
                buttons.addView(ConnectedUi.button(this,"ပြင်ရန်","connected_edit_${t.definition.plan.taskId}"){form(t)})
                buttons.addView(ConnectedUi.button(this,"ပြီးပြီဟု မှတ်ရန်","connected_done_${t.definition.plan.taskId}"){operate({it.localTasks().mark(t,true)}){reload()}})
                buttons.addView(ConnectedUi.button(this,"မလုပ်တော့ပါ","connected_cancel_${t.definition.plan.taskId}"){confirmCancel(t)})
            }
            buttons.addView(ConnectedUi.button(this,"ရလဒ် ကြည့်ရန်","connected_result_${t.definition.plan.taskId}"){
                dialog=AlertDialog.Builder(this).setTitle("သိမ်းထားသော မှတ်တမ်း").setView(ScrollView(this).apply{addView(ConnectedUi.text(this@ConnectedTasksActivity,connectedTaskDescription(t)))})
                    .setPositiveButton("ပိတ်ရန်",null).show().also(ConnectedUi::protect)
            })
            card.addView(HorizontalScrollView(this).apply{addView(buttons)});rows.addView(card)
        }
    }}
    private fun form(expected:BrainTask?){
        if(work!=null)return;val lease=owner?:return
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val title=ConnectedUi.input(this,"အလုပ်အမည်","connected_task_title",160).apply{setText(expected?.let{BetaLocalTasks.title(it)}.orEmpty())}
        val notes=ConnectedUi.input(this,"မှတ်ချက် (မဖြစ်မနေ မဟုတ်ပါ)","connected_task_notes",1000).apply{setText(expected?.let{BetaLocalTasks.notes(it)}.orEmpty())}
        inputs.add(title);inputs.add(notes);body.addView(title);body.addView(notes)
        dialog=AlertDialog.Builder(this).setTitle(if(expected==null)"အလုပ်သစ်"else "အလုပ် ပြင်ရန်").setView(ScrollView(this).apply{addView(body)})
            .setNegativeButton("မလုပ်တော့ပါ"){_,_->clearInputs()}.setPositiveButton("သိမ်းရန်",null).create().also{d->
                d.setOnDismissListener{clearInputs()};d.show();ConnectedUi.protect(d)
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                    if(owner!==lease||dialog!==d||!d.isShowing||work!=null)return@setOnClickListener
                    val name=title.text.toString().trim();val detail=notes.text.toString()
                    if(name.isBlank()||name.contains('\n')){title.error="တစ်ကြောင်းတည်း အလုပ်အမည် ရေးပါ";return@setOnClickListener}
                    clearInputs();d.dismiss();dialog=null
                    operate({w->if(expected==null)w.localTasks().create(name,detail)else w.localTasks().edit(expected,name,detail)}){reload()}
                }
            }
    }
    private fun confirmCancel(t:BrainTask){val lease=owner?:return
        if(work!=null)return
        dialog=AlertDialog.Builder(this).setTitle("အလုပ် ပယ်ဖျက်မလား").setMessage(BetaLocalTasks.title(t)).setNegativeButton("ဆက်ထားရန်",null)
            .setPositiveButton("အလုပ် ပယ်ဖျက်ရန်",null).create().also{d->
                d.show();ConnectedUi.protect(d)
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                    if(owner!==lease||dialog!==d||!d.isShowing)return@setOnClickListener
                    if(work!=null){status.text="လက်ရှိ သိမ်းနေမှု ပြီးသည်အထိ စောင့်ပါ။";return@setOnClickListener}
                    d.dismiss();dialog=null
                    operate({it.localTasks().mark(t,false)}){reload()}
                }
            }
    }
    private fun <T> operate(action:suspend(ConnectedWorkspace)->T,display:(T)->Unit){
        if(work!=null)return;val w=workspace?:return;val lease=owner?:return
        lateinit var launched:Job
        launched=lifecycleScope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){
            try{val result=action(w);withContext(Dispatchers.Main){if(owner===lease){if(work===launched)work=null;display(result)}}}
            catch(c:CancellationException){
                if(c is TimeoutCancellationException)withContext(NonCancellable+Dispatchers.Main){if(owner===lease)status.text=ConnectedUi.error(c)} else throw c
            }catch(e:Exception){withContext(Dispatchers.Main){if(owner===lease)status.text=ConnectedUi.error(e)}}
            finally{withContext(NonCancellable+Dispatchers.Main){if(work===launched)work=null}}
        };work=launched;status.text="ဖတ်နေသည်…";launched.start()
    }
}
