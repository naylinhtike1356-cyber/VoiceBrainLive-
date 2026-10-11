package com.example.voicebrainlive.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Proactive "မိုးရွာမယ်" – မနက် ၇ နာရီမှာ မိုးရွာမယ့် အခြေအနေ စစ်ဆေးပြီး သတိပေးသည်။
 */
object ProactiveRainScheduler {

    private const val REQUEST_CODE = 85000
    private const val HOUR = 7
    private const val MINUTE = 0

    fun schedule(context: Context) {
        val triggerTime = nextTriggerTime()
        val intent = Intent(context, ProactiveRainReceiver::class.java).apply {
            action = ProactiveRainReceiver.ACTION_PROACTIVE_RAIN
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
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
            android.util.Log.d("ProactiveRain", "scheduled at $HOUR:$MINUTE")
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        }
    }

    private fun nextTriggerTime(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, HOUR)
        cal.set(Calendar.MINUTE, MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
