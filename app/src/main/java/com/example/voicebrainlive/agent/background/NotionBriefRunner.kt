package com.example.voicebrainlive.agent.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Closed two-read recipe over existing adapters; no planner, tool dispatcher or side-effect grant. */
internal class NotionBriefRunner(private val repo:ScheduledRequests,private val port:NotionBriefPort,
    private val notifications:()->Boolean,private val publish:(ScheduledRequest)->Boolean){
    suspend fun scheduled(id:String,revision:Long):ScheduledRequest? {
        val r=repo.history().singleOrNull{it.id==id&&it.revision==revision&&it.state==RequestState.SCHEDULED}?:return null
        if(r.kind!=RequestKind.NOTION_BRIEF||r.brief?.automatic!=true)return null
        if(!notifications())return repo.fail(r,"NOTIFICATIONS_UNAVAILABLE")
        return execute(repo.claimBrief(r))
    }
    /** A fresh exact foreground review supplies this owned claim, never a restored biometric. */
    suspend fun execute(claim:ScheduledRequest):ScheduledRequest? {
        requestCheck(repo.owns(claim)&&claim.kind==RequestKind.NOTION_BRIEF,"STALE_REVIEW")
        try {
            val result=withTimeout(90_000){port.generate(requireNotNull(claim.brief),{repo.owns(claim)},{repo.reserveGemini(claim)})}
            val done=repo.finishBrief(claim,result)
            // Failed notification delivery cannot undo a persisted result or claim delivery.
            return if(runCatching{publish(done)}.getOrDefault(false))done else repo.notificationFailed(done)
        }catch(c:CancellationException){
            withContext(NonCancellable){if(repo.owns(claim))runCatching{repo.finishBrief(claim,null,"BRIEF_INTERRUPTED")}}
            throw c
        }catch(e:Exception){
            if(!repo.owns(claim))return null
            val reason=when{
                e is ScheduledRequestFailure&&e.code=="DAILY_CALL_BUDGET"->"DAILY_CALL_BUDGET"
                e is NotionBriefFailure->"BRIEF_"+e.failure.name
                else->"BRIEF_INVALID_RESPONSE"
            }
            return repo.finishBrief(claim,null,reason).also{publish(it)}
        }finally{repo.releaseBrief(claim)}
    }
}
