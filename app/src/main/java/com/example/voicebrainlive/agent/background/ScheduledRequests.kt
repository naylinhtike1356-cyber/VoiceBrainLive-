package com.example.voicebrainlive.agent.background

import java.io.*
import java.util.Base64
import java.util.UUID

/** Scheduling is a handoff, never a persisted approval or an executable model command. */
internal enum class RequestKind { OPEN_APP, PLAY_MEDIA, AI_GOAL, NOTION_BRIEF }
internal enum class RequestState { QUEUE_PENDING, SCHEDULED, READY, PAUSED, CANCELLED, SUBMITTING, SUBMITTED, DRAFT_READY, SUMMARY_READY, BLOCKED, EXPIRED }
internal data class ScheduledRequest(val id:String,val revision:Long,val kind:RequestKind,val objective:String,
    val packageName:String,val certificate:String,val dueAt:Long,val expiresAt:Long,val state:RequestState,
    val updatedAt:Long,val owner:String="",val reason:String="",val brief:NotionBriefScope?=null,
    val briefResult:NotionBriefResult?=null,val geminiAttemptAt:Long?=null) {
    override fun toString()="ScheduledRequest(id=$id,state=$state,content=<redacted>)"
}
internal interface RequestPersistence { fun read():String?; fun write(value:String):Boolean }
internal class ScheduledRequestFailure(val code:String):Exception(code)
internal fun requestCheck(ok:Boolean,code:String="INVALID_REQUEST"){if(!ok)throw ScheduledRequestFailure(code)}

/** New field in the existing encrypted Settings vault; no new database or legacy import. */
internal object RequestCodec {
    fun encode(rows:List<ScheduledRequest>):String {
        validate(rows)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{o->o.writeInt(2);o.writeInt(rows.size);rows.forEach{r->
            o.writeUTF(r.id);o.writeLong(r.revision);o.writeUTF(r.kind.name);o.writeUTF(r.objective)
            o.writeUTF(r.packageName);o.writeUTF(r.certificate);o.writeLong(r.dueAt);o.writeLong(r.expiresAt)
            o.writeUTF(r.state.name);o.writeLong(r.updatedAt);o.writeUTF(r.owner);o.writeUTF(r.reason)
            o.writeBoolean(r.brief!=null);r.brief?.let{o.writeUTF(it.databaseId);o.writeUTF(it.model);o.writeUTF(it.binding);o.writeBoolean(it.automatic)}
            o.writeBoolean(r.briefResult!=null);r.briefResult?.let{result->o.writeUTF(result.summary);o.writeInt(result.sourceIds.size);result.sourceIds.forEach(o::writeUTF);o.writeInt(result.matchedTasks);o.writeBoolean(result.providerCalled)}
            o.writeBoolean(r.geminiAttemptAt!=null);r.geminiAttemptAt?.let(o::writeLong)
        }}
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    fun decode(value:String?):List<ScheduledRequest> {
        if(value==null)return emptyList()
        try {
            requestCheck(value.length<=600_000,"DATA_UNREADABLE")
            return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use{i->
                val schema=i.readInt();requestCheck(schema in 1..2,"UNSUPPORTED_SCHEMA");val n=i.readInt();requestCheck(n in 0..30,"DATA_UNREADABLE")
                val rows=List(n){
                    val r=ScheduledRequest(i.readUTF(),i.readLong(),RequestKind.valueOf(i.readUTF()),i.readUTF(),i.readUTF(),
                        i.readUTF(),i.readLong(),i.readLong(),RequestState.valueOf(i.readUTF()),i.readLong(),i.readUTF(),i.readUTF())
                    if(schema==1)r else {
                        val scope=if(i.readBoolean())NotionBriefScope(i.readUTF(),i.readUTF(),i.readUTF(),i.readBoolean())else null
                        val result=if(i.readBoolean()){val summary=i.readUTF();val count=i.readInt();requestCheck(count in 0..10);NotionBriefResult(summary,List(count){i.readUTF()},i.readInt(),i.readBoolean())}else null
                        r.copy(brief=scope,briefResult=result,geminiAttemptAt=if(i.readBoolean())i.readLong()else null)
                    }
                }
                requestCheck(i.available()==0,"DATA_UNREADABLE");validate(rows);rows
            }
        }catch(e:ScheduledRequestFailure){throw e}catch(_:Exception){throw ScheduledRequestFailure("DATA_UNREADABLE")}
    }
    private fun validate(rows:List<ScheduledRequest>){
        requestCheck(rows.size<=30&&rows.map{it.id}.toSet().size==rows.size)
        rows.forEach{r->
            requestCheck(r.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))&&r.revision>0&&r.revision<Long.MAX_VALUE)
            requestCheck(r.objective.isNotBlank()&&r.objective.length<=1000&&r.objective.none{it=='\u0000'})
            requestCheck(r.updatedAt>=0&&r.dueAt>=0&&r.expiresAt>r.dueAt&&r.expiresAt-r.dueAt==86_400_000L)
            if(r.kind in setOf(RequestKind.AI_GOAL,RequestKind.NOTION_BRIEF))requestCheck(r.packageName.isEmpty()&&r.certificate.isEmpty())
            else requestCheck(r.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))&&r.certificate.matches(Regex("[a-f0-9]{64}")))
            requestCheck((r.state==RequestState.SUBMITTING)==r.owner.isNotEmpty())
            requestCheck(r.reason in setOf("","QUEUE_UNAVAILABLE","NOTIFICATIONS_UNAVAILABLE","PROCESS_INTERRUPTED","EFFECT_UNAVAILABLE","DAILY_CALL_BUDGET")||BriefError.entries.any{r.reason=="BRIEF_"+it.name})
            requestCheck((r.kind==RequestKind.NOTION_BRIEF)==(r.brief!=null));r.brief?.let(::validateBrief)
            requestCheck((r.state==RequestState.SUMMARY_READY)==(r.briefResult!=null));r.briefResult?.let(::validateBriefResult)
            requestCheck(r.briefResult?.providerCalled!=true||r.geminiAttemptAt!=null)
            requestCheck(r.kind!=RequestKind.NOTION_BRIEF||r.state !in setOf(RequestState.SUBMITTED,RequestState.DRAFT_READY))
            r.geminiAttemptAt?.let{requestCheck(r.kind==RequestKind.NOTION_BRIEF&&it in 0..r.updatedAt)}
        }
    }
}

