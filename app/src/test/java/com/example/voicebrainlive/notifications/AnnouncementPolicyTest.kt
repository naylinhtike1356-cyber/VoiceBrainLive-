package com.example.voicebrainlive.notifications

import org.junit.Assert.*
import org.junit.Test

class AnnouncementPolicyTest {
    private var now=1_000L
    private var id=0
    private val q=AnnouncementQueue({now},{"ticket-${++id}"})
    private fun offer(text:String="မြန်မာ 한국어 English")=q.offer("key","app","App","Title",text,setOf("app"))
    @Test fun selectedOnlyAndBoundedData(){assertNull(q.offer("k","other","App","T","body",setOf("app")));val a=offer("x".repeat(700))!!;assertEquals(480,a.text.length);assertFalse(a.toString().contains(a.text))}
    @Test fun duplicateIsNotClaimedTwice(){val a=offer()!!;assertNull(offer());assertEquals(a,q.claim(a.id));assertNull(q.claim(a.id));assertNull(offer())}
    @Test fun removedNotificationCannotBePlayed(){val a=offer()!!;q.removed("key");assertNull(q.claim(a.id))}
    @Test fun expiredAndClockRewoundTicketsFailClosed(){val a=offer()!!;now+=120_001;assertNull(q.claim(a.id));val b=offer("new")!!;now=1;assertNull(q.claim(b.id))}
    @Test fun secretsAreNotNarrated(){listOf("OTP 123456","Your password reset","အတည်ပြုကုဒ် ၁၂၃၄၅၆","인증번호 1234").forEach{assertNull(offer(it))}}
    @Test fun queueHasFiniteCapacity(){repeat(8){assertNotNull(offer("message $it"))};assertNull(offer("overflow"));q.clear();assertNotNull(offer())}
    @Test fun noAudioTimesOutInsteadOfFakeSuccess(){val l=AnnouncementLifetime{now};now+=19_999;assertFalse(l.shouldClose(false));now++;assertTrue(l.shouldClose(false))}
    @Test fun idleStartsAfterTurnAudioTailAndQueueDrain(){val l=AnnouncementLifetime{now};l.audio(48_000);l.turnComplete();now+=2_000;assertFalse(l.shouldClose(true));assertFalse(l.shouldClose(false));now+=14_999;assertFalse(l.shouldClose(false));now++;assertTrue(l.shouldClose(false))}
    @Test fun unfinishedTurnAndRepeatedAudioCannotRunForever(){val l=AnnouncementLifetime{now};l.audio(1);now+=119_999;assertFalse(l.shouldClose(false));now++;assertTrue(l.shouldClose(true))}
    @Test fun invalidAndOversizedAudioRejected(){val l=AnnouncementLifetime{now};listOf(0,-1,96_001).forEach{try{l.audio(it);fail()}catch(_:IllegalArgumentException){}}}
}
