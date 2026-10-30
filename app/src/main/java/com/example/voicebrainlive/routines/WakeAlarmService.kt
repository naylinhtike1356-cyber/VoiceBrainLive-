package com.example.voicebrainlive.routines

import android.app.*
import android.content.Intent
import android.os.*
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import kotlinx.coroutines.*

/** User-created one-shot wake alarm. Local installed voice only; never a background microphone. */
class WakeAlarmService:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private var tts:TtsUnifiedInterface?=null
    private var lock:PowerManager.WakeLock?=null
    private var id:String?=null
    private var deadline=0L
    private var checks=0
    private var pausedUntil=0L
    private var sentence=0
    private var audio:AudioManager?=null
    private var focus:AudioFocusRequest?=null
    private var hasFocus=false
    private val tick=object:Runnable{override fun run(){
        if(current!==this@WakeAlarmService)return
        if(SystemClock.elapsedRealtime()>=deadline){finishAlarm(SleepWakeState.EXPIRED);return}
        if(!hasFocus){handler.postDelayed(this,1000);return}
        if(SystemClock.elapsedRealtime()<pausedUntil){handler.postDelayed(this,1000);return}
        val voice=tts
        if(voice?.isAvailable()!=true){if(++checks<100)handler.postDelayed(this,50)else unavailable();return}
        if(voice.isSpeaking()){handler.postDelayed(this,1000);return}
        if(!voice.speakLocalAlarm(messages[sentence++%messages.size])){unavailable();return}
        handler.postDelayed(this,30_000)
    }}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){
            if(intent.getStringExtra("id")!=id)return START_NOT_STICKY
            tts?.stop() // Stop owned sound immediately; Clock dismissal still goes through the common guard.
            val expected=id
            // Dismissal must finish even when stopping this service cancels its own lifecycle scope.
            CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{
                try{withTimeout(8_000){PhoneCommandExecutor(this@WakeAlarmService).executeAsync("stop_wake_alarm",expected,null)}}
                catch(_:Exception){/* Stop owned sound regardless of storage/Clock availability. */}
                finally{stopSelf(startId)}
            }
            return START_NOT_STICKY
        }
        val wanted=intent?.getStringExtra("id")?:run{stopSelf(startId);return START_NOT_STICKY}
        val e=try{SleepWakeGraph.journal(this).history().singleOrNull{it.id==wanted&&it.kind==SleepWakeKind.WAKE&&it.state==SleepWakeState.RUNNING}}catch(_:Exception){null}
        val now=SleepWakeGraph.now(this).wall
        if(e==null||now !in e.due..(e.due+600_000)){stopSelf(startId);return START_NOT_STICKY}
        if(id==wanted&&current===this)return START_NOT_STICKY
        tts?.shutdown();handler.removeCallbacksAndMessages(null)
        id=wanted;current=this;deadline=SystemClock.elapsedRealtime()+(e.due+600_000-now).coerceIn(1,600_000)
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"Luma အသံနှိုးစက်",NotificationManager.IMPORTANCE_HIGH).apply{setSound(null,null)})
        val open=PendingIntent.getActivity(this,0,Intent(this,SleepWakeActivity::class.java).putExtra("ringing",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,0,Intent(this,WakeAlarmService::class.java).setAction(STOP).setData(android.net.Uri.parse("luma-wake-stop://alarm/$wanted")).putExtra("id",wanted),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("နိုးချိန်ရောက်ပါပြီ").setContentText("Luma နှိုးစကားနှင့် Clock နှိုးစက်ကို ရပ်နိုင်သည်။")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause,"ရပ်တော့",stop)
        if(Build.VERSION.SDK_INT<34||nm.canUseFullScreenIntent())notice.setFullScreenIntent(open,true)
        try{startForeground(NOTICE,notice.build())}catch(_:Exception){finishAlarm(SleepWakeState.BLOCKED);return START_NOT_STICKY}
        audio=getSystemService(AudioManager::class.java)
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> hasFocus=change==AudioManager.AUDIOFOCUS_GAIN;if(change<0)tts?.stop() }.build()
        hasFocus=try{audio?.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED}catch(_:Exception){false}
        if(!hasFocus){unavailable("အသံထုတ်ရန် ခွင့်မရပါ။ Clock နှိုးစက်ကို စစ်ပါ။");return START_NOT_STICKY}
        lock?.let{if(it.isHeld)it.release()}
        lock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VoiceBrain:LocalWakeAlarm").apply{acquire(605_000)}
        tts=TtsUnifiedInterface(this);checks=0;sentence=0;handler.post(tick)
        return START_NOT_STICKY
    }
    private fun unavailable(detail:String="Agent အသံ စတင်၍မရပါ။ Clock နှိုးစက်ကို စစ်ပါ။"){
        getSystemService(NotificationManager::class.java).notify(NOTICE+1,NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Luma နှိုးစကား မရနိုင်ပါ")
            .setContentText(detail).setAutoCancel(true).build())
        finishAlarm(SleepWakeState.BLOCKED)
    }
    private fun finishAlarm(state:SleepWakeState){id?.let{try{SleepWakeGraph.journal(this).mark(it,state)}catch(_:Exception){}};stopSound()}
    private fun stopSound(){
        if(current===this)current=null
        handler.removeCallbacksAndMessages(null);tts?.stop();tts?.shutdown();tts=null
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){if(current===this)current=null;handler.removeCallbacksAndMessages(null);tts?.shutdown();tts=null;focus?.let{audio?.abandonAudioFocusRequest(it)};focus=null;lock?.let{if(it.isHeld)it.release()};lock=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        const val CHANNEL="luma_local_wake_alarm_v1"
        private const val NOTICE=94621
        private const val STOP="com.example.voicebrainlive.STOP_LOCAL_WAKE"
        @Volatile private var current:WakeAlarmService?=null
        val active:Boolean get()=current!=null
        @Synchronized fun stopOwned(expected:String?=null):String?{val s=current?:return null;if(expected!=null&&s.id!=expected)return null;val id=s.id;current=null;s.stopSound();return id}
        fun pauseForVoiceInput(){current?.let{it.pausedUntil=SystemClock.elapsedRealtime()+20_000;it.tts?.stop()}}
        private val messages=listOf(
            "မင်္ဂလာ နံနက်ခင်းပါ။ နိုးချိန်ရောက်ပါပြီ။ အသက်ကို ဖြည်းဖြည်းရှူပြီး အိပ်ရာက အေးအေးဆေးဆေး ထကြရအောင်။",
            "ဒီနေ့ကို တစ်ဆင့်ချင်း စတင်လိုက်ရအောင်။ သင် လုပ်နိုင်ပါတယ်။ ရေလေးသောက်ပြီး စိတ်လန်းလန်းနဲ့ အိပ်ရာထလိုက်ပါနော်။",
            "အလျင်မလိုပါဘူး။ ဖြည်းဖြည်းလေး ထိုင်ပြီး ခြေလက်တွေ ဆန့်လိုက်ပါ။ ကောင်းမွန်တဲ့ နေ့သစ်လေး ဖြစ်ပါစေ။")
    }
}
