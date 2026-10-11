package com.example.voicebrainlive.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.MainActivity
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import com.example.voicebrainlive.weather.NaturalWeatherSpeaker
import com.example.voicebrainlive.location.LocationTriggerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Proactive Rain Alert Service (Enhanced v2)
 * 
 * မိုးရွာမယ့် အခြေအနေ ရှိရင် App ပွင့်ပြီး AI က သဘာဝကျကျ ပြောသည်။
 * 
 * Enhancements v2:
 * - 1-day advance rain alert (မနက်ဖြန် မိုးရွာမယ် သတိပေး)
 * - Specific rain timing info (မနက် ၉ နာရီလောက် မိုးစရွာမယ်)
 * - TTS Unified Interface integration for better voice output
 * - Fallback to NaturalWeatherSpeaker if TTS unavailable
 * - Improved notification with rain icon
 * - Better error handling
 */
class ProactiveRainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var weatherSpeaker: NaturalWeatherSpeaker? = null
    private var ttsUnified: TtsUnifiedInterface? = null
    private val httpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    private var serviceWakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        serviceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBrain:ProactiveRainServiceWakeLock").apply {
            acquire(300_000L) // 5 minutes max
        }
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Voice Brain - မိုးရွာမယ် စစ်ဆေးခြင်း")
            .setContentText("ရာသီဥတု စစ်ဆေးနေပါတယ်...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        startForeground(85001, notification)

        scope.launch(Dispatchers.IO) {
            try {
                val exec = PhoneCommandExecutor(this@ProactiveRainService)
                
                // Check today's rain
                val willRainToday = exec.isRainExpectedToday()
                
                // Check tomorrow's rain (1-day advance alert)
                val tomorrowRainInfo = checkTomorrowRain()
                
                // Initialize TTS Unified Interface
                withContext(Dispatchers.Main) {
                    ttsUnified = TtsUnifiedInterface(this@ProactiveRainService)
                    weatherSpeaker = NaturalWeatherSpeaker(this@ProactiveRainService)
                    
                    val message = buildMessage(willRainToday, tomorrowRainInfo)
                    
                    // Use TTS Unified Interface if available, fallback to NaturalWeatherSpeaker
                    if (ttsUnified?.isAvailable() == true) {
                        ttsUnified?.speakAlert(message)
                        kotlinx.coroutines.delay(10000)
                    } else {
                        weatherSpeaker?.speakSimple(message)
                        kotlinx.coroutines.delay(10000)
                    }
                    
                    // Cleanup
                    ttsUnified?.shutdown()
                    ttsUnified = null
                    weatherSpeaker?.shutdown()
                    weatherSpeaker = null
                    Log.d("ProactiveRain", "Rain alert spoken (today=$willRainToday, tomorrow=${tomorrowRainInfo.hasRain})")
                }
                
                // Reschedule for next check
                ProactiveRainScheduler.schedule(this@ProactiveRainService)
            } catch (e: Exception) {
                Log.e("ProactiveRain", "Error: ${e.message}", e)
                ProactiveRainScheduler.schedule(this@ProactiveRainService)
            } finally {
                if (serviceWakeLock?.isHeld == true) {
                    try { serviceWakeLock?.release() } catch (_: Exception) {}
                }
                ttsUnified?.shutdown()
                ttsUnified = null
                weatherSpeaker?.shutdown()
                weatherSpeaker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    /**
     * Check tomorrow's rain forecast with specific timing
     */
    private fun checkTomorrowRain(): TomorrowRainInfo {
        return try {
            // Retrieve home/office location triggers if defined, fallback to default
            val home = LocationTriggerStorage.getHomeLocation(this@ProactiveRainService)
            val office = LocationTriggerStorage.getOfficeLocation(this@ProactiveRainService)
            val lat = home?.lat ?: office?.lat ?: 37.57
            val lon = home?.lng ?: office?.lng ?: 126.98
            
            // Deduce timezone based on coordinates
            val timezone = if (lat in 9.0..28.0 && lon in 92.0..101.0) "Asia%2FYangon" else "Asia%2FSeoul"
            
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=weather_code,precipitation_probability,temperature_2m&timezone=$timezone&forecast_days=2"
            
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful || response.body == null) {
                return TomorrowRainInfo(false, emptyList())
            }
            
            val json = JSONObject(response.body!!.string())
            val hourly = json.optJSONObject("hourly") ?: return TomorrowRainInfo(false, emptyList())
            val codes = hourly.optJSONArray("weather_code") ?: return TomorrowRainInfo(false, emptyList())
            val rainProbs = hourly.optJSONArray("precipitation_probability") ?: return TomorrowRainInfo(false, emptyList())
            val temps = hourly.optJSONArray("temperature_2m") ?: return TomorrowRainInfo(false, emptyList())
            val times = hourly.optJSONArray("time") ?: return TomorrowRainInfo(false, emptyList())
            
            val rainHours = mutableListOf<RainHourInfo>()
            
            // Check hours 24-47 (tomorrow)
            for (i in 24 until minOf(48, codes.length())) {
                val code = codes.optInt(i, 0)
                val rainProb = rainProbs.optInt(i, 0)
                val temp = temps.optDouble(i, 0.0).toInt()
                
                // Rain codes: 51-67 (rain), 80-82 (showers), 95-99 (thunderstorm)
                if ((code in 51..67 || code in 80..82 || code in 95..99) && rainProb > 30) {
                    val timeStr = times.optString(i, "")
                    val hourPart = timeStr.split("T").getOrNull(1)?.split(":")?.getOrNull(0)?.toIntOrNull()
                    if (hourPart != null) {
                        rainHours.add(RainHourInfo(hourPart, rainProb, temp, code))
                    }
                }
            }
            
            TomorrowRainInfo(rainHours.isNotEmpty(), rainHours)
        } catch (e: Exception) {
            Log.e("ProactiveRain", "checkTomorrowRain error: ${e.message}")
            TomorrowRainInfo(false, emptyList())
        }
    }
    
    /**
     * Build natural speech message based on rain forecast
     */
    private fun buildMessage(willRainToday: Boolean, tomorrowRain: TomorrowRainInfo): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 5..11 -> "မင်္ဂလာ မနက်ခင်းပါ။ "
            in 12..16 -> "နေ့လယ်ခင်း ကောင်းမွန်ပါစေ။ "
            in 17..20 -> "ညနေခင်းလေးပါ။ "
            else -> "ညဦးယံပါ။ "
        }
        
        return buildString {
            append(timeGreeting)
            
            // Today's forecast
            if (willRainToday) {
                append("ဒီနေ့ မိုးရွာနိုင်တယ်။ ထီးလေး တစ်ခု ယူသွားလိုက်နော်။ ")
            } else {
                append("ဒီနေ့ မိုးမရွာနိုင်ဘူး။ ")
            }
            
            // Tomorrow's forecast (1-day advance alert)
            if (tomorrowRain.hasRain && tomorrowRain.hours.isNotEmpty()) {
                val firstRain = tomorrowRain.hours.first()
                val lastRain = tomorrowRain.hours.last()
                
                val startTime = formatHour(firstRain.hour)
                val endTime = formatHour(lastRain.hour)
                val maxProb = tomorrowRain.hours.maxOf { it.probability }
                val avgTemp = tomorrowRain.hours.map { it.temperature }.average().toInt()
                
                append("မနက်ဖြန် မိုးရွာနိုင်တယ်။ ")
                
                if (tomorrowRain.hours.size <= 3) {
                    // Few rain hours - be specific
                    val timeList = tomorrowRain.hours.map { formatHour(it.hour) }.joinToString("၊ ")
                    append("$timeList လောက် မိုးရွာနိုင်တယ်။ ")
                } else {
                    // Many rain hours - give range
                    append("$startTime ကနေ $endTime ကြား မိုးရွာနိုင်တယ်။ ")
                }
                
                append("မိုးရွာနိုင်ခြေ ${maxProb}% လောက် ရှိတယ်။ ")
                append("အပူချိန် ${avgTemp} ဒီဂရီလောက် ရှိမယ်။ ")
                append("မနက်ဖြန် ထီး မဖြစ်မနေ ယူသွားပါ။ ")
            } else {
                append("မနက်ဖြန် မိုးမရွာနိုင်ဘူး။ စိတ်ချလက်ချ အပြင်ထွက်လို့ ရတယ်။ ")
            }
        }
    }
    
    /**
     * Format hour to Burmese time string
     */
    private fun formatHour(hour: Int): String {
        return when (hour) {
            0 -> "သန်းခေါင်ယံ"
            in 1..4 -> "မနက် $hour နာရီ"
            in 5..11 -> "မနက် $hour နာရီ"
            12 -> "နေ့လယ် ၁၂ နာရီ"
            in 13..17 -> "ညနေ ${hour - 12} နာရီ"
            in 18..23 -> "ည ${hour - 12} နာရီ"
            else -> "$hour နာရီ"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "မိုးရွာမယ် သတိပေး",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_brain_proactive_rain"
    }
}

/**
 * Data class for tomorrow's rain information
 */
data class TomorrowRainInfo(
    val hasRain: Boolean,
    val hours: List<RainHourInfo>
)

/**
 * Data class for specific rain hour information
 */
data class RainHourInfo(
    val hour: Int,           // 0-23
    val probability: Int,    // 0-100%
    val temperature: Int,    // °C
    val weatherCode: Int     // WMO code
)
