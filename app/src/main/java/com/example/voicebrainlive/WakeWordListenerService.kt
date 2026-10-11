package com.example.voicebrainlive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.media.AudioManager

import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import android.os.BatteryManager
import android.content.IntentFilter
import java.util.Locale

/**
 * Wake Word Listener – "Hey Voice" / "Voice Brain" ပြောပြီး Assistant နှိုးချင်။
 * SpeechRecognizer နဲ့ continuous listening, phrase match ရင် AssistantActivity ဖွင့်သည်။
 * Battery Saver: proximity sensor သုံးပြီး pocket/face down ဖြစ်ရင် Mic ပိတ်ထားမည်။
 */
class WakeWordListenerService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isCovered = false // True if phone is in pocket or face down

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
        private var cooldownUntil = 0L
    private var restartDelayMs = 3000L
    private var restartScheduled = false
    private var consecutiveQuietSessions = 0
    private var recognitionSessionActive = false
    private var wakeHandled = false

    private val COOLDOWN_MS = 10000L
    private val MAX_RESTART_DELAY_MS = 30000L

    // Keep one conservative, Burmese-friendly phrase family. Short/common words
    // such as "hi voice" or "တီ" caused excessive false triggers.
    private var recognitionLanguage = "my-MM"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
                super.onCreate()
        wakeHandled = false
        Log.d(TAG, "WakeWordListenerService onCreate")

        // Foreground Service: Notification နဲ့ တတွဲတည်း – ၅ စက္ကန့်အတွင်း startForeground ခေါ်ရမည်
        if (!startForegroundWithNotification()) {
            stopSelf()
            return
        }
        
        // Initialize Proximity Sensor for Battery Saving
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startListening()
        } else {
            Log.e(TAG, "Speech recognition not available")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

        private fun startListening() {
        if (recognitionSessionActive || isDestroyed) return
        handler.removeCallbacksAndMessages(null)
        restartScheduled = false
        recognitionSessionActive = true
        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            recognitionSessionActive = false
                            if (wakeHandled) return
                            when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            Log.e(TAG, "Wake word permission revoked, stopping service")
                            WakeWordPrefs.setEnabled(this@WakeWordListenerService, false)
                            stopSelf()
                            return
                        }
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            // User was silent or timed out - restart quickly to keep wake word responsive
                                                        consecutiveQuietSessions = (consecutiveQuietSessions + 1).coerceAtMost(6)
                            // Do not restart every second: Samsung recognition services
                            // may emit a start/stop click for each new session.
                            restartDelayMs = (15000L + consecutiveQuietSessions * 5000L).coerceAtMost(30000L)
                            scheduleRestart()

                        }
                        SpeechRecognizer.ERROR_AUDIO,
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                            // Real error - apply exponential backoff to prevent battery drain & loop fatigue
                            Log.w(TAG, "SpeechRecognizer error: $error. Backing off.")
                            scheduleRestartWithBackoff()
                        }
                                                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
                        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            if (recognitionLanguage != "en-US") {
                                recognitionLanguage = "en-US"
                                restartDelayMs = 1500L
                                Log.w(TAG, "Burmese speech is unavailable; falling back to en-US")
                            } else {
                                Log.w(TAG, "SpeechRecognizer support error: $error. Backing off.")
                                scheduleRestartWithBackoff()
                            }
                            scheduleRestart()
                        }

                    }
                }
                                        override fun onResults(results: Bundle?) {
                            recognitionSessionActive = false
                            checkResults(results)
                            if (wakeHandled) return

                                        consecutiveQuietSessions = 0
                    restartDelayMs = 5000L
                    scheduleRestart()

                }
                                override fun onPartialResults(partialResults: Bundle?) {
                    // Never trigger from partial recognition.
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        restartListening()
    }

    private fun checkResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        // Only the best final result is considered. WakeWordMatcher requires the
        // phrase at the beginning of a short transcript, never as a substring.
        val text = matches.firstOrNull().orEmpty()
        val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.firstOrNull()
        if (WakeWordMatcher.matchesFinalTranscript(text, confidence)) {
            onWakeWordDetected()
        }
    }

        private fun onWakeWordDetected() {
        if (wakeHandled || System.currentTimeMillis() < cooldownUntil) return
        wakeHandled = true
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
        handler.removeCallbacksAndMessages(null)
        restartScheduled = false
        Log.d(TAG, "Wake word detected; stopping wake service before opening Assistant")

        // Stop the recognizer before opening the assistant so it cannot hear the
        // assistant's own audio and trigger another wake cycle.
        speechRecognizer?.cancel()
        wakeFeedback()
        handler.post {
                        val intent = Intent(this, AssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("EXTRA_WAKE_ORIGIN", true)
            }

            startActivity(intent)
            // Assistant must own the microphone. Keeping this service alive here
            // would cause a second recognizer session and Samsung route beeps.
            stopSelf()
        }
    }

    private fun wakeFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(120L)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Wake vibration failed: ${t.message}")
        }
        // No audible tone: notification-stream beeps can repeat on false
        // recognitions and are disruptive when enabling background wake word.
    }

    private fun isBatteryLow(): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            registerReceiver(null, filter)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()) else 100f

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return batteryPct < 15f && !isCharging
    }

        private fun restartListening() {
        if (wakeHandled || !WakeWordPrefs.isEnabled(this)) {

            stopSelf()
            return
        }
        // Battery Saver: If battery is low, apply longer delay between attempts
        if (isBatteryLow()) {
            Log.d(TAG, "Battery is low (<15%) and not charging. Applying power-saving delay.")
            restartDelayMs = 15000L // 15 seconds delay
            scheduleRestart()
            return
        }
                // Battery Saver: If proximity sensor says completely covered, don't restart speech recognizer right now.
        // It will restart automatically when the sensor detects it is uncovered.
        if (isCovered) {
            Log.d(TAG, "Phone is covered (Battery Saver Action). Pausing Wake Word.")
            return
        }
        // Android/Samsung SpeechRecognizer may request transient audio focus and
        // play a route notification when a recognition session starts. Never
        // begin a new recognition session over active music; this preserves the
        // user's playback instead of briefly ducking or pausing it.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.isMusicActive == true) {
            Log.d(TAG, "Music is active; delaying wake recognition to avoid audio-focus interruption")
            restartDelayMs = 60000L
            scheduleRestart()
            return
        }
        try {

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                // Partial results are intentionally disabled: they frequently
                // contain transient words from ordinary background speech.
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

                                // Prefer Burmese pronunciation; fall back to en-US if the
                // device speech service reports Burmese as unavailable.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)

                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                // ကလုံသံ နည်းအောင် – ဆူညံသံ ၁.၅ စက္ကန့်စောင့်ပြီးမှ ပြန်စ (Faster Start-to-End detection)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                // "Hey voice" တိုသောစကားအတွက် ၅၀၀ ms ပဲ လိုသည် 
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening error: ${e.message}", e)
            scheduleRestartWithBackoff()
        }
    }

    private fun scheduleRestart() {
        if (wakeHandled || restartScheduled || isDestroyed) return
        restartScheduled = true
        val delay = restartDelayMs
        handler.postDelayed({
            restartScheduled = false
            if (!isDestroyed) restartListening()
        }, delay)
    }

    private fun scheduleRestartWithBackoff() {
        scheduleRestart()
        restartDelayMs = (restartDelayMs * 2).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5.0f
            val nowCovered = distance < maxRange && distance < 3.0f
            if (nowCovered != isCovered) {
                isCovered = nowCovered
                if (isCovered) {
                    // Stop Listening
                    speechRecognizer?.cancel()
                } else {
                    // Resume Listening
                    restartListening()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

        private var isDestroyed = false

    override fun onDestroy() {
        isDestroyed = true
        restartScheduled = false
        recognitionSessionActive = false
        super.onDestroy()

        sensorManager?.unregisterListener(this)
        speechRecognizer?.destroy()
        speechRecognizer = null
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "WakeWordListenerService onDestroy")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (WakeWordPrefs.isEnabled(this)) {
            Log.d(TAG, "WakeWordListenerService task removed; restarting service")
            try {
                val restartIntent = Intent(this, WakeWordListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restart wake word service: ${e.message}")
            }
        }
    }

    /**
     * Foreground Service: Notification နဲ့ တတွဲတည်း run မည်။
     * @return true if startForeground succeeded, false otherwise (caller should stopSelf)
     */
    private fun startForegroundWithNotification(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Cannot start microphone foreground service without RECORD_AUDIO")
                return false
            }

            val channelId = "wake_word_listener"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "အသံနှိုး",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, SettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Voice Brain")
                .setContentText("Hey Voice / Voice Brain ပြောပြီး နှိုးနေသည်")
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build()
            // Android 14 (API 34) requires specific types and declared permissions
            val serviceType = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    // API 34+: must check permission before starting microphone service
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0 // Fallback to no type (may fail if required, but better than a crash)
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    // API 29-33: support microphone type
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                else -> 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serviceType != 0) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "WakeWordListener"
        const val ACTION_STOP = "com.example.voicebrainlive.STOP_WAKE_WORD"
        private const val NOTIFICATION_ID = 9002
    }
}
