package com.example.voicebrainlive.health

import java.util.Calendar

object HealthAdviceEngine {

    fun generateBurmeseAdvice(
        sleepHours: Double,
        steps: Int,
        activeCalories: Int,
        workoutMinutes: Int,
        avgHeartRate: Int
    ): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isMorning = hour in 5..11
        val isEvening = hour in 18..23

        val adviceList = mutableListOf<String>()

        // Sleep Analysis & Advice
        if (sleepHours > 0) {
            when {
                sleepHours >= 7.5 -> {
                    adviceList.add("အိပ်ချိန် ${String.format("%.1f", sleepHours)} နာရီ ရှိခဲ့တာကြောင့် လုံလောက်စွာ အနားရထားပြီး အားအပြည့်ရှိနေပါတယ်။")
                }
                sleepHours in 6.0..7.4 -> {
                    adviceList.add("အိပ်ချိန် ${String.format("%.1f", sleepHours)} နာရီ ရှိခဲ့လို့ အသင့်အတင့် အနားရထားပါတယ်။ ဒီနေ့ ရေများများ သောက်ပေးပါ။")
                }
                else -> {
                    adviceList.add("အိပ်ချိန် ${String.format("%.1f", sleepHours)} နာရီပဲ ရှိခဲ့တာကြောင့် အိပ်ရေးမဝသေးပါ။ ဒီနေ့ ညစောစော အနားယူဖို့ အကြံပြုပါတယ်။")
                }
            }
        }

        // Steps & Activity Advice
        when {
            steps >= 8000 -> {
                adviceList.add("ဒီနေ့ ခြေလှမ်း $steps လှောက်ထားပြီး လှုပ်ရှားမှု ပန်းတိုင် ပြည့်မြောက်ခဲ့ပါတယ်။ ကျန်းမာရေးအတွက် အရမ်းကောင်းပါတယ်။")
            }
            steps >= 4000 -> {
                val remaining = 8000 - steps
                if (isMorning) {
                    adviceList.add("ဒီနေ့ ခြေလှမ်း $steps လှောက်ထားပြီးပါပြီ။ လမ်းနည်းနည်း ပိုလျှောက်ပေးရင် ခြေလှမ်း ၈,၀၀၀ ပြည့်ပါလိမ့်မယ်။")
                } else {
                    adviceList.add("ဒီနေ့ ခြေလှမ်း $steps လှောက်ထားပါတယ်။ နောက်ထပ် ခြေလှမ်း $remaining လှောက်ပေးရင် အကောင်းဆုံး ပန်းတိုင် ရပါမယ်။")
                }
            }
            else -> {
                adviceList.add("ဒီနေ့ ခြေလှမ်း $steps ပဲ လှောက်ထားပါသေးတယ်။ လေကောင်းလေသန့်ရအောင် လမ်းလျှောက် လေ့ကျင့်ခန်း လုပ်ပေးပါ။")
            }
        }

        // Workout Advice
        if (workoutMinutes > 0) {
            adviceList.add("ဒီနေ့ လေ့ကျင့်ခန်း $workoutMinutes မိနစ် ပြုလုပ်ခဲ့ပြီး ကယ်လိုရီ $activeCalories kcal လောင်ကျွမ်းခဲ့ပါတယ်။ ခန္ဓာကိုယ် ကြံ့ခိုင်ရေးအတွက် အထူးကောင်းမွန်ပါတယ်။")
        } else if (isEvening) {
            adviceList.add("ဒီနေ့ လေ့ကျင့်ခန်း မပြုလုပ်ရသေးပါ။ ကိုယ်လက် ဆန့်ထုတ် လေ့ကျင့်ခန်း ပေါ့ပေါ့ပါးပါး လုပ်ပေးပါ။")
        }

        // Heart Rate Advice
        if (avgHeartRate > 0) {
            if (avgHeartRate in 60..100) {
                adviceList.add("ပျမ်းမျှ နှလုံးခုန်နှုန်း $avgHeartRate bpm ရှိပြီး ပုံမှန် ပမာဏအတွင်း ရှိနေပါတယ်။")
            } else if (avgHeartRate > 100) {
                adviceList.add("ပျမ်းမျှ နှလုံးခုန်နှုန်း $avgHeartRate bpm ဖြစ်ပြီး နည်းနည်း မြင့်နေတာကြောင့် စိတ်အေးအေးထားပြီး နားနားနေနေ နေပေးပါ။")
            }
        }

        return adviceList.joinToString("\n• ")
    }
}
