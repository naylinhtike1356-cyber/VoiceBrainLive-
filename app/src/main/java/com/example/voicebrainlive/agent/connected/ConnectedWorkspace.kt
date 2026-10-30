package com.example.voicebrainlive.agent.connected

import android.content.Context
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.beta.*
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.Call

internal enum class ConnectedTextMode { CHAT, PLAN }
internal class ReviewedConnectedText(val text:String,val mode:ConnectedTextMode,val model:String,
    internal val review:BetaTextReview,internal val credential:ConnectionCredential,internal val sourceId:String?,internal val sourceRevision:Long) {
    override fun toString()="ReviewedConnectedText(content=<redacted>)"
}
internal class ReviewedNotionRead internal constructor(val databaseId:String,internal val credential:ConnectionCredential,
    internal val expires:Long,val query:String,val tasksOnly:Boolean,internal val mapping:LegacyTaskMapping?) {
    private val used=AtomicBoolean()
    internal fun claim(){connectionCheck(used.compareAndSet(false,true),"NOTION_REVIEW_ALREADY_USED")}
    override fun toString()="ReviewedNotionRead(scope=<redacted>)"
}
internal class ConnectedReply(val text:String,val tokens:Int?=null,val plan:BrainTask?=null) {
    override fun toString()="ConnectedReply(content=<redacted>)"
}
internal class ConnectedNotionSource(val snapshot:LegacyNotionSnapshot,val credential:ConnectionCredential,val createdAt:Long) {
    val id="notion_"+UUID.randomUUID().toString()
    val pin:AgentConnectionPin?=snapshot.mapping?.let{m->AgentConnectionPin(id,1,"LOCAL_LEGACY_PROFILE_NAMESPACE",m.databaseId,
        "database",1,m.businessStatusId,java.util.Collections.unmodifiableSet(LinkedHashSet(m.unfinished)))}
    override fun toString()="ConnectedNotionSource(content=<redacted>)"
}

