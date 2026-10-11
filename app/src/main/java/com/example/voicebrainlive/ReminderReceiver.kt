package com.example.voicebrainlive

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REMINDER_DONE) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            if (id >= 0) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
                ReminderHelper.cancelReminderAlarm(context, id)
                ReminderHelper.removeFromPrefs(context, id)
            }
            return
        }
        if (intent.action != ACTION_REMINDER) return
        
        // Wake up the device
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceBrain:ReminderWakeLock"
        )
        wakeLock.acquire(10000) // 10 seconds max
        
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Reminder"
        val id = intent.getIntExtra(EXTRA_ID, 0)

        // Vibration – သတိပေးချက် မြန်မြန် တုန်ခါမှု
        vibrate(context)

        createChannel(context)

        // Proactive speak: Speak reminder message in background via RoutineSpeakerService
        val proactiveMessage = "သတိပေးချက်။ $message"
        val serviceIntent = Intent(context, com.example.voicebrainlive.routines.RoutineSpeakerService::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTOMATION_ID, id)
            putExtra(MainActivity.EXTRA_AUTOMATION_TYPE, "reminder")
            putExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE, message)
            putExtra(MainActivity.EXTRA_AUTOMATION_ANNOUNCE, proactiveMessage)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("ReminderReceiver", "startRoutineSpeakerService error", e)
        }

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        // Notification tap လည်း ဒီ intent ပဲ (AI ပြန်ပြောမယ်)
        val pending = PendingIntent.getActivity(
            context, id, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Done / ပြီးပြီ – dismiss reminder
        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER_DONE
            putExtra(EXTRA_ID, id)
        }
        val donePending = PendingIntent.getBroadcast(
            context, id + 10000, doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Soft sound + gentle vibration for agent reminders
        val notifSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 Voice Brain Reminder")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setDeleteIntent(donePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "ပြီးပြီ / Done", donePending)
            .setAutoCancel(true)
            .setSound(notifSoundUri)
            .setVibrate(VIBRATION_PATTERN)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(context).notify(id, notification)
                }
            } else {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        } catch (e: Exception) { android.util.Log.w("ReminderReceiver", "showNotification", e) }

        // Schedule repeat if enabled (မပိတ်မချင်း ပြန်သတိပေး)
        val repeatMin = SettingsActivity.getReminderRepeatMinutes(context)
        if (repeatMin > 0) {
            val repeatIntent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMINDER
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_ID, id)
            }
            val repeatPending = PendingIntent.getBroadcast(
                context, id, repeatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val nextTime = System.currentTimeMillis() + repeatMin * 60_000L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmMgr.canScheduleExactAlarms()) {
                        alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, repeatPending)
                    } else {
                        alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, repeatPending)
                    }
                } else {
                    alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, repeatPending)
                }
            } catch (e: Exception) { android.util.Log.w("ReminderReceiver", "scheduleRepeat", e) }
        }
        
        // Release wake lock
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }
    
    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500L)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Voice Brain reminder notifications"
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            )
            setShowBadge(true)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
    
    companion object {
        const val ACTION_REMINDER = "com.example.voicebrainlive.REMINDER"
        const val ACTION_REMINDER_DONE = "com.example.voicebrainlive.REMINDER_DONE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ID = "reminder_id"
        const val CHANNEL_ID = "voice_brain_reminders"
        /** တီးလုံး style: strong pulses - 800ms vibrate, 400ms pause x3, then 1000ms final beat */
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 400, 800, 600, 1000)
    }
}
