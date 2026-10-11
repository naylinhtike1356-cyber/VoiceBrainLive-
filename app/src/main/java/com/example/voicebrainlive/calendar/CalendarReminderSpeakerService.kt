package com.example.voicebrainlive.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Meeting မစခင် ၁၅ မိနစ်အလို: "Meeting X စတော့မယ် ၁၅ မိနစ်ရှိပြီ" TTS ပြောပြခြင်း
 */
class CalendarReminderSpeakerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val title = intent.getStringExtra(CalendarEventReminderReceiver.EXTRA_TITLE) ?: "Meeting"
        val text = "မကြာမီ $title စတင်ပါမည်။"
        createChannel()
        
        // Launch MainActivity for Proactive Gemini Interaction
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_PROACTIVE_SPEAK, true)
            putExtra(MainActivity.EXTRA_PROACTIVE_MESSAGE, text)
            putExtra(MainActivity.EXTRA_PROACTIVE_TYPE, "calendar_partner")
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Voice Brain - ပါတနာ သတိပေးချက်")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        startForeground(71000, notification)

        scope.launch(Dispatchers.Main) {
            // Wake Up the Screen
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "VoiceBrain:CalServiceWakeLock"
            )
            wakeLock.acquire(10000)

            // Start MainActivity directly instead of basic TTS
            try {
                startActivity(mainIntent)
            } catch (e: Exception) {
                Log.e("CalendarSpeakerService", "Failed to start MainActivity", e)
            }

            delay(3000) // Stop Foreground Service after ensuring the Intent fires
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ပြက္ခဒိန်သတိပေး",
                NotificationManager.IMPORTANCE_HIGH
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_brain_cal_reminder"
    }
}
