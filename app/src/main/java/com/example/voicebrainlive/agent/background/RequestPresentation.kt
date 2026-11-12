package com.example.voicebrainlive.agent.background

/** Presentation of persisted facts only. Grouping never grants execution or advances a request. */
internal enum class RequestSection(val title: String) {
    ACTIVE("ယခု လုပ်ဆောင်နေသည်"),
    ATTENTION("သင် ဆက်လုပ်ရန် လိုသည်"),
    UPCOMING("နောက်လုပ်မည့် အလုပ်များ"),
    PAUSED("ခဏရပ်ထားသော အလုပ်များ"),
    HISTORY("အလုပ်မှတ်တမ်း")
}

internal object RequestPresentation {
    fun section(state: RequestState): RequestSection = when (state) {
        RequestState.SUBMITTING -> RequestSection.ACTIVE
        RequestState.READY, RequestState.BLOCKED, RequestState.EXPIRED, RequestState.DRAFT_READY -> RequestSection.ATTENTION
        RequestState.QUEUE_PENDING, RequestState.SCHEDULED -> RequestSection.UPCOMING
        RequestState.PAUSED -> RequestSection.PAUSED
        RequestState.CANCELLED, RequestState.SUBMITTED, RequestState.SUMMARY_READY -> RequestSection.HISTORY
    }

    fun ordered(rows: List<ScheduledRequest>): List<ScheduledRequest> = rows.sortedWith(
        compareBy<ScheduledRequest> { section(it.state).ordinal }
            .thenBy { if (section(it.state) == RequestSection.HISTORY) -it.updatedAt else it.dueAt }
            .thenBy { it.id }
    )

    fun next(r: ScheduledRequest): String = when (r.state) {
        RequestState.QUEUE_PENDING -> "နောက်တစ်ဆင့် · Android အချိန်ဇယား အတည်ပြုရန် စောင့်နေသည်။"
        RequestState.SCHEDULED -> if (r.brief?.automatic == true)
            "နောက်တစ်ဆင့် · သတ်မှတ်ချိန်တွင် ခွင့်ပြုထားသော အကျဉ်းချုပ်ကို ပြင်ဆင်မည်။"
            else "နောက်တစ်ဆင့် · အချိန်ရောက် အသိပေးမည်။ သင်နှိပ်မှ ဆက်လုပ်မည်။"
        RequestState.READY -> "နောက်တစ်ဆင့် · အောက်မှ ကြည့်ပြီး ဆက်လုပ်ရန် နှိပ်ပါ။ မလုပ်ဆောင်ရသေးပါ။"
        RequestState.PAUSED -> "နောက်တစ်ဆင့် · ပြန်စီစဉ်ရန် နှိပ်မှ အချိန်ဇယားထဲ ပြန်ဝင်မည်။"
        RequestState.SUBMITTING -> "ရလဒ် စောင့်နေသည်။ တောင်းဆိုချက်ကို ထပ်မပို့ပါ။"
        RequestState.SUBMITTED -> "App က တောင်းဆိုချက် လက်ခံထားသည်။ App အတွင်း အလုပ်ပြီး/မပြီးကို ပြန်စစ်ပါ။"
        RequestState.DRAFT_READY -> "နောက်တစ်ဆင့် · မူကြမ်းဖွင့်ပြီး ပြန်စစ်ပါ။ AI ထံ မပို့ရသေးပါ။"
        RequestState.SUMMARY_READY -> "အကျဉ်းချုပ်ကို အောက်တွင် ဖတ်နိုင်သည်။ မူလအလုပ်များကို ပြီးပြီဟု မမှတ်ထားပါ။"
        RequestState.BLOCKED -> "နောက်တစ်ဆင့် · အောက်ပါအကြောင်းရင်းကို စစ်ပါ။ လိုအပ်လျှင် အသစ် ပြန်စီစဉ်ပါ။"
        RequestState.EXPIRED -> "သတ်မှတ်ချိန် ကျော်သွားပြီ။ လုပ်ချင်သေးလျှင် အချိန်အသစ်ဖြင့် ပြန်စီစဉ်ပါ။"
        RequestState.CANCELLED -> "ဤအလုပ်အတွက် နောက်ထပ် လုပ်ဆောင်ရန် မရှိပါ။"
    }
}
