package com.example.voicebrainlive.routines

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

internal class SleepMemory:SleepWakePersistence {
    var text:String?=null;var writable=true;var unreadable=false;var corruptReadback=false
    override fun read():String?{if(unreadable)throw IllegalStateException("private key");return if(corruptReadback)"corrupt"else text}
    override fun write(value:String):Boolean{if(!writable)return false;text=value;return true}
}
class SleepWakeJournalTest {
    private class F {
        val disk=SleepMemory();var now=SleepWakeNow(1_000_000,1000,1);var id=0
        fun repo()=SleepWakeJournal(disk,{now},{"alarm_${++id}"})
        val j=repo()
        fun timer()=j.add(SleepWakeKind.MEDIA_STOP,now.wall+300_000,now.elapsed+300_000).first
        fun advance(ms:Long){now=now.copy(wall=now.wall+ms,elapsed=now.elapsed+ms)}
    }
    private fun fails(code:String,action:()->Unit){try{action();fail("expected failure")}catch(e:SleepWakeFailure){assertEquals(code,e.code)}}
    @Test fun saveAndRestartPreserveExactDeadline(){val f=F();val t=f.timer();assertEquals(listOf(t),f.repo().history());assertEquals(t,SleepWakeJournal.decode(SleepWakeJournal.encode(listOf(t))).single())}
    @Test fun fiveMinutesAreSilentUntilDueAndClaimOnlyOnce(){val f=F();val t=f.timer();assertNull(f.j.claim(t.id));f.advance(299_999);assertNull(f.j.claim(t.id));f.advance(1);assertEquals(SleepWakeState.RUNNING,f.j.claim(t.id)?.state);assertNull(f.j.claim(t.id));assertNull(f.repo().claim(t.id))}
    @Test fun completionMeansPauseRequestNotVerifiedHardwareSuccess(){val f=F();val t=f.timer();f.advance(300_000);f.j.claim(t.id);f.j.mark(t.id,SleepWakeState.REQUESTED);assertEquals(SleepWakeState.REQUESTED,f.repo().history().single().state)}
    @Test fun duplicateCallbacksDoNotResetDeadlineOrReplayMusic(){val f=F();val t=f.timer();f.advance(2000);val pair=f.j.add(SleepWakeKind.MEDIA_STOP,f.now.wall+300_000,f.now.elapsed+300_000);assertFalse(pair.second);assertEquals(t,pair.first);assertEquals(1,f.j.history().size)}
    @Test fun replacementCancelsOldCallback(){val f=F();val t=f.timer();f.advance(20_000);val t2=f.timer();f.advance(300_000);assertNull(f.j.claim(t.id));assertNotNull(f.j.claim(t2.id))}
    @Test fun cancellationPersistsAndCannotBeReplayed(){val f=F();val t=f.timer();f.j.mark(t.id,SleepWakeState.CANCELLED);f.advance(300_000);assertNull(f.repo().claim(t.id))}
    @Test fun keyUnavailableFailsClosedWithoutPlaintextOrEmptyOverwrite(){val f=F();f.timer();val old=f.disk.text;f.disk.unreadable=true;fails("SECURE_STORAGE_UNAVAILABLE"){f.j.history()};assertEquals(old,f.disk.text)}
    @Test fun encryptionWriteFailureCannotClaimScheduledSuccess(){val f=F();f.disk.writable=false;fails("STORAGE_WRITE_UNVERIFIED"){f.timer()};assertNull(f.disk.text)}
    @Test fun failedClaimWritePreventsEffectDispatch(){val f=F();val t=f.timer();f.advance(300_000);f.disk.writable=false;var effects=0;fails("STORAGE_WRITE_UNVERIFIED"){if(f.j.claim(t.id)!=null)effects++};assertEquals(0,effects)}
    @Test fun corruptedAndUnknownSchemaAreNotSilentlyReset(){val f=F();f.disk.text="not valid";fails("DATA_UNREADABLE"){f.j.history()};assertEquals("not valid",f.disk.text);val bytes=byteArrayOf(0,0,0,99,0,0,0,0);f.disk.text=Base64.getEncoder().encodeToString(bytes);fails("UNSUPPORTED_SCHEMA"){f.j.history()}}
    @Test fun relativeTimerUsesElapsedTimeEvenWhenWallClockChanges(){val f=F();val t=f.timer();f.now=f.now.copy(wall=f.now.wall+3_600_000);assertNull(f.j.claim(t.id));f.now=f.now.copy(elapsed=f.now.elapsed+300_000);assertNotNull(f.j.claim(t.id))}
    @Test fun rebootExpiresOldMusicSessionInsteadOfPausingUnrelatedPlayback(){val f=F();val t=f.timer();f.advance(300_000);f.now=f.now.copy(boot=2);assertNull(f.j.claim(t.id));assertEquals(SleepWakeState.EXPIRED,f.j.history().single().state)}
    @Test fun overdueCallbackCannotWakeUserMuchLater(){val f=F();val t=f.timer();f.advance(901_000);assertNull(f.j.claim(t.id));assertEquals(SleepWakeState.EXPIRED,f.j.history().single().state)}
    @Test fun wakeClockHandoffIsMetadataNeverAnApprovalOrVerifiedNativeAlarm(){val f=F();val e=f.j.add(SleepWakeKind.WAKE,f.now.wall+60_000,0).first;assertFalse(e.clockRequested);f.j.clockRequested(e.id);assertTrue(f.repo().history().single().clockRequested);f.advance(60_000);assertNotNull(f.j.claim(e.id));f.j.mark(e.id,SleepWakeState.DISMISSED);assertNull(f.repo().claim(e.id))}
    @Test fun boundedDurationAndHistoryRejectMalformedInput(){val f=F();fails("INVALID_ALARM"){f.j.add(SleepWakeKind.MEDIA_STOP,f.now.wall+60_000,f.now.elapsed+11_000_000)};fails("INVALID_ALARM"){SleepWakeJournal.encode(listOf(SleepWakeEntry("../bad",SleepWakeKind.WAKE,1,0,0,SleepWakeState.SCHEDULED)))}}
}
