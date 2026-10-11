package com.example.voicebrainlive.weather

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import java.util.Locale

/**
 * Speaks weather in a short, natural style.
 */
class NaturalWeatherSpeaker(context: Context) {

    private val appContext: Context = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var ttsUnified: TtsUnifiedInterface? = null

    init {
        initializeTts()
        ttsUnified = TtsUnifiedInterface(appContext)
    }

    private fun initializeTts() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("my")
                tts?.setPitch(1.05f)
                tts?.setSpeechRate(0.9f)
                isInitialized = true
                Log.d(TAG, "TTS initialized for weather")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun speakWeather(
        temperature: Int,
        minTemp: Int,
        maxTemp: Int,
        weatherCode: Int,
        location: String,
        isRainExpected: Boolean,
        humidity: Int = 0,
        windSpeed: Double = 0.0,
        rainSummary: String = ""
    ) {
        val text = WeatherNarrative.current(
            location = location,
            temperature = temperature,
            minTemp = minTemp,
            maxTemp = maxTemp,
            weatherCode = weatherCode,
            isRainExpected = isRainExpected,
            humidity = humidity,
            windSpeed = windSpeed,
            rainSummary = rainSummary
        )
        speak(text)
        Log.d(TAG, "Speaking weather: $text")
    }

    fun speakSimple(text: String) {
        speak(text)
    }

    fun speakWeatherSummary(
        location: String,
        weatherType: WeatherType,
        temperature: Int,
        advice: String? = null
    ) {
        val code = when (weatherType) {
            WeatherType.SUNNY -> 0
            WeatherType.CLOUDY -> 2
            WeatherType.RAINY -> 61
            WeatherType.THUNDERSTORM -> 95
            WeatherType.SNOWY -> 71
            WeatherType.FOGGY -> 45
            WeatherType.WINDY -> 3
        }
        val text = WeatherNarrative.current(
            location = location,
            temperature = temperature,
            minTemp = temperature,
            maxTemp = temperature,
            weatherCode = code,
            isRainExpected = weatherType == WeatherType.RAINY || weatherType == WeatherType.THUNDERSTORM,
            humidity = 0,
            windSpeed = if (weatherType == WeatherType.WINDY) 28.0 else 0.0,
            rainSummary = advice.orEmpty()
        )
        speak(text)
        Log.d(TAG, "Speaking weather summary: $text")
    }

    private fun speak(text: String) {
        if (text.isBlank()) return

        // Track weather speaking duration in TtsUnifiedInterface so silence timeout does not close mid-speech
        com.example.voicebrainlive.tts.TtsUnifiedInterface.externalTtsSpeakingUntil = 
            System.currentTimeMillis() + 12000L

        if (ttsUnified?.isAvailable() == true) {
            ttsUnified?.speakWeather(text)
            return
        }

        if (!isInitialized) {
            Thread.sleep(300)
        }
        tts?.let {
            if (it.isSpeaking) it.stop()
            it.speak(text, TextToSpeech.QUEUE_FLUSH, null, "weather_speak")
        }
    }

    fun shutdown() {
        ttsUnified?.shutdown()
        ttsUnified = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "NaturalWeatherSpeaker"
    }
}
