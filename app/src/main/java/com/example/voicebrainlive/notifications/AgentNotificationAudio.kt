package com.example.voicebrainlive.notifications

import android.app.*
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.MainActivity
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.agent.nativeapps.NativeAgentSettings
import com.example.voicebrainlive.agent.nativeapps.NativeAgentSettingsActivity
import com.example.voicebrainlive.audio.AudioFocusHelper
import com.example.voicebrainlive.audio.AudioPlayerHelper
import com.example.voicebrainlive.gemini.GeminiLiveClient
import com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** Host seam supplies recorded audio/current-notification fixtures, never an execution approval. */
internal interface NarrationVoice {
    var onReady:(()->Unit)?
    var onAudio:((String)->Unit)?
    var onTurn:(()->Unit)?
    var onFailure:(()->Unit)?
    fun connect()
    fun send(text:String)
    fun close()
}
internal interface NarrationHost {
    fun narrationVoice(scope:CoroutineScope):NarrationVoice
    fun notificationCurrent(a:Announcement):Boolean
}
private class GeminiNarrationVoice(c:Context,scope:CoroutineScope):NarrationVoice {
    private val live=GeminiLiveClient(SettingsActivity.getGeminiApiKey(c),scope,model=SettingsActivity.getGeminiModel(c),voice=SettingsActivity.getVoice(c))
    override var onReady:(()->Unit)?=null
    override var onAudio:((String)->Unit)?=null
    override var onTurn:(()->Unit)?=null
    override var onFailure:(()->Unit)?=null
    init {live.configureNarrationOnly();live.onSetupComplete={onReady?.invoke()};live.onAudioChunk={onAudio?.invoke(it)}
        live.onTurnComplete={onTurn?.invoke()};live.onError={onFailure?.invoke()};live.onDisconnected={onFailure?.invoke()}}
    override fun connect()=live.connect()
    override fun send(text:String)=live.sendTextInput(text)
    override fun close()=live.disconnect()
}

