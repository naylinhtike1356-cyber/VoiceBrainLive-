package com.example.voicebrainlive.agent.background

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NotionBriefRunnerTest {
    private var now=1_000_000L
    private val bytes=RequestBytes()
    private var seq=0
    private val repo=ScheduledRequests(bytes,{now},{"brief_${++seq}"})
    private val scope=NotionBriefScope("1".repeat(32),"host-text-model","a".repeat(64),true)
    private var reads=0;private var ai=0;private var notices=0
    private val port=NotionBriefPort{_,current,reserve->
        reads++;check(current());reserve();ai++
        NotionBriefResult("အရေးကြီးသော အလုပ်တစ်ခု ရှိပါသည်။",listOf("2".repeat(32)),1,true)
    }
    private fun schedule(automatic:Boolean=true):ScheduledRequest=repo.queueAccepted(repo.createBrief("မနက် ၈ နာရီ အကျဉ်းချုပ်",scope.copy(automatic=automatic),now+60_000,true))
    private fun runner(p:NotionBriefPort=port,allowed:Boolean=true)=NotionBriefRunner(repo,p,{allowed},{notices++;true})
    @Test fun authorizedDueRecipePersistsActualBriefAndSourceWithoutChangingTasks()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt;val result=runner().scheduled(r.id,r.revision)!!
        assertEquals(RequestState.SUMMARY_READY,result.state);assertEquals(1,reads);assertEquals(1,ai);assertEquals(1,notices)
        assertEquals("2".repeat(32),result.briefResult!!.sourceIds.single());assertNotNull(result.geminiAttemptAt)
        assertEquals(result,ScheduledRequests(bytes,{now}).history().single())
    }
    @Test fun defaultOffPolicyNeverCallsNotionOrAiFromWorker()=runBlocking<Unit>{
        val r=schedule(false);now=r.dueAt;assertNull(runner().scheduled(r.id,r.revision));assertEquals(0,reads);assertEquals(0,ai)
        assertEquals(RequestState.READY,repo.due(r.id,r.revision)!!.state)
    }
    @Test fun explicitScheduleDataReviewIsRequired(){
        try{repo.createBrief("goal",scope,now+60_000,false);fail()}catch(e:ScheduledRequestFailure){assertEquals("BRIEF_REVIEW_REQUIRED",e.code)}
        assertNull(bytes.value)
    }
    @Test fun earlyWorkerCannotMakeAnyProviderRequest()=runBlocking<Unit>{
        val r=schedule();try{runner().scheduled(r.id,r.revision);fail()}catch(_:ScheduledRequestFailure){}
        assertEquals(0,reads);assertEquals(0,ai)
    }
    @Test fun notificationRevocationBlocksBeforeNetwork()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt;val blocked=runner(allowed=false).scheduled(r.id,r.revision)!!
        assertEquals(RequestState.BLOCKED,blocked.state);assertEquals("NOTIFICATIONS_UNAVAILABLE",blocked.reason);assertEquals(0,reads)
    }
    @Test fun duplicateNativeCallbackCannotDuplicateReadOrBill()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt;runner().scheduled(r.id,r.revision);assertNull(runner().scheduled(r.id,r.revision))
        assertEquals(1,reads);assertEquals(1,ai)
    }
    @Test fun failedNotificationPreservesSummaryAndRecordsUndeliveredNotice()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt
        val done=NotionBriefRunner(repo,port,{true},{false}).scheduled(r.id,r.revision)!!
        assertEquals(RequestState.SUMMARY_READY,done.state);assertEquals("NOTIFICATIONS_UNAVAILABLE",done.reason)
        assertNotNull(done.briefResult);assertEquals(done,repo.history().single());assertEquals(1,ai)
        assertNull(runner().scheduled(r.id,r.revision));assertEquals(1,ai)
    }
    @Test fun sharedRollingBudgetPreventsSecondAiAttempt()=runBlocking<Unit>{
        val first=schedule();now=first.dueAt;runner().scheduled(first.id,first.revision)
        val second=schedule();now=second.dueAt;val blocked=runner().scheduled(second.id,second.revision)!!
        assertEquals("DAILY_CALL_BUDGET",blocked.reason);assertEquals(RequestState.BLOCKED,blocked.state);assertEquals(1,ai)
    }
    @Test fun failedNotionReadDoesNotSpendAiBudgetOrRetry()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt
        val blocked=runner(NotionBriefPort{_,_,_->reads++;throw NotionBriefFailure(BriefError.AUTHENTICATION)}).scheduled(r.id,r.revision)!!
        assertEquals("BRIEF_AUTHENTICATION",blocked.reason);assertNull(blocked.geminiAttemptAt);assertEquals(0,ai)
        assertNull(runner().scheduled(r.id,r.revision));assertEquals(1,reads)
    }
    @Test fun emptyRealSourceCanCompleteWithoutClaimingAnAiResponse()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt
        val done=runner(NotionBriefPort{_,_,_->NotionBriefResult("အလုပ်မရှိပါ။ AI မခေါ်ပါ။",emptyList(),0,false)}).scheduled(r.id,r.revision)!!
        assertFalse(done.briefResult!!.providerCalled);assertNull(done.geminiAttemptAt);assertEquals(0,ai)
    }
    @Test fun cancellationDuringReadRejectsLateOutputAndAvoidsAi()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val task=async{runner(NotionBriefPort{_,current,reserve->entered.complete(Unit);release.await();if(!current())throw NotionBriefFailure(BriefError.SCOPE_CHANGED);reserve();ai++;NotionBriefResult("bad",emptyList(),0,false)}).scheduled(r.id,r.revision)}
        entered.await();repo.cancel(repo.history().single());release.complete(Unit);assertNull(task.await())
        assertEquals(RequestState.CANCELLED,repo.history().single().state);assertEquals(0,ai);assertEquals(0,notices)
    }
    @Test fun processDeathNeverReusesStoredReadConsentAsAnInFlightClaim()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt;repo.claimBrief(r)
        val restarted=ScheduledRequests(bytes,{now});assertEquals(RequestState.BLOCKED,restarted.recover().single().state)
        assertNull(NotionBriefRunner(restarted,port,{true},{true}).scheduled(r.id,r.revision));assertEquals(0,reads)
    }
    @Test fun keyLossCannotProduceFalseSuccessNotification()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt
        try{runner(NotionBriefPort{_,_,reserve->reserve();bytes.readable=false;NotionBriefResult("must not claim saved",listOf("2".repeat(32)),1,true)}).scheduled(r.id,r.revision);fail()}catch(_:ScheduledRequestFailure){}
        assertEquals(0,notices);bytes.readable=true;assertEquals(RequestState.BLOCKED,repo.recover().single().state)
    }
    @Test fun aPortCannotClaimAiCompletionWithoutDurablyReservingItsAttempt()=runBlocking<Unit>{
        val r=schedule();now=r.dueAt
        val blocked=runner(NotionBriefPort{_,_,_->NotionBriefResult("unreserved",listOf("2".repeat(32)),1,true)}).scheduled(r.id,r.revision)!!
        assertEquals(RequestState.BLOCKED,blocked.state);assertNull(blocked.briefResult)
    }
    @Test fun schemaOneRecordsRemainReadableWithoutWriteOrDestructiveMigration(){
        val legacy=repo.queueAccepted(repo.create(RequestKind.AI_GOAL,"မြန်မာ မူကြမ်း","","",now+60_000))
        val b=java.io.ByteArrayOutputStream();java.io.DataOutputStream(b).use{o->
            o.writeInt(1);o.writeInt(1);o.writeUTF(legacy.id);o.writeLong(legacy.revision);o.writeUTF(legacy.kind.name);o.writeUTF(legacy.objective)
            o.writeUTF(legacy.packageName);o.writeUTF(legacy.certificate);o.writeLong(legacy.dueAt);o.writeLong(legacy.expiresAt);o.writeUTF(legacy.state.name);o.writeLong(legacy.updatedAt);o.writeUTF(legacy.owner);o.writeUTF(legacy.reason)
        }
        bytes.value=java.util.Base64.getEncoder().encodeToString(b.toByteArray());val before=bytes.value;val writes=bytes.writes
        assertEquals(legacy,repo.history().single());assertEquals(before,bytes.value);assertEquals(writes,bytes.writes)
    }
}
