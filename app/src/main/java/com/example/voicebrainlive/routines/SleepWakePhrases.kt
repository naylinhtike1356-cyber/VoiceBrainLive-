package com.example.voicebrainlive.routines

import java.text.Normalizer
import java.util.Locale

/** Closed two-step music recipe, never a generic command-sequence interpreter. */
object SleepWakePhrases {
    private val no=Regex("[?？\"“”'\n\r]|မဖွင့်|မပိတ်|မလုပ်|မရပ်|မနက်ဖြန်|နေ့တိုင်း|လား|\\b(?:not|don't|tomorrow|daily)\\b")
    private val suffix=Regex("(?:ပေး(?:ပါ)?(?:ဦး|နော်)?|လိုက်(?:ပါ)?|ပါ(?:ဦး|နော်)?)$")
    private val duration="([0-9]{1,3})(?:မိနစ်|မီနစ်)(?:လောက်)?(?:ကြာရင်|နေရင်|အကြာမှာ|ပြီးရင်|ပီးရင်|ပြည့်ရင်|ကြာမှ)"
    private val stop="(?:ပြန်)?(?:ပိတ်|ရပ်)(?:ထား)?"
    private val music="(?:သီချင်း|သီးချင်း)(?:လေး)?(?:ကို)?"
    private val after=Regex("(?:$music)?$duration(?:$music)?$stop")
    private val bedtime=Regex("(?:အိပ်တော့မယ်(?:ဆိုရင်)?)?(?:အိပ်ရာဝင်|အိပ်ချိန်)?$music(?:လေး)?ဖွင့်(?:ပေး)?(?:ပြီး|ပီး)$duration(?:$music)?$stop")
    fun match(text:String):Triple<String,String?,String?>? {
        if(text.length !in 1..300)return null
        var s=Normalizer.normalize(text,Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()
        if(no.containsMatchIn(s))return null
        s=s.removePrefix("ကျေးဇူးပြုပြီး").trim().trimEnd('။','.','!').trim()
        s=suffix.replace(s,"").replace(Regex("\\s+"),"")
            .map{if(it in '၀'..'၉')'0'+(it-'၀')else it}.joinToString("")
            .replace("၊","").replace("။","").removePrefix("ငါ").removePrefix("ကျွန်တော်").removePrefix("ကျွန်မ")
            .replace("နှိုးစက်ကို","နှိုးစက်").replace("နိုးစက်ကို","နိုးစက်")
        if(s in setOf("နှိုးစက်ရပ်","နှိုးစက်ပိတ်","နိုးစက်ရပ်","နိုးစက်ပိတ်","နှိုးစက်နဲ့အသံရပ်","အသံရောနှိုးစက်ရောရပ်","နှိုးစက်ရောအသံရောရပ်"))return Triple("stop_wake_alarm",null,null)
        if(s in setOf("ရပ်","ရပ်တော့","ရပ်လိုက်တော့","နိုးပြီ","နိုးပါပြီ"))return Triple("stop_wake_alarm",null,null)
        if(s in setOf("သီချင်းပိတ်ချိန်ပယ်ဖျက်","သီချင်းပိတ်မယ့်အချိန်ပယ်ဖျက်","သီချင်းတိုင်မာပယ်ဖျက်"))return Triple("cancel_music_sleep_timer",null,null)
        bedtime.matchEntire(s)?.let{
            val n=it.groupValues[1].toInt();if(n !in 1..180)return null
            return Triple("start_bedtime_music",if(s.contains("အိပ်"))"bedtime"else "resume",n.toString())
        }
        after.matchEntire(s)?.let{
            if(!s.contains("သီချင်း")&&!s.contains("သီးချင်း"))return null
            val n=it.groupValues[1].toInt();if(n !in 1..180)return null
            return Triple("set_music_sleep_timer",null,n.toString())
        }
        return null
    }
    fun isWakeStop(text:String)=match(text)?.first=="stop_wake_alarm"
    fun blocksClockTimer(recipe:Triple<String,String?,String?>?,tool:String)=
        recipe?.first in setOf("set_music_sleep_timer","start_bedtime_music") &&
            tool in setOf("set_timer","set_reminder","add_scheduled_routine","add_routine")
    fun requiresUserRecipe(tool:String)=tool in setOf("set_music_sleep_timer","start_bedtime_music","cancel_music_sleep_timer","set_alarm","stop_wake_alarm","cancel_wake_alarm")
    fun matchesUserRecipe(recipe:Triple<String,String?,String?>?,tool:String,target:String?,value:String?):Boolean {
        if(recipe==null||recipe.first!=tool)return false
        return when(tool){
            "set_alarm"->recipe.second!=null&&target==recipe.second
            "set_music_sleep_timer","start_bedtime_music"->target==recipe.second&&value==recipe.third
            else->target.isNullOrBlank()&&value.isNullOrBlank()
        }
    }
}

/** Ephemeral bounded input-transcription buffer; never accepts model/tool output or persists text.
 * Only a closed recipe is copied onto the queued call, and it can only reject a wrong Clock tool.
 */
class SleepWakeTranscript {
    private var text=""
    private var newTurn=false
    private var overflow=false
    fun input(fragment:String){
        if(newTurn){text="";overflow=false;newTurn=false}
        if(overflow)return
        val next=when{ text.isEmpty()->fragment;fragment.startsWith(text)->fragment;text.endsWith(fragment)->text;else->text+fragment }
        if(next.length>300){text="";overflow=true}else text=next
    }
    fun finish(){newTurn=true}
    fun recipe()=if(overflow)null else SleepWakePhrases.match(text)
        ?:com.example.voicebrainlive.OfflineCommandMatcher.matchDirectAction(text)?.takeIf{it.first=="set_alarm"}
}
