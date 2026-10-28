package com.example.voicebrainlive.beta

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.R
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.brain.*
import kotlinx.coroutines.*

class BetaTaskBoardActivity:AppCompatActivity(){
    private lateinit var rows:LinearLayout
    private lateinit var status:TextView
    private var job:Job?=null
    private var foreground=false
    private var epoch=0L
    private var sequence=0L
    override fun onCreate(state:Bundle?){ThemeUtils.applyTheme(this);super.onCreate(state)
        val p=BetaUi.page(this,BetaWords.get(this,"mytasks"))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(BetaUi.button(this,BetaWords.get(this,"add"),"task_add"){BetaUi.open(this,BetaTasksActivity::class.java)},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(BetaUi.button(this,"↻","task_refresh"){load()}.apply{contentDescription="Reload tasks"},LinearLayout.LayoutParams(BetaUi.dp(this,48),-2))
        p.addView(actions)
        status=BetaUi.text(this,"Loading…",14f).apply{tag="task_board_status"};p.addView(status)
        rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="task_board_rows"};p.addView(rows)
    }
    override fun onResume(){super.onResume();foreground=true;epoch++;load()}
    override fun onPause(){foreground=false;epoch++;sequence++;job?.cancel();job=null;super.onPause()}
    private fun result(t:BrainTask){
        if(!foreground)return
        android.app.AlertDialog.Builder(this).setTitle(BetaWords.get(this,"result"))
            .setMessage(BetaLocalTasks.result(t)).setPositiveButton("OK",null).show().window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun mark(t:BrainTask,done:Boolean){
        if(!foreground||job!=null)return
        val lease=epoch;val stamp=++sequence;var changed=false
        job=lifecycleScope.launch{
            try{withContext(Dispatchers.IO){BetaLocalTasks((application as BetaApplication).betaStore(),owner={foreground&&epoch==lease}).mark(t,done)}
                changed=true
            }catch(c:CancellationException){throw c}
            catch(_:Exception){if(foreground&&epoch==lease)status.text="Change not verified. Reload tasks before retrying."}
            finally{if(epoch==lease&&sequence==stamp){job=null;if(changed&&foreground)load()}}
        }
    }
    private fun load(){
        job?.cancel();status.text="Loading…";rows.removeAllViews();val lease=epoch;val stamp=++sequence
        job=lifecycleScope.launch{
            try{
                val tasks=withContext(Dispatchers.IO){(application as BetaApplication).betaStore().list()}
                if(!foreground||epoch!=lease||sequence!=stamp)return@launch
                val visible=BetaLocalTasks.visible(tasks)
                status.text=if(visible.isEmpty())BetaWords.get(this@BetaTaskBoardActivity,"empty") else "${visible.size} · ${BetaWords.get(this@BetaTaskBoardActivity,"mytasks") }"
                visible.forEach{t->
                    val card=LinearLayout(this@BetaTaskBoardActivity).apply{
                        orientation=LinearLayout.VERTICAL;setPadding(BetaUi.dp(context,12),BetaUi.dp(context,8),BetaUi.dp(context,12),BetaUi.dp(context,8))
                        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=BetaUi.dp(context,12)}
                        background=android.graphics.drawable.GradientDrawable().apply{
                            cornerRadius=BetaUi.dp(context,12).toFloat();setColor(android.graphics.Color.TRANSPARENT)
                            val color=android.util.TypedValue();theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface,color,true);setStroke(BetaUi.dp(context,1),androidx.core.graphics.ColorUtils.setAlphaComponent(color.data,70))
                        }
                    }
                    card.addView(BetaUi.text(this@BetaTaskBoardActivity,BetaLocalTasks.title(t),16f))
                    val manual=BetaLocalTasks.isLocal(t)
                    val state=if(manual)BetaWords.get(this@BetaTaskBoardActivity,when(t.snapshot.status){AgentStatus.REPORTED->"completed";AgentStatus.CANCELLED->"cancelled";else->"todo"}) else "Local plan · ${t.state}"
                    card.addView(BetaUi.text(this@BetaTaskBoardActivity,state,13f))
                    fun open(){startActivity(Intent(this@BetaTaskBoardActivity,BetaTasksActivity::class.java).setPackage(packageName).putExtra("review_task_id",t.definition.plan.taskId))}
                    if(manual){
                        val row=LinearLayout(this@BetaTaskBoardActivity).apply{orientation=LinearLayout.HORIZONTAL}
                        if(t.snapshot.status==AgentStatus.DRAFT){
                            row.addView(BetaUi.button(this@BetaTaskBoardActivity,BetaWords.get(this@BetaTaskBoardActivity,"edit"),"task_open_${t.definition.plan.taskId}"){open()},LinearLayout.LayoutParams(0,-2,1f))
                            row.addView(BetaUi.button(this@BetaTaskBoardActivity,BetaWords.get(this@BetaTaskBoardActivity,"done"),"task_done_${t.definition.plan.taskId}"){mark(t,true)},LinearLayout.LayoutParams(0,-2,1f))
                        }
                        card.addView(row)
                        val second=LinearLayout(this@BetaTaskBoardActivity).apply{orientation=LinearLayout.HORIZONTAL}
                        if(t.snapshot.status==AgentStatus.DRAFT)second.addView(BetaUi.button(this@BetaTaskBoardActivity,BetaWords.get(this@BetaTaskBoardActivity,"cancel"),"task_cancel_${t.definition.plan.taskId}"){mark(t,false)},LinearLayout.LayoutParams(0,-2,1f))
                        second.addView(BetaUi.button(this@BetaTaskBoardActivity,BetaWords.get(this@BetaTaskBoardActivity,"result"),"task_result_${t.definition.plan.taskId}"){result(t)},LinearLayout.LayoutParams(0,-2,1f));card.addView(second)
                    }else card.addView(BetaUi.button(this@BetaTaskBoardActivity,"Open plan / results","task_open_${t.definition.plan.taskId}"){open()})
                    rows.addView(card)
                }
            }catch(c:CancellationException){throw c}
            catch(_:Exception){if(foreground&&epoch==lease)status.text="Tasks unavailable. Records are preserved. Tap ↻ to retry."}
            finally{if(epoch==lease&&sequence==stamp)job=null}
        }
    }
}

