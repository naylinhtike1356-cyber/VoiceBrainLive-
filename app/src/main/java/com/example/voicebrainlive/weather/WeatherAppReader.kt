package com.example.voicebrainlive.weather

import android.content.Context
import android.util.Log

/**
 * Weather App Reader
 * Phone ထဲက Weather App (Samsung Weather, Google Weather, etc.) ကနေ
 * ရာသီဥတု အချက်အလက်ကို ဖတ်ယူပေးသည်။
 * 
 * လုပ်ဆောင်ချက်များ:
 * 1. Notification Listener မှတစ်ဆင့် Weather App notification ကို ဖတ်မည်
 * 2. Cache သိမ်းထားတဲ့ weather data ကို ပြန်ပေးမည်
 * 3. ရလာတဲ့ data ကို WeatherResult data class အဖြစ် ပြန်ပေးမည်
 * 
 * အရေးကြီးသည်: Notification Listener Service က Weather App notification ကို
 * ဖတ်ပြီး cache သိမ်းပေးသည်။ WeatherAppReader က ထို cache ကို ဖတ်ယူပေးသည်။
 */
class WeatherAppReader(private val context: Context) {
    
    companion object {
        private const val TAG = "WeatherAppReader"
        
        // SharedPreferences key for cached weather data
        private const val PREFS_NAME = "weather_app_cache"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MIN_TEMP = "min_temp"
        private const val KEY_MAX_TEMP = "max_temp"
        private const val KEY_WEATHER_CODE = "weather_code"
        private const val KEY_LOCATION = "location"
        private const val KEY_IS_RAIN = "is_rain"
        private const val KEY_HUMIDITY = "humidity"
        private const val KEY_WIND_SPEED = "wind_speed"
        private const val KEY_TIMESTAMP = "timestamp"
        
        // Cache validity duration (5 minutes)
        private const val CACHE_VALID_MS = 5 * 60 * 1000L
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    data class WeatherData(
        val temperature: Int,
        val minTemp: Int,
        val maxTemp: Int,
        val weatherCode: Int,
        val location: String,
        val isRainExpected: Boolean
    )

    /**
     * Notification Listener Service မှ ဖတ်ထားတဲ့ weather data ကို cache သိမ်းခြင်း
     */
    fun cacheWeatherData(data: WeatherData) {
        prefs.edit().apply {
            putInt(KEY_TEMPERATURE, data.temperature)
            putInt(KEY_MIN_TEMP, data.minTemp)
            putInt(KEY_MAX_TEMP, data.maxTemp)
            putInt(KEY_WEATHER_CODE, data.weatherCode)
            putString(KEY_LOCATION, data.location)
            putBoolean(KEY_IS_RAIN, data.isRainExpected)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Cached weather data: ${data.location} - ${data.temperature}°C")
    }
    
    /**
     * Weather App ထဲက cached weather data ကို ဖတ်ယူခြင်း
     * Notification မှတစ်ဆင့် ဖတ်ထားတဲ့ data ကို ပြန်ပေးသည်
     */
    fun getCachedWeatherData(): WeatherResult? {
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        
        // Cache expired?
        if (now - timestamp > CACHE_VALID_MS) {
            Log.d(TAG, "Weather cache expired (${(now - timestamp) / 1000}s ago)")
            return null
        }
        
        val location = prefs.getString(KEY_LOCATION, null) ?: return null
        val temperature = prefs.getInt(KEY_TEMPERATURE, 0)
        val minTemp = prefs.getInt(KEY_MIN_TEMP, 0)
        val maxTemp = prefs.getInt(KEY_MAX_TEMP, 0)
        val weatherCode = prefs.getInt(KEY_WEATHER_CODE, 0)
        val isRain = prefs.getBoolean(KEY_IS_RAIN, false)
        val humidity = prefs.getInt(KEY_HUMIDITY, 0)
        val windSpeed = prefs.getLong(KEY_WIND_SPEED, 0.0.toLong()).toDouble()
        
        Log.d(TAG, "Returning cached weather: $location - ${temperature}°C, code=$weatherCode")
        
        return WeatherResult(
            locationName = location,
            lat = 0.0,
            lon = 0.0,
            currentTemperature = temperature,
            currentWeatherCode = weatherCode,
            currentDescription = weatherCodeToDesc(weatherCode),
            humidity = humidity,
            windSpeed = windSpeed,
            precipitation = 0.0,
            minTemp = minTemp,
            maxTemp = maxTemp,
            isRainExpected = isRain,
            forecastPeriod = ForecastPeriod.TODAY
        )
    }
    
    /**
     * Weather App ရဲ့ notification ကို တိုက်ရိုက် စစ်ဆေးခြင်း
     * Note: ဤ method သည် Notification Listener Service မှတစ်ဆင့်သာ အလုပ်လုပ်သည်
     * Regular app အနေနဲ့ active notifications ကို ဖတ်လို့မရပါဘူး
     */
    fun checkWeatherNotifications(): WeatherResult? {
        // This method is deprecated for direct use
        // Use getCachedWeatherData() instead which reads from cache
        Log.d(TAG, "checkWeatherNotifications() is deprecated. Use getCachedWeatherData() instead.")
        return getCachedWeatherData()
    }
    
    /**
     * Clear cached weather data
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Weather cache cleared")
    }

    private fun weatherCodeToDesc(code: Int): String = when (code) {
        0 -> "အပူပြင်း ရှင်းလင်း"
        in 1..3 -> "တိမ်အနည်းငယ်ရှိ"
        in 45..48 -> "မြူထူ"
        in 51..67 -> "မိုးရွာ"
        in 71..77 -> "နှင်းကျ"
        in 80..82 -> "မိုးသီးသန့်"
        in 85..86 -> "နှင်းသီးသန့်"
        95 -> "မိုးကြိုးမုန်တိုင်း"
        in 96..99 -> "မိုးကြိုးမုန်တိုင်း နဲ့ မြေပဲဆန်"
        else -> "တိမ်များ"
    }
}