package com.example.voicebrainlive.routines

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.AlarmClock
import android.speech.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.agent.connected.ConnectedUi
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Ordinary screen contains no credentials. Locked alarm UI reveals no saved history. */
class SleepWakeActivity:AppCompatActivity(){
    private lateinit var status:TextView
    private lateinit var rows:LinearLayout
    private var recognizer:SpeechRecognizer?=null
    private val handler=Handler(Looper.getMainLooper())
    private var busy=false
    private val micPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it)listen()else status.text="မိုက်ခရိုဖုန်းခွင့် မပေးထားပါ။ ရပ်ရန်ခလုတ်ကို သုံးနိုင်သည်။"}
    private val notices=registerForActivityResult(ActivityResultContracts.RequestPermission()){reload()}
    override fun onCreate(savedInstanceState:Bundle?){
        ConnectedUi.theme(this);super.onCreate(savedInstanceState)
        val locked=(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if(intent.getBooleanExtra("ringing",false)&&WakeAlarmService.active&&Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true)}
        val body=ConnectedUi.page(this,"အိပ်စက်နှင့် နှိုးစက်")
        status=ConnectedUi.text(this,"").apply{tag="sleep_wake_status"};body.addView(status)
        body.addView(ConnectedUi.button(this,"Luma နှင့် ဆိုင်ရာ Clock နှိုးစက် ရပ်ရန်","wake_stop"){run("stop_wake_alarm")})
        body.addView(ConnectedUi.button(this,"ရပ်တော့ဟု အသံဖြင့် ပြောရန်","wake_voice_stop"){
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)micPermission.launch(Manifest.permission.RECORD_AUDIO)else listen()
        })
        rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        if(!locked){
            body.addView(ConnectedUi.text(this,"သီချင်းပိတ်ချိန်တွင် အသံမမြည်ပါ။ နှိုးစက်တွင် Clock နှင့် Luma နှစ်ခု မြည်နိုင်သည်။ Clock စာမျက်နှာမှာ အတည်ပြုရန်လိုသည်။"))
            val minutes=ConnectedUi.input(this,"ဘယ်နှမိနစ်ကြာလျှင် သီချင်းပိတ်မလဲ","sleep_minutes",3).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("5")}
            body.addView(minutes)
            body.addView(ConnectedUi.button(this,"သီချင်းပိတ်ချိန် ထားရန်","sleep_set"){run("set_music_sleep_timer",null,minutes.text.toString())})
            body.addView(ConnectedUi.button(this,"သီချင်းပိတ်ချိန် ပယ်ဖျက်ရန်","sleep_cancel"){run("cancel_music_sleep_timer")})
            body.addView(ConnectedUi.button(this,"Clock နှင့် Luma နှိုးစက် အသစ်","wake_set"){
                TimePickerDialog(this,{_,h,m->run("set_alarm","%02d:%02d".format(java.util.Locale.ROOT,h,m))},7,0,true).show()
            })
            body.addView(ConnectedUi.button(this,"တိကျသောနှိုးစက်ခွင့် ဖွင့်ရန်","wake_exact_permission"){startActivity(ExactAlarmPermissionHelper.openExactAlarmSettings(this))})
            body.addView(ConnectedUi.button(this,"နှိုးစက် အကြောင်းကြားခွင့်","wake_notice_permission"){
                if(Build.VERSION.SDK_INT>=33)notices.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
            })
            body.addView(ConnectedUi.button(this,"ဖုန်း Clock ကို ကြည့်ရန်","wake_clock"){
                if(!SleepWakeGraph.requestClock(this,Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))status.text="Clock ကို ဖွင့်မရပါ။"
            })
            body.addView(rows)
        }
        reload()
    }
    override fun onResume(){super.onResume();if(::status.isInitialized)reload()}
    private fun reload(){
        if(!::rows.isInitialized)return
        rows.removeAllViews()
        try{
            val all=SleepWakeGraph.journal(this).history()
            if(status.text.isBlank())status.text=if(WakeAlarmService.active)"နှိုးချိန်ရောက်ပါပြီ။"else "အသံဖြင့် သို့မဟုတ် ခလုတ်ဖြင့် အချိန်ထားနိုင်သည်။"
            all.asReversed().forEach{e->
                rows.addView(ConnectedUi.text(this,"${if(e.kind==SleepWakeKind.MEDIA_STOP)"သီချင်းပိတ်ချိန်"else "Luma နှိုးစက်"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(e.due))}\n${state(e.state)}"))
                if(e.state==SleepWakeState.SCHEDULED)rows.addView(ConnectedUi.button(this,"ဤအချိန်ကို ပယ်ဖျက်ရန်","cancel_${e.id}"){
                    if(e.kind==SleepWakeKind.WAKE)run("cancel_wake_alarm",e.id)
                    else run("cancel_music_sleep_timer",e.id)
                })
            }
        }catch(_:Exception){status.text="လုံခြုံသောမှတ်တမ်းကို ဖတ်မရပါ။ မူလဒေတာကို မဖျက်ပါ။"}
    }
    private fun run(command:String,target:String?=null,value:String?=null){
        if(busy)return;busy=true
        lifecycleScope.launch{try{val result=withContext(Dispatchers.IO){PhoneCommandExecutor(this@SleepWakeActivity).executeAsync(command,target,value)};status.text=result;reload()}
        catch(c:CancellationException){throw c}catch(_:Exception){status.text="လုပ်ဆောင်မှုကို အတည်မပြုနိုင်ပါ။"}finally{busy=false}}
    }
    private fun listen(){
        if(Build.VERSION.SDK_INT<31||!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){status.text="စက်တွင်း အသံနားထောင်စနစ် မရှိပါ။ ရပ်ရန်ခလုတ်ကို သုံးပါ။";return}
        recognizer?.destroy();WakeAlarmService.pauseForVoiceInput()
        val r=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);recognizer=r
        r.setRecognitionListener(object:RecognitionListener{
            override fun onResults(b:Bundle?){if(recognizer!==r)return;val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();endListening();if(SleepWakePhrases.isWakeStop(text))run("stop_wake_alarm")else status.text="ရပ်တော့ဟု ပြောပေးပါ၊ သို့မဟုတ် ရပ်ရန်ခလုတ်ကို နှိပ်ပါ။"}
            override fun onError(error:Int){if(recognizer!==r)return;endListening();status.text="စက်တွင်း မြန်မာအသံကို နားမလည်နိုင်ပါ။ ရပ်ရန်ခလုတ်ကို သုံးပါ။"}
            override fun onReadyForSpeech(params:Bundle?){status.text="ရပ်တော့ဟု ပြောနိုင်ပါပြီ။"}
            override fun onBeginningOfSpeech(){};override fun onRmsChanged(rmsdB:Float){};override fun onBufferReceived(buffer:ByteArray?){}
            override fun onEndOfSpeech(){};override fun onPartialResults(partialResults:Bundle?){};override fun onEvent(eventType:Int,params:Bundle?){}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"my-MM").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false))
        handler.postDelayed({endListening()},15_000)
    }
    private fun endListening(){handler.removeCallbacksAndMessages(null);val old=recognizer;recognizer=null;old?.cancel();old?.destroy()}
    override fun onStop(){endListening();super.onStop()}
    private fun state(s:SleepWakeState)=when(s){SleepWakeState.SCHEDULED->"အချိန်စောင့်နေသည်";SleepWakeState.RUNNING->"လုပ်ဆောင်နေသည်";SleepWakeState.REQUESTED->"မီဒီယာရပ်ရန် အမိန့်ပို့ပြီး";SleepWakeState.DISMISSED->"Luma နှိုးစကား ရပ်ပြီး";SleepWakeState.CANCELLED->"ပယ်ဖျက်ပြီး";SleepWakeState.BLOCKED->"လုပ်ဆောင်မှုကို အတည်မပြုနိုင်ပါ";SleepWakeState.EXPIRED->"သတ်မှတ်ကာလ ကျော်သွားပြီ"}
}