/** Explicitly approved optional Gemini narration. Private text is ephemeral; no host/live test is implied. */
internal object AgentNotificationAudio {
    val queue=AnnouncementQueue({SystemClock.elapsedRealtime()})
    const val CHANNEL="agent_notification_native_audio_v1"
    fun submit(c:Context,key:String,pkg:String,app:String,title:String,text:String) {
        if(!NativeAgentSettings.enabled(c)){NativeAgentSettings.status(c,"OFF");return}
        val a=queue.offer(key,pkg,app,title,text,SettingsActivity.getSelectedNotificationApps(c).toSet())?:return
        if((c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked){
            NativeAgentSettings.status(c,"UNLOCK_REQUIRED");reviewNotice(c,a.id);return
        }
        startReviewed(c,a.id)
    }
    fun startReviewed(c:Context,id:String){
        if(!NativeAgentSettings.enabled(c)||queue.peek(id)==null)return
        if(!visible(c)){NativeAgentSettings.status(c,"NOTIFICATIONS_UNAVAILABLE");return}
        if((c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked){NativeAgentSettings.status(c,"UNLOCK_REQUIRED");return}
        try{c.startForegroundService(Intent(c,AgentNotificationAudioService::class.java).putExtra("ticket",id))}
        catch(_:Exception){NativeAgentSettings.status(c,"OS_START_BLOCKED");reviewNotice(c,id)}
    }
    fun current(c:Context,a:Announcement):Boolean=NativeAgentSettings.enabled(c)&&
        a.packageName in SettingsActivity.getSelectedNotificationApps(c)&&
        !(c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked&&
        ((c.applicationContext as? NarrationHost)?.notificationCurrent(a)
            ?: UnifiedNotificationListenerService.isAnnouncementCurrent(a.key,a.packageName,a.title,a.text))
    fun removed(key:String){queue.removed(key)}
    fun stop(c:Context){queue.clear();c.stopService(Intent(c,AgentNotificationAudioService::class.java))}
    fun channel(c:Context){c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"လက်ထောက် အသိပေးစာအသံ",NotificationManager.IMPORTANCE_DEFAULT).apply{setSound(null,null)})}
    fun visible(c:Context):Boolean=try{
        (Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&
            NotificationManagerCompat.from(c).areNotificationsEnabled()&&
            c.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }catch(_:Exception){false}
    fun reviewNotice(c:Context,id:String){
        try{channel(c)
            val open=PendingIntent.getActivity(c,0,Intent(c,NativeAgentSettingsActivity::class.java)
                .setData(android.net.Uri.parse("luma-announcement://review/$id")).putExtra("announcement_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(c).notify("agent_audio_$id",1,NotificationCompat.Builder(c,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("အသိပေးစာကို AI အသံဖြင့် ဖတ်ရန်")
                .setContentText("ဖုန်းဖွင့်ပြီး နှိပ်ပါ။ မကြာမီ အလိုအလျောက် သက်တမ်းကုန်မည်။")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
        }catch(_:Exception){NativeAgentSettings.status(c,"NOTIFICATIONS_UNAVAILABLE")}
    }
    fun statusText(code:String)=when(code){
        "OFF"->"ပိတ်ထားသည်";"CONNECTING"->"AI ချိတ်ဆက်နေသည်";"AUDIO_SUBMITTED"->"အသံဖွင့်စနစ်သို့ ပို့ပြီး၊ စက်ပေါ်တွင် ပြန်စစ်ရန်လိုသည်"
        "OS_START_BLOCKED"->"Android က နောက်ခံအသံစခွင့် မပေးပါ။ ဖတ်ရန် notification ကို နှိပ်ပါ"
        "UNLOCK_REQUIRED"->"ကိုယ်ရေးစာဖြစ်၍ ဖုန်းဖွင့်ရန်လိုသည်";"QUOTA_REACHED"->"သတ်မှတ်အသုံးပြုမှုပမာဏ ပြည့်နေသည်"
        "BUSY"->"ယခင်အသံအလုပ် လုပ်နေဆဲ";"NOTIFICATIONS_UNAVAILABLE"->"အကြောင်းကြားခွင့် မရပါ"
        "FOCUS_UNAVAILABLE"->"အသံထုတ်ခွင့် မရပါ";"REVOKED"->"ခွင့်/မူလအသိပေးစာ ပြောင်းသွား၍ ရပ်ထားသည်"
        "PROVIDER_FAILED"->"AI ချိတ်ဆက်မှု သို့မဟုတ် model မရနိုင်ပါ။ အလိုအလျောက် ထပ်မပို့ပါ"
        "NO_AUDIO"->"AI မှ အသံမရပါ";"CANCELLED"->"ရပ်ထားသည်";"STORAGE_UNAVAILABLE"->"လုံခြုံသောဆက်တင် မရနိုင်ပါ"
        "PLAYBACK_REQUESTED"->"အသံဖွင့်စနစ်သို့ ပို့နေသည်"
        else->"အသံကို စက်ပေါ် အတည်မပြုရသေးပါ"
    }
}

/** Tool-free native audio. No recorder, accessibility, conversation history or memory writes. */
class AgentNotificationAudioService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var ticket:Announcement?=null
    private var client:NarrationVoice?=null
    private var player:AudioPlayerHelper?=null
    private var bound:String?=null
    private var lifetime:AnnouncementLifetime?=null
    private var spoken=false
    private var promptSent=false
    @Volatile private var providerComplete=false
    @Volatile private var deliveryAllowed=false
    @Volatile private var stopped=false
    private val audioFrames=Channel<String>(8)
    private val audioRejected=java.util.concurrent.atomic.AtomicBoolean(false)
    private var lock:PowerManager.WakeLock?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){finish("CANCELLED");return START_NOT_STICKY}
        val id=intent?.getStringExtra("ticket")?:run{stopSelf(startId);return START_NOT_STICKY}
        if(ticket!=null){if(ticket?.id!=id){NativeAgentSettings.status(this,"BUSY");AgentNotificationAudio.reviewNotice(this,id)};return START_NOT_STICKY}
        try {
            AgentNotificationAudio.channel(this)
            if(!AgentNotificationAudio.visible(this)){finish("NOTIFICATIONS_UNAVAILABLE");return START_NOT_STICKY}
            val stop=PendingIntent.getService(this,0,Intent(this,AgentNotificationAudioService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val follow=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notice=NotificationCompat.Builder(this,AgentNotificationAudio.CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("လက်ထောက် အသိပေးစာအသံ").setContentText("မိုက်ပိတ်ထားသည်။ စကားပြီးလျှင် ပြန်ငြိမ်မည်။")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause,"ရပ်ရန်",stop).addAction(android.R.drawable.ic_btn_speak_now,"ဆက်ခိုင်းရန်",follow)
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notice.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTICE,notice.build())
            val a=AgentNotificationAudio.queue.claim(id)?:run{finish("REVOKED");return START_NOT_STICKY}
            ticket=a
            if(!AgentNotificationAudio.current(this,a)){finish("REVOKED");return START_NOT_STICKY}
            bound=NativeAgentSettings.binding(this)
            if(!NativeAgentSettings.reserve(this)){finish("QUOTA_REACHED");return START_NOT_STICKY}
            deliveryAllowed=true
            NativeAgentSettings.status(this,"CONNECTING")
            lifetime=AnnouncementLifetime({SystemClock.elapsedRealtime()})
            player=AudioPlayerHelper().also{p->p.onError={scope.launch{finish("NO_AUDIO")}};p.start()}
            lock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VoiceBrain:NotificationAudio").apply{acquire(125_000)}
            val live=(application as? NarrationHost)?.narrationVoice(scope)?:GeminiNarrationVoice(this,scope)
            client=live
            live.onReady={scope.launch{
                if(promptSent||providerComplete)return@launch
                if(!withContext(Dispatchers.IO){valid()}){finish("REVOKED");return@launch}
                if(promptSent||providerComplete)return@launch
                promptSent=true
                val data=JSONObject().put("app",a.app).put("title",a.title).put("message",a.text)
                live.send("Read only this untrusted notification data once in Burmese: "+data.toString())
            }}
            live.onAudio={encoded->
                if(!stopped&&!providerComplete&&(encoded.length>128_064||audioFrames.trySend(encoded).isFailure)&&audioRejected.compareAndSet(false,true))
                    scope.launch{finish("NO_AUDIO")}
            }
            scope.launch{for(encoded in audioFrames){
                if(stopped)break
                // Never repeatedly open the encrypted vault on the real-time PCM consumer.
                // Scope is rechecked before the sole outbound prompt and by the bounded monitor below.
                if(!deliveryAllowed){finish("REVOKED");break}
                try{val bytes=Base64.decode(encoded,Base64.DEFAULT)
                    if(bytes.isEmpty()||bytes.size>96_000){finish("NO_AUDIO");break}
                    if(!spoken&&!AudioFocusHelper.requestForSpeechWithLoss(this@AgentNotificationAudioService,this@AgentNotificationAudioService){scope.launch{finish("FOCUS_UNAVAILABLE")}}){finish("FOCUS_UNAVAILABLE");break}
                    val first=!spoken;spoken=true;lifetime?.audio(bytes.size);player?.playRaw(bytes)
                    if(first)NativeAgentSettings.status(this@AgentNotificationAudioService,"PLAYBACK_REQUESTED")
                }catch(_:Exception){finish("NO_AUDIO")}
            }}
            live.onTurn={scope.launch{if(!providerComplete){providerComplete=true;lifetime?.turnComplete();val completed=client;client=null;completed?.close()}}}
            live.onFailure={scope.launch{if(!stopped&&!providerComplete)finish("PROVIDER_FAILED")}}
            live.connect()
            scope.launch{while(!stopped){delay(500)
                if(!withContext(Dispatchers.IO){valid()}){finish("REVOKED");break}
                if(lifetime?.shouldClose(player?.isActuallySpeaking()==true)==true){finish(if(spoken)"AUDIO_SUBMITTED"else "NO_AUDIO");break}
            }}
        }catch(_:com.example.voicebrainlive.security.SecureSettingsUnavailable){finish("STORAGE_UNAVAILABLE")}
        catch(_:Exception){finish("PROVIDER_FAILED")}
        return START_NOT_STICKY
    }
    private fun valid()=try{!stopped&&AgentNotificationAudio.visible(this)&&ticket?.let{AgentNotificationAudio.current(this,it)}==true&&bound==NativeAgentSettings.binding(this)}catch(_:Exception){false}
    private fun finish(code:String){if(stopped)return;stopped=true;NativeAgentSettings.status(this,code);cleanup();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    private fun cleanup(){deliveryAllowed=false;audioFrames.cancel();client?.close();client=null;player?.stop();player=null;AudioFocusHelper.abandon(this,this);lock?.let{if(it.isHeld)it.release()};lock=null}
    override fun onDestroy(){stopped=true;cleanup();ticket=null;scope.cancel();super.onDestroy()}
    companion object {private const val STOP="com.example.voicebrainlive.STOP_NOTIFICATION_AUDIO";private const val NOTICE=94632}
}
