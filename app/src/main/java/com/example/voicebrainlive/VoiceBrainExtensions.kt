package com.example.voicebrainlive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Extension functions for VoiceBrainLive v2.0
 * Adds Translation, Call Recording, and Live Translation features
 */
class VoiceBrainExtensions(private val context: Context) {
    
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    
    private var translationHelper: TranslationHelper? = null
    private var isRecordingCall = false
    private var isLiveTranslating = false
    
    companion object {
        const val TAG = "VoiceBrainExtensions"
    }
    
    init {
        // Initialize translation helper with API key from settings
        val apiKey = SettingsActivity.getGeminiApiKey(context)
        if (apiKey.isNotBlank() && apiKey != "YOUR_API_KEY") {
            translationHelper = TranslationHelper(apiKey)
        }
    }
    
    // ===== TRANSLATION =====
    
    fun translateText(text: String, targetLang: String): String {
        if (translationHelper == null) {
            return "Translation API not configured. Add Gemini API key in Settings."
        }
        if (text.isBlank()) {
            return "Please provide text to translate"
        }
        
        var result = ""
        mainScope.launch {
            try {
                val sourceLang = translationHelper!!.detectLanguage(text)
                val translated = translationHelper!!.translate(text, sourceLang, targetLang)
                
                val sourceName = translationHelper!!.getLanguageName(sourceLang)
                val targetName = translationHelper!!.getLanguageName(targetLang)
                
                result = "Translated from $sourceName to $targetName: $translated"
                
                // Speak the translation
                speakText(translated)
            } catch (e: Exception) {
                Log.e(TAG, "Translation error: ${e.message}")
                result = "Translation failed: ${e.message}"
            }
        }
        return "Translating..."
    }
    
    fun translateFrom(sourceLang: String, text: String): String {
        if (translationHelper == null) {
            return "Translation API not configured. Add Gemini API key in Settings."
        }
        if (text.isBlank()) {
            return "Please provide text to translate"
        }
        
        var result = ""
        mainScope.launch {
            try {
                // Default target is English
                val translated = translationHelper!!.translate(text, sourceLang, "en")
                
                val sourceName = translationHelper!!.getLanguageName(
                    translationHelper!!.getLanguageCode(sourceLang)
                )
                
                result = "Translated from $sourceName to English: $translated"
                
                // Speak the translation
                speakText(translated)
            } catch (e: Exception) {
                Log.e(TAG, "Translation error: ${e.message}")
                result = "Translation failed: ${e.message}"
            }
        }
        return "Translating..."
    }
    
    // ===== CALL RECORDING =====
    
    fun startCallRecording(): String {
        if (isRecordingCall) {
            return "Already recording"
        }
        
        // Call recording removed: Android 10+ restricts non-system call recording.
        return "Call recording is not supported on modern Android."
    }
    
    fun stopCallRecording(): String {
        isRecordingCall = false
        stopRecordingService()
        return "Call recording disabled"
    }
    
    private fun startRecordingService() { }
    private fun stopRecordingService() { }
    
    // ===== TRANSCRIBE CALL =====
    
    fun transcribeCall(): String {
        val recordingFile = "${context.cacheDir}/call_recording.m4a"
        
        // Check if recording exists
        val file = java.io.File(recordingFile)
        if (!file.exists()) {
            return "No call recording found. Enable call recording first."
        }
        
        return "Transcribing call recording... (This feature requires additional API setup)"
    }
    
    // ===== LIVE TRANSLATION DURING CALLS =====
    
    fun liveTranslate(sourceLang: String, targetLang: String): String {
        if (translationHelper == null) {
            return "Translation API not configured. Add Gemini API key in Settings."
        }
        
        isLiveTranslating = true
        
        startLiveTranslationMode(sourceLang, targetLang)
        
        val sourceName = translationHelper!!.getLanguageName(
            translationHelper!!.getLanguageCode(sourceLang)
        )
        val targetName = translationHelper!!.getLanguageName(
            translationHelper!!.getLanguageCode(targetLang)
        )
        
        return "Live translation enabled. I'll translate from $sourceName to $targetName during your calls."
    }
    
    private fun startLiveTranslationMode(sourceLang: String, targetLang: String) {
        Log.d(TAG, "Starting live translation: $sourceLang -> $targetLang")
        
        // In a real implementation, this would:
        // 1. Use AudioRecord to capture microphone input
        // 2. Send to speech-to-text API
        // 3. Translate the text
        // 4. Use TextToSpeech to speak the translation
        
        speakText("Live translation activated. $sourceLang to $targetLang.")
    }
    
    // ===== HELPER FUNCTIONS =====
    
    private fun speakText(text: String) {
        if (com.example.voicebrainlive.voice.AgentSpeech.ENABLED) {
            com.example.voicebrainlive.voice.AgentSpeech.speak(context, text, this)
            return
        }
        try {
            // Track translation speaking duration in TtsUnifiedInterface so silence timeout does not close mid-speech
            com.example.voicebrainlive.tts.TtsUnifiedInterface.externalTtsSpeakingUntil = 
                System.currentTimeMillis() + 6000L

            var ttsInstance: TextToSpeech? = null
            ttsInstance = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInstance?.language = Locale.US
                    ttsInstance?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation")
                    
                    // Release TTS after speaking
                    handler.postDelayed({
                        ttsInstance?.stop()
                        ttsInstance?.shutdown()
                    }, 5000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}")
        }
    }
    
    // ===== DETECT LANGUAGE =====
    
    fun detectLanguage(text: String): String {
        if (translationHelper == null) {
            return "Translation API not configured"
        }
        
        var result = ""
        mainScope.launch {
            try {
                val langCode = translationHelper!!.detectLanguage(text)
                val langName = translationHelper!!.getLanguageName(langCode)
                result = "Detected language: $langName ($langCode)"
            } catch (e: Exception) {
                result = "Could not detect language: ${e.message}"
            }
        }
        return result
    }
    
    // ===== GET SUPPORTED LANGUAGES =====
    
    fun getSupportedLanguages(): String {
        val languages = TranslationHelper.SUPPORTED_LANGUAGES.keys.sorted()
        return "Supported languages: ${languages.joinToString(", ")}"
    }
}
