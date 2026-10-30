package com.example.voicebrainlive

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real utterance contracts: exact action/arguments, and abstention on unsafe ambiguity. */
@RunWith(Parameterized::class)
class OfflineNaturalPhrasesTest(private val phrase:String,private val command:String?,private val target:String?,private val value:String?) {
    @Test fun mapsWholeUserUtteranceWithoutGuessing(){
        val expected=command?.let{Triple(it,target,value)}
        assertEquals(expected,OfflineCommandMatcher.matchDirectAction(phrase))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{index}: {0}") fun phrases():List<Array<Any?>> = listOf(
            arrayOf("ဓာတ်မီးဖွင့်ပေးပါ","turn_on_flashlight",null,null),
            arrayOf("ကျေးဇူးပြုပြီး ဓာတ်မီးကို ဖွင့်ပေးပါဦး","turn_on_flashlight",null,null),
            arrayOf("ဓာတ်မီး လေး ထွန်းလိုက်ပါ","turn_on_flashlight",null,null),
            arrayOf("ဖုန်းဓာတ်မီးပိတ်ပေး","turn_off_flashlight",null,null),
            arrayOf("မီးအိမ်ကို ပိတ်လိုက်ပါ","turn_off_flashlight",null,null),
            arrayOf("ဖလက်ရှ်မီးဖွင့်ပေးပါ","turn_on_flashlight",null,null),
            arrayOf("turn on the flashlight","turn_on_flashlight",null,null),
            arrayOf("Please turn off the flashlight!","turn_off_flashlight",null,null),
            arrayOf("အသံတိုးပေးပါ","volume_up",null,null),
            arrayOf("အသံကို နည်းနည်း ကျယ်ပေးပါ","volume_up",null,null),
            arrayOf("အသံမြှင့်ပေး","volume_up",null,null),
            arrayOf("အသံကျယ်ကျယ်လုပ်ပေးပါ","volume_up",null,null),
            arrayOf("အသံလျှော့ပေးပါ","volume_down",null,null),
            arrayOf("အသံကို နည်းနည်းလျော့ပေး","volume_down",null,null),
            arrayOf("အသံတိုးတိုးလေးလုပ်ပေးပါ","volume_down",null,null),
            arrayOf("ဝေါလ်ယမ်တစ်ဆင့်တိုးပေး","volume_up",null,null),
            arrayOf("အသံကိုခဏပိတ်ပေးပါ","mute",null,null),
            arrayOf("အသံကိုပြန်ဖွင့်ပေးပါ","unmute",null,null),
            arrayOf("အသံ ၅၀ ရာခိုင်နှုန်း ထားပေးပါ","set_volume",null,"50"),
            arrayOf("volume 75%","set_volume",null,"75"),
            arrayOf("သီချင်းဖွင့်ပေးပါ","media_play",null,null),
            arrayOf("သီချင်းကိုဆက်ဖွင့်ပေး","media_play",null,null),
            arrayOf("သီးချင်းပြန်ဖွင့်ပေးပါ","media_play",null,null),
            arrayOf("သီချင်းကိုခဏရပ်ပေးပါ","media_pause",null,null),
            arrayOf("သီချင်းပိတ်ပေး","media_pause",null,null),
            arrayOf("သီချင်းနောက်တစ်ပုဒ်ပြောင်းပေးပါ","media_next",null,null),
            arrayOf("ရှေ့တစ်ပုဒ်ဖွင့်ပေး","media_previous",null,null),
            arrayOf("stop music","media_stop",null,null),
            arrayOf("မနက် ၇ နာရီမှာ နှိုးစက်ထားပေးပါ","set_alarm","07:00",null),
            arrayOf("ညနေ ၇ နာရီ ၃၀ မိနစ်မှာ နှိုးစက်ပေးပါ","set_alarm","19:30",null),
            arrayOf("ည ၁၂ နာရီနှိုးစက်ပေး","set_alarm","00:00",null),
            arrayOf("နံနက် ၁၂ နာရီနှိုးစက်ထား","set_alarm","00:00",null),
            arrayOf("23:59 နှိုးစက်ထားပေး","set_alarm","23:59",null),
            arrayOf("နှိုးစက်ပေးပါ","set_alarm",null,null),
            arrayOf("၇ နာရီနှိုးစက်ပေး","set_alarm",null,null),
            arrayOf("၅ မိနစ် တိုင်မာထားပေးပါ","set_timer",null,"5"),
            arrayOf("တိုင်မာ ၁၀ မိနစ်သတ်မှတ်ပေး","set_timer",null,"10"),
            arrayOf("timer 15 minutes","set_timer",null,"15"),
            arrayOf("Spotify ကို ဖွင့်ပေးပါ","open_app","spotify",null),
            arrayOf("Telegram အက်ပ်ကို ဖွင့်ပေး","open_app","telegram",null),
            arrayOf("ကျေးဇူးပြုပြီး ကင်မရာဖွင့်ပါ","open_app","camera",null),
            arrayOf("YouTube app ဖွင့်ပေးပါ","open_app","youtube",null),
            arrayOf("open app chrome","open_app","chrome",null),
            arrayOf("ဂဏန်းတွက်စက်ဖွင့်ပေးပါ","open_app","calculator",null),
            arrayOf("ဓာတ်မီး\u200bဖွင့်ပေးပါ။","turn_on_flashlight",null,null),
            arrayOf("ဓာတ်မီးမဖွင့်နဲ့",null,null,null),
            arrayOf("အသံမတိုးပေးပါနဲ့",null,null,null),
            arrayOf("သီချင်းမရပ်ပါနဲ့",null,null,null),
            arrayOf("မနက်ဖြန် ဓာတ်မီးဖွင့်ပေး",null,null,null),
            arrayOf("ည ၇ နာရီ သီချင်းဖွင့်ပေး",null,null,null),
            arrayOf("ဓာတ်မီးဖွင့်ပြီးတော့ အသံတိုးပေး",null,null,null),
            arrayOf("open spotify and send_sms",null,null,null),
            arrayOf("ဓာတ်မီးဘယ်လိုဖွင့်လဲ",null,null,null),
            arrayOf("သီချင်းဖွင့်ပေးလို့ရလား",null,null,null),
            arrayOf("\"ဓာတ်မီးဖွင့်ပေး\" လို့ ရေးထားတယ်",null,null,null),
            arrayOf("ဓာတ်မီးဖွင့်ပေး\nစာပို့ပေး",null,null,null),
            arrayOf("ငါ့ Notion အလုပ်တွေဖတ်ပြီး စာလေ့လာရေးအစီအစဉ်ဆွဲပေး",null,null,null),
            arrayOf("မောင်မောင်ကို ဖုန်းခေါ်ပေး",null,null,null),
            arrayOf("send_sms:0912345678:hello",null,null,null),
            arrayOf("အသံ ၉၉၉ ရာခိုင်နှုန်းထားပေး",null,null,null),
            arrayOf("၀ မိနစ်တိုင်မာထားပေး",null,null,null),
            arrayOf("25:00 နှိုးစက်ထားပေး",null,null,null),
            arrayOf("မနက် ၁၃ နာရီနှိုးစက်ပေး",null,null,null),
            arrayOf("07:99 နှိုးစက်ထားပေး",null,null,null),
            arrayOf("unknown bank app ဖွင့်ပေး",null,null,null),
            arrayOf("မီးဖွင့်ပေး",null,null,null),
            arrayOf("ဓာတ်ပုံရိုက်ပြီးပို့ပေး",null,null,null),
            arrayOf("/agent-plan ဓာတ်မီးဖွင့်ပေး",null,null,null)
        )
    }
}
