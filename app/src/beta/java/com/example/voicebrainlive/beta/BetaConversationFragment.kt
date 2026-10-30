package com.example.voicebrainlive.beta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.*
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voicebrainlive.R
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.databinding.FragmentChatBinding
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryActivity
import com.example.voicebrainlive.ui.ChatMessage
import com.example.voicebrainlive.ui.ChatMessageAdapter
import kotlinx.coroutines.*

/** Real local workflow in the original Chat layout/bubbles. No legacy plaintext conversation history. */
class BetaConversationFragment : Fragment() {
    private var binding: FragmentChatBinding? = null
    private lateinit var adapter: ChatMessageAdapter
    private var recipe: BetaRecipe? = null
    private lateinit var notice: TextView
    private lateinit var save: Button
    private lateinit var run: Button
    private lateinit var cancel: Button
    private lateinit var edit: Button
    private var session: BetaLocalAgent? = null
    @Volatile private var owner: Any? = null
    @Volatile private var operation = 0L
    private var work: Job? = null
    private var preview: BrainTask? = null
    private var selected: BrainTask? = null
    private var dialog: AlertDialog? = null
    private var messages = listOf<ChatMessage>()
    private var messageSequence = 0L
    private data class Reply(val task: BrainTask? = null, val previewOnly: Boolean = false, val rows: List<BrainTask>? = null, val editGoal: String? = null, val editRecipe: BetaRecipe? = null)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View = FragmentChatBinding.inflate(inflater.cloneInContext(android.view.ContextThemeWrapper(requireContext(),R.style.ThemeOverlay_VoiceBrainLive_BetaChat)),container,false).also { binding=it }.root
    override fun onViewCreated(view: View, state: Bundle?) {
        val b=binding!!
        b.tvChatTitle.text=BetaWords.get(requireContext(),"chat")
        b.etMessageInput.hint=if(BetaWords.language(requireContext())=="my")"စာရေးပါ…" else "Write a message…"
        b.etMessageInput.filters=arrayOf(InputFilter.LengthFilter(2000)); b.etMessageInput.isSaveEnabled=false; b.etMessageInput.isSaveFromParentEnabled=false
        adapter=ChatMessageAdapter(allowTransfer=false)
        b.recyclerViewChat.layoutManager=LinearLayoutManager(requireContext()).apply { stackFromEnd=true }; b.recyclerViewChat.adapter=adapter
        val controls=LinearLayout(requireContext()).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        notice=BetaUi.text(requireContext(),chatOff(),13f).apply{tag="beta_chat_status"}
        controls.addView(notice,LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(BetaUi.button(requireContext(),"⋮","beta_local_actions"){localActions()}.apply{contentDescription=BetaWords.get(requireContext(),"localactions");minimumWidth=BetaUi.dp(context,48)})
        (b.root as LinearLayout).addView(controls,1,LinearLayout.LayoutParams(-1,-2))
        val buttons=LinearLayout(requireContext()).apply {orientation=LinearLayout.VERTICAL}
        fun button(text:String,tag:String,action:()->Unit)=Button(requireContext()).apply {this.text=text;this.tag=tag;contentDescription=text;minHeight=(48*resources.displayMetrics.density).toInt();isAllCaps=false;textSize=14f;layoutParams=LinearLayout.LayoutParams(-2,-2);setOnClickListener {if(isResumed)action()}}.also {buttons.addView(it)}
        save=button("Save plan","beta_save") {val t=preview?:return@button;operate {Reply(task=it.saveReviewed(t.definition.plan.taskId,t.definition.digest))}}
        run=button("Run local steps","beta_run") {val t=selected?:return@button;operate {Reply(task=it.runReviewed(t))}}
        cancel=button("Cancel / reject","beta_cancel") {
            if(work!=null){operation++;session?.discardPreview();work?.cancel();preview=null;selected=null;message("Cancelled locally. No external action executed.");refresh()}
            else selected?.let {t-> confirm(t,"Cancel this exact task/proposal?",false)} ?: kotlin.run {session?.discardPreview();preview=null;message("Preview discarded; nothing saved or executed.");refresh()}
        }
        edit=button("Edit task","beta_edit") {selected?.let {t->confirm(t,"Cancel this version and edit as a new plan? History is retained. Preview and save are required again.",true)}}
        button("Plan details","beta_plan_details") {
            (selected?:preview)?.let { t -> dialog=AlertDialog.Builder(requireContext()).setTitle("Exact plan details").setView(ScrollView(requireContext()).apply {addView(BetaUi.text(requireContext(),BetaLocalAgent.describe(t)))}).setPositiveButton("Close",null).show().also{it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)} }
                ?: message("Preview or open a saved plan first.")
        }
        val previewScroll=ScrollView(requireContext()).apply{tag="beta_action_scroll";isFillViewport=false;addView(buttons);visibility=View.GONE}
        (b.root as LinearLayout).addView(previewScroll,2,LinearLayout.LayoutParams(-1,0,0.65f))
        b.btnSendMessage.contentDescription="Preview a validated local plan";b.btnSendMessage.setOnClickListener {previewGoal()}
        b.btnClearChat.contentDescription="Clear visible conversation only";b.btnClearChat.setOnClickListener {messages=emptyList();adapter.submitList(messages)}
        b.etMessageInput.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){invalidatePreview()}
            override fun afterTextChanged(s:Editable?){}
        })
        b.etMessageInput.setOnEditorActionListener{_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){previewGoal();true}else false}
        refresh()
    }
    override fun onResume() {
        super.onResume();val lease=Any();owner=lease
        activity?.findViewById<View>(android.R.id.content)?.findViewWithTag<View>("beta_app_toolbar")?.visibility=View.GONE
        val updates=viewLifecycleOwner.lifecycleScope // Capture on the main thread while the view exists.
        session=(requireActivity().application as BetaApplication).localAgent({owner===lease&&isResumed}) {task->
            val stamp=operation
            updates.launch(Dispatchers.Main){if(owner===lease&&isResumed&&operation==stamp){selected=task;message(summary(task));refresh()}}
        }
        val goal=(activity as? BetaMainActivity)?.takePendingGoal()
        if(!goal.isNullOrBlank()) {binding?.etMessageInput?.setText(goal); }
        notice.text=chatOff()
        val reviewId=activity?.intent?.getStringExtra("review_task_id")
        if(!reviewId.isNullOrBlank()){activity?.intent?.removeExtra("review_task_id");operate {Reply(task=it.restoreForReview(reviewId))}}
        refresh()
    }
    override fun onPause() {
        (activity as? BetaMainActivity)?.stopMic()
        owner=null;operation++;dialog?.dismiss();dialog=null;session?.discardPreview();work?.cancel();session=null;preview=null;selected=null;recipe=null
        binding?.etMessageInput?.text?.clear();messages=emptyList();if(::adapter.isInitialized)adapter.submitList(messages)
        super.onPause()
    }
    override fun onDestroyView(){binding?.recyclerViewChat?.adapter=null;binding=null;super.onDestroyView()}
    private fun chatOff()=if(BetaWords.language(requireContext())=="my")"AI မချိတ်ရသေးပါ" else "AI OFF · Local mode"
    private fun localActions(){
        val lease=owner?:return
        val items=BetaRecipe.entries.map{it.title}+listOf("Saved plans & results","On-device voice input","AI provider · OFF")
        dialog=AlertDialog.Builder(requireContext()).setTitle(BetaWords.get(requireContext(),"localactions"))
            .setItems(items.toTypedArray()){_,i->
                if(owner!==lease||!isResumed)return@setItems
                if(i<BetaRecipe.entries.size){
                    invalidatePreview();recipe=BetaRecipe.entries[i]
                    if(binding?.etMessageInput?.text.isNullOrBlank())binding?.etMessageInput?.setText(when(recipe){BetaRecipe.STATUS->"Check device time and battery";BetaRecipe.STUDY->"Plan my saved tasks for tonight";else->"Prepare a reminder proposal"})
                    refresh()
                }else when(i){
                    3->operate{Reply(rows=it.saved())}
                    4->(activity as? BetaMainActivity)?.requestMic()?:message("Open Chat from Home to use optional on-device voice input.")
                    5->startActivity(Intent(requireContext(),BetaPilotActivity::class.java).setPackage(requireContext().packageName))
                }
            }.setNegativeButton("Close",null).show()
    }
    private fun invalidatePreview(){preview=null;selected=null;session?.discardPreview();if(::save.isInitialized)refresh()}
    private fun message(text:String,role:String="model") {
        if(binding==null)return
        messages=(messages+ChatMessage(id=++messageSequence,role=role,content=text.take(32768))).takeLast(30)
        adapter.submitList(messages){binding?.recyclerViewChat?.scrollToPosition((messages.size-1).coerceAtLeast(0))}
    }
    private fun summary(t:BrainTask):String {
        val outputs=t.snapshot.steps.mapNotNull { row -> when(val d=row.result){
            is com.example.voicebrainlive.agent.AgentData.Report -> d.exactText
            is com.example.voicebrainlive.agent.AgentData.Draft -> d.exactText
            is com.example.voicebrainlive.agent.AgentData.Clock -> d.exactDisplay
            is com.example.voicebrainlive.agent.AgentData.Battery -> "Battery: ${d.percent}%"
            else -> null
        }}
        val steps=t.definition.plan.steps.joinToString(" → "){when(it.command.intent){
            "get_current_time"->"Time";"get_battery_status"->"Battery";"agent_local_report"->"Local report"
            "agent_read_tasks"->"Read saved tasks";"agent_prioritize_tasks"->"Select by recency";"agent_study_draft"->"Draft plan";else->"Approval proposal only"
        }}
        val proposal=t.definition.plan.steps.filter{it.command.intent=="set_reminder"}.joinToString("\n"){"Proposed reminder: ${it.command.target}\n${it.command.value}\nNot scheduled or approved."}
        return "${t.definition.plan.objective}\n${t.state.name.replace('_',' ')}\n$steps\n"+outputs.joinToString("\n")+"\n$proposal"+(if(t.reason!=null)"\n${t.reason}" else "")
    }
    private fun refresh(){
        val ready=owner!=null&&isResumed&&work==null
        binding?.root?.findViewWithTag<View>("beta_action_scroll")?.visibility=if(preview!=null||selected!=null||work!=null)View.VISIBLE else View.GONE
        cancel.visibility=if(preview!=null||selected!=null||work!=null)View.VISIBLE else View.GONE
        notice.text=if(work!=null)"Preparing local plan…" else recipe?.let{"Local · ${it.title}"}?:chatOff()
        save.visibility=if(preview!=null)View.VISIBLE else View.GONE
        save.isEnabled=ready&&preview!=null
        run.visibility=if(selected!=null)View.VISIBLE else View.GONE
        run.isEnabled=ready&&selected?.snapshot?.status==AgentStatus.DRAFT&&selected!!.definition.plan.deadlineMillis>System.currentTimeMillis()
        edit.visibility=if(selected!=null)View.VISIBLE else View.GONE
        edit.isEnabled=ready&&selected?.snapshot?.status in setOf(AgentStatus.DRAFT,AgentStatus.BLOCKED,AgentStatus.FAILED,AgentStatus.WAITING_FOR_APPROVAL)
        cancel.isEnabled=owner!=null&&isResumed
        binding?.btnSendMessage?.isEnabled=ready;binding?.etMessageInput?.isEnabled=work==null
    }
    private fun previewGoal(){
        val goal=binding?.etMessageInput?.text.toString().trim()
        val selectedRecipe=recipe
        if(goal.isBlank()){binding?.etMessageInput?.error="Enter your goal first";return}
        message(goal,"user")
        if(selectedRecipe==null){
            message(if(BetaWords.language(requireContext())=="my")"AI မချိတ်ရသေးသဖြင့် ဒီစာကို နားလည်ပြီး မစီစဉ်နိုင်သေးပါ။ ဖုန်းတွင်းလုပ်ဆောင်မှု မီနူးမှ ရွေးပါ။ အလုပ်သိမ်းလိုလျှင် Tasks ကိုဖွင့်ပါ။" else "AI is off, so I cannot interpret this freely. Choose Local actions for a supported workflow, or use Tasks to save a task.")
            return
        }
        operate {Reply(task=it.preview(goal,selectedRecipe),previewOnly=true)}
    }
    private fun confirm(t:BrainTask,text:String,editing:Boolean){
        val lease=owner?:return
        dialog=AlertDialog.Builder(requireContext()).setTitle(if(editing)"Edit task" else "Cancel task").setMessage(text+"\n\n"+t.definition.plan.objective+"\n"+t.state)
            .setNegativeButton("Keep unchanged",null).setPositiveButton(if(editing)"Cancel old version and edit" else "Cancel exact task") {_,_->
                if(owner!==lease||!isResumed)return@setPositiveButton
                val recipe=when {t.definition.plan.steps.any {it.command.intent=="set_reminder"}->BetaRecipe.REMINDER;t.definition.plan.steps.any {it.command.intent=="agent_read_tasks"}->BetaRecipe.STUDY;else->BetaRecipe.STATUS}
                operate {Reply(task=it.cancelReviewed(t),editGoal=if(editing)t.definition.plan.objective else null,editRecipe=recipe)}
            }.show().also {it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    private fun operate(action:suspend(BetaLocalAgent)->Reply){
        if(work!=null)return
        val s=session?:return;val lease=owner?:return;val stamp=++operation
        lateinit var launched:Job
        launched=viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){
            try {
                val result=action(s)
                withContext(Dispatchers.Main){if(owner===lease&&isResumed&&operation==stamp){
                    if(work===launched)work=null // Result actions are available when shown; final cleanup cannot clear a newer job.
                    preview=if(result.previewOnly)result.task else null;selected=if(result.previewOnly)null else result.task
                    result.task?.let {message((if(result.previewOnly)"Preview only — not saved or run\n" else "Saved task\n")+summary(it))}
                    result.rows?.let {rows->
                        if(rows.isEmpty())message("No saved BETA tasks yet. Preview and Save your own goal.")
                        else dialog=AlertDialog.Builder(requireContext()).setTitle("Your encrypted BETA tasks")
                            .setItems(rows.map {"${it.state}: ${it.definition.plan.objective}"}.toTypedArray()){_,i->if(owner===lease&&isResumed)operate {Reply(task=it.restoreForReview(rows[i].definition.plan.taskId))}}
                            .setNegativeButton("Close",null).show().also {it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
                    }
                    result.editGoal?.let {binding?.etMessageInput?.setText(it);recipe=result.editRecipe;message("Old version cancelled. Edit the goal, Send to preview, then Save as a new immutable plan.")}
                    refresh()
                }}
            }catch(c:CancellationException){throw c}
            catch(e:Exception){withContext(Dispatchers.Main){if(owner===lease&&isResumed&&operation==stamp){preview=null;selected=null;message(when((e as? BrainFailure)?.code){
                    "DUPLICATE_TASK_OR_CAPACITY"->"Task history is full (8 checkpoints). Existing history is preserved. No task was saved. See Diagnostics for the current limit."
                    "STALE_TASK_OWNER","FRESH_REVIEW_REQUIRED"->"Open the saved task to inspect or cancel. Edit/replan and review it again before running."
                    else->"Could not verify the task change. Existing records are preserved. Check Saved tasks before retrying."
                })}}}
            finally{withContext(NonCancellable+Dispatchers.Main){if(work===launched)work=null;if(binding!=null)refresh()}}
        }
        work=launched;refresh();launched.start()
    }
}