/** Foreground composition only: existing planner/store/runner; no new executor or durable grants. */
internal class ConnectedWorkspace(val settings:ConnectedSettings,val store:EncryptedBrainStore,
    private val device:AgentReadPort,private val memory:BrainMemoryAccess,private val current:()->Boolean,
    private val now:()->Long=System::currentTimeMillis,private val geminiCalls:Call.Factory?=null,
    private val notionFactory:(ConnectionCredential,()->Boolean)->LegacyReadExchange={c,owner->NotionReadOnlyHttp(c,owner)}) {
    private var source:ConnectedNotionSource?=null
    private var requiresNotion=false
    private var sourceRevision=0L
    private var agent:BetaLocalAgent?=null
    private var preview:BrainTask?=null
    private var selected:BrainTask?=null
    private var cooldownUntil=0L
    private fun guard(){connectionCheck(current(),"STALE_FOREGROUND_SESSION")}
    private fun validSource(s:ConnectedNotionSource):Boolean=try{current()&&s.credential.isCurrent()&&now() in s.createdAt until s.createdAt+300_000&&s.snapshot.complete&&s.pin!=null&&settings.mapping(s.credential.target)==s.snapshot.mapping}catch(_:Exception){false}
    fun sourceDescription():String=source?.takeIf(::validSource)?.let{"Notion · ${it.snapshot.records.size} mapped unfinished tasks · ${it.snapshot.evidence}"}
        ?:if(requiresNotion)"Notion source unavailable/partial. Read it again or explicitly choose local tasks." else "Local saved tasks"
    fun clearSource(){sourceRevision++;requiresNotion=false;source=null;agent?.discardPreview();agent=null;preview=null;selected=null}
    fun reviewText(text:String,mode:ConnectedTextMode):ReviewedConnectedText {
        guard();connectionCheck(text.isNotBlank()&&text.length<=2000,"GOAL_TEXT_BUDGET")
        if(mode==ConnectedTextMode.PLAN&&requiresNotion)connectionCheck(source?.let(::validSource)==true,"NOTION_COMPLETE_MAPPED_SOURCE_REQUIRED")
        val credential=settings.gemini()
        val r=if(mode==ConnectedTextMode.CHAT)BetaTextReview.prepareChat(credential.target,text,true,now())
            else BetaTextReview.prepare(credential.target,text,true,now())
        return ReviewedConnectedText(text,mode,credential.target,r,credential,if(mode==ConnectedTextMode.PLAN)source?.takeIf(::validSource)?.id else null,sourceRevision)
    }
    suspend fun sendReviewed(action:ReviewedConnectedText):ConnectedReply {
        guard();connectionCheck(action.credential.isCurrent(),"CONNECTION_SETTINGS_CHANGED")
        if(action.mode==ConnectedTextMode.PLAN)connectionCheck(action.sourceRevision==sourceRevision&&action.sourceId==source?.takeIf(::validSource)?.id,"PLAN_SOURCE_CHANGED_REVIEW_AGAIN")
        val exchange=BetaGeminiTextExchange(action.review,{action.credential.requireSecret()},current,
            {current()&&action.credential.isCurrent()},now,geminiCalls)
        val response=withTimeout(30_000){exchange.exchange(action.review.request)}
        currentCoroutineContext().ensureActive();guard()
        connectionCheck(action.credential.isCurrent(),"CONNECTION_SETTINGS_CHANGED")
        if(action.mode==ConnectedTextMode.PLAN)connectionCheck(action.sourceRevision==sourceRevision&&action.sourceId==source?.takeIf(::validSource)?.id,"PLAN_SOURCE_CHANGED_REVIEW_AGAIN")
        if(action.mode==ConnectedTextMode.CHAT){
            val reply=BetaTextProtocol.textResponse(response.first,response.second)
            return ConnectedReply(reply.first,reply.second)
        }
        val output=BetaTextProtocol.response(response.first,response.second,action.text)
        val captured=source?.takeIf(::validSource)
        val connection=captured?.pin?:BetaLocalAgent.LOCAL
        val reads:(suspend (AgentConnectionPin)->AgentObservation)?=captured?.let{s->
            val read:suspend (AgentConnectionPin)->AgentObservation={pin->
            if(!validSource(s)||pin!=s.pin)AgentObservation(AgentStatus.BLOCKED,errorCode="NOTION_STALE_CONNECTION")
            else AgentObservation(AgentStatus.SUCCEEDED,AgentData.Tasks(pin,s.snapshot.records.filter{it.unfinished==true}.map{
                AgentTaskRow(it.pageId,it.title,it.rawStatus.orEmpty(),"UNKNOWN",it.priority)
            },true))
            };read
        }
        val composed=BetaLocalAgent(store,device,memory,current,now,taskConnection=connection,externalTasks=reads,
            sourceCurrent={captured==null||validSource(captured)})
        // Network completed before planner's bounded local interpretation. No timeout/security limit relaxed.
        val task=composed.previewTextPilot(action.text,BrainStructuredPort{goal,_->
            connectionCheck(goal==action.text,"CHANGED_GOAL");output
        })
        guard();agent?.discardPreview();agent=composed;preview=task;selected=null
        return ConnectedReply("Validated plan preview · not saved or executed",output.reportedTokens,task)
    }
    suspend fun savePreview(expected:BrainTask):BrainTask {
        guard();connectionCheck(preview?.definition?.digest==expected.definition.digest&&preview?.definition?.plan?.taskId==expected.definition.plan.taskId,"REVIEW_EXACT_PLAN")
        val saved=requireNotNull(agent).saveReviewed(expected.definition.plan.taskId,expected.definition.digest)
        guard();preview=null;selected=saved;return saved
    }
    suspend fun runSaved(expected:BrainTask):BrainTask {
        guard();connectionCheck(selected==expected,"REVIEW_EXACT_PLAN")
        val output=requireNotNull(agent).runReviewed(expected);guard();selected=output;return output
    }
    fun cancelSaved(expected:BrainTask):BrainTask {
        guard();connectionCheck(selected==expected,"REVIEW_EXACT_PLAN")
        return requireNotNull(agent).cancelReviewed(expected).also{selected=it;preview=null}
    }
    fun discard(){clearSource()}
    fun discardPreview(){agent?.discardPreview();agent=null;preview=null;selected=null}
    fun savedPlans():List<BrainTask>{guard();return store.list().filterNot(LocalUserTaskRecord::matches)}
    fun restoreSaved(id:String):BrainTask {
        guard();agent?.discardPreview();preview=null
        val reader=BetaLocalAgent(store,device,memory,current,now)
        return reader.restoreForReview(id).also{agent=reader;selected=it}
    }
    fun localTasks()=BetaLocalTasks(store,now,current)
    fun reviewNotion(query:String="",tasksOnly:Boolean=false):ReviewedNotionRead {
        guard();connectionCheck(now()>=cooldownUntil,"NOTION_RATE_LIMITED")
        connectionCheck(query.length<=100&&query.none{it.isISOControl()},"NOTION_INVALID_READ")
        val at=now();connectionCheck(at in 0..Long.MAX_VALUE-120_000,"INVALID_CLOCK")
        val c=settings.notion();val mapping=if(tasksOnly)settings.mapping(c.target)?:throw ConnectionFailure("BUSINESS_STATUS_MAPPING_REQUIRED")else null
        return ReviewedNotionRead(c.target,c,at+120_000,query,tasksOnly,mapping)
    }
    private fun owner(a:ReviewedNotionRead):Boolean=try{current()&&a.credential.isCurrent()&&now() in a.expires-120_000 until a.expires&&(!a.tasksOnly||settings.mapping(a.databaseId)==a.mapping)}catch(_:Exception){false}
    private suspend fun <T> notion(a:ReviewedNotionRead,block:suspend(LegacyNotionReader)->T):T {
        guard();a.claim();connectionCheck(owner(a),"NOTION_STALE_CONNECTION")
        val reader=LegacyNotionReader(a.databaseId,notionFactory(a.credential){owner(a)}){owner(a)}
        try{return block(reader).also{connectionCheck(owner(a),"NOTION_STALE_CONNECTION")}}
        catch(e:ConnectionFailure){if(e.code=="NOTION_RATE_LIMITED"){
            val delay=(e.retryAfterSeconds?:60).coerceIn(1,86_400)*1000;val at=now()
            cooldownUntil=if(at<=Long.MAX_VALUE-delay)at+delay else Long.MAX_VALUE
        };throw e}
    }
    suspend fun readSchema(a:ReviewedNotionRead)=notion(a){it.schema()}
    suspend fun readNotion(a:ReviewedNotionRead):LegacyNotionSnapshot=notion(a){reader->
        clearSource()
        requiresNotion=a.tasksOnly
        val schema=reader.schema()
        val mapping=if(a.tasksOnly)a.mapping?:throw ConnectionFailure("BUSINESS_STATUS_MAPPING_REQUIRED")else null
        val snapshot=reader.read(schema,mapping,a.query)
        connectionCheck(owner(a),"NOTION_STALE_CONNECTION")
        if(a.tasksOnly&&snapshot.complete)source=ConnectedNotionSource(snapshot,a.credential,now())
        snapshot
    }
    fun saveMapping(a:ReviewedNotionRead,schema:LegacyNotionSchema,mapping:LegacyTaskMapping,reviewed:Boolean):Boolean {
        guard();connectionCheck(owner(a)&&schema.databaseId==a.databaseId,"NOTION_STALE_CONNECTION")
        val checked=schema.validate(mapping)
        return settings.saveMapping(checked,a.credential,reviewed).also{clearSource()}
    }
}

/** Optional app-owned dependency composition; host tests supply fakes without a production bypass. */
internal interface ConnectedWorkspaceHost {
    fun connectedWorkspace(current:()->Boolean):ConnectedWorkspace
    fun connectedSettings():ConnectedSettings
}
internal object ConnectedGraph {
    fun settings(context:Context):ConnectedSettings=(context.applicationContext as? ConnectedWorkspaceHost)?.connectedSettings()
        ?:ConnectedSettings.android(context)
    fun workspace(context:Context,current:()->Boolean):ConnectedWorkspace {
        val app=context.applicationContext
        (app as? ConnectedWorkspaceHost)?.let{return it.connectedWorkspace(current)}
        val voice=app as VoiceBrainApplication
        return ConnectedWorkspace(ConnectedSettings.android(app),AndroidBrainAlpha.store(app),AndroidAgentReadPort(app),
            BrainMemoryAccess({ConfirmedMemoryContextConsent.isEnabled(app)},{voice.confirmedMemoryStore.list()},System::currentTimeMillis),current)
    }
}
