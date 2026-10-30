package com.example.voicebrainlive.routines

import android.app.*
import android.content.*
import android.os.*
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.example.voicebrainlive.OfflineCommandMatcher
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.handlers.CommandHandler
import com.example.voicebrainlive.security.SecureSettingsStorage
import kotlinx.coroutines.*
import java.util.Calendar

internal interface SleepWakeHost { val sleepWakeJournal:SleepWakeJournal; val sleepWakeNow:SleepWakeNow }
internal object SleepWakeGraph {
    const val FIRE="com.example.voicebrainlive.SLEEP_WAKE_FIRE"
    const val KEY="local_sleep_wake_v1"
    private var single:SleepWakeJournal?=null
    fun now(c:Context)=(c.applicationContext as? SleepWakeHost)?.sleepWakeNow
        ?:SleepWakeNow(System.currentTimeMillis(),SystemClock.elapsedRealtime(),Settings.Global.getInt(c.contentResolver,Settings.Global.BOOT_COUNT,0))
    @Synchronized fun journal(c:Context):SleepWakeJournal {
        (c.applicationContext as? SleepWakeHost)?.let{return it.sleepWakeJournal}
        val app=c.applicationContext
        return single?:SleepWakeJournal(object:SleepWakePersistence{
            override fun read()=SecureSettingsStorage.require(app).getString(KEY,null)
            override fun write(value:String)=SecureSettingsStorage.require(app).edit().putString(KEY,value).commit()
        },{now(app)}).also{single=it}
    }
    private fun pending(c:Context,e:SleepWakeEntry)=PendingIntent.getBroadcast(c,0,Intent(c,SleepWakeReceiver::class.java).apply{
        action=FIRE;data=android.net.Uri.Builder().scheme("luma-local-alarm").authority("fire").appendPath(e.id).build();putExtra("id",e.id)
    },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c:Context,e:SleepWakeEntry){
        swCheck(ExactAlarmPermissionHelper.canScheduleExactAlarms(c),"EXACT_ALARM_REQUIRED")
        val am=c.getSystemService(AlarmManager::class.java)
        if(e.kind==SleepWakeKind.MEDIA_STOP)am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,e.elapsedDue,pending(c,e))
        else am.setAlarmClock(AlarmManager.AlarmClockInfo(e.due,PendingIntent.getActivity(c,0,Intent(c,SleepWakeActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)),pending(c,e))
    }
    fun cancel(c:Context,e:SleepWakeEntry){journal(c).mark(e.id,SleepWakeState.CANCELLED);c.getSystemService(AlarmManager::class.java).cancel(pending(c,e))}
    fun restore(c:Context){
        try{val n=now(c);val j=journal(c);j.history().filter{it.state==SleepWakeState.SCHEDULED}.forEach{
            if(it.kind==SleepWakeKind.MEDIA_STOP&&it.boot!=n.boot)j.mark(it.id,SleepWakeState.CANCELLED)
            else if(n.wall-it.due>600_000)j.claim(it.id)
            else schedule(c,it)
        }}catch(_:Exception){/* Never replace unreadable encrypted records or replay a running effect. */}
    }
    fun ready(c:Context,wake:Boolean){
        swCheck(ExactAlarmPermissionHelper.canScheduleExactAlarms(c),"EXACT_ALARM_REQUIRED")
        if(wake)swCheck(NotificationManagerCompat.from(c).areNotificationsEnabled()&&
            c.getSystemService(NotificationManager::class.java).getNotificationChannel(WakeAlarmService.CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE,"NOTIFICATIONS_REQUIRED")
    }
    fun nextTime(hour:Int,minute:Int,now:Long=System.currentTimeMillis()):Long {
        swCheck(hour in 0..23&&minute in 0..59)
        return Calendar.getInstance().apply{timeInMillis=now;set(Calendar.HOUR_OF_DAY,hour);set(Calendar.MINUTE,minute);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);if(timeInMillis<=now)add(Calendar.DAY_OF_YEAR,1)}.timeInMillis
    }
    fun label(id:String)="Luma wake $id"
    fun clockSet(e:SleepWakeEntry):Intent {
        val t=Calendar.getInstance().apply{timeInMillis=e.due}
        return Intent(AlarmClock.ACTION_SET_ALARM).apply{putExtra(AlarmClock.EXTRA_HOUR,t.get(Calendar.HOUR_OF_DAY));putExtra(AlarmClock.EXTRA_MINUTES,t.get(Calendar.MINUTE));putExtra(AlarmClock.EXTRA_MESSAGE,label(e.id));putExtra(AlarmClock.EXTRA_SKIP_UI,false);addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)}
    }
    fun clockDismiss(id:String):Intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply{
        swCheck(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")),"OWNED_ALARM_REQUIRED")
        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE,AlarmClock.ALARM_SEARCH_MODE_LABEL);putExtra(AlarmClock.EXTRA_MESSAGE,label(id))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun requestClock(c:Context,intent:Intent):Boolean=try{c.startActivity(intent);true}catch(_:Exception){false}
}

