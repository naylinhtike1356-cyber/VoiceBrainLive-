package com.example.voicebrainlive.beta

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import kotlinx.coroutines.*

/** Only reviewed ordinary BETA windows may be captured. Unknown and credential windows fail closed. */
object BetaCapturePolicy {
    private val ordinary = setOf(
        "com.example.voicebrainlive.beta.BetaMainActivity", "com.example.voicebrainlive.beta.BetaTasksActivity",
        "com.example.voicebrainlive.beta.BetaTaskBoardActivity", "com.example.voicebrainlive.beta.BetaHelpActivity",
        "com.example.voicebrainlive.beta.BetaNotionActivity", "com.example.voicebrainlive.beta.BetaDiagnosticsActivity",
        "com.example.voicebrainlive.SettingsActivity", "com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryActivity",
        "com.example.voicebrainlive.agent.brain.AgentActivityInboxActivity", "com.example.voicebrainlive.updates.AppUpdateActivity")
    fun permits(name: String) = name in ordinary
    fun apply(a: Activity) {
        if (permits(a.javaClass.name)) a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Compact primary labels only. Security disclosures keep their exact reviewed English wording. */
object BetaWords {
    private val en=mapOf("home" to "Home","chat" to "Chat","tasks" to "Tasks","memory" to "Memory","settings" to "Settings",
        "start" to "Get started","add" to "+ Add task","help" to "Help","mytasks" to "My Tasks","greeting" to "Hello. What would you like to do?",
        "chat_action" to "Chat with Assistant","off" to "AI is off · Local features available","today" to "Today's local tasks",
        "approvals" to "Activity & Approvals","empty" to "No tasks yet. Create your first task.","newtask" to "New task","edit" to "Edit",
        "title" to "Task title","notes" to "Notes (optional)","save" to "Save task","done" to "Mark done","cancel" to "Cancel","result" to "View result",
        "todo" to "To do","completed" to "Done · marked by you","cancelled" to "Cancelled","general" to "General","language" to "Language",
        "theme" to "Theme","voice" to "Voice input language","ai" to "AI","provider" to "Provider setup","personal" to "Personalization",
        "memoryuse" to "Memory use","app" to "App","about" to "About & Updates","diagnostics" to "Diagnostics","more" to "More settings",
        "localactions" to "Local actions","stop" to "Stop","plan" to "Local plan preview","notconnected" to "Not connected","notion" to "SmartNote / Notion")
    private val my=mapOf("home" to "ပင်မ","chat" to "စကားပြော","tasks" to "အလုပ်များ","memory" to "မှတ်စု","settings" to "ဆက်တင်",
        "start" to "စတင်ရန်","add" to "+ အလုပ်ထည့်ရန်","help" to "အကူအညီ","mytasks" to "မိမိအလုပ်များ","greeting" to "မင်္ဂလာပါ။ ဘာလုပ်ချင်ပါသလဲ။",
        "chat_action" to "လက်ထောက်နှင့် စကားပြောရန်","off" to "AI မချိတ်ရသေးပါ · ဖုန်းတွင်းအလုပ်များ သုံးနိုင်သည်",
        "today" to "ယနေ့အလုပ်များ","approvals" to "လုပ်ဆောင်မှုနှင့် အတည်ပြုရန်များ","empty" to "အလုပ်မရှိသေးပါ။ ပထမအလုပ်ကို ထည့်ပါ။",
        "newtask" to "အလုပ်အသစ်","edit" to "ပြင်ရန်","title" to "အလုပ်အမည်","notes" to "အသေးစိတ် (မဖြစ်မနေ မလိုပါ)",
        "save" to "အလုပ်သိမ်းရန်","done" to "ပြီးစီးကြောင်း မှတ်ရန်","cancel" to "ပယ်ဖျက်ရန်","result" to "ရလဒ်ကြည့်ရန်",
        "todo" to "လုပ်ရန်","completed" to "မိမိက ပြီးစီးကြောင်း မှတ်ထားသည်","cancelled" to "ပယ်ဖျက်ထားသည်","general" to "အထွေထွေ",
        "language" to "ဘာသာစကား","theme" to "မျက်နှာပြင်အရောင်","voice" to "အသံထည့်သွင်းမှု ဘာသာစကား","ai" to "AI",
        "provider" to "AI ချိတ်ဆက်ရန်","personal" to "မိမိစိတ်ကြိုက်","memoryuse" to "မှတ်စုအသုံးပြုမှု","app" to "အက်ပ်",
        "about" to "ဗားရှင်းနှင့် အပ်ဒိတ်","diagnostics" to "စစ်ဆေးရန်","more" to "နောက်ထပ်ဆက်တင်များ","localactions" to "ဖုန်းတွင်းလုပ်ဆောင်မှု",
        "stop" to "ရပ်ရန်","plan" to "ဖုန်းတွင်းအစီအစဉ်","notconnected" to "မချိတ်ဆက်ရသေးပါ","notion" to "SmartNote / Notion")
    private val ko=mapOf("home" to "홈","chat" to "채팅","tasks" to "할 일","memory" to "메모리","settings" to "설정","start" to "시작하기","add" to "+ 할 일 추가","help" to "도움말")
    fun language(a:android.content.Context)=a.getSharedPreferences("beta_options",0).getString("ui_language","my")?:"my"
    fun get(a:android.content.Context,key:String):String=(when(language(a)){"my"->my;"ko"->ko;else->en})[key]?:en[key]?:key
    val tutorial=listOf("Home → Chat\nAI is off. Local actions are in the compact Chat menu.",
        "Tasks → + Add task\nWrite a title and Save. Reopen, Edit or Mark done from the task card.",
        "Memory → Add confirmed memory\nReview the exact information before saving. You control sharing.",
        "Activity & Approvals\nInspect real results or cancel a proposal. A preview never grants permission.")
}

class BetaHelpActivity:AppCompatActivity(){
    private var position=0
    override fun onCreate(state:Bundle?){ThemeUtils.applyTheme(this);super.onCreate(state)
        val p=BetaUi.page(this,BetaWords.get(this,"help"))
        val intro=BetaUi.text(this,"Try one local task first. AI, Notion and background delivery are off.");p.addView(intro)
        val step=BetaUi.text(this,"").apply{tag="tutorial_step"};p.addView(step)
        position=(state?.getInt("step")?:0).coerceIn(0,3)
        fun paint(){step.text="${position+1}/4 · ${BetaWords.tutorial[position]}"}
        paint()
        p.addView(BetaUi.button(this,"Next / Finish","tutorial_next"){
            if(position<3){position++;paint()}else complete()
        })
        p.addView(BetaUi.button(this,"Skip","tutorial_skip"){complete()})
        p.addView(BetaUi.button(this,"Try adding a task","help_task"){BetaUi.open(this,BetaTasksActivity::class.java)})
        p.addView(BetaUi.button(this,"Open Memory","help_memory"){BetaUi.open(this,com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryActivity::class.java)})
        p.addView(BetaUi.button(this,"Activity & Approvals","help_activity"){BetaUi.open(this,com.example.voicebrainlive.agent.brain.AgentActivityInboxActivity::class.java)})
        p.addView(BetaUi.text(this,"Screenshots include visible chat and task text. Avoid secrets. Memory details and provider setup stay protected. Notifications and task due dates are not supported in this offline build."))
    }
    override fun onSaveInstanceState(out:Bundle){out.putInt("step",position);super.onSaveInstanceState(out)}
    private fun complete(){
        if(getSharedPreferences("beta_options",0).edit().putBoolean("ux_tutorial_seen",true).commit())finish()
        else Toast.makeText(this,"Could not save tutorial preference. You can still use local features.",Toast.LENGTH_LONG).show()
    }
}

/** Allowlisted summary, not arbitrary logs/exception strings. Never includes user payloads or identifiers. */
object BetaDiagnosticSummary {
    fun create(screen:String,testState:String):String {
        val safeScreen=if(screen in setOf("Diagnostics","Home","Chat","Tasks","Memory","Settings"))screen else "Unknown"
        val safeState=if(testState in setOf("NOT RUN","RUNNING","PASS","FAIL"))testState else "UNKNOWN"
        return "VoiceBrainLive BETA ${com.example.voicebrainlive.BuildConfig.VERSION_NAME} (${com.example.voicebrainlive.BuildConfig.VERSION_CODE})\n"+
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n"+
            "Screen: $safeScreen\nCategory: LOCAL_DIAGNOSTIC\nUTC: ${java.time.Instant.now()}\n"+
            "UI: foreground diagnostic review; AI/network/background OFF\nTest status: $safeState\nNo conversations, memories, keys, contacts or device identifiers included."
    }
}
