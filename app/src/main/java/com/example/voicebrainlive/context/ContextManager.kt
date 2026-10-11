package com.example.voicebrainlive.context

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.voicebrainlive.SettingsActivity
import java.util.Calendar

/**
 * Context Manager - Provides context-aware information to enhance AI responses
 * 
 * Gathers and provides information about:
 * - Current time of day (morning, afternoon, evening, night)
 * - User's current location (home, office, other)
 * - Current weather conditions
 * - User's routines and patterns
 * 
 * This context is used to make AI responses more relevant and proactive.
 * 
 * Usage:
 * ```
 * val contextManager = ContextManager(context)
 * val contextInfo = contextManager.getCurrentContext()
 * // Use contextInfo to enhance AI prompt
 * ```
 */
class ContextManager(private val context: Context) {

    companion object {
        private const val TAG = "ContextManager"
    }

    /**
     * Get current time-based context
     */
    fun getTimeContext(): TimeContext {
        val cal = Calendar.getInstance(SettingsActivity.getTimezone(context))
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        
        return TimeContext(
            hour = hour,
            period = when (hour) {
                in 5..11 -> TimePeriod.MORNING
                in 12..16 -> TimePeriod.AFTERNOON
                in 17..20 -> TimePeriod.EVENING
                else -> TimePeriod.NIGHT
            },
            isWeekend = cal.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            dayOfWeek = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale("my"))
        )
    }

    /**
     * Get location-based context
     */
    fun getLocationContext(): LocationContext {
        val homeLocation = com.example.voicebrainlive.location.LocationTriggerStorage.getHomeLocation(context)
        val officeLocation = com.example.voicebrainlive.location.LocationTriggerStorage.getOfficeLocation(context)
        val officeWifi = com.example.voicebrainlive.location.LocationTriggerStorage.getOfficeSsid(context)
        
        return LocationContext(
            homeLocation = homeLocation?.let { Pair(it.lat, it.lng) },
            officeLocation = officeLocation?.let { Pair(it.lat, it.lng) },
            officeWifi = officeWifi,
            currentLocation = getCurrentLocationType(
                homeLocation?.let { Pair(it.lat, it.lng) },
                officeLocation?.let { Pair(it.lat, it.lng) },
                officeWifi
            )
        )
    }

    /**
     * Get weather-based context
     */
    fun getWeatherContext(): WeatherContext {
        val weatherData = com.example.voicebrainlive.weather.WeatherAppReader(context).getCachedWeatherData()
        
        return WeatherContext(
            isRainExpected = weatherData?.isRainExpected ?: false,
            temperature = weatherData?.currentTemperature,
            weatherCode = weatherData?.currentWeatherCode,
            location = weatherData?.locationName
        )
    }

    /**
     * Get user behavior context
     */
    fun getBehaviorContext(): BehaviorContext {
        val sessionCount = com.example.voicebrainlive.memory.BehaviorMemory.getSessionCount(context)
        val frequentContact = com.example.voicebrainlive.memory.BehaviorMemory.getMostFrequentContact(context)
        val lastMusic = com.example.voicebrainlive.memory.BehaviorMemory.getLastMusic(context)
        val lastCalled = com.example.voicebrainlive.memory.BehaviorMemory.getLastCalledContact(context)
        
        return BehaviorContext(
            sessionCount = sessionCount,
            familiarityLevel = getFamiliarityLevel(sessionCount),
            frequentContact = frequentContact,
            lastMusic = lastMusic,
            lastCalled = lastCalled
        )
    }

    /**
     * Get combined context string for AI prompt enhancement
     */
    fun getContextPrompt(): String {
        val timeContext = getTimeContext()
        val locationContext = getLocationContext()
        val weatherContext = getWeatherContext()
        val behaviorContext = getBehaviorContext()
        
        return buildString {
            // Time context
            append("\n[Current Time Context]\n")
            append("- Time: ${timeContext.hour}:00 (${timeContext.period.name.lowercase()})\n")
            append("- Day: ${timeContext.dayOfWeek}\n")
            if (timeContext.isWeekend) append("- It's the weekend\n")
            
            // Location context
            append("\n[Location Context]\n")
            val locationType = locationContext.currentLocation
            append("- User is at: ${locationType.displayName}\n")
            if (locationType != LocationType.UNKNOWN) {
                append("- This is their ${locationType.displayName.lowercase()}\n")
            }
            
            // Weather context
            append("\n[Weather Context]\n")
            if (weatherContext.isRainExpected) {
                append("- It might rain today. Consider reminding user to bring an umbrella.\n")
            }
            if (weatherContext.temperature != null) {
                val temp = weatherContext.temperature
                when {
                    temp > 30 -> append("- It's hot (${temp}°C). Suggest staying cool.\n")
                    temp < 10 -> append("- It's cold (${temp}°C). Suggest wearing warm clothes.\n")
                    else -> append("- Temperature is ${temp}°C.\n")
                }
            }
            
            // Behavior context
            append("\n[User Relationship Context]\n")
            when (behaviorContext.familiarityLevel) {
                FamiliarityLevel.NEW -> append("- New user. Be helpful and welcoming.\n")
                FamiliarityLevel.LEARNING -> append("- User has used assistant ${behaviorContext.sessionCount} times. Still learning preferences.\n")
                FamiliarityLevel.FAMILIAR -> append("- Familiar user (${behaviorContext.sessionCount}+ sessions). Be more casual and proactive.\n")
                FamiliarityLevel.CLOSE -> append("- Close relationship with user (${behaviorContext.sessionCount}+ sessions). Be warm, casual, and very proactive.\n")
            }
            
            if (behaviorContext.frequentContact != null) {
                append("- User frequently calls: ${behaviorContext.frequentContact}\n")
            }
            if (behaviorContext.lastCalled != null) {
                append("- Last called: ${behaviorContext.lastCalled}\n")
            }
            if (behaviorContext.lastMusic != null) {
                append("- Last played music: ${behaviorContext.lastMusic}\n")
            }
            
            append("[End Context]\n")
        }
    }

    private fun getCurrentLocationType(
        homeLocation: Pair<Double, Double>?,
        officeLocation: Pair<Double, Double>?,
        officeWifi: String?
    ): LocationType {
        // Check WiFi first (more accurate for indoor)
        val currentWifi = getCurrentWifiSsid()
        if (officeWifi != null && currentWifi == officeWifi) {
            return LocationType.OFFICE
        }
        
        // For GPS-based location, we would need current location
        // This is simplified - in production, compare current GPS to home/office
        return LocationType.UNKNOWN
    }

    private fun getCurrentWifiSsid(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = cm?.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return null
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val info = wifi?.connectionInfo ?: return null
            @Suppress("DEPRECATION")
            val raw = info.ssid ?: return null
            if (raw == "<unknown ssid>") return null
            raw.trim().removeSurrounding("\"").trim()
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentWifiSsid: ${e.message}")
            null
        }
    }

    private fun getFamiliarityLevel(sessionCount: Int): FamiliarityLevel {
        return when {
            sessionCount < 5 -> FamiliarityLevel.NEW
            sessionCount < 20 -> FamiliarityLevel.LEARNING
            sessionCount < 100 -> FamiliarityLevel.FAMILIAR
            else -> FamiliarityLevel.CLOSE
        }
    }

    /**
     * Time period of the day
     */
    enum class TimePeriod {
        MORNING, AFTERNOON, EVENING, NIGHT
    }

    /**
     * Time context data
     */
    data class TimeContext(
        val hour: Int,
        val period: TimePeriod,
        val isWeekend: Boolean,
        val dayOfWeek: String?
    )

    /**
     * Location type
     */
    enum class LocationType(val displayName: String) {
        HOME("Home"),
        OFFICE("Office"),
        OTHER("Somewhere else"),
        UNKNOWN("Unknown location")
    }

    /**
     * Location context data
     */
    data class LocationContext(
        val homeLocation: Pair<Double, Double>?,
        val officeLocation: Pair<Double, Double>?,
        val officeWifi: String?,
        val currentLocation: LocationType
    )

    /**
     * Weather context data
     */
    data class WeatherContext(
        val isRainExpected: Boolean,
        val temperature: Int?,
        val weatherCode: Int?,
        val location: String?
    )

    /**
     * Behavior familiarity level
     */
    enum class FamiliarityLevel {
        NEW, LEARNING, FAMILIAR, CLOSE
    }

    /**
     * Behavior context data
     */
    data class BehaviorContext(
        val sessionCount: Int,
        val familiarityLevel: FamiliarityLevel,
        val frequentContact: String?,
        val lastMusic: String?,
        val lastCalled: String?
    )
}