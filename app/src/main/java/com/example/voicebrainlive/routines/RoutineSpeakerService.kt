package com.example.voicebrainlive.routines

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.MainActivity
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import com.example.voicebrainlive.usecases.AssistantAutomationPayload
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Foreground service: agent-style scheduled automation.
 * Supports silent direct execution (လုပ်ဆောင်ရုံနဲ့ပြီး) and serene, calm announcements (အေးဆေးတည်ငြိမ်စွာ အသိပေးပြောကြားခြင်း).
 */
class RoutineSpeakerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val commandRunner by lazy { AutomationCommandRunner(this) }
    private var serviceWakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val id = intent.getIntExtra(MainActivity.EXTRA_AUTOMATION_ID, -1)
        val type = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_TYPE) ?: "reminder"
        val message = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE) ?: ""
        val musicQuery = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY) ?: ""
        val reminderText = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT) ?: ""
        val isPre = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_IS_PRE, false)
        val shouldSpeak = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_SPEAK, true)

        if (!AutomationExecutionLedger.tryClaim(this, id, type, isPre)) {
            Log.i(TAG, "Skipping duplicate routine trigger id=$id type=$type isPre=$isPre")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val announce = if (shouldSpeak) {
            intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_ANNOUNCE).orEmpty().ifBlank {
                AssistantAutomationPayload.buildCalmAnnounce(type, message, musicQuery, isPre)
                    .let { if (isPre) reminderText.ifBlank { it } else it }
            }
        } else {
            ""
        }

        Log.d(TAG, "Triggered id=$id type=$type isPre=$isPre shouldSpeak=$shouldSpeak")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        serviceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBrain:RoutineSpeakerServiceWakeLock").apply {
            acquire(300_000L) // 5 minutes max
        }

        try {
            @Suppress("DEPRECATION")
            val screenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "VoiceBrain:RoutineSpeakerScreenWakeLock"
            )
            screenLock.acquire(10000L) // Wake up screen for 10 seconds to allow playback to start
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire screen wake lock: ${e.message}")
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            id,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isPre) CHANNEL_ID_LOW else CHANNEL_ID_HIGH
        createChannel(channelId, NotificationManager.IMPORTANCE_HIGH)

        val notifText = when {
            announce.isNotBlank() -> announce
            !shouldSpeak -> if (message.isNotBlank()) "အသံတိတ် ဆောင်ရွက်နေသည်: $message" else "အလိုအလျောက် ဆောင်ရွက်နေသည်"
            else -> "အလိုအလျောက် လုပ်ဆောင်ချက်"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Voice Brain Agent")
            .setContentText(notifText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(true)
            .build()

        startForeground(id.coerceIn(100, 99999), notification)

        scope.launch {
            try {
                if (shouldSpeak && announce.isNotBlank()) {
                    speakAndAwait(announce, isCalm = true)
                }

                if (!isPre) {
                    val result = withContext(Dispatchers.IO) {
                        commandRunner.run(
                            type = type,
                            message = message,
                            musicQuery = musicQuery,
                            isPreReminder = false
                        )
                    }
                    if (shouldSpeak && shouldSpeakResult(type, result, announce)) {
                        speakAndAwait(result, isCalm = true)
                    }

                    RoutineStorage.getById(this@RoutineSpeakerService, id)?.let { item ->
                        RoutineScheduler.schedule(this@RoutineSpeakerService, item)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Execution error", e)
                if (shouldSpeak) {
                    speakAndAwait("အလိုအလျောက် လုပ်ဆောင်ချက် မအောင်မြင်ပါ။", isCalm = true)
                }
            } finally {
                if (serviceWakeLock?.isHeld == true) {
                    try { serviceWakeLock?.release() } catch (_: Exception) {}
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun shouldSpeakResult(type: String, result: String, announce: String): Boolean {
        if (result.isBlank()) return false
        if (result == announce) return false
        // Avoid speaking over music playback
        if (type.lowercase() in setOf("music", "play_music", "play_spotify")) return false
        if (result.startsWith("Error", ignoreCase = true)) return true
        // Speak informational narratives (weather, daily log, health briefing)
        if (type.lowercase() in setOf("weather", "get_weather", "daily_log", "health", "routine_health", "get_health_summary")) return true
        if (result.startsWith("Approval pending") || result.contains("အတည်ပြုရန်")) return true
        return false
    }

    private suspend fun speakAndAwait(text: String, isCalm: Boolean = true) {
        if (text.isBlank()) return
        suspendCoroutine { cont ->
            val tts = TtsUnifiedInterface(this)
            val handler = Handler(Looper.getMainLooper())
            var checkCount = 0
            val checkRunnable = object : Runnable {
                override fun run() {
                    if (tts.isAvailable()) {
                        tts.setOnSpeakingComplete {
                            tts.shutdown()
                            cont.resume(Unit)
                        }
                        if (isCalm) {
                            tts.speakCalm(text) // Speaks with calm, serene Burmese voice settings
                        } else {
                            tts.speak(text)
                        }
                    } else {
                        checkCount++
                        if (checkCount < 100) { // Timeout after 5 seconds
                            handler.postDelayed(this, 50)
                        } else {
                            tts.shutdown()
                            cont.resume(Unit)
                        }
                    }
                }
            }
            handler.post(checkRunnable)
        }
    }

    private fun createChannel(channelId: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Routines", importance)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RoutineSpeaker"
        private const val CHANNEL_ID_LOW = "voice_brain_routines_low"
        private const val CHANNEL_ID_HIGH = "voice_brain_routines_alarm"
    }
}
