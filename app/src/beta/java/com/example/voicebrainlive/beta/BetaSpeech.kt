package com.example.voicebrainlive.beta

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*

interface BetaSpeechPort {
    fun available(): Boolean
    fun start(language: String, text: (String) -> Unit, state: (String) -> Unit)
    fun cancel()
}
/** OS on-device API only. Never invokes createSpeechRecognizer or a cloud recognizer fallback. */
class AndroidBetaSpeechPort(private val activity: BetaMainActivity) : BetaSpeechPort {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    override fun available(): Boolean = Build.VERSION.SDK_INT >= 31 && try { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) } catch (_: Exception) { false }
    override fun start(language: String, text: (String) -> Unit, state: (String) -> Unit) {
        cancel()
        if (Build.VERSION.SDK_INT < 31 || !available()) { state("On-device speech unavailable. Use text; no cloud fallback."); return }
        val owner = generation
        try {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity); recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                private fun current() = generation == owner && recognizer === r
                override fun onReadyForSpeech(params: Bundle?) { if (current()) state("Listening on device · $language") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {} // Never store raw audio.
                override fun onEndOfSpeech() { if (current()) state("Processing speech on device") }
                override fun onError(error: Int) { if (current()) { cancel(); state("On-device recognition error $error. Check installed language; text remains available.") } }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val value = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.take(2000).orEmpty()
                    cancel(); state("Speech stopped. Review transcript before planning; nothing executed.")
                    if (value.isNotBlank()) text(value)
                }
                override fun onPartialResults(partialResults: Bundle?) {} // No partial command execution.
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: Exception) { cancel(); state("On-device speech could not start. Use text; no audio sent by BETA.") }
    }
    override fun cancel() { generation++; val r = recognizer; recognizer = null; try { r?.cancel() } catch (_: Exception) {}; try { r?.destroy() } catch (_: Exception) {} }
}
