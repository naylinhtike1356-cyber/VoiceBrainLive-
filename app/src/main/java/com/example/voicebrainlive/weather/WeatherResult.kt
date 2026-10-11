package com.example.voicebrainlive.weather

/**
 * ရာသီဥတု ရလဒ်ကို structured data အဖြစ် ပြန်ပေးသည့် data class
 * High priority fix: pipe-separated string ကို data class အဖြစ် ပြောင်းခြင်း
 */
data class WeatherResult(
    // Location information
    val locationName: String,
    val lat: Double,
    val lon: Double,
    
    // Current weather
    val currentTemperature: Int,           // °C
    val currentWeatherCode: Int,           // WMO code
    val currentDescription: String,        // Burmese description
    val humidity: Int,                     // %
    val windSpeed: Double,                 // km/h
    val precipitation: Double,             // mm
    
    // Daily forecast
    val minTemp: Int,                      // °C
    val maxTemp: Int,                      // °C
    val isRainExpected: Boolean,
    
    // Time-based info
    val forecastPeriod: ForecastPeriod = ForecastPeriod.TODAY,
    val hourlyRainTimes: List<HourlyForecasts> = emptyList(),
    val aqi: Int = 0,                      // US AQI
    val aqiDescription: String = ""
) {
    /**
     * TTS အတွက် သဘာဝကျကျ စာသားတည်ဆောက်ခြင်း
     */
    fun getNaturalSpeechString(): String {
        return buildString {
            val periodText = when (forecastPeriod) {
                ForecastPeriod.TODAY -> "ဒီနေ့"
                ForecastPeriod.TOMORROW -> "မနက်ဖြန်"
                ForecastPeriod.DAY_AFTER_TOMORROW -> "သဘက်ခါ"
                ForecastPeriod.WEEK -> "လာမယ့် တစ်ပတ်"
            }
            append("$periodText $locationName မှာ ရာသီဥတုက $currentDescription ဖြစ်ပါမယ်။ ")
            append("အပူချိန်က $currentTemperature ဒီဂရီလောက်ရှိမယ်၊ ")
            append("အမြင့်ဆုံး $maxTemp ဒီဂရီ၊ အနိမ့်ဆုံး $minTemp ဒီဂရီ ရှိနိုင်ပါတယ်။ ")
            
            if (isRainExpected) {
                append("မိုးလေး ရွာနိုင်တယ်။ ထီးလေး တစ်ခု ယူသွားလိုက်နော်။ ")
            } else {
                append("မိုးမရွာနိုင်ဘူး။ စိတ်ချလက်ချ အပြင်ထွက်လို့ ရတယ်။ ")
            }
            
            if (aqi > 0 && aqi <= 100) {
                append("လေထုအခြေအနေက $aqi ($aqiDescription) ဖြစ်ပြီး ကောင်းမွန်ပါတယ်။ ")
            }
        }
    }
}

/**
 * ရာသီဥတု ကြိုတင်ခန့်မှန်းချက် ကာလ
 */
enum class ForecastPeriod {
    TODAY,
    TOMORROW,
    DAY_AFTER_TOMORROW,
    WEEK
}

/**
 * တစ်နာရီချင်း ရာသီဥတု ခန့်မှန်းချက်
 */
data class HourlyForecasts(
    val hour: Int,           // 0-23
    val weatherCode: Int,
    val temperature: Int,
    val rainProbability: Int // 0-100 %
)