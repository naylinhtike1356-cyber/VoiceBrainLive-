package com.example.voicebrainlive.agent.nativeapps

import android.Manifest
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.agent.connected.ConnectedUi
import com.example.voicebrainlive.notifications.AgentNotificationAudio
import com.example.voicebrainlive.notifications.AnnouncementQueue
import com.example.voicebrainlive.security.SecureSettingsStorage

/** Consent authorizes a narrowly bounded narration recipe, never tools or biometric effects. */
internal object NativeAgentSettings {
    @Volatile private var locallyRevoked=false
    private const val CONSENT="native_agent_notification_binding_v1"
    private const val STATUS="native_agent_notification_status_v1"
    private const val CLOCK="native_agent_phone_clock_only_v1"
    private fun prefs(c:Context)=SecureSettingsStorage.require(c)
    fun binding(c:Context):String? {
        val key=SettingsActivity.getGeminiApiKey(c)
        val apps=SettingsActivity.getSelectedNotificationApps(c).sorted()
        if(key.isBlank()||apps.isEmpty())return null
        return AnnouncementQueue.digest(key+"\u0000"+SettingsActivity.getGeminiModel(c)+"\u0000"+SettingsActivity.getVoice(c)+"\u0000"+apps.joinToString("\u0000"))
    }
    fun enabled(c:Context):Boolean=try {!locallyRevoked&&binding(c)?.let{prefs(c).getString(CONSENT,null)==it}==true}catch(_:Exception){false}
    @Synchronized fun setEnabled(c:Context,on:Boolean,reviewedBinding:String?=null):Boolean {
        if(!on)locallyRevoked=true
        val b=if(on)reviewedBinding?.takeIf{it==binding(c)}?:return false else null
        val p=prefs(c);val saved=p.edit().putString(CONSENT,b).commit()&&p.getString(CONSENT,null)==b&&(!on||binding(c)==b)
        if(on)locallyRevoked=!saved
        return saved
    }
    fun phoneClockOnly(c:Context)=prefs(c).getBoolean(CLOCK,false) // Legacy dual-alarm records/settings remain unchanged.
    fun setPhoneClockOnly(c:Context,value:Boolean):Boolean {val p=prefs(c);return p.edit().putBoolean(CLOCK,value).commit()&&p.getBoolean(CLOCK,!value)==value}
    fun status(c:Context):String=try{prefs(c).getString(STATUS,"OFF").orEmpty()}catch(_:Exception){"STORAGE_UNAVAILABLE"}
    fun status(c:Context,code:String){require(code.matches(Regex("[A-Z_]{1,40}")));try{prefs(c).edit().putString(STATUS,code).commit()}catch(_:Exception){}}
    @Synchronized fun reserve(c:Context,now:Long=System.currentTimeMillis()):Boolean {
        if(!enabled(c))return false
        val p=prefs(c);val start=p.getLong("native_agent_audio_window_v1",0);val last=p.getLong("native_agent_audio_last_v1",0)
        if(now<0||now<start||now<last||last>0&&now-last<60_000)return false
        val fresh=start==0L||now-start>=86_400_000
        val count=if(fresh)0 else p.getInt("native_agent_audio_count_v1",0)
        if(count !in 0..11)return false
        val window=if(fresh)now else start
        val saved=p.edit().putLong("native_agent_audio_window_v1",window).putLong("native_agent_audio_last_v1",now)
            .putInt("native_agent_audio_count_v1",count+1).commit()&&p.getLong("native_agent_audio_last_v1",-1)==now&&
            p.getInt("native_agent_audio_count_v1",-1)==count+1&&p.getLong("native_agent_audio_window_v1",-1)==window
        if(!saved)throw com.example.voicebrainlive.security.SecureSettingsUnavailable()
        return true
    }
}