/** Explicit, private PendingIntent; the broadcast contains an ID, never executable commands. */
class SleepWakeReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=SleepWakeGraph.FIRE)return
        val id=intent.getStringExtra("id")?:return
        if(!id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try{
                withTimeout(8_000){
                    val j=SleepWakeGraph.journal(context);val e=j.claim(id)?:return@withTimeout
                    if(e.kind==SleepWakeKind.MEDIA_STOP){
                        // No reminder/timer/notification/TTS. Always use the existing authorization boundary.
                        val result=PhoneCommandExecutor(context).executeAsync("media_pause",null,null)
                        j.mark(id,if(result==com.example.voicebrainlive.commands.handlers.MusicCommands.PAUSE_REQUESTED)SleepWakeState.REQUESTED else SleepWakeState.BLOCKED)
                    }else{
                        val service=Intent(context,WakeAlarmService::class.java).putExtra("id",id)
                        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service)else context.startService(service)
                    }
                }
            }catch(_:Exception){try{SleepWakeGraph.journal(context).mark(id,SleepWakeState.BLOCKED)}catch(_:Exception){}}
            finally{pending.finish()}
        }
    }
}

/** Fixed local recipes only; the ToolRegistry's existing common guard surrounds every entry. */
class SleepWakeCommands(private val context:Context,private val dispatch:suspend(String,String?,String?)->String?):CommandHandler {
    override fun getSupportedCommands()=listOf("set_music_sleep_timer","start_bedtime_music","cancel_music_sleep_timer","set_alarm","stop_wake_alarm","cancel_wake_alarm")
    override suspend fun execute(commandType:String,target:String?,value:String?):String? {
        val command=commandType.trim().lowercase(java.util.Locale.ROOT)
        return try{
        when(command){
            "set_music_sleep_timer","start_bedtime_music"->{
                val m=value?.toIntOrNull();swCheck(m!=null&&m in 1..180)
                swCheck(if(command=="start_bedtime_music")target in setOf("bedtime","resume")else target.isNullOrBlank())
                SleepWakeGraph.ready(context,false);val now=SleepWakeGraph.now(context);val j=SleepWakeGraph.journal(context)
                val (e,created)=j.add(SleepWakeKind.MEDIA_STOP,now.wall+m!!*60_000L,now.elapsed+m*60_000L)
                try{SleepWakeGraph.schedule(context,e)}catch(ex:Exception){j.mark(e.id,SleepWakeState.BLOCKED);throw ex}
                if(created&&command=="start_bedtime_music"){
                    val result=if(target=="bedtime")dispatch("play_music","အိပ်ရာဝင်သီချင်း",null)else dispatch("media_play",null,null)
                    if(result==null||result.startsWith("FAILED:")||result.startsWith("BLOCKED:")){SleepWakeGraph.cancel(context,e);return "သီချင်းဖွင့်ရန် မအောင်မြင်ပါ။ အလိုအလျောက်ပိတ်ချိန် မထားခဲ့ပါ။"}
                }
                "$m မိနစ်ကြာလျှင် လက်ရှိမီဒီယာကို တိတ်တဆိတ် ရပ်ရန် အချိန်သတ်မှတ်ပြီးပါပြီ။"+
                    if(command=="start_bedtime_music")" သီချင်းဖွင့်ရန် player သို့ တောင်းဆိုထားသည်။"else ""
            }
            "cancel_music_sleep_timer"->{
                swCheck(value.isNullOrBlank()&&(target.isNullOrBlank()||target.matches(Regex("[a-zA-Z0-9_-]{1,80}"))))
                val j=SleepWakeGraph.journal(context)
                val found=j.history().filter{it.kind==SleepWakeKind.MEDIA_STOP&&it.state==SleepWakeState.SCHEDULED&&(target.isNullOrBlank()||it.id==target)}
                found.forEach{SleepWakeGraph.cancel(context,it)}
                if(found.isEmpty())"ပယ်ဖျက်ရန် သီချင်းပိတ်ချိန် မရှိပါ။"else "သီချင်းအလိုအလျောက်ပိတ်ချိန်ကို ပယ်ဖျက်ပြီးပါပြီ။"
            }
            "set_alarm"->{
                val time=target?.takeIf{it.matches(Regex("[0-9]{2}:[0-9]{2}"))}
                    ?:target?.let{OfflineCommandMatcher.matchDirectAction("$it နှိုးစက်ထား")?.takeIf{r->r.first=="set_alarm"}?.second}
                    ?:return "နှိုးစက်ထားမည့်အချိန်ကို မနက် ၇ နာရီ သို့မဟုတ် 07:00 ဟု ပြောပေးပါ။"
                val (h,m)=time.split(':').map{it.toInt()}
                if(com.example.voicebrainlive.agent.nativeapps.NativeAgentSettings.phoneClockOnly(context)){
                    swCheck(h in 0..23&&m in 0..59)
                    val native=Intent(AlarmClock.ACTION_SET_ALARM).apply{
                        putExtra(AlarmClock.EXTRA_HOUR,h);putExtra(AlarmClock.EXTRA_MINUTES,m)
                        putExtra(AlarmClock.EXTRA_SKIP_UI,false);putExtra(AlarmClock.EXTRA_MESSAGE,"Luma Clock request")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    return if(SleepWakeGraph.requestClock(context,native))"$time အတွက် ဖုန်း Clock ကို ဖွင့်ထားသည်။ Clock မှာ အတည်ပြုသိမ်းပေးပါ။ Luma နှိုးစက် ထပ်မထားပါ။"
                        else "ဖုန်း Clock ကို ဖွင့်မရပါ။ နှိုးစက် မထားခဲ့ပါ။"
                }
                SleepWakeGraph.ready(context,true)
                val now=SleepWakeGraph.now(context);val j=SleepWakeGraph.journal(context)
                val (e,created)=j.add(SleepWakeKind.WAKE,SleepWakeGraph.nextTime(h,m,now.wall),0)
                if(created){
                    try{SleepWakeGraph.schedule(context,e)}catch(ex:Exception){j.mark(e.id,SleepWakeState.BLOCKED);throw ex}
                    if(!SleepWakeGraph.requestClock(context,SleepWakeGraph.clockSet(e))){SleepWakeGraph.cancel(context,e);return "Clock ဖွင့်မရသဖြင့် နှိုးစက်နှစ်ခုလုံး သတ်မှတ်မထားပါ။"}
                    j.clockRequested(e.id)
                }
                "$time အတွက် Luma အသံနှိုးစက် သတ်မှတ်ပြီးပါပြီ။ Clock စာမျက်နှာမှာလည်း နှိုးစက်ကို အတည်ပြုပေးပါ။ နှစ်ခုမြည်နိုင်သည်။"
            }
            "cancel_wake_alarm"->{
                swCheck(target!=null&&target.matches(Regex("[a-zA-Z0-9_-]{1,80}"))&&value.isNullOrBlank(),"OWNED_ALARM_REQUIRED")
                val e=SleepWakeGraph.journal(context).history().singleOrNull{it.id==target&&it.kind==SleepWakeKind.WAKE}
                    ?:throw SleepWakeFailure("OWNED_ALARM_REQUIRED")
                if(e.state==SleepWakeState.CANCELLED)return "Luma နှိုးစက်ကို ပယ်ဖျက်ပြီးသား ဖြစ်သည်။"
                swCheck(e.state==SleepWakeState.SCHEDULED,"OWNED_ALARM_REQUIRED")
                SleepWakeGraph.cancel(context,e)
                if(e.clockRequested)SleepWakeGraph.requestClock(context,SleepWakeGraph.clockDismiss(e.id))
                "Luma အချိန်ကို ပယ်ဖျက်ပြီး Clock ထံ ဆိုင်ရာနှိုးစက်ကို ပိတ်ရန် တောင်းဆိုထားသည်။ Clock ကိုလည်း ပြန်စစ်ပါ။"
            }
            "stop_wake_alarm"->{
                val expected=target?.takeIf{it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))}
                swCheck(target.isNullOrBlank()||expected!=null)
                val active=withContext(Dispatchers.Main.immediate){WakeAlarmService.stopOwned(expected)}
                val j=try{SleepWakeGraph.journal(context)}catch(_:Exception){null}
                val saved=try{j?.history().orEmpty()}catch(_:Exception){emptyList()}
                val now=SleepWakeGraph.now(context).wall
                val ringing=saved.filter{it.kind==SleepWakeKind.WAKE&&(expected==null||it.id==expected)&&
                    (it.state==SleepWakeState.RUNNING||it.clockRequested&&now-it.due in 0..600_000L)}
                val ids=(ringing.map{it.id}+listOfNotNull(active)).distinct()
                ids.forEach{id->try{j?.mark(id,SleepWakeState.DISMISSED)}catch(_:Exception){};SleepWakeGraph.requestClock(context,SleepWakeGraph.clockDismiss(id))}
                if(ids.isEmpty())"ဤအက်ပ်မှ ဖန်တီးထားသော နှိုးစက် ယခု မမြည်နေပါ။ အခြားနှိုးစက်ကို Clock မှ ရပ်ပါ။"
                else "Luma နှိုးစကားကို ရပ်ထားပြီး Clock ထံ ပိတ်ရန် တောင်းဆိုထားသည်။ Clock မရပ်လျှင် ၎င်း၏ ရပ်ရန်ခလုတ်ကို နှိပ်ပါ။"
            }
            else->null
        }
    }catch(_:com.example.voicebrainlive.security.SecureSettingsUnavailable){"လုံခြုံသော သိမ်းဆည်းမှုကို အတည်မပြုနိုင်ပါ။ ရှိပြီးသားအချိန်များကို စစ်ပြီးမှ ပြန်ခိုင်းပါ။"}
    catch(e:SleepWakeFailure){when(e.code){
        "EXACT_ALARM_REQUIRED"->"ဆက်တင် → အိပ်စက်နှင့်နှိုးစက်တွင် တိကျသောနှိုးစက်ခွင့်ပြုချက်ကို အရင်ဖွင့်ပြီး ပြန်ခိုင်းပါ။"
        "NOTIFICATIONS_REQUIRED"->"Luma နှိုးစက်အတွက် အကြောင်းကြားခွင့်ကို ဆက်တင်တွင် အရင်ဖွင့်ပါ။"
        "INVALID_ALARM"->"အချိန်ကို ပြန်စစ်ပါ။ သီချင်းပိတ်ချိန်သည် ၁ မှ ၁၈၀ မိနစ်အတွင်း ဖြစ်ရမည်။"
        "OWNED_ALARM_REQUIRED"->"ဤအက်ပ်မှ ဖန်တီးထားသော စောင့်နေဆဲ နှိုးစက်ကိုသာ ရွေးပြီး ပယ်ဖျက်နိုင်သည်။"
        else->"အချိန်သတ်မှတ်မှုကို အတည်မပြုနိုင်ပါ။ ဆက်တင်တွင် ရှိပြီးသားအချိန်များကို စစ်ပြီးမှ ပြန်ခိုင်းပါ။"
    }}
    }
}
