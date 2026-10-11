package com.example.voicebrainlive.wakeword

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Wake Word Detector - Detects "Hey Voice Brain" or "Voice Brain" to activate the assistant
 * 
 * Uses Android's SpeechRecognizer for continuous listening.
 * When wake word is detected, it triggers the assistant automatically.
 * 
 * Supported wake words:
 * - "hey voice brain"
 * - "voice brain"
 * - "hey assistant"
 * - "assistant"
 * 
 * Usage:
 * ```
 * val detector = WakeWordDetector(context)
 * detector.setWakeWordListener { detectedWord ->
 *     // Wake word detected, start assistant
 *     startAssistant()
 * }
 * detector.startListening()
 * ```
 */
class WakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordDetector"
        
        // Supported wake words (lowercase for comparison)
        val WAKE_WORDS = listOf(
            "hey voice brain",
            "voice brain",
            "hey assistant",
            "assistant",
            "ဟေး ဗွိုစ့်ဘရိန်း",
            "ဗွိုစ့်ဘရိန်း"
        )
        
        // Confidence threshold for wake word detection (0.0 - 1.0)
        private const val CONFIDENCE_THRESHOLD = 0.5f
        
        // Delay before restarting listening after detection (ms)
        private const val RESTART_DELAY_MS = 2000L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var wakeWordListener: ((String) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    
    // Track consecutive errors to avoid infinite restart loops
    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 5

    /**
     * Set listener for wake word detection
     */
    fun setWakeWordListener(listener: (String) -> Unit) {
        wakeWordListener = listener
    }
    
    /**
     * Set listener for errors
     */
    fun setOnErrorListener(listener: (String) -> Unit) {
        onErrorListener = listener
    }

    /**
     * Start listening for wake word
     */
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening")
            return
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            onErrorListener?.invoke("Speech recognition not available on this device")
            return
        }
        
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            
            startRecognition()
            isListening = true
            consecutiveErrors = 0
            Log.d(TAG, "Started listening for wake word")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            onErrorListener?.invoke("Failed to start wake word detection: ${e.message}")
        }
    }

    /**
     * Stop listening for wake word
     */
    fun stopListening() {
        if (!isListening) return
        
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            Log.d(TAG, "Stopped listening for wake word")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
    }

    /**
     * Check if currently listening
     */
    fun isListening(): Boolean = isListening

    private fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recognition: ${e.message}")
            handleRestart()
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
                consecutiveErrors = 0
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Can be used for visual feedback
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer received
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                val errorMsg = getErrorMessage(error)
                Log.w(TAG, "Recognition error: $errorMsg (code: $error)")
                
                consecutiveErrors++
                
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.e(TAG, "Too many consecutive errors, stopping")
                    onErrorListener?.invoke("Wake word detection failed too many times. Please restart.")
                    stopListening()
                    return
                }
                
                // Don't restart on certain errors
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Normal behavior, just restart
                        handleRestart()
                    }
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_NETWORK -> {
                        // Network/server errors, retry with delay
                        handleRestart(delayMs = 3000)
                    }
                    else -> {
                        handleRestart()
                    }
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                consecutiveErrors = 0
                processResults(results)
                handleRestart()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                processResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                // Handle events if needed
            }
        }
    }

    private fun processResults(results: android.os.Bundle?) {
        if (results == null) return
        
        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val confidenceScores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        
        matches?.forEachIndexed { index, spokenText ->
            val normalizedText = spokenText.lowercase().trim()
            val confidence = confidenceScores?.getOrNull(index) ?: 1.0f
            
            Log.d(TAG, "Heard: '$normalizedText' (confidence: $confidence)")
            
            // Check if any wake word was detected
            for (wakeWord in WAKE_WORDS) {
                if (normalizedText.contains(wakeWord) && confidence >= CONFIDENCE_THRESHOLD) {
                    Log.d(TAG, "Wake word detected: '$wakeWord'")
                    wakeWordListener?.invoke(wakeWord)
                    break
                }
            }
        }
    }

    private fun handleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!isListening) return
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isListening) {
                try {
                    speechRecognizer?.cancel()
                    startRecognition()
                } catch (e: Exception) {
                    Log.e(TAG, "Restart failed: ${e.message}")
                }
            }
        }, delayMs)
    }

    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            else -> "Unknown error"
        }
    }
}