/** Plain task form. Existing agent plans still open their original review-only controller. */
class BetaTasksActivity:AppCompatActivity(){
    private var foreground=false
    private var epoch=0L
    private var work:Job?=null
    override fun onCreate(state:Bundle?){ThemeUtils.applyTheme(this);super.onCreate(state)
        val id=intent.getStringExtra("review_task_id")
        if(id!=null&&!id.startsWith("todo_")){
            val outer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            outer.addView(BetaUi.header(this,"Local plan"))
            outer.addView(androidx.fragment.app.FragmentContainerView(this).apply{this.id=R.id.chatSessionContainer},LinearLayout.LayoutParams(-1,0,1f));setContentView(outer)
            if(state==null)supportFragmentManager.beginTransaction().replace(R.id.chatSessionContainer,BetaConversationFragment()).commit()
            return
        }
        val p=BetaUi.page(this,BetaWords.get(this,if(id==null)"newtask" else "edit"))
        fun field(key:String,tag:String,limit:Int)=EditText(this).apply{
            hint=BetaWords.get(this@BetaTasksActivity,key);this.tag=tag;textSize=16f;minHeight=BetaUi.dp(context,48)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO;isSaveEnabled=false;includeFontPadding=true
            filters=arrayOf(android.text.InputFilter.LengthFilter(limit))
        }
        val title=field("title","task_title",160);p.addView(title)
        val notes=field("notes","task_notes",1000).apply{inputType=inputType or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=2;maxLines=5};p.addView(notes)
        val status=BetaUi.text(this,if(id==null)"" else "Loading…",14f).apply{tag="task_editor_status"};p.addView(status)
        var existing:BrainTask?=null
        lateinit var save:Button
        save=BetaUi.button(this,BetaWords.get(this,"save"),"task_save"){
            if(work!=null||!foreground)return@button
            if(title.text.isNullOrBlank()){title.error=BetaWords.get(this,"title");return@button}
            val exactTitle=title.text.toString();val exactNotes=notes.text.toString();val reviewed=existing;val lease=epoch
            save.isEnabled=false;status.text="Saving…"
            work=lifecycleScope.launch{
                try{
                    val saved=withContext(Dispatchers.IO){
                        val repo=BetaLocalTasks((application as BetaApplication).betaStore(),owner={foreground&&epoch==lease})
                        if(reviewed==null)repo.create(exactTitle,exactNotes) else repo.edit(reviewed,exactTitle,exactNotes)
                    }
                    if(foreground&&epoch==lease){setResult(RESULT_OK,Intent().putExtra("saved_task_id",saved.definition.plan.taskId));finish()}
                }catch(c:CancellationException){throw c}
                catch(e:Exception){if(foreground&&epoch==lease){status.text=if((e as? BrainFailure)?.code=="DUPLICATE_TASK_OR_CAPACITY")"History is full (8 versions). Nothing was deleted; this save did not succeed." else "Save not verified. Existing tasks are preserved. Reopen Tasks before retrying.";save.isEnabled=true}}
                finally{if(epoch==lease)work=null}
            }
        };p.addView(save)
        if(id!=null){save.isEnabled=false
            lifecycleScope.launch{
                try{existing=withContext(Dispatchers.IO){(application as BetaApplication).betaStore().list().single{it.definition.plan.taskId==id}}
                    val t=existing!!;check(BetaLocalTasks.isLocal(t)&&t.snapshot.status==AgentStatus.DRAFT)
                    title.setText(BetaLocalTasks.title(t));notes.setText(BetaLocalTasks.notes(t));status.text="";save.isEnabled=true
                }catch(_:Exception){status.text="This task cannot be edited. Reopen Tasks to review its current state."}
            }
        }
    }
    override fun onResume(){super.onResume();foreground=true;epoch++}
    override fun onPause(){foreground=false;epoch++;work?.cancel();work=null;super.onPause()}
}
