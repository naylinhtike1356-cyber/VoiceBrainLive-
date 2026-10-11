package com.example.voicebrainlive.commands.handlers

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.ProfileManager
import com.example.voicebrainlive.ReminderHelper
import com.example.voicebrainlive.ReminderReceiver
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.memory.NoteRepository
import com.example.voicebrainlive.notion.NotionClient
import com.example.voicebrainlive.routines.RoutineScheduler
import com.example.voicebrainlive.routines.RoutineStorage
import com.example.voicebrainlive.routines.AutomationApprovalQueue

import com.example.voicebrainlive.weather.WeatherAppReader
import com.example.voicebrainlive.weather.WeatherNarrative
import com.example.voicebrainlive.weather.WeatherResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Utility Handler - Handles utility-related commands
 * - get_weather, weather, get_current_time, get_time, get_current_date, get_date
 * - get_battery_status, get_wifi_status, get_bluetooth_status
 * - turn_on_wifi, turn_off_wifi, turn_on_bluetooth, turn_off_bluetooth
 * - turn_on_flashlight, turn_off_flashlight, toggle_flashlight
 * - set_alarm, set_timer, set_reminder, list_reminders
 * - add_scheduled_routine, list_routines, delete_scheduled_routine
 * - save_note, read_notes, delete_note, clear_notes
 * - send_to_notion, send_last_note_to_notion, test_notion
 * - set_listening_timeout, screenshot, read_action_logs
 * 
 * This handler extracts utility-related functionality from PhoneCommandExecutor
 * to improve code maintainability and reduce file size.
 */