/** App-owned persistence coordinator, not a second agent/tool execution engine.
 * Generic requests require a fresh foreground tap. The closed Notion recipe requires its own
 * exact, explicitly reviewed one-shot source/data/cost policy before background execution.
 */
internal class ScheduledRequests(private val persistence:RequestPersistence,private val clock:()->Long=System::currentTimeMillis,
    private val ids:()->String={UUID.randomUUID().toString()}) {
    private val process=UUID.randomUUID().toString()
    @Volatile private var latest:List<ScheduledRequest> = emptyList()
    private val activeBriefIds=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Synchronized fun history():List<ScheduledRequest> = load()
    @Synchronized fun create(kind:RequestKind,objective:String,pkg:String,certificate:String,dueAt:Long):ScheduledRequest {
        requestCheck(kind!=RequestKind.NOTION_BRIEF)
        val rows=load();val now=time(rows)
        requestCheck(rows.size<30,"CAPACITY_REACHED")
        requestCheck(now<=Long.MAX_VALUE-31L*86_400_000&&dueAt>=now+60_000&&dueAt-now<=30L*86_400_000)
        val r=ScheduledRequest(ids(),1,kind,objective.trim(),pkg,certificate,dueAt,dueAt+86_400_000,RequestState.QUEUE_PENDING,now)
        save(rows+r);return r
    }
    @Synchronized fun createBrief(objective:String,scope:NotionBriefScope,dueAt:Long,reviewed:Boolean):ScheduledRequest {
        requestCheck(reviewed,"BRIEF_REVIEW_REQUIRED");validateBrief(scope)
        val rows=load();val now=time(rows);requestCheck(rows.size<30,"CAPACITY_REACHED")
        requestCheck(now<=Long.MAX_VALUE-31L*86_400_000&&dueAt>=now+60_000&&dueAt-now<=30L*86_400_000)
        val r=ScheduledRequest(ids(),1,RequestKind.NOTION_BRIEF,objective.trim(),"","",dueAt,dueAt+86_400_000,RequestState.QUEUE_PENDING,now,brief=scope)
        save(rows+r);return r
    }
    @Synchronized fun queueAccepted(r:ScheduledRequest):ScheduledRequest=change(r){old,now->
        requestCheck(old.state==RequestState.QUEUE_PENDING&&now<old.dueAt,"QUEUE_CONFIRMATION_EXPIRED")
        old.copy(state=RequestState.SCHEDULED,updatedAt=now)}
    @Synchronized fun due(id:String,revision:Long):ScheduledRequest? {
        val rows=load();val now=time(rows);val r=rows.singleOrNull{it.id==id&&it.revision==revision}?:return null
        if(r.state!=RequestState.SCHEDULED||now<r.dueAt)return null
        return replace(rows,r,r.copy(revision=r.revision+1,state=if(now>=r.expiresAt)RequestState.EXPIRED else RequestState.READY,updatedAt=now))
    }
    @Synchronized fun pause(r:ScheduledRequest):ScheduledRequest=change(r){old,now->
        requestCheck(old.state in setOf(RequestState.SCHEDULED,RequestState.READY));old.copy(state=RequestState.PAUSED,updatedAt=now)}
    @Synchronized fun resume(r:ScheduledRequest):ScheduledRequest=change(r){old,now->
        requestCheck(old.state==RequestState.PAUSED&&now<old.expiresAt)
        old.copy(state=if(now>=old.dueAt)RequestState.READY else RequestState.QUEUE_PENDING,reason="",updatedAt=now)}
    @Synchronized fun cancel(r:ScheduledRequest):ScheduledRequest=change(r){old,now->
        requestCheck(old.state in setOf(RequestState.SCHEDULED,RequestState.READY,RequestState.PAUSED,RequestState.BLOCKED,RequestState.EXPIRED)||old.kind==RequestKind.NOTION_BRIEF&&old.state==RequestState.SUBMITTING)
        old.copy(state=RequestState.CANCELLED,reason="",owner="",updatedAt=now)}
    @Synchronized fun fail(r:ScheduledRequest,reason:String):ScheduledRequest=change(r){old,now->
        requestCheck(old.state in setOf(RequestState.QUEUE_PENDING,RequestState.SCHEDULED,RequestState.READY));old.copy(state=RequestState.BLOCKED,reason=reason,updatedAt=now)}
    @Synchronized fun notificationFailed(r:ScheduledRequest):ScheduledRequest=change(r){old,now->
        requestCheck(old.state in setOf(RequestState.READY,RequestState.SUMMARY_READY));old.copy(reason="NOTIFICATIONS_UNAVAILABLE",updatedAt=now)}
    /** Caller must have shown exact immutable record in a current foreground review. No worker calls this. */
    @Synchronized fun claimReviewed(r:ScheduledRequest,current:()->Boolean):ScheduledRequest=change(r){old,now->
        requestCheck(current(),"STALE_REVIEW");requestCheck(old.state==RequestState.READY&&now>=old.dueAt&&now<old.expiresAt,"NOT_READY")
        old.copy(state=RequestState.SUBMITTING,owner=process,reason="",updatedAt=now)}.also{if(it.kind==RequestKind.NOTION_BRIEF)activeBriefIds.add(it.id)}
    @Synchronized fun finish(r:ScheduledRequest,submitted:Boolean):ScheduledRequest=change(r){old,now->
        requestCheck(old.kind!=RequestKind.NOTION_BRIEF)
        requestCheck(old.state==RequestState.SUBMITTING&&old.owner==process)
        old.copy(state=if(!submitted)RequestState.BLOCKED else if(old.kind==RequestKind.AI_GOAL)RequestState.DRAFT_READY else RequestState.SUBMITTED,
            owner="",reason=if(submitted)""else "EFFECT_UNAVAILABLE",updatedAt=now)}
    @Synchronized fun claimBrief(r:ScheduledRequest):ScheduledRequest=change(r){old,now->
        requestCheck(old.kind==RequestKind.NOTION_BRIEF&&old.brief?.automatic==true&&old.state==RequestState.SCHEDULED&&now>=old.dueAt&&now<old.expiresAt,"NOT_READY")
        old.copy(state=RequestState.SUBMITTING,owner=process,reason="",updatedAt=now)}.also{activeBriefIds.add(it.id)}
    fun owns(r:ScheduledRequest):Boolean {
        val now=clock()
        return latest.any{it.id==r.id&&it.state==RequestState.SUBMITTING&&it.owner==r.owner&&r.owner==process&&it.brief==r.brief&&now in it.updatedAt until it.expiresAt&&(it.kind!=RequestKind.NOTION_BRIEF||it.id in activeBriefIds)}
    }
    fun releaseBrief(r:ScheduledRequest){activeBriefIds.remove(r.id)}
    @Synchronized fun reserveGemini(r:ScheduledRequest){
        val rows=load();val now=time(rows);val old=rows.singleOrNull{it.id==r.id}?:throw ScheduledRequestFailure("STALE_REVIEW")
        requestCheck(owns(r)&&now<old.expiresAt&&old.geminiAttemptAt==null,"STALE_REVIEW")
        requestCheck(rows.none{it.geminiAttemptAt?.let{at->now-at<86_400_000}==true},"DAILY_CALL_BUDGET")
        replace(rows,old,old.copy(revision=old.revision+1,geminiAttemptAt=now,updatedAt=now))
    }
    @Synchronized fun finishBrief(r:ScheduledRequest,result:NotionBriefResult?,reason:String=""):ScheduledRequest {
        val rows=load();val now=time(rows);val old=rows.singleOrNull{it.id==r.id}?:throw ScheduledRequestFailure("STALE_REVIEW")
        requestCheck(owns(r),"STALE_REVIEW")
        if(result!=null){validateBriefResult(result);requestCheck(now<old.expiresAt&&(!result.providerCalled||old.geminiAttemptAt!=null))}
        return replace(rows,old,old.copy(revision=old.revision+1,state=if(result==null)RequestState.BLOCKED else RequestState.SUMMARY_READY,
            briefResult=result?.copy(sourceIds=result.sourceIds.toList()),owner="",reason=reason,updatedAt=now)).also{releaseBrief(r)}
    }
    @Synchronized fun recover():List<ScheduledRequest>{
        val rows=load();val now=time(rows);val next=rows.map{r->
            when{
                r.state==RequestState.QUEUE_PENDING->r.copy(revision=r.revision+1,state=RequestState.BLOCKED,reason="QUEUE_UNAVAILABLE",updatedAt=now)
                r.state==RequestState.SUBMITTING&&r.kind==RequestKind.NOTION_BRIEF&&r.owner==process&&r.id !in activeBriefIds->r.copy(revision=r.revision+1,state=RequestState.BLOCKED,owner="",reason="PROCESS_INTERRUPTED",updatedAt=now)
                r.state==RequestState.SUBMITTING&&r.owner!=process->r.copy(revision=r.revision+1,state=RequestState.BLOCKED,owner="",reason="PROCESS_INTERRUPTED",updatedAt=now)
                r.state in setOf(RequestState.SCHEDULED,RequestState.READY,RequestState.PAUSED,RequestState.SUBMITTING)&&now>=r.expiresAt->r.copy(revision=r.revision+1,state=RequestState.EXPIRED,owner="",reason="",updatedAt=now)
                else->r
            }
        };if(next!=rows)save(next);return next
    }
    private fun change(r:ScheduledRequest,f:(ScheduledRequest,Long)->ScheduledRequest):ScheduledRequest {
        val rows=load();val now=time(rows);val old=rows.singleOrNull{it.id==r.id}?:throw ScheduledRequestFailure("STALE_REVIEW")
        requestCheck(old==r,"STALE_REVIEW");return replace(rows,old,f(old,now).copy(revision=old.revision+1))
    }
    private fun replace(rows:List<ScheduledRequest>,old:ScheduledRequest,next:ScheduledRequest):ScheduledRequest {
        save(rows.map{if(it.id==old.id)next else it});return next
    }
    private fun load():List<ScheduledRequest> = try {RequestCodec.decode(persistence.read()).also{latest=it}}catch(e:ScheduledRequestFailure){throw e}catch(_:Exception){throw ScheduledRequestFailure("SECURE_STORAGE_UNAVAILABLE")}
    private fun save(rows:List<ScheduledRequest>){
        val value=RequestCodec.encode(rows)
        try {requestCheck(persistence.write(value)&&persistence.read()==value,"STORAGE_WRITE_UNVERIFIED");latest=RequestCodec.decode(value)}
        catch(e:ScheduledRequestFailure){throw e}catch(_:Exception){throw ScheduledRequestFailure("SECURE_STORAGE_UNAVAILABLE")}
    }
    private fun time(rows:List<ScheduledRequest>)=clock().also{now->requestCheck(now>=0&&rows.all{now>=it.updatedAt},"CLOCK_CHANGED")}
}
