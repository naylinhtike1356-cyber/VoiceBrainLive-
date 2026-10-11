package com.example.voicebrainlive.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.MainActivity
import com.example.voicebrainlive.SpotifyManager
import com.example.voicebrainlive.notion.NotionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Event-Driven: Office / Home ရောက်တဲ့အခါ TTS + Phase 2.2: Home=Spotify, Office=Notion to-do
 */
class LocationArrivalSpeakerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "ခုပဲ ရောက်တာလား"
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_OFFICE

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceBrain:LocationArrival"
        )
        wakeLock.acquire(15_000L)

        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Voice Brain - နေရာရောက်ပြီ")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        startForeground(72000, notification)

        scope.launch(Dispatchers.Main) {
            val proactiveType = when (action) {
                ACTION_HOME -> "location_home"
                ACTION_OFFICE -> "location_office"
                ACTION_HOME_EXIT -> "location_home_exit"
                ACTION_OFFICE_EXIT -> "location_office_exit"
                else -> "location_generic"
            }
            
            // Phase 2.1: Use Gemini for proactive partner-like greeting instead of basic TTS
            launchProactiveGemini(message, proactiveType)

            if (action == ACTION_HOME) {
                delay(2000)
                playSpotifyChill()
            }
            
            delay(5000)
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Phase 2.2: Home ရောက်ရင် Spotify အေးအေးဆေးဆေး သီချင်း (Lo-fi / Peaceful) */
    private fun playSpotifyChill() {
        try {
            val uri = SpotifyManager.getMoodPlaylistUri("အေးဆေး")
                ?: SpotifyManager.getMoodPlaylistUri("စာကြည့်")
                ?: "spotify:playlist:37i9dQZF1DWWQRwui0ExPn"  // Lo-fi Beats
            SpotifyManager.playUri(this, uri)
        } catch (_: Exception) { }
    }

    /** Phase 2.2: Office ရောက်ရင် Notion to-do ဖတ်ပြ */
    private fun getNotionTodoSummary(): String {
        return try {
            val items = NotionClient(this).getTodoItems(limit = 5)
            if (items.isEmpty()) ""
            else "ယနေ့ လုပ်ရမည့်အရာ ${items.size} ခု ရှိပါတယ်။ " +
                items.take(3).joinToString("။ ") { it.first }
        } catch (_: Exception) { "" }
    }

    private fun launchProactiveGemini(baseMessage: String, type: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_PROACTIVE_SPEAK, true)
            putExtra(MainActivity.EXTRA_PROACTIVE_MESSAGE, baseMessage)
            putExtra(MainActivity.EXTRA_PROACTIVE_TYPE, type)
        }
        startActivity(intent)
    }

    private fun speakAndStop(text: String) {
        // Legacy TTS fallback removed in favor of Proactive Gemini
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "နေရာရောက်ပြီ",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_MESSAGE = "location_arrival_message"
        const val EXTRA_ACTION = "location_arrival_action"
        const val ACTION_HOME = "home"
        const val ACTION_OFFICE = "office"
        const val ACTION_HOME_EXIT = "home_exit"
        const val ACTION_OFFICE_EXIT = "office_exit"
        private const val CHANNEL_ID = "voice_brain_location_arrival"
    }
}
