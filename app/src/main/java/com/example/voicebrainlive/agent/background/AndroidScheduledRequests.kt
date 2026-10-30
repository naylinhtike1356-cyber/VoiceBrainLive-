package com.example.voicebrainlive.agent.background

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.voicebrainlive.security.SecureSettingsStorage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import com.example.voicebrainlive.agent.connected.NotionMorningBrief

/** Queue/persistence composition only. Existing WorkManager and secure-settings owners are reused. */
internal interface ScheduledRequestHost {
    val scheduledRequests:ScheduledRequests
    val scheduledRequestQueue:AgentWorkQueue
}
internal interface ScheduledBriefHost { val notionBriefPort:NotionBriefPort }
internal object ScheduledRequestGraph {
    private var single:ScheduledRequests?=null
    @Synchronized fun repository(context:Context):ScheduledRequests {
        (context.applicationContext as? ScheduledRequestHost)?.let{return it.scheduledRequests}
        return single?:ScheduledRequests(object:RequestPersistence{
        private val app=context.applicationContext
        override fun read():String?=SecureSettingsStorage.require(app).getString("reviewed_scheduled_requests_v1",null)
        override fun write(value:String)=SecureSettingsStorage.require(app).edit().putString("reviewed_scheduled_requests_v1",value).commit()
        }).also{single=it}
    }
    suspend fun enqueue(context:Context,r:ScheduledRequest):Boolean {
        val data=Data.Builder().putString("request_id",r.id).putLong("request_revision",r.revision).build()
        val request=OneTimeWorkRequest.Builder(ScheduledRequestWorker::class.java)
            .setInputData(data).setInitialDelay((r.dueAt-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().apply{if(r.kind==RequestKind.NOTION_BRIEF&&r.brief?.automatic==true){setRequiredNetworkType(NetworkType.CONNECTED);setRequiresBatteryNotLow(true)}}.build())
            .addTag(tag(r.id)).build()
        val queue=(context.applicationContext as? ScheduledRequestHost)?.scheduledRequestQueue?:AndroidWorkQueue(context)
        return queue.enqueue("${tag(r.id)}_${r.revision}",request)
    }
    suspend fun cancel(context:Context,id:String){
        val queue=(context.applicationContext as? ScheduledRequestHost)?.scheduledRequestQueue?:AndroidWorkQueue(context)
        queue.cancelTag(tag(id))
        NotificationManagerCompat.from(context).cancel(tag(id),1)
    }
    fun tag(id:String)="voicebrain_reviewed_request_$id"
    fun briefRunner(context:Context):NotionBriefRunner {
        val app=context.applicationContext
        val port=(app as? ScheduledBriefHost)?.notionBriefPort?:NotionMorningBrief.android(app)
        return NotionBriefRunner(repository(app),port,{RequestNotifications.available(app)},{RequestNotifications.publish(app,it)})
    }
}

/** Generic wakeup or closed explicitly reviewed Notion recipe; no executable payload in WorkManager Data. */
class ScheduledRequestWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        if(inputData.keyValueMap.keys!=setOf("request_id","request_revision"))return Result.failure()
        val id=inputData.keyValueMap["request_id"] as? String?:return Result.failure()
        val revision=inputData.keyValueMap["request_revision"] as? Long?:return Result.failure()
        if(!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))||revision<=0)return Result.failure()
        return try {
            val repo=ScheduledRequestGraph.repository(applicationContext)
            val target=repo.history().singleOrNull{it.id==id&&it.revision==revision}
            if(target?.kind==RequestKind.NOTION_BRIEF&&target.brief?.automatic==true){
                ScheduledRequestGraph.briefRunner(applicationContext).scheduled(id,revision)
                return Result.success() // History, not WorkManager result, proves a persisted briefing.
            }
            val r=repo.due(id,revision)?:return Result.success() // duplicate/stale callback has no effects
            if(r.state==RequestState.READY&&!RequestNotifications.publish(applicationContext,r))repo.notificationFailed(r)
            Result.success() // queue handling only, never means the requested action was executed
        }catch(c:CancellationException){throw c}catch(_:Exception){Result.failure()}
    }
}

internal object RequestNotifications {
    const val CHANNEL="voicebrain_reviewed_requests"
    fun available(context:Context):Boolean {
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled())return false
        return context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    fun publish(context:Context,r:ScheduledRequest):Boolean=try{
        if(!available(context))false else {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL,"အချိန်သတ်မှတ်ထားသော အလုပ်များ",NotificationManager.IMPORTANCE_DEFAULT))
            val intent=Intent(context,ScheduledRequestsActivity::class.java).apply{
                data=Uri.Builder().scheme("voicebrain-request").authority("review").appendPath(r.id).build()
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notice=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if(r.state==RequestState.SUMMARY_READY)"Notion အကျဉ်းချုပ် ရပါပြီ"else "သတ်မှတ်ထားသော အလုပ် အခြေအနေ")
                .setContentText(if(r.state==RequestState.SUMMARY_READY)"အက်ပ်ထဲတွင် ရလဒ်နှင့် ရင်းမြစ်ကို ကြည့်နိုင်ပါသည်။"else "အလုပ်အခြေအနေကို ကြည့်ပြီး ဆက်လုပ်ရန် နှိပ်ပါ။")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
            NotificationManagerCompat.from(context).notify(ScheduledRequestGraph.tag(r.id),1,notice);true
        }
    }catch(_:Exception){false}
}