class UtilityHandler(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "UtilityHandler"
        private const val WEATHER_TAG = "Weather"
    }

    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val httpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    private val notionClient by lazy { NotionClient(context) }
    private val notionRepository by lazy { com.example.voicebrainlive.notion.NotionRepository(context) }
    private val noteRepository by lazy { NoteRepository(context) }
    private val reminderPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("voice_reminders", Context.MODE_PRIVATE)
    }
    private val weatherAppReader by lazy { WeatherAppReader(context) }
    private val profileManager by lazy { ProfileManager(context) }
    private var isFlashlightOn = false

    suspend fun execute(commandType: String, target: String?, value: String?): String {
        return withContext(Dispatchers.IO) {
            executeInternal(commandType, target, value)
        }
    }

    private suspend fun executeInternal(commandType: String, target: String?, value: String?): String {
        return when (commandType.lowercase()) {
            // Weather
            "get_weather", "weather" -> getWeather(target, value)
            
            // Time & Date
            "get_current_time", "get_time", "get_current_date", "get_date" -> getTimeAndDate(value)
            
            // Status
            "get_battery_status" -> getBatteryStatus()
            "get_wifi_status" -> getWifiStatus()
            "get_bluetooth_status" -> getBluetoothStatus()
            
            // Device Controls
            "turn_on_wifi", "wifi_on" -> turnOnWifi()
            "turn_off_wifi", "wifi_off" -> turnOffWifi()
            "turn_on_bluetooth", "bluetooth_on" -> turnOnBluetooth()
            "turn_off_bluetooth", "bluetooth_off" -> turnOffBluetooth()
            "turn_on_flashlight", "flashlight_on" -> turnOnFlashlight()
            "turn_off_flashlight", "flashlight_off" -> turnOffFlashlight()
            "toggle_flashlight" -> toggleFlashlight()
            
            // Alarm & Timer
            "set_alarm" -> setAlarm(target, value)
            "dismiss_alarm", "cancel_alarm", "clear_alarm" -> dismissAlarm(target, value)
            "set_timer" -> setTimer(value?.toIntOrNull() ?: 5)
            "set_reminder" -> setReminder(target ?: "", value)
            "list_reminders", "get_reminders", "get_pending_reminders" -> listPendingReminders()
            
            // Routines
            "add_scheduled_routine", "add_routine" -> addScheduledRoutine(target ?: "", value ?: "")
            "list_routines", "get_routines", "list_scheduled_routines" -> listScheduledRoutines()
            "delete_scheduled_routine", "delete_routine", "remove_routine" -> deleteScheduledRoutine(target ?: "")
            
            // Notes
            "save_note", "add_note" -> saveNote(target ?: "", value)
            "read_notes", "list_notes", "show_notes" -> readNotes()
            "delete_note" -> deleteNote(target ?: "")
            "clear_notes", "delete_all_notes" -> clearAllNotes()
            
            // Notion
            "send_to_notion", "sync_notion", "notion_sync" -> sendToNotion(target, value)
            "send_last_note_to_notion" -> sendLastNoteToNotion(value)
            "test_notion" -> testNotionConnection()
            "search_notion", "notion_search", "query_notion" -> notionRepository.searchAndDiscussNotes(target ?: value ?: "")
            "append_notion_note", "update_notion_note", "notion_append" -> notionRepository.appendToNoteByTitle(target ?: "", value ?: "")
            "delete_notion_note", "archive_notion_note", "notion_delete" -> notionRepository.deleteNoteByTitle(target ?: value ?: "")
            
            // Memory & Facts Management
            "save_preference", "remember_fact", "add_memory", "remember" -> saveFactOrPreference(target, value)
            "get_remembered_facts", "list_memories", "read_memories", "get_memories" -> getRememberedFacts()
            "search_memory", "search_facts", "query_memory" -> searchMemories(target ?: value ?: "")
            "forget_memory", "delete_fact", "forget_fact", "remove_memory" -> forgetMemory(target ?: value ?: "")

            // Misc
            "set_listening_timeout" -> setListeningTimeout(value?.toIntOrNull() ?: 10)
            "screenshot" -> "Screenshot မရသေးပါ။ ပါဝါ+အသံလျော့ နှိပ်ပါ။"
            "read_action_logs" -> com.example.voicebrainlive.ActionRecordManager.getLogs(context, limit = 50)
            "list_pending_approvals", "pending_approvals", "list_automation_approvals" -> listPendingApprovals()
            "dismiss_approval", "dismiss_pending_approval" -> dismissPendingApproval(target ?: value ?: "")
            "switch_profile" -> switchProfile(target ?: value ?: "")

            else -> "Unknown utility command: $commandType"
        }
    }

    private fun saveFactOrPreference(target: String?, value: String?): String {
        val fullText = when {
            !target.isNullOrBlank() && !value.isNullOrBlank() -> "$target: $value"
            !value.isNullOrBlank() -> value
            !target.isNullOrBlank() -> target
            else -> return "မှတ်သားရန် အချက်အလက် မပါရှိပါ။"
        }
        val parts = fullText.split(":", limit = 2)
        val key = if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() else "user_fact"
        val content = if (parts.size == 2) parts[1].trim() else fullText.trim()

        com.example.voicebrainlive.memory.PersistentFactsStorage.addOrUpdateFact(context, key, content)
        return "သင့်အကြောင်း '$key: $content' ကို မန်မိုရီထဲတွင် အောင်မြင်စွာ မှတ်ထားလိုက်ပါပြီ။"
    }

    private fun getRememberedFacts(): String {
        val facts = com.example.voicebrainlive.memory.PersistentFactsStorage.getAllFacts(context)
        if (facts.isEmpty()) {
            return "လောလောဆယ် မန်မိုရီထဲတွင် မှတ်ထားသော အချက်အလက်များ မရှိသေးပါ။"
        }
        val sb = java.lang.StringBuilder("မန်မိုရီထဲတွင် မှတ်ထားသော အချက်အလက်များ:\n")
        facts.take(20).forEachIndexed { index, (key, value) ->
            sb.append("${index + 1}. $key: $value\n")
        }
        return sb.toString().trim()
    }

    private fun searchMemories(query: String): String {
        if (query.isBlank()) return getRememberedFacts()
        val facts = com.example.voicebrainlive.memory.PersistentFactsStorage.searchFacts(context, query)
        if (facts.isEmpty()) {
            return "'$query' နှင့် ပတ်သက်၍ မန်မိုရီထဲတွင် မှတ်ထားသော အချက်အလက် ရှာမတွေ့ပါ။"
        }
        val sb = java.lang.StringBuilder("'$query' နှင့် ပတ်သက်သော မန်မိုရီ မှတ်တမ်းများ:\n")
        facts.take(15).forEachIndexed { index, (key, value) ->
            sb.append("${index + 1}. $key: $value\n")
        }
        return sb.toString().trim()
    }

        private fun listPendingApprovals(): String {
        val items = AutomationApprovalQueue.list(context)
        if (items.isEmpty()) return "အတည်ပြုရန် စောင့်နေသော automation မရှိပါ။"
        return items.mapIndexed { index, item ->
            "${index + 1}. ${AutomationApprovalQueue.preview(item)} id=${item.id.take(8)}"
        }.joinToString("\n")
    }

    private fun dismissPendingApproval(id: String): String {
        if (id.isBlank()) return "ဖျက်လိုသော approval ID ကို ပြောပေးပါ။"
        val item = AutomationApprovalQueue.list(context).firstOrNull { it.id == id || it.id.startsWith(id) }
            ?: return "ထို approval မတွေ့ပါ သို့မဟုတ် သက်တမ်းကုန်ပြီးပါပြီ။"
        return if (AutomationApprovalQueue.remove(context, item.id)) {
            "Pending automation ကို ဖယ်ရှားလိုက်ပါပြီ။"
        } else {
            "Pending automation ကို ဖယ်ရှား၍ မရပါ။"
        }
    }

    private fun forgetMemory(query: String): String {

        if (query.isBlank()) return "မေ့ပစ်လိုသော အချက်အလက် အမည်ကို ပြောပေးပါ။"
        val ok = com.example.voicebrainlive.memory.PersistentFactsStorage.deleteFact(context, query)
        return if (ok) {
            "မန်မိုရီထဲမှ '$query' ဆိုင်ရာ အချက်အလက်ကို အောင်မြင်စွာ ဖျက်ပစ်/မေ့လိုက်ပါပြီ။"
        } else {
            "မန်မိုရီထဲတွင် '$query' နှင့် ကိုက်ညီသော အချက်အလက် ရှာမတွေ့ပါ။"
        }
    }

    // ===== WEATHER =====
    private fun getWeather(cityOrLocation: String?, period: String? = null): String {
        AppLog.d(WEATHER_TAG, "getWeather start | cityOrLocation=$cityOrLocation | period=$period")
        
        // Check if user is asking for current weather (not forecast)
        val p = (period ?: "today").lowercase()
        val isCurrentWeather = p !in listOf(
            "tomorrow", "မနက်ဖြန်", 
            "day after tomorrow", "သဘက်ခါ",
            "week", "7 day", "တပတ်စာ"
        )
        
        // Try to get from Weather App first (only for current weather)
        if (isCurrentWeather && cityOrLocation.isNullOrBlank()) {
            val cachedWeather = weatherAppReader.getCachedWeatherData()
            if (cachedWeather != null) {
                AppLog.d(WEATHER_TAG, "Using cached weather data from Weather App")
                val w = cachedWeather
                return buildString {
                    append("WEATHER_DATA|")
                    append("${w.locationName}|")
                    append("${w.currentTemperature}|")
                    append("${w.minTemp}|")
                    append("${w.maxTemp}|")
                    append("${w.currentWeatherCode}|")
                    append("${w.isRainExpected}|")
                    append("${w.humidity}|")
                    append("${w.windSpeed}|")
                    append("${w.currentDescription}|")
                    append(WeatherNarrative.summarizeRainWindowsFromHourly(w.hourlyRainTimes, "ဒီနေ့"))
                }
            }
            Log.d(WEATHER_TAG, "No cached weather data, will fetch from API")
        }
        
        val locationData: Triple<Double, Double, String>
        
        if (cityOrLocation.isNullOrBlank()) {
            // Use GPS - simplified for handler
            locationData = Triple(37.57, 126.98, "Seoul (fallback)")
        } else {
            locationData = getLocationFromCity(cityOrLocation)
        }
        
        val lat = locationData.first
        val lon = locationData.second
        val locationName = locationData.third
        val timezone = getTimezoneForLocation(lat, lon)
        
        // Check for specific time queries like "မနက် ၉ နာရီ", "ညနေ ၃ နာရီ"
        val specificHour = extractSpecificHour(p)
        
        val url = when {
            p.contains("day after tomorrow") || p.contains("သဘက်ခါ") ->
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min&hourly=weather_code,temperature_2m,precipitation_probability&timezone=$timezone&forecast_days=3"
            p.contains("tomorrow") || p.contains("မနက်ဖြန်") -> 
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min&hourly=weather_code,temperature_2m,precipitation_probability&timezone=$timezone&forecast_days=2"
            p.contains("week") || p.contains("7 day") || p.contains("တပတ်စာ") -> 
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=$timezone&forecast_days=7"
            else ->
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,precipitation&hourly=weather_code,precipitation_probability,temperature_2m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=$timezone&forecast_days=1"
        }
        
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            
            when {
                !response.isSuccessful -> "ရာသီဥတု မရပါ။ ဆာဗာ အမှား (${response.code})။"
                response.body == null -> "ရာသီဥတု မရပါ။ ဒေတာ မရှိပါ။"
                else -> {
                    val body = response.body!!.string()
                    val json = JSONObject(body)
                    val pType = (period ?: "today").lowercase()
                    
                    // Handle specific hour query
                    if (specificHour != null) {
                        return getWeatherForSpecificHour(json, locationName, specificHour, pType)
                    }
                    
                    if (pType.contains("day after tomorrow") || pType.contains("သဘက်ခါ")) {
                        val daily = json.optJSONObject("daily") ?: return "သဘက်ခါအတွက် ဒေတာ မရှိပါ။"
                        val maxArr = daily.optJSONArray("temperature_2m_max")
                        val minArr = daily.optJSONArray("temperature_2m_min")
                        val codeArr = daily.optJSONArray("weather_code")
                        if (minArr == null || minArr.length() < 3) return "Weather data unavailable."
                        if (maxArr == null || maxArr.length() < 3) return "သဘက်ခါအတွက် အသေးစိတ် မသိရပါ။"
                        val tMax = maxArr.optDouble(2, 0.0)
                        val tMin = minArr?.optDouble(2, 0.0) ?: 0.0
                        val code = codeArr?.optInt(2, 0) ?: 0
                        val rainTiming = getRainWindowInfo(json, dayOffset = 48, dayLabel = "သဘက်ခါ")
                        return WeatherNarrative.dayForecast(
                            location = locationName,
                            dayLabel = "သဘက်ခါ",
                            minTemp = tMin.toInt(),
                            maxTemp = tMax.toInt(),
                            weatherCode = code,
                            rainSummary = rainTiming
                        )
                        "$locationName မှာ သဘက်ခါ ရာသီဥတုက ${weatherCodeToDesc(code)} ဖြစ်ပြီး၊ အမြင့်ဆုံး ${tMax.toInt()}°C နဲ့ အနိမ့်ဆုံး ${tMin.toInt()}°C ရှိပါမယ်။"
                    } else if (pType.contains("tomorrow") || pType.contains("မနက်ဖြန်")) {
                        val daily = json.optJSONObject("daily") ?: return "မနက်ဖြန်အတွက် ဒေတာ မရှိပါ။"
                        val maxArr = daily.optJSONArray("temperature_2m_max")
                        val minArr = daily.optJSONArray("temperature_2m_min")
                        val codeArr = daily.optJSONArray("weather_code")
                        if (maxArr == null || maxArr.length() < 2) return "မနက်ဖြန်အတွက် အသေးစိတ် မသိရပါ။"
                        val tMax = maxArr.optDouble(1, 0.0)
                        val tMin = minArr?.optDouble(1, 0.0) ?: 0.0
                        val code = codeArr?.optInt(1, 0) ?: 0
                        val rainTiming = getRainWindowInfo(json, dayOffset = 24, dayLabel = "မနက်ဖြန်")
                        return WeatherNarrative.dayForecast(
                            location = locationName,
                            dayLabel = "မနက်ဖြန်",
                            minTemp = tMin.toInt(),
                            maxTemp = tMax.toInt(),
                            weatherCode = code,
                            rainSummary = rainTiming
                        )

                        "$locationName မှာ မနက်ဖြန် ရာသီဥတုက ${weatherCodeToDesc(code)} ဖြစ်ပြီး၊ အမြင့်ဆုံး ${tMax.toInt()}°C နဲ့ အနိမ့်ဆုံး ${tMin.toInt()}°C ရှိပါမယ်။$rainTiming"
                    } else if (pType.contains("week") || pType.contains("တပတ်စာ")) {
                        val daily = json.optJSONObject("daily") ?: return "တစ်ပတ်စာ ဒေတာ မရှိပါ။"
                        val codeArr = daily.optJSONArray("weather_code") ?: return "ဒေတာ မပြည့်စုံပါ။"
                        val maxArr = daily.optJSONArray("temperature_2m_max")
                        val minArr = daily.optJSONArray("temperature_2m_min")
                        val times = daily.optJSONArray("time")
                        val sb = StringBuilder("$locationName မှာ နောက်တစ်ပတ်စာ ရာသီဥတု ပြောပြပါမယ်။ ")
                        val count = codeArr.length().coerceAtMost(7)
                        for (i in 0 until count) {
                            val dayName = when(i) {
                                0 -> "ဒီနေ့"; 1 -> "မနက်ဖြန်"; 2 -> "သဘက်ခါ"
                                else -> times?.optString(i)?.split("-")?.lastOrNull()?.let { "$it ရက်နေ့" } ?: "နောက် ${i} ရက်နေ့"
                            }
                            sb.append("$dayName မှာ ${weatherCodeToDesc(codeArr.optInt(i, 0))}၊ ${minArr?.optDouble(i, 0.0)?.toInt()} မှ ${maxArr?.optDouble(i, 0.0)?.toInt()}°C။ ")
                        }
                        sb.toString()
                    } else {
                        val current = json.optJSONObject("current") ?: return "ရာသီဥတု မရပါ။"
                        val tempC = current.optDouble("temperature_2m", 0.0)
                        val humidity = current.optInt("relative_humidity_2m", 0)
                        val wind = current.optDouble("wind_speed_10m", 0.0)
                        val desc = weatherCodeToDesc(current.optInt("weather_code", 0))
                        val minTemp = json.optJSONObject("daily")?.optJSONArray("temperature_2m_min")?.optDouble(0, 0.0)?.toInt() ?: 0
                        val maxTemp = json.optJSONObject("daily")?.optJSONArray("temperature_2m_max")?.optDouble(0, 0.0)?.toInt() ?: 0
                        val weatherCode = current.optInt("weather_code", 0)
                        val isRainExpected = (weatherCode in 51..67 || weatherCode in 80..82 || weatherCode in 95..99)
                        val rainSummary = getRainWindowInfo(json, dayOffset = 0, dayLabel = "ဒီနေ့")
                        
                        // Return structured data for NaturalWeatherSpeaker
                        buildString {
                            append("WEATHER_DATA|")
                            append("$locationName|")
                            append("${tempC.toInt()}|")
                            append("$minTemp|")
                            append("$maxTemp|")
                            append("$weatherCode|")
                            append("$isRainExpected|")
                            append("$humidity|")
                            append("$wind|")
                            append("$desc|")
                            append(rainSummary)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(WEATHER_TAG, "Weather error: ${e.message}", e)
            "ရာသီဥတု မရပါ။ (${e.javaClass.simpleName}: ${e.message})"
        }
    }
    
    /**
     * Extract specific hour from query like "မနက် ၉ နာရီ", "ညနေ ၃ နာရီ"
     */
    private fun extractSpecificHour(query: String): Int? {
        val normalized = myanmarToArabic(query)
        
        // Match patterns like "9 နာရီ", "၉ နာရီ", "9:00", "09:00"
        val hourMatch = Regex("(\\d{1,2})\\s*နာရီ").find(normalized)
        if (hourMatch != null) {
            var hour = hourMatch.groupValues[1].toIntOrNull() ?: return null
            
            // Adjust for AM/PM context
            val isPM = normalized.contains("ညနေ") || normalized.contains("pm") || normalized.contains("နေ့လယ်")
            val isAM = normalized.contains("မနက်") || normalized.contains("နံနက်") || normalized.contains("am")
            
            when {
                isPM && hour in 1..11 -> hour += 12
                isAM && hour == 12 -> hour = 0
                !isAM && !isPM && hour in 1..6 -> hour += 12 // Default to PM for 1-6
            }
            
            return hour.coerceIn(0, 23)
        }
        
        // Match time format like "9:00", "09:30"
        val timeMatch = Regex("(\\d{1,2}):(\\d{1,2})").find(normalized)
        if (timeMatch != null) {
            return timeMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 23)
        }
        
        return null
    }
    
    /**
     * Get weather for a specific hour from forecast data
     */
    private fun getWeatherForSpecificHour(json: JSONObject, locationName: String, hour: Int, period: String): String {
        val hourly = json.optJSONObject("hourly") ?: return "အချိန်အတွက် ဒေတာ မရှိပါ။"
        val times = hourly.optJSONArray("time") ?: return "အချိန် ဒေတာ မရှိပါ။"
        val tempArr = hourly.optJSONArray("temperature_2m")
        val codeArr = hourly.optJSONArray("weather_code")
        val rainArr = hourly.optJSONArray("precipitation_probability")
        
        // Find the index for the requested hour
        // For tomorrow, we need to offset by 24 hours
        val dayOffset = when {
            period.contains("tomorrow") || period.contains("မနက်ဖြန်") -> 24
            period.contains("day after") || period.contains("သဘက်ခါ") -> 48
            else -> 0
        }
        
        for (i in 0 until times.length()) {
            val timeStr = times.optString(i, "")
            // Parse time like "2024-01-15T09:00"
            val hourPart = timeStr.split("T").getOrNull(1)?.split(":")?.getOrNull(0)?.toIntOrNull()
            if (hourPart == hour && i >= dayOffset && i < dayOffset + 24) {
                val temp = tempArr?.optDouble(i, 0.0)?.toInt() ?: 0
                val code = codeArr?.optInt(i, 0) ?: 0
                val rainProb = rainArr?.optInt(i, 0) ?: 0
                return WeatherNarrative.specificHour(
                    location = locationName,
                    hour = hour,
                    temperature = temp,
                    weatherCode = code,
                    rainProbability = rainProb
                )
                
                val hourName = when (hour) {
                    in 5..11 -> "မနက် $hour နာရီ"
                    12 -> "နေ့လယ် ၁၂ နာရီ"
                    in 13..17 -> "ညနေ ${hour - 12} နာရီ"
                    in 18..23 -> "ည ${hour - 12} နာရီ"
                    else -> "မနက် ${if (hour == 0) 12 else hour} နာရီ"
                }
                
                val rainInfo = if (rainProb > 0) " မိုးရွာနိုင်ခြေ ${rainProb}% ရှိတယ်။" else ""
                
                return "$locationName မှာ $hourName မှာ ရာသီဥတုက ${weatherCodeToDesc(code)} ဖြစ်ပြီး၊ အပူချိန် ${temp}°C လောက် ရှိပါမယ်။$rainInfo"
            }
        }
        
        return "$locationName မှာ $hour နာရီအတွက် ဒေတာ မရှိပါ။"
    }
    
    /**
     * Get rain timing info for tomorrow
     */
    private fun getRainWindowInfo(json: JSONObject, dayOffset: Int, dayLabel: String): String {
        val hourly = json.optJSONObject("hourly") ?: return ""
        val rainArr = hourly.optJSONArray("precipitation_probability") ?: return ""
        val times = hourly.optJSONArray("time") ?: return ""

        val hours = mutableListOf<Pair<Int, Int>>()
        val start = dayOffset.coerceAtLeast(0)
        val end = minOf(dayOffset + 24, times.length(), rainArr.length())
        for (i in start until end) {
            val rainProb = rainArr.optInt(i, 0)
            if (rainProb >= 55) {
                val hour = times.optString(i, "")
                    .split("T").getOrNull(1)
                    ?.split(":")?.getOrNull(0)
                    ?.toIntOrNull()
                if (hour != null) hours.add(hour to rainProb)
            }
        }
        return WeatherNarrative.summarizeRainWindows(hours, dayLabel)
    }

    private fun getTomorrowRainInfo(json: JSONObject): String {
        val hourly = json.optJSONObject("hourly") ?: return ""
        val rainArr = hourly.optJSONArray("precipitation_probability") ?: return ""
        val times = hourly.optJSONArray("time") ?: return ""
        
        val rainHours = mutableListOf<String>()
        for (i in 24 until minOf(48, times.length())) { // Tomorrow's hours
            val rainProb = rainArr.optInt(i, 0)
            if (rainProb > 50) {
                val timeStr = times.optString(i, "")
                val hourPart = timeStr.split("T").getOrNull(1)?.split(":")?.getOrNull(0)?.toIntOrNull()
                if (hourPart != null) {
                    val hourName = when (hourPart) {
                        in 5..11 -> "မနက် $hourPart နာရီ"
                        12 -> "နေ့လယ် ၁၂ နာရီ"
                        in 13..17 -> "ညနေ ${hourPart - 12} နာရီ"
                        in 18..23 -> "ည ${hourPart - 12} နာရီ"
                        else -> "မနက် ${if (hourPart == 0) 12 else hourPart} နာရီ"
                    }
                    rainHours.add("$hourName လောက် (${rainProb}%)")
                }
            }
        }
        
        return if (rainHours.isNotEmpty()) {
            "မိုးရွာနိုင်တဲ့ အချိန်တွေက ${rainHours.joinToString("၊ ")} မှာ ဖြစ်ပါတယ်။"
        } else ""
    }

    private fun getLocationFromCity(cityOrLocation: String): Triple<Double, Double, String> {
        return when {
            cityOrLocation.contains("seoul", ignoreCase = true) || cityOrLocation.contains("ဆိုးလ်", ignoreCase = true) -> Triple(37.57, 126.98, "Seoul")
            cityOrLocation.contains("busan", ignoreCase = true) || cityOrLocation.contains("ပူဆန်", ignoreCase = true) -> Triple(35.18, 129.08, "Busan")
            cityOrLocation.contains("yangon", ignoreCase = true) || cityOrLocation.contains("ရန်ကုန်", ignoreCase = true) -> Triple(16.8, 96.1, "Yangon")
            cityOrLocation.contains("mandalay", ignoreCase = true) || cityOrLocation.contains("မန္တလေး", ignoreCase = true) -> Triple(21.98, 96.09, "Mandalay")
            else -> Triple(37.57, 126.98, cityOrLocation)
        }
    }
    
    private fun getTimezoneForLocation(lat: Double, lon: Double): String {
        return when {
            lat in 33.0..43.0 && lon in 125.0..132.0 -> "Asia%2FSeoul"
            lat in 9.0..28.0 && lon in 92.0..101.0 -> "Asia%2FYangon"
            else -> "Asia%2FSeoul"
        }
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

    // ===== TIME & DATE =====
    private fun getTimeAndDate(mode: String?): String {
        val tz = SettingsActivity.getTimezone(context)
        val cal = Calendar.getInstance(tz)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val (period, h12) = when {
            hour == 0 -> Pair("မနက်", 12)
            hour < 12 -> Pair("မနက်", hour)
            hour == 12 -> Pair("ညနေ", 12)
            else -> Pair("ညနေ", hour - 12)
        }
        val timeStr = if (minute == 0) "$period $h12 နာရီ" else "$period $h12 နာရီ မိနစ် $minute"
        val dateFormat = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale("my"))
        dateFormat.timeZone = tz
        val dateStr = dateFormat.format(cal.time)
        return when (mode?.lowercase()) {
            "date", "ရက်စွဲ", "date_only" -> "ဒီနေ့က $dateStr ပါ။"
            "time", "အချိန်", "time_only" -> "အခု အချိန်က $timeStr ပါ။"
            else -> "အခု အချိန်က $timeStr ပါ။ ဒီနေ့က $dateStr ပါ။"
        }
    }

    // ===== STATUS =====
    private fun getBatteryStatus(): String {
        val bat = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return "ဘတ်တရီ အချက်အလက် မရပါ။"
        val level = bat.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val statusCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bat.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        } else {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        }
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "အားသွင်းနေသည်"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "အားကုန်နေသည်"
            BatteryManager.BATTERY_STATUS_FULL -> "ပြည့်ပါပြီ"
            else -> ""
        }
        return "ဘတ်တရီ $level ရာခိုင်နှုန်း${if (status.isNotEmpty()) "၊ $status" else ""}။"
    }

    private fun getWifiStatus(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "WiFi ပိတ်နေသည် သို့မဟုတ် မချိတ်ပါ။"
        val caps = cm.getNetworkCapabilities(network) ?: return "WiFi အခြေအနေ မသိပါ။"
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enabled = wifi?.isWifiEnabled == true
        return if (enabled && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            "WiFi ဖွင့်ပြီး ချိတ်ဆက်ပြီးပါပြီ။"
        } else if (enabled) {
            "WiFi ဖွင့်နေပြီး မချိတ်သေးပါ။"
        } else {
            "WiFi ပိတ်နေပါသည်။"
        }
    }

    private fun getBluetoothStatus(): String {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (e: Exception) { null }
            ?: return "Bluetooth မရနိုင်ပါ။"
        return when (adapter.state) {
            BluetoothAdapter.STATE_ON -> "Bluetooth ဖွင့်ပြီးပါပြီ။"
            BluetoothAdapter.STATE_OFF -> "Bluetooth ပိတ်နေပါသည်။"
            BluetoothAdapter.STATE_TURNING_ON -> "Bluetooth ဖွင့်နေပါသည်။"
            BluetoothAdapter.STATE_TURNING_OFF -> "Bluetooth ပိတ်နေပါသည်။"
            else -> "Bluetooth အခြေအနေ မသိပါ။"
        }
    }

    // ===== DEVICE CONTROLS =====
    private fun turnOnWifi(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                "WiFi/အင်တာနက် panel ပြပေးလိုက်ပါပြီ။"
            } catch (e: Exception) {
                openWifiSettingsFallback()
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifi != null && wifi.setWifiEnabled(true)) "WiFi ဖွင့်ပြီးပါပြီ။"
                else openWifiSettingsFallback()
            } catch (e: Exception) {
                openWifiSettingsFallback()
            }
        }
    }

    private fun turnOffWifi(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            turnOnWifi()
        } else {
            try {
                @Suppress("DEPRECATION")
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifi != null && wifi.setWifiEnabled(false)) "WiFi ပိတ်ပြီးပါပြီ။"
                else openWifiSettingsFallback()
            } catch (e: Exception) {
                openWifiSettingsFallback()
            }
        }
    }

    private fun openWifiSettingsFallback(): String {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return "WiFi Settings ဖွင့်ပေးလိုက်ပါပြီ။"
    }

    private fun turnOnBluetooth(): String {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (e: Exception) { null }
            ?: return "Bluetooth မရနိုင်ပါ။"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return openBluetoothSettingsFallback()
        }
        return if (adapter.isEnabled) {
            "Bluetooth ဖွင့်ပြီးသား ရှိပါပြီ။"
        } else if (Build.VERSION.SDK_INT >= 33) {
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Bluetooth ဖွင့်ဖို့ စနစ်က မေးမြန်းပါမယ်။"
            } catch (e: Exception) {
                openBluetoothSettingsFallback()
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                if (adapter.enable()) "Bluetooth ဖွင့်နေပါပြီ။"
                else openBluetoothSettingsFallback()
            } catch (e: Exception) {
                openBluetoothSettingsFallback()
            }
        }
    }

    private fun turnOffBluetooth(): String {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (e: Exception) { null }
            ?: return "Bluetooth မရနိုင်ပါ။"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return openBluetoothSettingsFallback()
        }
        return if (!adapter.isEnabled) {
            "Bluetooth ပိတ်ပြီးသား ရှိပါပြီ။"
        } else if (Build.VERSION.SDK_INT >= 33) {
            openBluetoothSettingsFallback()
        } else {
            try {
                @Suppress("DEPRECATION")
                if (adapter.disable()) "Bluetooth ပိတ်ပြီးပါပြီ။"
                else openBluetoothSettingsFallback()
            } catch (e: Exception) {
                openBluetoothSettingsFallback()
            }
        }
    }

    private fun openBluetoothSettingsFallback(): String {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return "Bluetooth Settings ဖွင့်ပေးလိုက်ပါပြီ။"
    }

    private fun turnOnFlashlight(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, true)
            isFlashlightOn = true
            return "Flashlight on"
        }
        return "Flashlight not supported"
    }

    private fun turnOffFlashlight(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, false)
            isFlashlightOn = false
            return "Flashlight off"
        }
        return "Flashlight not supported"
    }

    private fun toggleFlashlight(): String {
        return if (isFlashlightOn) turnOffFlashlight() else turnOnFlashlight()
    }

    // ===== ALARM & TIMER =====
    private fun setAlarm(time: String?, label: String?): String {
        if (time.isNullOrBlank()) return "နိုးစက် ပေးမည့် အချိန် ပြောပြပေးပါ။"
        
        val normalizedTime = myanmarToArabic(time).trim()
        var hour = 8
        var minute = 0
        var parsed = false
        
        // 1. Try HH:mm format
        val parts = normalizedTime.split(":")
        if (parts.size >= 2) {
            val h = parts[0].replace(Regex("[^0-9]"), "").toIntOrNull()
            val m = parts[1].replace(Regex("[^0-9]"), "").toIntOrNull()
            if (h != null && m != null) {
                hour = h
                minute = m
                parsed = true
            }
        }
        
        // 2. If not parsed, try natural language parsing
        if (!parsed) {
            val cal = parseTimeInput(context, time)
            if (cal != null) {
                hour = cal.get(Calendar.HOUR_OF_DAY)
                minute = cal.get(Calendar.MINUTE)
                parsed = true
            }
        }
        
        if (!parsed) {
            // 3. Fallback: just extract any numbers found in normalizedTime
            val numbers = normalizedTime.replace(Regex("[^0-9]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (numbers.isNotEmpty()) {
                val h = numbers[0].toIntOrNull()
                val m = numbers.getOrNull(1)?.toIntOrNull() ?: 0
                if (h != null && h in 0..23) {
                    hour = h
                    minute = m.coerceIn(0, 59)
                    parsed = true
                }
            }
        }
        
        if (!parsed) {
            return "နိုးစက်ပေးရန် အချိန် $time ကို နားမလည်ပါ။ ဥပမာ - မနက် ၇ နာရီ၊ 07:30 ဟု ပြောပေးပါ။"
        }
        
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Voice Brain Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val formattedTime = String.format("%02d:%02d", hour, minute)
        return "နိုးစက်ကို အချိန် $formattedTime အတွက် သတ်မှတ်ပေးလိုက်ပါပြီ။"
    }

    private fun dismissAlarm(time: String?, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            val normalizedTime = time?.let { myanmarToArabic(it) }?.trim()
            var parsed = false
            if (!normalizedTime.isNullOrBlank()) {
                val parts = normalizedTime.split(":")
                if (parts.size >= 2) {
                    val h = parts[0].replace(Regex("[^0-9]"), "").toIntOrNull()
                    val m = parts[1].replace(Regex("[^0-9]"), "").toIntOrNull()
                    if (h != null && m != null) {
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        val cal = Calendar.getInstance()
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, m)
                    }
                }
            }
            
            if (time.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            }
            
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        return try {
            context.startActivity(intent)
            if (time.isNullOrBlank()) {
                "နိုးစက်ကို ပိတ်ပေးလိုက်ပါပြီ။"
            } else {
                "အချိန် $time အတွက် နိုးစက်ကို ပိတ်ပေးလိုက်ပါပြီ။"
            }
        } catch (e: Exception) {
            try {
                val openClockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(openClockIntent)
                "နိုးစက် ပိတ်ရန်အတွက် Clock settings ကို ဖွင့်ပေးလိုက်ပါပြီ။"
            } catch (ex: Exception) {
                "နိုးစက် ပိတ်၍ မရပါ။ စနစ်ထဲတွင် နိုးစက် အက်ပ် မရှိပါ သို့မဟုတ် ခွင့်ပြုချက် လိုအပ်နေပါသည်။"
            }
        }
    }

    private fun setTimer(minutes: Int): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Voice Brain Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Setting timer for $minutes minutes"
    }

    // ===== REMINDERS =====
    private fun setReminder(message: String, timeOrMinutes: String?): String {
        if (message.isBlank()) return "Please say what to remind you about"
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime: Long
        val label: String
        
        val timeNorm = timeOrMinutes?.let { myanmarToArabic(it) } ?: ""
        val looksLikeAbsoluteTime = timeNorm.contains("နာရီ") || timeNorm.contains("မနက်ဖြန်") ||
            timeNorm.contains("ယနေ့") || timeNorm.contains(":") || timeNorm.contains("am", ignoreCase = true) ||
            timeNorm.contains("pm", ignoreCase = true) || timeNorm.contains("tomorrow")
        val minutes = if (!looksLikeAbsoluteTime) timeNorm.replace(Regex("[^0-9]"), "").toIntOrNull() else null
        
        when {
            minutes != null && minutes in 1..40320 -> {
                triggerTime = System.currentTimeMillis() + minutes * 60_000L
                label = "$minutes မိနစ်ကြာရင်"
            }
            timeOrMinutes != null -> {
                val cal = parseTimeInput(context, timeOrMinutes)
                if (cal == null) {
                    triggerTime = System.currentTimeMillis() + 30 * 60_000L
                    label = "၃၀ မိနစ်ကြာရင်"
                } else {
                    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
                    triggerTime = cal.timeInMillis
                    val h = cal.get(Calendar.HOUR_OF_DAY)
                    val m = cal.get(Calendar.MINUTE)
                    val todayCal = Calendar.getInstance(SettingsActivity.getTimezone(context))
                    val isTomorrow = cal.get(Calendar.DAY_OF_YEAR) != todayCal.get(Calendar.DAY_OF_YEAR)
                    label = if (isTomorrow) "မနက်ဖြန် ${h}:${m.toString().padStart(2, '0')}"
                    else String.format("%02d:%02d", h, m)
                }
            }
            else -> {
                triggerTime = System.currentTimeMillis() + 30 * 60_000L
                label = "၃၀ မိနစ်ကြာရင်"
            }
        }
        
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        reminderPrefs.edit().putString("rem_$id", "$triggerTime|$message").apply()
        
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                    return "သတိပေးချက် ထားပြီးပါပြီ။ $message — $label"
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            }
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
        }
        
        return "သတိပေးချက် ထားပြီးပါပြီ။ $message — $label"
    }

    private fun listPendingReminders(): String {
        val all = ReminderHelper.getReminders(context)
        val pending = all.filter { !it.isPast }
        return if (pending.isEmpty()) {
            "မလုပ်ရသေးတဲ့ သတိပေးချက် မရှိပါဘူး။"
        } else {
            val lines = pending.mapIndexed { i, r -> "${i + 1}. ${r.message} - ${r.formatTime(context)}" }
            "မလုပ်ရသေးတဲ့ သတိပေးချက် ${pending.size} ခု ရှိပါတယ်။ " + lines.joinToString("။ ")
        }
    }

    // ===== ROUTINES =====
    private fun addScheduledRoutine(target: String, value: String): String {
        val type = when (target.lowercase()) {
            "weather", "ရာသီဥတု" -> "weather"
            "reminder", "သတိပေး" -> "reminder"
            "command", "\u1021\u1019\u102d\u1014\u1037\u103a", "\u1001\u102d\u102f\u1004\u103a\u1038\u1005\u1031\u1001\u103b\u1000\u103a" -> "command"
            else -> {
                val t = target.lowercase()
                if (t.contains("ရာသီဥတု") || t.contains("weather")) "weather"
                else if (t.contains("\u1021\u1019\u102d\u1014\u1037\u103a") || t.contains("\u1001\u102d\u102f\u1004\u103a\u1038\u1005\u1031") || t.contains("command")) "command"
                else "reminder"
            }
        }
        val valueNorm = myanmarToArabic(value).trim()
        val (timePart, message) = when {
            valueNorm.contains("|") -> {
                val parts = valueNorm.split("|", limit = 2)
                parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
            }
            else -> valueNorm to ""
        }
        val (dayOfWeek, timeStr) = parseDayOfWeek(timePart)
        val (hour, minute) = parseTimeForRoutine(timeStr) ?: return "အချိန် မမှန်ပါ။"
        if ((type == "reminder" || type == "command") && message.isBlank()) return if (type == "command") "\u1021\u1019\u102d\u1014\u1037\u103a\u1000\u102d\u102f\u0020\u101e\u1031\u1001\u103b\u102c\u1005\u103d\u102c\u0020\u1015\u103c\u1031\u102c\u1015\u1031\u1038\u1015\u102b\u104b" else "သတိပေးချက် အတွက် ဘာ ပြောပြမလဲ ပြောပေးပါ။"
        val item = RoutineStorage.add(context, type, hour, minute, message, dayOfWeek)
        RoutineScheduler.schedule(context, item)
        val dayLabel = if (dayOfWeek in 1..7) dayNameFromDow(dayOfWeek) + " " else ""
        return when (type) {
            "weather" -> if (dayOfWeek == 0) "နေ့တိုင်း ${item.timeLabel()} တိုင်း ရာသီဥတု ပြောပြပေးမယ်။" else "အပတ်စဉ် $dayLabel${item.timeLabel()} တိုင်း ရာသီဥတု ပြောပြပေးမယ်။"
            "command" -> if (dayOfWeek == 0) "\u1014\u1031\u1037\u1010\u102d\u102f\u1004\u103a\u1038 ${item.timeLabel()} \u1019\u103e\u102c '$message' \u1021\u1019\u102d\u1014\u1037\u103a\u1000\u102d\u102f \u1021\u101c\u102d\u102f\u1021\u101c\u103b\u1031\u102cက\u103a \u1001\u102d\u102f\u1004\u103a\u1038\u1005\u1031\u1015\u1031\u1038\u1019\u101a\u103a\u104b" else "\u1021\u1015\u1010\u103a\u1005\u1009\u103a $dayLabel${item.timeLabel()} \u1019\u103e\u102c '$message' \u1021\u1019\u102d\u1014\u1037\u103a\u1000\u102d\u102f \u1021\u101c\u102d\u102f\u1021\u101c\u103b\u1031\u102cက\u103a \u1001\u102d\u102f\u1004\u103a\u1038\u1005\u1031\u1015\u1031\u1038\u1019\u101a\u103a\u104b"
            else -> if (dayOfWeek == 0) "နေ့တိုင်း ${item.timeLabel()} မှာ '$message' သတိပေးပေးမယ်။" else "အပတ်စဉ် $dayLabel${item.timeLabel()} မှာ '$message' သတိပေးပေးမယ်။"
        }
    }

    private fun parseDayOfWeek(s: String): Pair<Int, String> {
        val lower = s.lowercase().trim()
        val days = mapOf(
            "sunday" to 1, "တနင်္ဂနွေ" to 1, "sun" to 1,
            "monday" to 2, "တနင်္လာ" to 2, "mon" to 2,
            "tuesday" to 3, "အင်္ဂါ" to 3, "tue" to 3,
            "wednesday" to 4, "ဗုဒ္ဓဟူး" to 4, "wed" to 4,
            "thursday" to 5, "ကြာသပတေး" to 5, "thu" to 5,
            "friday" to 6, "သောကြာ" to 6, "fri" to 6,
            "saturday" to 7, "စနေ" to 7, "sat" to 7
        )
        for ((name, dow) in days) {
            if (lower.startsWith(name) || lower.contains(" $name ")) {
                val rest = s.replace(Regex("(?i)$name\\s*"), "").trim()
                return dow to rest.ifBlank { s }
            }
        }
        return 0 to s
    }

    private fun dayNameFromDow(dow: Int): String = when (dow) {
        1 -> "တနင်္ဂနွေ"; 2 -> "တနင်္လာ"; 3 -> "အင်္ဂါ"; 4 -> "ဗုဒ္ဓဟူး"; 5 -> "ကြာသပတေး"; 6 -> "သောကြာ"; 7 -> "စနေ"
        else -> ""
    }

    private fun parseTimeForRoutine(s: String): Pair<Int, Int>? {
        val norm = myanmarToArabic(s)
        val lower = norm.lowercase()
        val isPm = lower.contains("ညနေ") || lower.contains("pm")
        val isAm = lower.contains("မနက်") || lower.contains("am")
        val digitOnly = norm.replace(Regex("[^0-9]"), "")
        val digits = digitOnly.trim()
        when {
            norm.contains(":") -> {
                val parts = norm.split(":")
                val h = parts[0].replace(Regex("[^0-9]"), "").toIntOrNull() ?: return null
                val m = parts.getOrNull(1)?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                var hour = h
                if (isPm && h in 1..11) hour = h + 12
                if (isAm && h == 12) hour = 0
                if (hour in 0..23 && m in 0..59) return hour to m
            }
            digits.length in 1..4 -> {
                val n = digits.toIntOrNull() ?: return null
                val (h, m) = when {
                    n in 100..2359 -> (n / 100) to (n % 100)
                    n in 1..11 && isPm -> (n + 12) to 0
                    n == 12 && isPm -> 12 to 0
                    n == 12 && isAm -> 0 to 0
                    n in 0..23 -> n to 0
                    else -> return null
                }
                if (h in 0..23 && m in 0..59) return h to m
            }
        }
        return null
    }

    private fun listScheduledRoutines(): String {
        val items = RoutineStorage.getAll(context)
        if (items.isEmpty()) return "အချိန်ဇယား routine မရှိပါဘူး။"
        val lines = items.mapIndexed { i, r ->
            val desc = when (r.type) {
                "weather" -> "ရာသီဥတု ပြောပြ"
                else -> "သတိပေး: ${r.message.take(30)}"
            }
            val dayPref = if (r.dayOfWeek in 1..7) dayNameFromDow(r.dayOfWeek) + " " else ""
            "${i + 1}. $dayPref${r.timeLabel()} - $desc"
        }
        return "Routine ${items.size} ခု ရှိပါတယ်။ " + lines.joinToString("။ ")
    }

    private fun deleteScheduledRoutine(target: String): String {
        val id = target.toIntOrNull() ?: return "Routine နံပါတ် ပြောပေးပါ။"
        val item = RoutineStorage.getById(context, id) ?: return "Routine မရှိပါဘူး။"
        RoutineScheduler.cancel(context, id)
        RoutineStorage.delete(context, id)
        return "${item.timeLabel()} routine ဖျက်ပြီးပါပြီ။"
    }

    // ===== NOTES =====
    private suspend fun saveNote(content: String, category: String? = null): String {
        if (content.isBlank()) return "Please tell me what to note"
        val timestamp = System.currentTimeMillis()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val dateStr = dateFormat.format(java.util.Date(timestamp))
        val cat = when {
            category.isNullOrBlank() -> ""
            category.contains("idea", ignoreCase = true) -> "Idea"
            category.contains("korean", ignoreCase = true) -> "Korean"
            category.contains("work", ignoreCase = true) -> "Work"
            else -> "Quick Note"
        }
        val title = if (cat.isNotBlank()) "[$cat] $dateStr" else dateStr
        noteRepository.saveNote(title, content)
        return "Note saved: $content"
    }

    private suspend fun readNotes(): String {
        val notionSummary = if (notionRepository.isConfigured()) {
            notionRepository.searchAndDiscussNotes("all")
        } else ""

        val notes = noteRepository.getAllNotes()
        val localSummary = if (notes.isNotEmpty()) {
            val notesList = notes.take(10).joinToString("\n") { "${it.title}: ${it.content}" }
            "Local Offline Notes:\n$notesList"
        } else ""

        if (notionSummary.isBlank() && localSummary.isBlank()) {
            return "No notes saved yet in Notion or local storage."
        }
        return listOf(notionSummary, localSummary).filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
    }

    private suspend fun deleteNote(keyword: String): String {
        if (keyword.isBlank()) return "Please specify which note to delete"
        val deleted = noteRepository.deleteNoteByKeyword(keyword)
        return if (deleted) "Deleted note containing: $keyword" else "No note found with: $keyword"
    }

    private suspend fun clearAllNotes(): String {
        noteRepository.clearAll()
        return "All notes cleared from database"
    }

    // ===== NOTION =====
    private suspend fun sendToNotion(arg1: String?, arg2: String?): String {
        if (!notionClient.isConfigured()) return "Notion not configured."
        if (arg1.isNullOrBlank() && arg2.isNullOrBlank()) return "Please tell me what to send to Notion"
        
        val content: String
        val title: String
        var noteCategory = "Quick Note"

        if (!arg2.isNullOrBlank() && !arg2.equals("Idea", true) && !arg2.equals("Korean", true) && !arg2.equals("Quick Note", true) && !arg2.equals("Work", true)) {
            // arg1 is treated as Title, arg2 as Content (Structured AI Save!)
            title = arg1 ?: "Note"
            content = arg2
        } else {
            // Traditional usage: arg1 is content, arg2 is category
            content = arg1 ?: ""
            if (!arg2.isNullOrBlank()) noteCategory = arg2
            title = content.take(50).let { if (content.length > 50) "$it..." else it }
        }

        return notionRepository.createNote(title, content, noteCategory)
    }

    private suspend fun sendLastNoteToNotion(category: String?): String {
        if (!notionClient.isConfigured()) return "Notion not configured."
        val allNotes = noteRepository.getAllNotes()
        if (allNotes.isEmpty()) return "No notes to send."
        return sendToNotion(allNotes.first().content, category)
    }

    private fun testNotionConnection(): String = notionClient.testConnection()

    // ===== MISC =====
    private fun setListeningTimeout(seconds: Int): String {
        val prefs = SettingsActivity.getSecurePrefs(context)
        prefs.edit().apply {
            putInt(SettingsActivity.KEY_SILENCE_TIMEOUT_VALUE, seconds)
            putString(SettingsActivity.KEY_SILENCE_TIMEOUT_UNIT, "s")
            apply()
        }
        return "Listening timeout set to $seconds seconds."
    }

    /** Myanmar numerals ၀၁၂၃၄၅၆၇၈၉ -> 0-9 */
    private fun myanmarToArabic(s: String): String = s.map { c ->
        when (c) {
            '၀' -> '0'; '၁' -> '1'; '၂' -> '2'; '၃' -> '3'; '၄' -> '4'
            '၅' -> '5'; '၆' -> '6'; '၇' -> '7'; '၈' -> '8'; '၉' -> '9'
            else -> c
        }
    }.joinToString("")

    /** Parse natural date+time */
    private fun parseTimeInput(ctx: Context, input: String): Calendar? {
        return try {
            val tz = SettingsActivity.getTimezone(ctx)
            val cal = Calendar.getInstance(tz)
            val normalized = myanmarToArabic(input).trim()
            var dayOffset = 0
            when {
                normalized.contains("မနက်ဖြန်") || normalized.contains("tomorrow") -> dayOffset = 1
                normalized.contains("ညနောက်နေ့") || normalized.contains("day after") -> dayOffset = 2
                normalized.contains("ယနေ့") || normalized.contains("today") -> dayOffset = 0
            }
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            val timeMatch = Regex("(\\d{1,2}):(\\d{1,2})").find(normalized)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = timeMatch.groupValues[2].toInt()
                val isPM = normalized.contains("pm", ignoreCase = true)
                val isAM = normalized.contains("am", ignoreCase = true)
                when {
                    isPM && hour < 12 -> hour += 12
                    isAM && hour == 12 -> hour = 0
                }
                cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                return cal
            }
            val hourMatch = Regex("(\\d{1,2})\\s*နာရီ").find(normalized)
            val minMatch = Regex("(\\d{1,2})\\s*မိနစ်").find(normalized)
            val hour = hourMatch?.groupValues?.get(1)?.toIntOrNull()
            val minute = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (hour != null) {
                var h = hour.coerceIn(0, 23)
                when {
                    (normalized.contains("မနက်") || normalized.contains("နံနက်")) && h < 12 -> { }
                    (normalized.contains("ညနေ") || normalized.contains("မိုးချုပ်")) && h in 1..7 -> h += 12
                    normalized.contains("ည ") && h in 1..11 -> h += 12
                    normalized.contains("နေ့လယ်") -> h = 12
                }
                cal.set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                return cal
            }
            null
        } catch (e: Exception) { null }
    }

    private fun switchProfile(profileName: String): String {
        if (profileName.isBlank()) return "ဘယ် profile သို့ ပြောင်းချင်သလဲ ပြောပေးပါ။"
        return profileManager.switchProfile(profileName)
    }
}
