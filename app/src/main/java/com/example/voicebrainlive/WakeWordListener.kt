package com.example.voicebrainlive

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Listens for Burmese wake word "ဟေး ကောင်လေး" and triggers Assistant.
 * Uses Android SpeechRecognizer (my-MM) - no custom model needed.
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeDetected: () -> Unit
) {
    private val appContext: Context get() = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var restartScheduled = false
    private val restartRunnable = Runnable { start() }

    companion object {
        private const val TAG = "WakeWord"
        private const val RESTART_DELAY_MS = 1500L

        /** Wake phrase variations - SpeechRecognizer may return different forms */
        private val WAKE_PHRASES = listOf(
            "ဟေးကောင်လေး",
            "ဟေး ကောင်လေး",
            "ဟေ ကောင်လေး",
            "ကောင်လေးဟေး",
            "ကောင်လေး ဟေး",
            "ကောင်လေး",
            "ဟေး",
            "hey kaung lay",
            "hey kaunglay",
            "heykaunglay",
            "hey gaung lay",
            "kaung lay",
            "kaunglay",
            "တီ",
            "တီး",
            "တီးလုံး"
        )
    }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No RECORD_AUDIO - wake word disabled")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available")
            return
        }
        if (isListening) return

        handler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(createListener())
            }
            startListening()
            isListening = true
            Log.d(TAG, "Wake word listener started - ဟေး ကောင်လေး")
        }
    }

    fun stop() {
        isListening = false
        restartScheduled = false
        handler.removeCallbacks(restartRunnable)
        handler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.d(TAG, "Ignored speech recognizer destroy error: ${e.message}")
            }
            speechRecognizer = null
            Log.d(TAG, "Wake word listener stopped")
        }
    }

    private fun startListening() {
        if (!isListening || speechRecognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "my-MM")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            handler.post {
                android.widget.Toast.makeText(appContext, "ဟေး ကောင်လေး စတင် မရ: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (!isListening || restartScheduled) return
        restartScheduled = true
        handler.postDelayed({
            restartScheduled = false
            if (isListening) startListening()
        }, RESTART_DELAY_MS)
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> { /* Often when offline - restart */ }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "SpeechRecognizer error: $error (No mic permission)")
                    handler.post {
                        android.widget.Toast.makeText(appContext, "ဟေး ကောင်လေး: မိုက်ခ် ခွင့်ပြုချက် လိုပါသည်", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return
                }
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                    Log.w(TAG, "SpeechRecognizer error: $error - ပြန်ကြိုးစားမည်")
                }
                SpeechRecognizer.ERROR_NO_MATCH -> { /* No speech - normal, restart */ }
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
                SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { /* Restart */ }
            }
            scheduleRestart()
        }

        override fun onResults(results: android.os.Bundle?) {
            checkResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            checkResults(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    private fun checkResults(phrases: List<String>?) {
        if (phrases.isNullOrEmpty()) return
        for (phrase in phrases) {
            if (matchesWakePhrase(phrase)) {
                Log.d(TAG, "Wake word detected: '$phrase'")
                handler.post {
                    playWakeTone()
                    onWakeDetected()
                    isListening = false
                    try { 
                        speechRecognizer?.destroy() 
                    } catch (e: Exception) {
                        Log.d(TAG, "Ignored speech recognizer destroy error: ${e.message}")
                    }
                    speechRecognizer = null
                    handler.postDelayed(restartRunnable, 2500)  // Cooldown 2.5s
                }
                return
            }
        }
    }

    private fun playWakeTone() {
        try {
            val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.w(TAG, "Wake tone failed: ${e.message}")
        }
    }

    private fun matchesWakePhrase(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.trim().replace(" ", "").replace("\u200B", "").lowercase()
        val normalizedWithSpace = text.trim().lowercase()
        for (wake in WAKE_PHRASES) {
            val wakeNorm = wake.replace(" ", "").lowercase()
            if (wakeNorm.length >= 1 && (normalized.contains(wakeNorm) || normalizedWithSpace.contains(wake))) return true
        }
        return false
    }
}
