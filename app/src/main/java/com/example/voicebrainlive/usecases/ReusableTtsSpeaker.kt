package com.example.voicebrainlive.usecases

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class ReusableTtsSpeaker(private val context: Context) {

    private var sharedTts: TextToSpeech? = null

    fun speak(text: String, utteranceId: String = "reusable_tts") {
        if (com.example.voicebrainlive.voice.AgentSpeech.ENABLED) {
            com.example.voicebrainlive.voice.AgentSpeech.speak(context, text, this)
            return
        }
        if (text.isBlank()) return

        val existingTts = sharedTts
        if (existingTts != null) {
            try {
                existingTts.language = Locale.forLanguageTag("my")
                existingTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                return
            } catch (e: Exception) {
                Log.w("ReusableTtsSpeaker", "Reusing TTS failed", e)
            }
        }

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("my")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                sharedTts = tts
            } else {
                tts?.shutdown()
            }
        }
    }

    fun isSpeaking(): Boolean {
        if (com.example.voicebrainlive.voice.AgentSpeech.ENABLED) return com.example.voicebrainlive.voice.AgentSpeech.isSpeaking
        return sharedTts?.isSpeaking ?: false
    }

    fun shutdown() {
        if (com.example.voicebrainlive.voice.AgentSpeech.ENABLED) com.example.voicebrainlive.voice.AgentSpeech.stop(this)
        sharedTts?.shutdown()
        sharedTts = null
    }
}
