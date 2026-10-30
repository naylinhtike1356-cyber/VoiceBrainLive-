package com.example.voicebrainlive.commands

import com.example.voicebrainlive.OfflineCommandMatcher
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineDirectCommandsTest {
    @Test fun exactPayloadExecutesOnceAndReturnsActualFailureWithoutPlannerFallback()=runBlocking<Unit>{
        val calls=mutableListOf<Triple<String,String?,String?>>()
        val result=OfflineDirectCommands.execute("အသံ ၅၀ ရာခိုင်နှုန်းထားပေး"){c,t,v->calls+=Triple(c,t,v);"DENIED:permission"}
        assertEquals(listOf(Triple("set_volume",null,"50")),calls);assertEquals("DENIED:permission",result)
    }
    @Test fun unsupportedSensitiveAndCompositeInputsNeverReachDispatcher()=runBlocking<Unit>{
        var effects=0
        for(s in listOf("မောင်မောင်ကိုဖုန်းခေါ်ပေး","send_sms:123:hi","send_to_notion:x:y","click_at:1:2","ဓာတ်မီးဖွင့်ပြီးသီချင်းရပ်ပေး","/agent-run task1"))
            assertNull(OfflineDirectCommands.execute(s){_,_,_->effects++;"bad"})
        assertEquals(0,effects)
    }
    @Test fun staleOwnerCannotDispatch()=runBlocking<Unit>{
        var effects=0;assertEquals("BLOCKED:STALE_DIRECT_INPUT",OfflineDirectCommands.execute("ဓာတ်မီးဖွင့်ပေး",{false}){_,_,_->effects++;"bad"});assertEquals(0,effects)
    }
    @Test fun cancellationIsPropagatedWithoutRetry()=runBlocking<Unit>{
        var calls=0
        try{OfflineDirectCommands.execute("သီချင်းရပ်ပေး"){_,_,_->calls++;throw CancellationException("cancelled")};fail()}catch(_:CancellationException){}
        assertEquals(1,calls)
    }
    @Test fun transportExceptionCannotClaimSuccessOrLeakPayload()=runBlocking<Unit>{
        assertEquals("FAILED:LOCAL_COMMAND_UNAVAILABLE",OfflineDirectCommands.execute("Spotify ဖွင့်ပေး"){_,_,_->error("private app payload")})
    }
    @Test fun sensitiveRawLegacyCommandsStillReachCommonBoundaryAsExactUnchangedData(){
        assertEquals(Triple("send_sms","0912","don't play music?"),OfflineCommandMatcher.match("send_sms:0912:don't play music?"))
        assertEquals(CommandSafetyPolicy.Classification.SENSITIVE,CommandSafetyPolicy.classify("send_sms"))
    }
    @Test fun newDirectDomainDoesNotFallBackToLegacySubstringSideEffects(){
        for(s in listOf("ဓာတ်မီးမဖွင့်နဲ့","မနက်ဖြန်ဓာတ်မီးဖွင့်ပေး","ဓာတ်မီးဖွင့်ပြီးတော့အသံတိုးပေး","25:00 နှိုးစက်ထားပေး","သီချင်းဖွင့်ပေးလို့ရလား"))assertNull(OfflineCommandMatcher.match(s))
    }
    @Test fun warmedRoutingLatencyIsMeasuredSeparatelyFromDeviceCompletion(){
        val sample=listOf("ဓာတ်မီးဖွင့်ပေးပါ","အသံကိုနည်းနည်းကျယ်ပေးပါ","Spotify ကိုဖွင့်ပေးပါ","မနက် ၇ နာရီနှိုးစက်ပေးပါ")
        repeat(100){OfflineCommandMatcher.matchDirectAction(sample[it%sample.size])}
        val times=List(400){i->val start=System.nanoTime();assertNotNull(OfflineCommandMatcher.matchDirectAction(sample[i%sample.size]));System.nanoTime()-start}.sorted()
        val p95=times[(times.size*0.95).toInt()]
        val dir=java.io.File("build/reports/natural-intent").apply{mkdirs()}
        java.io.File(dir,"routing-latency.json").writeText("{\"samples\":400,\"p50_ns\":${times[times.size/2]},\"p95_ns\":$p95,\"max_ns\":${times.last()},\"warmed_host_only\":true,\"native_effects_measured\":false}")
        assertTrue("Warmed matcher p95 exceeded the 100ms routing target",p95<100_000_000L)
    }
}
