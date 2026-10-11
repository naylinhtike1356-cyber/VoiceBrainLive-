package com.example.voicebrainlive

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.ArrayList

/**
 * RecognitionService for Voice Brain – required by Android/Samsung so the app is
 * registered as a full Voice Interaction Application (VIA).
 * System က Default Digital Assistant အဖြစ် စစ်ဆေးတဲ့အခါ ဤ Service ပါရင် လက်ခံပါတယ်။
 * Voice Brain uses its own AssistantActivity + GeminiLive for actual AI; this service
 * provides minimal compliance so Power-button long-press works.
 */
class VoiceBrainRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        Log.d(TAG, "onStartListening - System က Voice Recognition တောင်းခံမှု")
        // Minimal response so system sees we're valid.
        // Voice Brain handles real recognition in AssistantActivity via GeminiLive.
        Handler(Looper.getMainLooper()).post {
            try {
                val results = ArrayList<String>().apply { add("") }
                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results)
                }
                callback.results(bundle)
            } catch (e: Exception) {
                Log.e(TAG, "onStartListening results: ${e.message}", e)
                callback.error(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun onCancel(callback: Callback) {
        Log.d(TAG, "onCancel")
    }

    override fun onStopListening(callback: Callback) {
        Log.d(TAG, "onStopListening")
    }

    companion object {
        private const val TAG = "VoiceBrainRecog"
    }
}
