package com.example.voicebrainlive.agent.connected

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.agent.brain.BrainFailure
import kotlinx.coroutines.*

internal object ConnectedUi {
    fun state(value:Any?):String=when(value?.toString()){
        "DRAFT"->"မူကြမ်း";"PLANNED"->"အစီအစဉ် ရေးပြီး";"VALIDATED"->"စစ်ဆေးပြီး";"SCHEDULED"->"အချိန်စောင့်နေသည်"
        "RUNNING"->"လုပ်ဆောင်နေသည်";"SUCCEEDED","COMPLETED"->"လုပ်ဆောင်ပြီး";"REPORTED"->"မိမိက ပြီးပြီဟု မှတ်ထားသည်"
        "CANCELLED"->"ပယ်ဖျက်ပြီး";"BLOCKED"->"ရပ်ထားသည်";"FAILED"->"မအောင်မြင်ပါ";"PAUSED"->"ခဏရပ်ထားသည်"
        "WAITING_FOR_APPROVAL"->"အတည်ပြုချက် စောင့်နေသည်";"PENDING"->"စောင့်နေသည်";null->"မစရသေးပါ";else->value.toString()
    }
    fun theme(a:AppCompatActivity){
        ThemeUtils.applyTheme(a)
        a.delegate.localNightMode=if(ThemeUtils.getTheme(a)=="light")
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
    }
    fun dp(c:Context,n:Int)=(c.resources.displayMetrics.density*n).toInt()
    fun text(c:Context,s:String,size:Float=15f)=TextView(c).apply{text=s;textSize=size;includeFontPadding=true
        setLineSpacing(dp(c,3).toFloat(),1f);setPadding(dp(c,4),dp(c,6),dp(c,4),dp(c,6));isSaveEnabled=false}
    fun button(c:Context,s:String,t:String,action:()->Unit)=Button(c).apply{text=s;tag=t;contentDescription=s;textSize=14f;isAllCaps=false;minHeight=dp(c,48)
        layoutParams=LinearLayout.LayoutParams(-2,-2);setOnClickListener{action()}}
    fun input(c:Context,hintText:String,t:String,max:Int,secret:Boolean=false)=EditText(c).apply{
        hint=hintText;tag=t;contentDescription=hintText;textSize=16f;includeFontPadding=true;minHeight=dp(c,48)
        inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters=arrayOf(InputFilter.LengthFilter(max));isSaveEnabled=false;isSaveFromParentEnabled=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setPadding(dp(c,8),dp(c,8),dp(c,8),dp(c,8))
    }
    fun page(a:Activity,title:String):LinearLayout{
        a.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(a,12),0,dp(a,12),0)}
        val header=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        header.addView(button(a,"‹","connected_back"){a.finish()}.apply{contentDescription="နောက်သို့"})
        header.addView(text(a,title,18f),LinearLayout.LayoutParams(0,-2,1f));root.addView(header)
        val scroll=ScrollView(a).apply{isFillViewport=true}
        val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(a,4),0,dp(a,4),dp(a,16))}
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));a.setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(dp(a,12)+bars.left,bars.top,dp(a,12)+bars.right,maxOf(bars.bottom,ime.bottom));insets
        };ViewCompat.requestApplyInsets(root);return body
    }
    fun open(a:Activity,type:Class<out Activity>){a.startActivity(Intent(a,type).setPackage(a.packageName))}
    fun protect(d:AlertDialog){d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    fun error(e:Throwable):String=when((e as? ConnectionFailure)?.code?: (e as? BrainFailure)?.code){
        "SECURE_SETTINGS_UNAVAILABLE","PILOT_SECURE_CREDENTIAL_UNAVAILABLE"->"လုံခြုံသော ဆက်တင်များကို ဖတ်မရပါ။ ရိုးရိုးစာသားအဖြစ် အစားထိုးမသိမ်းပါ။ စက်ဖွင့်ပြီး ပြန်ကြိုးစားပါ။"
        "TEXT_MODEL_REQUIRED"->"AI နှင့် Notion ဆက်တင်တွင် Gemini စာသားမော်ဒယ် ရွေးပါ။ အသံမော်ဒယ် မပြောင်းပါ။"
        "GEMINI_CREDENTIAL_REQUIRED"->"သုံးနိုင်သော Gemini key မရှိသေးပါ။ AI နှင့် Notion ဆက်တင်တွင် လုံခြုံစွာ ထည့်ပါ။"
        "NOTION_CREDENTIAL_REQUIRED","NOTION_DATABASE_ID_REQUIRED"->"AI နှင့် Notion ဆက်တင်တွင် ရှိပြီးသား Notion token နှင့် ဒေတာဘေ့စ် ID အတိအကျ ထည့်ပါ။"
        "NOTION_AUTHENTICATION","PILOT_AUTHENTICATION"->"အကောင့် အတည်ပြုမှု မအောင်မြင်ပါ။ ရွေးထားသောအကောင့်နှင့် key ကို စစ်ပါ။"
        "PILOT_RATE_LIMIT_OR_QUOTA","NOTION_RATE_LIMITED"->"ဝန်ဆောင်မှု အသုံးပြုခွင့်ပမာဏ သို့မဟုတ် အကြိမ်နှုန်း ပြည့်နေသည်။ အလိုအလျောက် ထပ်မပို့ပါ။ ခဏစောင့်ပြီး quota ကို စစ်ပါ။"
        "NOTION_PERMISSION_OR_DATABASE_UNAVAILABLE"->"Notion ဒေတာဘေ့စ် မရနိုင်ပါ သို့မဟုတ် ချိတ်ဆက်မှုကို မျှဝေခွင့် မပေးထားပါ။"
        "BUSINESS_STATUS_MAPPING_REQUIRED","SYNC_STATUS_IS_NOT_TASK_COMPLETION"->"အလုပ်ပြီး/မပြီး အကွက်ကို အရင်သတ်မှတ်ပါ။ ဒေတာကူးယူပြီးခြင်းသည် အလုပ်ပြီးခြင်း မဟုတ်ပါ။"
        "NOTION_SCHEMA_CHANGED","NOTION_SCHEMA_OR_LEGACY_API_UNSUPPORTED"->"Notion ဒေတာပုံစံနှင့် လက်ရှိ API မကိုက်ညီပါ။ သတ်မှတ်ချက်ကို စစ်ပါ။ ဒေတာ မပြောင်းပါ။"
        "DUPLICATE_TASK_OR_CAPACITY"->"အလုပ်မှတ်တမ်း ၈ ခု ပြည့်နေသည်။ မူလမှတ်တမ်းများ မဖျက်ပါ။ အသစ် မသိမ်းနိုင်ပါ။"
        "PILOT_INVALID_OR_UNSAFE_RESPONSE","PILOT_TOOL_FORBIDDEN","PLAN_ADMISSION_REJECTED"->"AI အဖြေ မပြည့်စုံပါ သို့မဟုတ် လုံခြုံရေးစည်းမျဉ်း မကိုက်ပါ။ အစီအစဉ် မလုပ်ဆောင်ပါ။"
        "FRESH_REVIEW_REQUIRED","STALE_REVIEW_OR_CHANGED_ARGUMENTS","EXPLICIT_RECOVERY_REQUIRED_OR_TERMINAL"->"ဤအလုပ်ကို အသစ် ပြန်စစ်အတည်ပြုရန် လိုပါသည်။ အရင်အကြိမ် ခွင့်ပြုချက်ကို ပြန်မသုံးပါ။"
        else->if(e is TimeoutCancellationException)"စောင့်ဆိုင်းချိန် ကုန်သွားပါပြီ။ အောင်မြင်သည်ဟု အတည်မပြုနိုင်ပါ။ ထပ်မလုပ်မီ သိမ်းထားသောအခြေအနေ စစ်ပါ။"
            else if(e is CancellationException)"စက်တွင်း ရပ်ပြီးပါပြီ။ ပို့ပြီးသော တောင်းဆိုချက်ကို ငွေကောက်ခံနိုင်သေးသည်။"
            else "ဤလုပ်ဆောင်မှုကို အတည်မပြုနိုင်ပါ။ အောင်မြင်သည်ဟု မဖော်ပြပါ။ ထပ်မလုပ်မီ ရင်းမြစ်နှင့် ဆက်တင်များ စစ်ပါ။"
    }
}

