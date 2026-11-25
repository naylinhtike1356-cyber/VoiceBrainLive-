package com.example.voicebrainlive.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.MainActivity
import com.example.voicebrainlive.SpotifyManager

/**
 * Lightweight dispatcher for calendar and location arrival alerts.
 * Replaces dedicated specialUse ForegroundServices with direct notifications + MainActivity intent.
 */
object ProactiveAlertDispatcher {

    private const val TAG = "ProactiveAlertDispatcher"
    private const val CAL_CHANNEL_ID = "voice_brain_cal_reminder"
    private const val LOC_CHANNEL_ID = "voice_brain_location_arrival"
    private const val CAL_NOTIFICATION_ID = 71000
    private const val LOC_NOTIFICATION_ID = 72000

    const val ACTION_HOME = "home"
    const val ACTION_OFFICE = "office"
    const val ACTION_HOME_EXIT = "home_exit"
    const val ACTION_OFFICE_EXIT = "office_exit"

    fun dispatchCalendarReminder(context: Context, title: String) {
        val text = "မကြာမီ $title စတင်ပါမည်။"
        ensureChannel(context, CAL_CHANNEL_ID, "ပြက္ခဒိန်သတိပေး", NotificationManager.IMPORTANCE_HIGH)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(MainActivity.EXTRA_PROACTIVE_SPEAK, true)
            putExtra(MainActivity.EXTRA_PROACTIVE_MESSAGE, text)
            putExtra(MainActivity.EXTRA_PROACTIVE_TYPE, "calendar_partner")
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Voice Brain - ပါတနာ သတိပေးချက်")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(CAL_NOTIFICATION_ID, notification)

        try {
            context.startActivity(mainIntent)
        } catch (e: Exception) {
            Log.d(TAG, "Background launch deferred to notification: ${e.message}")
        }
    }

    fun dispatchLocationArrival(context: Context, message: String, action: String) {
        ensureChannel(context, LOC_CHANNEL_ID, "နေရာရောက်ပြီ", NotificationManager.IMPORTANCE_LOW)

        val proactiveType = when (action) {
            ACTION_HOME -> "location_home"
            ACTION_OFFICE -> "location_office"
            ACTION_HOME_EXIT -> "location_home_exit"
            ACTION_OFFICE_EXIT -> "location_office_exit"
            else -> "location_generic"
        }

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_PROACTIVE_SPEAK, true)
            putExtra(MainActivity.EXTRA_PROACTIVE_MESSAGE, message)
            putExtra(MainActivity.EXTRA_PROACTIVE_TYPE, proactiveType)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 1, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, LOC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Voice Brain - နေရာရောက်ပြီ")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(LOC_NOTIFICATION_ID, notification)

        if (action == ACTION_HOME) {
            try {
                val uri = SpotifyManager.getMoodPlaylistUri("အေးဆေး")
                    ?: SpotifyManager.getMoodPlaylistUri("စာကြည့်")
                    ?: "spotify:playlist:37i9dQZF1DWWQRwui0ExPn"
                SpotifyManager.playUri(context, uri)
            } catch (_: Exception) { }
        }
    }

    private fun ensureChannel(context: Context, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, name, importance))
            }
        }
    }
}
