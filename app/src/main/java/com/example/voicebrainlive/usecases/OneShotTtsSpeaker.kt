package com.example.voicebrainlive.usecases

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class OneShotTtsSpeaker(private val context: Context) {

    fun speak(text: String, utteranceId: String, onError: (() -> Unit)? = null, onDone: () -> Unit) {
        if (text.isBlank()) {
            onDone()
            return
        }

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                try {
                    tts?.shutdown()
                } catch (_: Exception) {
                }
                onError?.invoke()
                onDone()
                return@TextToSpeech
            }

            val burmeseLocale = Locale("my", "MM")
            val result = tts?.setLanguage(burmeseLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.forLanguageTag("my"))
            }
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(0.9f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    try {
                        tts?.shutdown()
                    } catch (_: Exception) {
                    }
                    onDone()
                }

                override fun onError(utteranceId: String?) {
                    try {
                        tts?.shutdown()
                    } catch (_: Exception) {
                    }
                    onError?.invoke()
                    onDone()
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }
}
