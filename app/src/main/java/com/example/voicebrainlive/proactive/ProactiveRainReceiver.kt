package com.example.voicebrainlive.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * Proactive "မိုးရွာမယ်" – မနက် ၇ နာရီ alarm ဖြစ်ရင် ProactiveRainService စတင်
 */
class ProactiveRainReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROACTIVE_RAIN) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceBrain:ProactiveRainWakeLock"
        )
        wakeLock.acquire(30_000L)

        try {
            val serviceIntent = Intent(context, ProactiveRainService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        const val ACTION_PROACTIVE_RAIN = "com.example.voicebrainlive.PROACTIVE_RAIN"
    }
}
