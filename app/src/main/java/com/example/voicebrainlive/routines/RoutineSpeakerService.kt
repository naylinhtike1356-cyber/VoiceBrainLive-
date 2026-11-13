package com.example.voicebrainlive.routines

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Foreground service: agent-style scheduled automation.
 * Supports silent direct execution, serene calm background announcements via playback-only TTS,
 * and multi-action pipelines for Samsung Modes & Routines.
 */
class RoutineSpeakerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val commandRunner by lazy { AutomationCommandRunner(this) }
    private val execution = Mutex()
    private var pendingRuns = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val id = intent.getIntExtra(MainActivity.EXTRA_AUTOMATION_ID, -1)
        val type = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_TYPE) ?: "reminder"
        val message = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE) ?: ""
        val musicQuery = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_MUSIC_QUERY) ?: ""
        val reminderText = intent.getStringExtra(MainActivity.EXTRA_AUTOMATION_REMINDER_TEXT) ?: ""
        val isPre = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_IS_PRE, false)
        val shouldSpeak = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_SPEAK, true)
        val routineName = intent.getStringExtra("extra_automation_name") ?: ""
        val ringerMode = intent.getStringExtra("extra_automation_ringer_mode") ?: ""
        val volumePercent = intent.getIntExtra("extra_automation_volume_percent", -1)
        val sleepTimerMinutes = intent.getIntExtra("extra_automation_sleep_timer_minutes", 0)
        val actionsSequence = intent.getStringExtra("extra_automation_actions_sequence") ?: ""

        val scheduled = intent.getLongExtra(RoutineReceiver.EXTRA_OCCURRENCE, 0L) > 0
        if (!scheduled && !AutomationExecutionLedger.tryClaim(this, id, type, isPre)) {
            Log.i(TAG, "Skipping duplicate routine trigger id=$id type=$type isPre=$isPre")
            if (pendingRuns == 0) stopSelf(startId)
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

        Log.d(TAG, "Triggered id=$id type=$type isPre=$isPre shouldSpeak=$shouldSpeak routineName=$routineName")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
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
            routineName.isNotBlank() -> "လုပ်ဆောင်နေသည်: $routineName"
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

        try {
            startForeground(ROUTINE_NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to start foreground routine playback", e)
            if (scheduled && !isPre) {
                AutomationExecutionLedger.record(this, id, AutomationExecutionLedger.Result.BLOCKED)
            }
            if (pendingRuns == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        pendingRuns++
        scope.launch {
            try {
                execution.withLock {
                    val wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "VoiceBrain:RoutineSpeakerServiceWakeLock"
                    ).apply { acquire(300_000L) }
                    try {
                        if (shouldSpeak && announce.isNotBlank()) speakAndAwait(announce, isCalm = true)
                        if (!isPre) {
                            val result = withContext(Dispatchers.IO) {
                                commandRunner.run(RoutineStorage.RoutineItem(
                                    id = if (scheduled) id else -1, hour = 0, minute = 0,
                                    type = type, message = message, musicQuery = musicQuery,
                                    ringerMode = ringerMode, volumePercent = volumePercent,
                                    sleepTimerMinutes = sleepTimerMinutes, actionsSequence = actionsSequence
                                ))
                            }
                            if (scheduled && AutomationExecutionLedger.classify(result) in setOf(
                                    AutomationExecutionLedger.Result.BLOCKED,
                                    AutomationExecutionLedger.Result.FAILED,
                                    AutomationExecutionLedger.Result.PARTIAL
                                )) {
                                com.example.voicebrainlive.voice.AgentSpeech.showOpenNotification(this@RoutineSpeakerService)
                            }
                            if (shouldSpeak && shouldSpeakResult(type, result, announce)) {
                                speakAndAwait(result, isCalm = true)
                            }
                        }
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (scheduled && !isPre) AutomationExecutionLedger.record(
                    this@RoutineSpeakerService, id, AutomationExecutionLedger.Result.INTERRUPTED
                )
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Execution error", e)
                if (scheduled && !isPre) AutomationExecutionLedger.record(
                    this@RoutineSpeakerService, id, AutomationExecutionLedger.Result.FAILED
                )
                if (shouldSpeak) {
                    speakAndAwait("အလိုအလျောက် လုပ်ဆောင်ချက် မအောင်မြင်ပါ။", isCalm = true)
                }
            } finally {
                pendingRuns--
                if (pendingRuns == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun shouldSpeakResult(type: String, result: String, announce: String): Boolean {
        if (result.isBlank()) return false
        if (result == announce) return false
        // Avoid speaking over music playback
        if (type.lowercase() in setOf("music", "play_music", "play_spotify", "bedtime", "sleep")) return false
        if (result.startsWith("Error", ignoreCase = true)) return true
        // Speak informational narratives (weather, daily log, health briefing)
        if (type.lowercase() in setOf("weather", "get_weather", "daily_log", "health", "routine_health", "get_health_summary")) return true
        if (result.startsWith("Approval pending") || result.contains("အတည်ပြုရန်")) return true
        return false
    }

    private suspend fun speakAndAwait(text: String, isCalm: Boolean = true) {
        if (text.isBlank()) return
        val tts = TtsUnifiedInterface(this)
        try {
            withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine { continuation ->
                    tts.speakLocalDirect(text, isCalm = isCalm) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        } finally {
            tts.shutdown()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
        private const val ROUTINE_NOTIFICATION_ID = 94641
        private const val CHANNEL_ID_LOW = "voice_brain_routines_low"
        private const val CHANNEL_ID_HIGH = "voice_brain_routines_alarm"
    }
}
