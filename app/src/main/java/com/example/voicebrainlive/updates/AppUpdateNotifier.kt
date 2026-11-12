package com.example.voicebrainlive.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.voicebrainlive.R

/**
 * Dispatches a polished, actionable system notification when an app update is available.
 */
object AppUpdateNotifier {
    const val CHANNEL_ID = "voicebrain_app_updates"
    const val NOTIFICATION_ID = 4001

    fun notifyUpdateAvailable(context: Context, release: ReleaseManifest) {
        createNotificationChannel(context)

        val updateIntent = Intent(context, AppUpdateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val updatePendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val sizeMb = release.apkBytes / (1024.0 * 1024.0)
        val shortSummary = if (release.releaseNotes.isNotBlank()) {
            release.releaseNotes.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "ဗားရှင်းအသစ်သို့ အဆင့်မြှင့်တင်နိုင်ပါပြီ"
        } else {
            "စွမ်းဆောင်ရည်မြှင့်တင်မှုများနှင့် ပြင်ဆင်ချက်များ ပါဝင်ပါသည်"
        }

        val fullSummary = buildString {
            append("ဗားရှင်းအသစ်: v${release.androidVersionName} (Build ${release.androidVersionCode})\n")
            append("ဒေါင်းလုဒ် အရွယ်အစား: %.1f MB\n\n".format(sizeMb))
            if (release.releaseNotes.isNotBlank()) {
                append("📌 အသစ်ပါဝင်သော အချက်များ:\n")
                append(release.releaseNotes)
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.luma_icon)
            .setContentTitle("🚀 VoiceBrainLive v${release.androidVersionName} ရရှိနေပါပြီ")
            .setContentText(shortSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullSummary))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(updatePendingIntent)
            .addAction(
                android.R.drawable.stat_sys_download,
                "ယခု Update တင်မည်",
                updatePendingIntent
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Handled when POST_NOTIFICATIONS is not yet granted
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Updates (အက်ပ် အပ်ဒိတ်များ)"
            val descriptionText = "VoiceBrainLive ဗားရှင်းအသစ်များ အသိပေးချက်"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