/** Existing encrypted credentials only. Nothing is read from BuildConfig, external apps or backups. */
class ConnectedSettingsActivity:AppCompatActivity(){
    private lateinit var settings:ConnectedSettings
    private var owner:Any?=null
    private var dialog:AlertDialog?=null
    private val secrets=mutableListOf<EditText>()
    private var job:Job?=null
    private lateinit var state:TextView
    override fun onCreate(saved:Bundle?){ConnectedUi.theme(this);super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings=ConnectedGraph.settings(this)
        val body=ConnectedUi.page(this,"AI နှင့် Notion ချိတ်ဆက်မှု")
        state=ConnectedUi.text(this,"မချိတ်ဆက်ရသေးပါ").apply{tag="connected_settings_status"};body.addView(state)
        body.addView(ConnectedUi.text(this,"ရှိပြီးသား Gemini key နှင့် Notion ချိတ်ဆက်မှုကို သုံးနိုင်သည်။ မိမိ ပြန်စစ်အတည်ပြုမှသာ ကွန်ရက်သို့ ပို့မည်။ နောက်ခံ AI တောင်းဆိုမှု မဖွင့်ထားပါ။"))
        body.addView(ConnectedUi.button(this,"Gemini စာသားမော်ဒယ် ရွေးရန်","connected_model"){editModel()})
        body.addView(ConnectedUi.button(this,"Gemini key ကို လုံခြုံစွာ ပြင်ရန်","connected_key"){editGemini()})
        body.addView(ConnectedUi.button(this,"ရှိပြီးသား Notion ချိတ်ဆက်မှု စစ်ရန်","connected_notion_settings"){editNotion()})
        body.addView(ConnectedUi.button(this,"ကိုယ်ရေးလုံခြုံမှုနှင့် ကုန်ကျစရိတ်","connected_privacy"){
            dialog=AlertDialog.Builder(this).setTitle("မချိတ်ဆက်မီ သိထားရန်")
                .setMessage("Gemini ထံ သင်ပြန်စစ်ထားသော စာသားနှင့် အက်ပ်၏ ပုံသေညွှန်ကြားချက်များကိုသာ ပို့မည်။ ကိုယ်ပိုင်မှတ်ဉာဏ်၊ သိမ်းထားသောအလုပ်၊ စကားပြောမှတ်တမ်း၊ Notion ဒေတာ၊ အဆက်အသွယ်နှင့် အသံကို အလိုအလျောက် မပို့ပါ။ စကားပြောပုံစံတွင် ကိရိယာ မလုပ်ဆောင်ပါ။ အလုပ်စီစဉ်ပုံစံတွင် စစ်ဆေးထားသော ဖတ်ရှု/စက်တွင်းအဆင့်များကိုသာ ခွင့်ပြုသည်။\n\nပြန်စစ်မှုတစ်ကြိမ်လျှင် တောင်းဆိုချက်တစ်ခုသာ ပို့မည်။ အဖြေကန့်သတ်ချက်မှာ စကားပြော ၅၁၂ တိုကင်၊ အစီအစဉ် ၁၀၂၄ တိုကင် ဖြစ်သည်။ အဝင်စာသားနှင့် စဉ်းစားမှုတိုကင်များကိုလည်း ငွေကောက်ခံနိုင်သည်။ ဤကန့်သတ်ချက်သည် ငွေသုံးစွဲမှုအများဆုံးပမာဏ မဟုတ်ပါ။ မိမိ project ၏ quota နှင့် ဈေးနှုန်းကို စစ်ပါ။ ရပ်လိုက်ခြင်းက ဝန်ဆောင်မှုပေးသူ စလုပ်ပြီးသောအရာကို ပြန်ဖျက်မပေးပါ။\n\nNotion တွင် ရွေးထားသော ရှိပြီးသားဒေတာဘေ့စ်ကိုသာ ဖတ်သည်။ ရေးခြင်း၊ ဖျက်ခြင်း၊ ဒေတာကူးယူပြောင်းခြင်း မလုပ်ပါ။ ဆက်တင်များကို encrypted storage တွင် သိမ်းပြီး APK ထဲ key မထည့်ပါ။ ChatGPT Plus ရှိခြင်းက Gemini API အသုံးပြုခွင့် မဟုတ်ပါ။")
                .setPositiveButton("ပိတ်ရန်",null).show().also(ConnectedUi::protect)
        })
    }
    override fun onResume(){super.onResume();owner=Any();refresh()}
    override fun onPause(){owner=null;job?.cancel();job=null;clearSecrets();dialog?.dismiss();dialog=null;super.onPause()}
    private fun clearSecrets(){secrets.forEach{it.text.clear()};secrets.clear()}
    private fun refresh(){try{state.text="Gemini key: ${if(settings.hasGeminiKey())"သိမ်းထားပြီး" else "မရှိသေး"}\nစာသားမော်ဒယ်: ${settings.textModel().ifBlank{"မရွေးရသေး"}}\nNotion: ${if(settings.hasNotion())"ရှိပြီးသားဆက်တင် ရှိသည်" else "မချိတ်ဆက်ရသေး"}\nဤစာမျက်နှာက အင်တာနက်ချိတ်ဆက်မှု အောင်မြင်ကြောင်း မစစ်ရသေးပါ။"}
        catch(e:Exception){state.text=ConnectedUi.error(e)}}
    private fun save(block:()->Boolean){
        val lease=owner?:return;if(job!=null)return
        lateinit var launched:Job
        launched=lifecycleScope.launch(start=CoroutineStart.LAZY){try{
            val ok=withContext(Dispatchers.IO){ensureActive();block()}
            if(owner===lease){Toast.makeText(this@ConnectedSettingsActivity,if(ok)"သိမ်းပြီး စစ်ဆေးပြီးပါပြီ" else "သိမ်းဆည်းမှု အတည်မပြုနိုင်ပါ။ မူလမှတ်တမ်းများ မဖျက်ပါ။",Toast.LENGTH_LONG).show();refresh()}
        }catch(c:CancellationException){throw c}catch(e:Exception){if(owner===lease)state.text=ConnectedUi.error(e)}finally{if(job===launched)job=null}}
        job=launched;launched.start()
    }
    private fun form(title:String,body:LinearLayout,accept:()->Unit){
        val lease=owner?:return
        dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply{addView(body)})
            .setNegativeButton("မလုပ်တော့ပါ"){_,_->clearSecrets()}.setPositiveButton("သိမ်းရန်",null).create().also{d->
                d.setOnDismissListener{clearSecrets()};d.show();ConnectedUi.protect(d)
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(owner===lease&&dialog===d&&d.isShowing)accept()}
            }
    }
    private fun editModel(){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val input=ConnectedUi.input(this,"Gemini စာသားမော်ဒယ် အမည်","connected_model_input",80).apply{setText(runCatching{settings.textModel()}.getOrDefault(""))}
        body.addView(input);body.addView(ConnectedUi.text(this,"သင့် Gemini project တွင် သုံးနိုင်သော စာသားမော်ဒယ် အမည်ကို ထည့်ပါ။ ဤနေရာမှ အသုံးပြုခွင့်နှင့် ဈေးနှုန်း မစစ်ထားပါ။ မူလအသံမော်ဒယ် မပြောင်းပါ။"))
        form("စာသားမော်ဒယ်",body){val value=input.text.toString();input.text.clear();dialog?.dismiss();save{settings.saveModel(value)}}
    }
    private fun editGemini(){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val input=ConnectedUi.input(this,"Gemini API key","connected_key_input",256,true).also{secrets.add(it);body.addView(it)}
        val reviewed=CheckBox(this).apply{text="ဤ key အတိအကျကို ရှိပြီးသား encrypted Gemini setting ထဲ သိမ်းရန် အတည်ပြုပါသည်";tag="connected_key_review"};body.addView(reviewed)
        form("Gemini key ပြင်ရန်",body){if(!reviewed.isChecked){reviewed.error="အစားထိုးမည့် key ကို အရင်အတည်ပြုပါ";return@form}
            val value=input.text.toString();clearSecrets();dialog?.dismiss();save{settings.saveGeminiKey(value,true)}}
    }
    private fun editNotion(){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val token=ConnectedUi.input(this,"Token အသစ် (မဖြည့်လျှင် ရှိပြီးသားကို ဆက်သုံးမည်)","connected_token_input",512,true).also{secrets.add(it);body.addView(it)}
        val db=ConnectedUi.input(this,"ရှိပြီးသား ဒေတာဘေ့စ် ID","connected_database_input",80).apply{setText(runCatching{settings.notionDatabase()}.getOrDefault(""))};body.addView(db)
        val reviewed=CheckBox(this).apply{text="ကျွန်ုပ်၏ ရှိပြီးသား Notion ချိတ်ဆက်မှုဖြင့် ဤဒေတာဘေ့စ် အတိအကျကို သုံးမည်";tag="connected_database_review"};body.addView(reviewed)
        form("ရှိပြီးသား Notion ချိတ်ဆက်မှု",body){if(!reviewed.isChecked){reviewed.error="ရွေးထားသော ဒေတာဘေ့စ်ကို အတည်ပြုပါ";return@form}
            val value=token.text.toString().takeIf{it.isNotBlank()};val database=db.text.toString();db.text.clear();clearSecrets();dialog?.dismiss()
            save{settings.saveExistingNotion(value,database,true)}}
    }
}
