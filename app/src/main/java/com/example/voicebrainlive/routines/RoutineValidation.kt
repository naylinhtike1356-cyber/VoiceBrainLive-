package com.example.voicebrainlive.routines

data class RoutineDraft(
    val type: String,
    val message: String,
    val musicQuery: String,
    val reminderText: String,
    val reminderMinutesBefore: Int,
    val hour: Int,
    val minute: Int,
    val dayOfWeek: Int
)

object RoutineValidation {
    data class Result(val valid: Boolean, val field: Field? = null, val message: String? = null)
    enum class Field { TYPE, MESSAGE, MUSIC, REMINDER, TIME, REPEAT }

    fun validate(draft: RoutineDraft): Result {
        if (draft.type !in setOf("weather", "reminder", "music", "daily_log", "command"))
            return Result(false, Field.TYPE, "အော်တိုမေးရှင်းအမျိုးအစားကို ရွေးပေးပါ။")
        if (draft.hour !in 0..23 || draft.minute !in 0..59)
            return Result(false, Field.TIME, "အချိန်မမှန်ပါ။ Time picker မှ ပြန်ရွေးပါ။")
        if (draft.dayOfWeek !in 0..7)
            return Result(false, Field.REPEAT, "ပြန်လုပ်မည့်အစီအစဉ် မမှန်ပါ။")
        when (draft.type) {
            "reminder", "command" -> if (draft.message.isBlank())
                return Result(false, Field.MESSAGE, if (draft.type == "command") "လုပ်ဆောင်မည့် command ထည့်ပေးပါ။" else "သတိပေးမည့်စာ ထည့်ပေးပါ။")
            "music" -> if (draft.musicQuery.isBlank())
                return Result(false, Field.MUSIC, "သီချင်း၊ artist သို့ playlist ထည့်ပေးပါ။")
        }
        if (draft.reminderMinutesBefore !in 0..1440)
            return Result(false, Field.REMINDER, "ကြိုတင်သတိပေးချိန်သည် ၀ မှ ၁၄၄၀ မိနစ်အတွင်း ဖြစ်ရမည်။")
        if (draft.reminderMinutesBefore > 0 && draft.reminderText.isBlank())
            return Result(false, Field.REMINDER, "ကြိုတင်သတိပေးစာ ထည့်ပေးပါ သို့မဟုတ် မိနစ်ကို ၀ ထားပါ။")
        return Result(true)
    }
}
