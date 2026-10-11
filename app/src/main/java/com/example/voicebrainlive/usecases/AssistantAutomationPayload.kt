package com.example.voicebrainlive.usecases

import android.content.Intent
import com.example.voicebrainlive.MainActivity
import java.util.Calendar

data class AssistantAutomationPayload(
    val type: String,
    val message: String,
    val musicQuery: String,
    val announceText: String,
    val isPreReminder: Boolean,
    val alreadySpoken: Boolean,
    val shouldSpeak: Boolean = true
) {
    companion object {
        fun fromIntent(intent: Intent): AssistantAutomationPayload {
            val type = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_TYPE).orEmpty()
            val message = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE).orEmpty()
            val musicQuery = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY).orEmpty()
            val announce = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_ANNOUNCE).orEmpty()
            val isPreReminder = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_IS_PRE, false)
            val alreadySpoken = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_ALREADY_SPOKEN, false)
            val shouldSpeak = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_SPEAK, true)

            val announceText = announce.ifBlank {
                buildCalmAnnounce(type, message, musicQuery, isPreReminder)
            }

            return AssistantAutomationPayload(
                type = type,
                message = message,
                musicQuery = musicQuery,
                announceText = announceText,
                isPreReminder = isPreReminder,
                alreadySpoken = alreadySpoken,
                shouldSpeak = shouldSpeak
            )
        }

        fun timeGreeting(hourOfDay: Int): String {
            return when {
                hourOfDay < 12 -> "မင်္ဂလာ နံနက်ခင်းပါခင်ဗျာ"
                hourOfDay in 12..16 -> "မင်္ဂလာ နေ့လယ်ခင်းပါခင်ဗျာ"
                hourOfDay in 17..20 -> "မင်္ဂလာ ညနေခင်းပါခင်ဗျာ"
                else -> "မင်္ဂလာ ညချမ်းပါခင်ဗျာ"
            }
        }

        fun buildCalmAnnounce(
            type: String,
            message: String = "",
            musicQuery: String = "",
            isPreReminder: Boolean = false,
            hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        ): String {
            val greeting = timeGreeting(hourOfDay)
            if (isPreReminder) {
                return if (message.isNotBlank()) {
                    "$greeting၊ $message အတွက် အေးအေးဆေးဆေး ကြိုတင်အသိပေးအပ်ပါတယ်ခင်ဗျာ"
                } else {
                    "$greeting၊ အေးအေးဆေးဆေး ကြိုတင်အသိပေးအပ်ပါတယ်ခင်ဗျာ"
                }
            }

            return when (type.lowercase()) {
                "reminder" -> {
                    if (message.isNotBlank()) {
                        "$greeting၊ $message အတွက် အေးအေးဆေးဆေး သတိပေးလိုက်ပါတယ်ခင်ဗျာ"
                    } else {
                        "$greeting၊ အေးအေးဆေးဆေး သတိပေးလိုက်ပါတယ်ခင်ဗျာ"
                    }
                }
                "weather" -> "$greeting၊ ရာသီဥတု အခြေအနေလေးကို အေးဆေးသက်သာစွာ ကြားသိနိုင်ဖို့ တင်ပြပေးပါမယ်ခင်ဗျာ"
                "daily_log" -> "$greeting၊ လစာနဲ့ အသုံးစရိတ် မှတ်တမ်းလေးကို အေးအေးဆေးဆေး စစ်ဆေးဖော်ပြပေးပါမယ်ခင်ဗျာ"
                "music" -> {
                    val q = musicQuery.ifBlank { message }
                    if (q.isBlank() || q.equals("music", ignoreCase = true)) {
                        "$greeting၊ စိတ်အေးချမ်းစေမယ့် တေးသီချင်းလေးကို ဖွင့်ပေးပါမယ်ခင်ဗျာ"
                    } else {
                        "$greeting၊ သီချင်း $q ကို ဖွင့်ပေးပါမယ်ခင်ဗျာ"
                    }
                }
                "command" -> "$greeting၊ သတ်မှတ်ထားတဲ့ လုပ်ဆောင်ချက်လေးကို အေးအေးဆေးဆေး စတင်ဆောင်ရွက်ပေးပါမယ်ခင်ဗျာ"
                else -> if (message.isNotBlank()) "$greeting၊ $message" else "$greeting၊ သတ်မှတ်ထားတဲ့ အချိန်ရောက်ရှိပါပြီခင်ဗျာ"
            }
        }

        fun buildDefaultAnnounce(
            type: String,
            message: String,
            musicQuery: String,
            isPreReminder: Boolean
        ): String {
            return buildCalmAnnounce(type, message, musicQuery, isPreReminder)
        }
    }
}
