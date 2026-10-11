package com.example.voicebrainlive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Helper for listing and managing voice reminders stored in SharedPreferences.
 */
object ReminderHelper {

    private const val PREFS_NAME = "voice_reminders"
    private const val PREFIX = "rem_"

    data class ReminderItem(
        val id: Int,
        val message: String,
        val triggerTime: Long,
        val isPast: Boolean
    ) {
        /** Uses device default timezone. Prefer formatTime(context) when available. */
        fun formatTime(): String = formatTime(null)

        /** Uses user's selected timezone from Settings when context provided. */
        fun formatTime(context: Context?): String {
            val tz = context?.let { SettingsActivity.getTimezone(it) } ?: java.util.TimeZone.getDefault()
            val now = System.currentTimeMillis()
            return when {
                isPast -> "ပြီးပြီ"
                triggerTime - now < 60_000 -> "ယခု"
                triggerTime - now < 3600_000 -> "${(triggerTime - now) / 60_000} မိနစ်"
                else -> {
                    val cal = Calendar.getInstance(tz)
                    cal.timeInMillis = triggerTime
                    String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                }
            }
        }
    }

    fun getReminders(context: Context): List<ReminderItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        val now = System.currentTimeMillis()
        return all.entries
            .filter { it.key.startsWith(PREFIX) }
            .mapNotNull { entry ->
                val value = entry.value as? String ?: return@mapNotNull null
                val parts = value.split("|", limit = 2)
                val triggerTime = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val message = parts.getOrNull(1) ?: ""
                val id = entry.key.removePrefix(PREFIX).toIntOrNull() ?: return@mapNotNull null
                ReminderItem(id, message, triggerTime, triggerTime < now)
            }
            .sortedBy { it.triggerTime }
    }

    fun deleteReminder(context: Context, id: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("$PREFIX$id").apply()
        cancelAlarm(context, id)
    }

    fun clearAllPast(context: Context) {
        val reminders = getReminders(context).filter { it.isPast }
        reminders.forEach { deleteReminder(context, it.id) }
    }

    /** Cancel scheduled alarm for reminder (e.g. when user taps Done - stops repeat). */
    fun cancelReminderAlarm(context: Context, id: Int) {
        cancelAlarm(context, id)
    }

    private fun cancelAlarm(context: Context, id: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    fun rescheduleAllFuture(context: Context): Int {
        val now = System.currentTimeMillis()
        var scheduled = 0
        getReminders(context)
            .filter { it.triggerTime > now }
            .forEach { reminder ->
                scheduleAlarm(context, reminder.id, reminder.message, reminder.triggerTime)
                scheduled++
            }
        return scheduled
    }

    private fun scheduleAlarm(context: Context, id: Int, message: String, triggerTime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }

        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            }
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        }
    }

    fun removeFromPrefs(context: Context, id: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$PREFIX$id")
            .apply()
    }

    /** Myanmar numerals ၀၁၂၃၄၅၆၇၈၉ -> 0-9 */
    fun myanmarToArabic(s: String): String = s.map { c ->
        when (c) {
            '၀' -> '0'; '၁' -> '1'; '၂' -> '2'; '၃' -> '3'; '၄' -> '4'
            '၅' -> '5'; '၆' -> '6'; '၇' -> '7'; '၈' -> '8'; '၉' -> '9'
            else -> c
        }
    }.joinToString("")

    /** Parse natural date+time: မနက်ဖြန် ၉ နာရီ, ယနေ့ ညနေ ၆, ၉:၃၀. Uses user's timezone from Settings. */
    fun parseTimeInput(ctx: Context, input: String): Calendar? {
        return try {
            val tz = SettingsActivity.getTimezone(ctx)
            val cal = Calendar.getInstance(tz)
            val normalized = myanmarToArabic(input).trim()
            
            // Date offset: ယနေ့=0, မနက်ဖြန်=1, ညနောက်နေ့=2
            var dayOffset = 0
            when {
                normalized.contains("မနက်ဖြန်") || normalized.contains("tomorrow") -> dayOffset = 1
                normalized.contains("ညနောက်နေ့") || normalized.contains("day after") -> dayOffset = 2
                normalized.contains("ယနေ့") || normalized.contains("today") -> dayOffset = 0
            }
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            
            // Try "HH:mm" or "H:mm" first
            val timeMatch = Regex("(\\d{1,2}):(\\d{1,2})").find(normalized)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = timeMatch.groupValues[2].toInt()
                val isPM = normalized.contains("pm", ignoreCase = true)
                val isAM = normalized.contains("am", ignoreCase = true)
                when {
                    isPM && hour < 12 -> hour += 12
                    isAM && hour == 12 -> hour = 0
                }
                cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                return cal
            }
            
            // Extract hour from "၉ နာရီ", "၉ နာရီ ၃၀ မိနစ်", "9 o'clock"
            val hourMatch = Regex("(\\d{1,2})\\s*နာရီ|(\\d{1,2})\\s*o'?clock|(\\d{1,2})\\s*시").find(normalized)
            val minMatch = Regex("(\\d{1,2})\\s*မိနစ်|(\\d{1,2})\\s*minute").find(normalized)
            val hour = hourMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            val minute = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (hour != null) {
                var h = hour.coerceIn(0, 23)
                // Period hints: မနက်=am, ညနေ/မိုးချုပ်=pm, ည=evening
                when {
                    (normalized.contains("မနက်") || normalized.contains("နံနက်")) && h < 12 -> { } // 9am
                    (normalized.contains("ညနေ") || normalized.contains("မိုးချုပ်") || normalized.contains("evening")) && h in 1..7 -> h += 12  // 6pm
                    normalized.contains("ည ") && h in 1..11 -> h += 12  // ည ၈ = 8pm
                    normalized.contains("နေ့လယ်") || normalized.contains("မုန်း") -> h = 12
                }
                cal.set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                return cal
            }
            
            // Time-of-day words only (no hour number)
            val hourOnly = when {
                normalized.contains("နံနက်") || normalized.contains("မနက်") && !Regex("\\d").containsMatchIn(normalized) -> 9
                normalized.contains("နေ့လယ်") || normalized.contains("မုန်း") -> 12
                normalized.contains("ညနေ") -> 17
                normalized.contains("ည") -> 20
                else -> return null
            }
            cal.set(Calendar.HOUR_OF_DAY, hourOnly)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal
        } catch (e: Exception) {
            null
        }
    }

    fun setReminder(context: Context, message: String, timeOrMinutes: String?): String {
        if (message.isBlank()) return "ဘာ သတိပေးရမလဲ ပြောပေးပါ။"

        val triggerTime: Long
        val label: String
        val timeNorm = timeOrMinutes?.let { myanmarToArabic(it) } ?: ""
        val looksLikeAbsoluteTime = timeNorm.contains("နာရီ") || timeNorm.contains("မနက်ဖြန်") ||
                timeNorm.contains("ယနေ့") || timeNorm.contains(":") || timeNorm.contains("am", ignoreCase = true) ||
                timeNorm.contains("pm", ignoreCase = true) || timeNorm.contains("tomorrow")
        val minutes = if (!looksLikeAbsoluteTime) timeNorm.replace(Regex("[^0-9]"), "").toIntOrNull() else null

        when {
            minutes != null && minutes in 1..40320 -> {
                triggerTime = System.currentTimeMillis() + minutes * 60_000L
                label = "$minutes မိနစ်ကြာရင်"
            }
            timeOrMinutes != null -> {
                val cal = parseTimeInput(context, timeOrMinutes)
                if (cal == null) {
                    triggerTime = System.currentTimeMillis() + 30 * 60_000L
                    label = "၃၀ မိနစ်ကြာရင်"
                } else {
                    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
                    triggerTime = cal.timeInMillis
                    val h = cal.get(Calendar.HOUR_OF_DAY)
                    val m = cal.get(Calendar.MINUTE)
                    val todayCal = Calendar.getInstance(SettingsActivity.getTimezone(context))
                    val isTomorrow = cal.get(Calendar.DAY_OF_YEAR) != todayCal.get(Calendar.DAY_OF_YEAR) ||
                            cal.get(Calendar.YEAR) != todayCal.get(Calendar.YEAR)
                    label = if (isTomorrow) "မနက်ဖြန် ${h}:${m.toString().padStart(2, '0')}"
                    else String.format("%02d:%02d", h, m)
                }
            }
            else -> {
                triggerTime = System.currentTimeMillis() + 30 * 60_000L
                label = "၃၀ မိနစ်ကြာရင်"
            }
        }
        
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$PREFIX$id", "$triggerTime|$message").apply()
        
        scheduleAlarm(context, id, message, triggerTime)
        
        return "သတိပေးချက် ထားပြီးပါပြီ။ $message — $label"
    }

    /** Clear all reminders and cancel their pending alarms */
    fun clearAllReminders(context: Context) {
        try {
            val reminders = getReminders(context)
            reminders.forEach { cancelAlarm(context, it.id) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            Log.d("ReminderHelper", "Cleared all reminders and canceled alarms")
        } catch (e: Exception) {
            Log.e("ReminderHelper", "clearAllReminders error", e)
        }
    }
}
