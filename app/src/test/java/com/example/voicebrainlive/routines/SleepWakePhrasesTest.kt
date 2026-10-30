package com.example.voicebrainlive.routines

import com.example.voicebrainlive.OfflineCommandMatcher
import org.junit.Assert.*
import org.junit.Test

class SleepWakePhrasesTest {
    @Test fun delayedMusicIsNotAnAlarmOrReminder(){
        for(s in listOf("၅ မိနစ်ကြာရင် သီချင်းပိတ်ပေး","သီချင်းကို ၅ မိနစ်နေရင် ပြန်ပိတ်ပေးပါ","၅ မီနစ်ကြာရင် သီးချင်းကို ရပ်ပေး"))
            assertEquals(Triple("set_music_sleep_timer",null,"5"),OfflineCommandMatcher.matchDirectAction(s))
    }
    @Test fun userBedtimeGoalMapsToSingleBoundedRecipe(){
        for(s in listOf("အိပ်တော့မယ် အိပ်ရာဝင်သီချင်းဖွင့်ပြီး ၅ မိနစ်ကြာရင် ပြန်ပိတ်ပေး","အိပ်တော့မယ်ဆိုရင် အိပ်ရာဝင်သီးချင်းကိုဖွင့်ပြီး ၅ မီနစ်ကြာရင် ပိတ်ပေးပါ","ငါ အိပ်တော့မယ်။ အိပ်ရာဝင်သီချင်းလေးကို ဖွင့်ပီး ၅ မိနစ်လောက်ကြာရင် ပြန်ပိတ်ထားပေးပါ။"))
            assertEquals(Triple("start_bedtime_music","bedtime","5"),OfflineCommandMatcher.matchDirectAction(s))
    }
    @Test fun genericPlayThenStopResumesWithoutInventingSong(){assertEquals(Triple("start_bedtime_music","resume","10"),SleepWakePhrases.match("သီချင်းဖွင့်ပြီး ၁၀ မိနစ်ကြာရင် ပိတ်ပေး"))}
    @Test fun boundsNegationQuestionsAndExtraActionsDoNotExecute(){
        for(s in listOf("၀ မိနစ်ကြာရင်သီချင်းပိတ်ပေး","၉၉၉ မိနစ်ကြာရင်သီချင်းပိတ်ပေး","၅ မိနစ်ကြာရင်သီချင်းမပိတ်နဲ့","၅ မိနစ်ကြာရင်သီချင်းပိတ်လို့ရလား","မနက်ဖြန်၅မိနစ်ကြာရင်သီချင်းပိတ်ပေး","သီချင်းဖွင့်ပြီး၅မိနစ်ကြာရင်ဖုန်းခေါ်ပေး","၅ မိနစ်ကြာရင်ပိတ်ပေး","\"၅မိနစ်ကြာရင်သီချင်းပိတ်ပေး\"","သီချင်းပိတ်ပေး\nဖုန်းခေါ်ပေး"))assertNull(s,SleepWakePhrases.match(s))
    }
    @Test fun stopWakeAndCancelSleepAreDifferentActions(){
        assertEquals("stop_wake_alarm",SleepWakePhrases.match("နှိုးစက်ကို ရပ်ပေးပါ")?.first)
        assertEquals("stop_wake_alarm",SleepWakePhrases.match("ရပ်တော့")?.first)
        assertEquals("cancel_music_sleep_timer",SleepWakePhrases.match("သီချင်းပိတ်ချိန်ပယ်ဖျက်ပေး")?.first)
        assertNull(SleepWakePhrases.match("နှိုးစက်မရပ်နဲ့"))
    }
    @Test fun spokenWakeTimeRemainsExactAndAmbiguityAsksForClarification(){
        assertEquals(Triple("set_alarm","07:00",null),OfflineCommandMatcher.matchDirectAction("မနက် ၇ နာရီမှာ နူးနူးညံ့ညံ့ နှိုးပေးပါ"))
        assertEquals(Triple("set_alarm","07:30",null),OfflineCommandMatcher.matchDirectAction("မနက် ၇ နာရီ ၃၀ မိနစ်မှာ အိပ်ရာထဖို့ နှိုးပေးပါ"))
        assertEquals(Triple("set_alarm",null,null),OfflineCommandMatcher.matchDirectAction("၇ နာရီ နှိုးစက်ထားပေး"))
        assertNull(OfflineCommandMatcher.matchDirectAction("မနက် ၂၅ နာရီနှိုးပေး"))
    }
    @Test fun voiceToolCannotTurnRecognizedSleepRequestIntoClockTimer(){
        val goal=SleepWakePhrases.match("၅မိနစ်ကြာရင်သီချင်းပိတ်ပေး")
        for(t in listOf("set_timer","set_reminder","add_routine","add_scheduled_routine"))assertTrue(SleepWakePhrases.blocksClockTimer(goal,t))
        assertFalse(SleepWakePhrases.blocksClockTimer(goal,"set_music_sleep_timer"))
        assertFalse(SleepWakePhrases.blocksClockTimer(null,"set_timer"))
        assertFalse(SleepWakePhrases.blocksClockTimer(goal,"send_sms")) // This policy cannot authorize a sensitive effect.
    }
    @Test fun splitAndCumulativeInputTranscriptsKeepTheMusicStopIntent(){
        val input=SleepWakeTranscript();input.input("၅ မိနစ်");input.input("ကြာရင် သီချင်းပိတ်ပေး")
        assertEquals(Triple("set_music_sleep_timer",null,"5"),input.recipe())
        val cumulative=SleepWakeTranscript();cumulative.input("၅ မိနစ်");cumulative.input("၅ မိနစ်ကြာရင် သီချင်းပိတ်ပေး")
        assertEquals(input.recipe(),cumulative.recipe())
    }
    @Test fun nextUserTurnAndOverlongInputCannotInheritOldRecipe(){
        val input=SleepWakeTranscript();input.input("၅ မိနစ်ကြာရင်သီချင်းပိတ်ပေး");assertNotNull(input.recipe())
        input.finish();input.input("နှိုးစက်ထားပေး");assertEquals(Triple("set_alarm",null,null),input.recipe());assertFalse(SleepWakePhrases.matchesUserRecipe(input.recipe(),"set_alarm","07:00",null))
        input.input("x".repeat(400));input.input("၅ မိနစ်ကြာရင်သီချင်းပိတ်ပေး");assertNull(input.recipe())
    }
}
