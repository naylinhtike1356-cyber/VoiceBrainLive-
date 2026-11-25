package com.example.voicebrainlive.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.voicebrainlive.MainActivity
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.location.LocationTriggerStorage
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import com.example.voicebrainlive.weather.NaturalWeatherSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for proactive rain alerts.
 * Replaces the legacy specialUse ForegroundService to comply with Android 14/15+ background limits.
 */
class ProactiveRainWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val httpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var ttsUnified: TtsUnifiedInterface? = null
        var weatherSpeaker: NaturalWeatherSpeaker? = null
        try {
            val exec = PhoneCommandExecutor(applicationContext)
            val willRainToday = exec.isRainExpectedToday()
            val tomorrowRainInfo = checkTomorrowRain()
            val message = buildMessage(willRainToday, tomorrowRainInfo)

            postNotification(message)

            withContext(Dispatchers.Main) {
                ttsUnified = TtsUnifiedInterface(applicationContext)
                weatherSpeaker = NaturalWeatherSpeaker(applicationContext)
                if (ttsUnified?.isAvailable() == true) {
                    ttsUnified?.speakAlert(message)
                    delay(10_000)
                } else {
                    weatherSpeaker?.speakSimple(message)
                    delay(10_000)
                }
                ttsUnified?.shutdown()
                ttsUnified = null
                weatherSpeaker?.shutdown()
                weatherSpeaker = null
            }

            Log.d(TAG, "Rain alert completed (today=$willRainToday, tomorrow=${tomorrowRainInfo.hasRain})")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ProactiveRainWorker error: ${e.message}", e)
            Result.retry()
        } finally {
            withContext(Dispatchers.Main) {
                ttsUnified?.shutdown()
                weatherSpeaker?.shutdown()
            }
            try {
                ProactiveRainScheduler.schedule(applicationContext)
            } catch (_: Exception) { }
        }
    }

    private fun postNotification(message: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "မိုးရွာမယ် သတိပေး", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Voice Brain - မိုးလေဝသ သတိပေးချက်")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun checkTomorrowRain(): TomorrowRainInfo {
        return try {
            val home = LocationTriggerStorage.getHomeLocation(applicationContext)
            val office = LocationTriggerStorage.getOfficeLocation(applicationContext)
            val lat = home?.lat ?: office?.lat ?: 37.57
            val lon = home?.lng ?: office?.lng ?: 126.98
            val timezone = if (lat in 9.0..28.0 && lon in 92.0..101.0) "Asia%2FYangon" else "Asia%2FSeoul"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=weather_code,precipitation_probability,temperature_2m&timezone=$timezone&forecast_days=2"

            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
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
            for (i in 24 until minOf(48, codes.length())) {
                val code = codes.optInt(i, 0)
                val rainProb = rainProbs.optInt(i, 0)
                val temp = temps.optDouble(i, 0.0).toInt()
                if ((code in 51..67 || code in 80..82 || code in 95..99) && rainProb > 30) {
                    val hourPart = times.optString(i, "").split("T").getOrNull(1)?.split(":")?.getOrNull(0)?.toIntOrNull()
                    if (hourPart != null) {
                        rainHours.add(RainHourInfo(hourPart, rainProb, temp, code))
                    }
                }
            }
            TomorrowRainInfo(rainHours.isNotEmpty(), rainHours)
        } catch (e: Exception) {
            Log.e(TAG, "checkTomorrowRain error: ${e.message}")
            TomorrowRainInfo(false, emptyList())
        }
    }

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
            if (willRainToday) {
                append("ဒီနေ့ မိုးရွာနိုင်တယ်။ ထီးလေး တစ်ခု ယူသွားလိုက်နော်။ ")
            } else {
                append("ဒီနေ့ မိုးမရွာနိုင်ဘူး။ ")
            }

            if (tomorrowRain.hasRain && tomorrowRain.hours.isNotEmpty()) {
                val firstRain = tomorrowRain.hours.first()
                val lastRain = tomorrowRain.hours.last()
                val startTime = formatHour(firstRain.hour)
                val endTime = formatHour(lastRain.hour)
                val maxProb = tomorrowRain.hours.maxOf { it.probability }
                val avgTemp = tomorrowRain.hours.map { it.temperature }.average().toInt()

                append("မနက်ဖြန် မိုးရွာနိုင်တယ်။ ")
                if (tomorrowRain.hours.size <= 3) {
                    val timeList = tomorrowRain.hours.map { formatHour(it.hour) }.joinToString("၊ ")
                    append("$timeList လောက် မိုးရွာနိုင်တယ်။ ")
                } else {
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

    private fun formatHour(hour: Int): String {
        return when (hour) {
            0 -> "သန်းခေါင်ယံ"
            in 1..11 -> "မနက် $hour နာရီ"
            12 -> "နေ့လယ် ၁၂ နာရီ"
            in 13..17 -> "ညနေ ${hour - 12} နာရီ"
            in 18..23 -> "ည ${hour - 12} နာရီ"
            else -> "$hour နာရီ"
        }
    }

    companion object {
        private const val TAG = "ProactiveRain"
        private const val CHANNEL_ID = "voice_brain_proactive_rain"
        private const val NOTIFICATION_ID = 85001
        const val UNIQUE_WORK_NAME = "proactive_rain_check"
    }
}

data class TomorrowRainInfo(
    val hasRain: Boolean,
    val hours: List<RainHourInfo>
)

data class RainHourInfo(
    val hour: Int,
    val probability: Int,
    val temperature: Int,
    val weatherCode: Int
)
