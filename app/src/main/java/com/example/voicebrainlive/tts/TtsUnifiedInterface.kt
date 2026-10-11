package com.example.voicebrainlive.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TTS Unified Interface - Centralized Text-to-Speech management
 * 
 * Provides a unified way to speak text across the app with consistent voice settings.
 * Supports multiple languages (Burmese, Korean, English) and different speech styles.
 * 
 * FIX: Stuttering caused by resetting pitch/rate/language immediately after speak() call.
 * The fix uses pending settings that are applied in the onDone callback instead.
 */
class TtsUnifiedInterface(
    context: Context
) {

    private val appContext: Context = context.applicationContext
    companion object {
        private const val TAG = "TtsUnified"
        private const val UTTERANCE_ID = "voicebrain_tts"
        
        // Default values
        private const val DEFAULT_PITCH = 1.0f
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private val DEFAULT_LANGUAGE = Locale("my", "MM")
        private const val WEATHER_SPEECH_RATE = 0.85f
        private const val ERROR_PITCH = 1.1f
        
        // Track if we need to reset after current speech
        private var needsPitchReset: Boolean = false
        private var needsSpeechRateReset: Boolean = false
        private var needsLanguageReset: Boolean = false

        @Volatile var externalTtsSpeakingUntil: Long = 0L

        private val activeInstances = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<TtsUnifiedInterface, Boolean>())
        )

        fun isAnyInstanceSpeaking(): Boolean {
            if (System.currentTimeMillis() < externalTtsSpeakingUntil) {
                return true
            }
            synchronized(activeInstances) {
                for (instance in activeInstances) {
                    if (instance.isSpeaking()) {
                        return true
                    }
                }
            }
            return false
        }
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onSpeakingComplete: (() -> Unit)? = null

    init {
        initializeTts()
        activeInstances.add(this)
    }

    private fun initializeTts() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val burmeseLocale = DEFAULT_LANGUAGE
                val result = tts?.setLanguage(burmeseLocale)
                
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Burmese TTS not available, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                
                tts?.setPitch(DEFAULT_PITCH)
                tts?.setSpeechRate(DEFAULT_SPEECH_RATE)
                
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
        
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed: $utteranceId")
                
                // Apply reset settings AFTER speech completes to prevent stuttering
                if (needsPitchReset) {
                    tts?.setPitch(DEFAULT_PITCH)
                    needsPitchReset = false
                }
                if (needsSpeechRateReset) {
                    tts?.setSpeechRate(DEFAULT_SPEECH_RATE)
                    needsSpeechRateReset = false
                }
                if (needsLanguageReset) {
                    tts?.setLanguage(DEFAULT_LANGUAGE)
                    needsLanguageReset = false
                }
                
                onSpeakingComplete?.invoke()
                Handler(Looper.getMainLooper()).post {
                    // Double-check resets in case utterance ID didn't match
                    if (needsPitchReset) {
                        tts?.setPitch(DEFAULT_PITCH)
                        needsPitchReset = false
                    }
                    if (needsSpeechRateReset) {
                        tts?.setSpeechRate(DEFAULT_SPEECH_RATE)
                        needsSpeechRateReset = false
                    }
                    if (needsLanguageReset) {
                        tts?.setLanguage(DEFAULT_LANGUAGE)
                        needsLanguageReset = false
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                onSpeakingComplete?.invoke()
            }
        })
    }

    fun speak(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, skipping: $text")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Speak weather - slower rate
     * FIX: No immediate reset after speak
     */
    fun speakWeather(text: String) {
        if (!isInitialized) return
        
        tts?.setSpeechRate(WEATHER_SPEECH_RATE)
        needsSpeechRateReset = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_weather")
    }

    /**
     * Speak calm - serene, slightly relaxed rate and gentle pitch
     */
    fun speakCalm(text: String) {
        if (!isInitialized) return
        tts?.setSpeechRate(0.9f)
        tts?.setPitch(0.95f)
        needsSpeechRateReset = true
        needsPitchReset = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_calm")
    }

    fun speakStatus(text: String) {
        if (!isInitialized) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_status")
    }

    /**
     * Speak error - higher pitch
     * FIX: Flag reset needed, onDone will apply it
     */
    fun speakError(text: String) {
        if (!isInitialized) return
        
        tts?.setPitch(ERROR_PITCH)
        needsPitchReset = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_error")
    }

    /**
     * Speak alert - faster rate
     * FIX: Flag reset needed, onDone will apply it
     */
    fun speakAlert(text: String) {
        if (!isInitialized) return
        
        tts?.setSpeechRate(1.0f)
        needsSpeechRateReset = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_alert")
    }

    /**
     * Speak in Korean
     * FIX: Flag reset needed, onDone will apply it
     */
    fun speakKorean(text: String) {
        if (!isInitialized) return
        
        val koreanLocale = Locale("ko", "KR")
        val result = tts?.setLanguage(koreanLocale)
        
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Korean TTS not available")
            return
        }
        
        needsLanguageReset = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_korean")
    }

    /**
     * Speak in English
     * FIX: Flag reset needed, onDone will apply it
     */
    fun speakEnglish(text: String) {
        if (!isInitialized) return
        
        tts?.setLanguage(Locale.US)
        needsLanguageReset = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_english")
    }

    fun queueSpeak(text: String) {
        if (!isInitialized) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "${UTTERANCE_ID}_queued")
    }

    fun stop() {
        tts?.stop()
        // Reset any pending changes if stopped
        needsPitchReset = false
        needsSpeechRateReset = false
        needsLanguageReset = false
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    fun setOnSpeakingComplete(callback: () -> Unit) {
        onSpeakingComplete = callback
    }

    fun shutdown() {
        activeInstances.remove(this)
        tts?.stop()
        tts?.shutdown()
        isInitialized = false
        Log.d(TAG, "TTS shutdown")
    }

    fun isAvailable(): Boolean = isInitialized
}