class NativeAgentSettingsActivity:AppCompatActivity(){
    private lateinit var status:android.widget.TextView
    private lateinit var review:CheckBox
    private var shownBinding:String?=null
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){refresh()}
    override fun onCreate(savedInstanceState:Bundle?){
        ConnectedUi.theme(this);super.onCreate(savedInstanceState)
        val body=ConnectedUi.page(this,"လက်ထောက်အသံနှင့် ဖုန်းအက်ပ်များ")
        status=ConnectedUi.text(this,"").apply{tag="native_agent_status"};body.addView(status)
        body.addView(ConnectedUi.text(this,"ရွေးထားသော App အသိပေးစာကို Gemini မြန်မာအသံဖြင့် ရှင်းပြမည်။ မိုက်ပိတ်ထားမည်။ အင်တာနက်လိုပြီး API ကုန်ကျနိုင်သည်။"))
        review=CheckBox(this).apply{text="App အမည်၊ ခေါင်းစဉ်နှင့် စာသားအတိုကို Gemini သို့ပို့ရန်နှင့် API ကုန်ကျနိုင်ခြင်းကို သဘောတူသည်။";tag="native_audio_review"};body.addView(review)
        body.addView(ConnectedUi.text(this,"စမ်းသပ်ကန့်သတ်ချက်: ၁ မိနစ်လျှင် ၁ ကြိမ်၊ ၂၄ နာရီတွင် ၁၂ ကြိမ်။ ဖုန်းလော့ခ်ကျနေစဉ် မဖတ်ပါ။"))
        val details=ConnectedUi.text(this,"စာသား ၄၈၀ အက္ခရာနှင့် output ၅၁၂ tokens အထိ။ Android မြန်မာ TTS မသုံးပါ။ Provider turn ပြီးလျှင် cloud ချိတ်ဆက်မှု ပိတ်ပြီး အသံ buffer/ငြိမ်ချိန်ပြီးလျှင် service ရပ်မည်။ App/model/key/voice ပြောင်းလျှင် ပြန်အတည်ပြုရမည်။ Android နောက်ခံကန့်သတ်ချက်ကြောင့် ဖတ်ရန်နှိပ်ရနိုင်သည်။").apply{visibility=android.view.View.GONE}
        body.addView(ConnectedUi.button(this,"ကိုယ်ရေးအချက်အလက်နှင့် အသုံးပြုမှုအသေးစိတ်","native_audio_details"){details.visibility=if(details.visibility==android.view.View.VISIBLE)android.view.View.GONE else android.view.View.VISIBLE});body.addView(details)
        body.addView(ConnectedUi.button(this,"ရွေးထားသော App များအတွက် AI အသံဖွင့်ရန်","native_audio_enable"){
            if(!review.isChecked){review.error="ပို့မည့်အချက်အလက်ကို အရင်အတည်ပြုပါ";return@button}
            try{if(shownBinding==null||shownBinding!=NativeAgentSettings.binding(this)){review.isChecked=false;refresh();status.append("\nဖတ်မည့် App/model ပြောင်းနေသည်။ အချက်အလက်ကို ပြန်စစ်ပြီး အတည်ပြုပါ။");return@button}
                if(!NativeAgentSettings.setEnabled(this,true,shownBinding))status.text="Gemini key နှင့် ဖတ်မည့် App ကို မူလဆက်တင်တွင် အရင်ရွေးပါ။"else{review.isChecked=false;refresh()}}
            catch(_:Exception){status.text=SecureSettingsStorage.ERROR}
        })
        body.addView(ConnectedUi.button(this,"AI အသံပိတ်ရန်","native_audio_disable"){
            AgentNotificationAudio.stop(this)
            try{if(NativeAgentSettings.setEnabled(this,false))refresh()else status.text="လက်ရှိအသံ ရပ်ထားသည်။ ပိတ်ဆက်တင် သိမ်းမရပါ။ Restart မလုပ်မီ Notification access ကိုလည်း ပိတ်ထားပါ။"}
            catch(_:Exception){status.text="လက်ရှိအသံ ရပ်ထားသည်။ ပိတ်ဆက်တင် သိမ်းမရပါ။ Notification access ကိုလည်း ပိတ်ထားပါ။"}
        })
        body.addView(ConnectedUi.button(this,"Luma အကြောင်းကြားခွင့် ပေးရန်","native_audio_notice_permission"){
            if(android.os.Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        })
        body.addView(ConnectedUi.button(this,"အသိပေးစာဖတ်ခွင့် ဆက်တင်","native_notification_access"){startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))})
        body.addView(ConnectedUi.button(this,"မူလဆက်တင် (App / Gemini / ဖုန်းမုဒ်)","native_original_settings"){startActivity(Intent(this,SettingsActivity::class.java))})
        body.addView(ConnectedUi.button(this,"ဖုန်းတိုက်ရိုက်ခေါ်ခွင့် ပေးရန်","native_call_permission"){permission.launch(Manifest.permission.CALL_PHONE)})
        body.addView(ConnectedUi.button(this,"အမည်ဖြင့်ခေါ်ရန် Contacts ဖတ်ခွင့်","native_contacts_permission"){permission.launch(Manifest.permission.READ_CONTACTS)})
        body.addView(ConnectedUi.text(this,"ဖုန်းတကယ်ခေါ်ရန် နံပါတ်အတိအကျနှင့် မူလအတည်ပြုချက်/biometric လိုသည်။ စမ်းသပ်ဖုန်းမုဒ် ဖွင့်ထားလျှင် မခေါ်ပါ။ မူလဆက်တင် → Quick Controls တွင် ပြင်နိုင်သည်။"))
        body.addView(ConnectedUi.button(this,"နောင်နှိုးစက်ကို ဖုန်း Clock တွင်သာ ထားမည်","native_clock_only"){
            try{if(NativeAgentSettings.setPhoneClockOnly(this,true))refresh()else status.text="ဆက်တင် သိမ်းမရပါ။"}catch(_:Exception){status.text=SecureSettingsStorage.ERROR}
        })
        body.addView(ConnectedUi.button(this,"နောင်နှိုးစက်ကို Clock နှင့် Luma နှစ်ခုထားမည်","native_clock_dual"){
            try{if(NativeAgentSettings.setPhoneClockOnly(this,false))refresh()else status.text="ဆက်တင် သိမ်းမရပါ။"}catch(_:Exception){status.text=SecureSettingsStorage.ERROR}
        })
        body.addView(ConnectedUi.text(this,"ရွေးချယ်မှုသည် နောင်နှိုးစက်အတွက်သာ ဖြစ်သည်။ ယခင်နှိုးစက်များ မဖျက်ပါ။ Samsung background ကန့်သတ်ချက်ကြောင့် အလိုအလျောက် အသံစမရလျှင် ဖတ်ပြရန် notification ကို နှိပ်ရနိုင်သည်။"))
        refresh()
        intent.getStringExtra("announcement_id")?.let{id->
            if(!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked)AgentNotificationAudio.startReviewed(this,id)
        }
    }
    override fun onResume(){super.onResume();if(::status.isInitialized)refresh()}
    private fun refresh(){
        if(::review.isInitialized)review.isChecked=false
        val call=ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED
        val contacts=ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
        status.text=try{
            shownBinding=NativeAgentSettings.binding(this)
            val apps=SettingsActivity.getSelectedNotificationApps(this).sorted().joinToString("၊ "){pkg->
                try{packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString().take(60)}catch(_:Exception){pkg.take(180)}
            }
            "AI အသံ: ${if(NativeAgentSettings.enabled(this))"ဖွင့်ထားသည်"else "ပိတ်ထားသည် / ပြန်အတည်ပြုရန်လိုသည်"}\nဖတ်မည့် App: $apps\nမော်ဒယ်: ${SettingsActivity.getGeminiModel(this)}\nဖုန်းခေါ်ခွင့်: ${if(call)"ရရှိ"else "မရသေး"} · Contacts: ${if(contacts)"ရရှိ"else "မရသေး"}\nဖုန်းမုဒ်: ${if(SettingsActivity.isTestCallMode(this))"စမ်းသပ်မှုသာ"else "မူလအတည်ပြုချက်ပြီးမှ တကယ်ခေါ်မည်"}\nနောင်နှိုးစက်: ${if(NativeAgentSettings.phoneClockOnly(this))"ဖုန်း Clock သာ"else "Clock နှင့် Luma"}\nနောက်ဆုံးအသံအခြေအနေ: ${AgentNotificationAudio.statusText(NativeAgentSettings.status(this))}"}
        catch(_:Exception){SecureSettingsStorage.ERROR}
    }
}
