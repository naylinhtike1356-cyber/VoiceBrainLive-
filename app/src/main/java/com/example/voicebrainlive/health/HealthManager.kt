package com.example.voicebrainlive.health

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object HealthManager {
    private const val TAG = "HealthManager"
    private const val KEY_HEALTH_ENABLED = "health_assistant_enabled"
    private const val KEY_LAST_SYNC_TIME = "last_health_sync_time"
    private const val KEY_DATA_SOURCE = "health_data_source"
    private const val SOURCE_HEALTH_CONNECT = "Health Connect / Samsung Health"

    fun isHealthAssistantEnabled(context: Context): Boolean {
        return HealthSecureStorage.read(context, false) { it.getBoolean(KEY_HEALTH_ENABLED, true) }
    }

    fun setHealthAssistantEnabled(context: Context, enabled: Boolean): Boolean =
        HealthSecureStorage.write(context) { it.putBoolean(KEY_HEALTH_ENABLED, enabled) }

    data class HealthData(
        val sleepHours: Double = 0.0,
        val steps: Int = 0,
        val activeCalories: Int = 0,
        val workoutMinutes: Int = 0,
        val workoutType: String = "",
        val avgHeartRate: Int = 0,
        val lastSyncTime: Long = 0L,
        val sleepQuality: String = "",
        val bedtime: String? = null,
        val wakeTime: String? = null,
        val dataSource: String? = null
    ) {
        val hasAnyData: Boolean
            get() = !dataSource.isNullOrBlank() && lastSyncTime > 0L &&
                (sleepHours > 0.0 || steps > 0 || activeCalories > 0 || workoutMinutes > 0 || avgHeartRate > 0)
    }

    fun fetchHealthData(context: Context): HealthData {
        return HealthSecureStorage.read(context, HealthData()) { readCachedData(it) }
    }

    private fun readCachedData(prefs: SharedPreferences): HealthData {
        return HealthData(
            sleepHours = prefs.getFloat("cached_sleep_hours", 0f).toDouble(),
            steps = prefs.getInt("cached_steps", 0),
            activeCalories = prefs.getInt("cached_calories", 0),
            workoutMinutes = prefs.getInt("cached_workout_min", 0),
            workoutType = prefs.getString("cached_workout_type", "") ?: "",
            avgHeartRate = prefs.getInt("cached_heart_rate", 0),
            lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L),
            sleepQuality = prefs.getString("cached_sleep_quality", "") ?: "",
            bedtime = prefs.getString("cached_bedtime", null),
            wakeTime = prefs.getString("cached_waketime", null),
            dataSource = prefs.getString(KEY_DATA_SOURCE, null)
        )
    }

    suspend fun fetchHealthDataLive(context: Context): HealthData {
        // An unavailable vault must not lead to live provider collection or a plaintext cache.
        val cached = HealthSecureStorage.read<HealthData?>(context, null) { readCachedData(it) }
            ?: return HealthData()
        return try {
            if (HealthConnectClient.getSdkStatus(context, "com.google.android.apps.healthdata") != HealthConnectClient.SDK_AVAILABLE) {
                return cached
            }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
            val caloriesPermission = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
            val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
            val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
            val canReadSteps = stepsPermission in granted
            val canReadCalories = caloriesPermission in granted
            val canReadSleep = sleepPermission in granted
            val canReadHeart = heartPermission in granted
            if (!canReadSteps && !canReadCalories && !canReadSleep && !canReadHeart) return cached

            val zone = ZoneId.systemDefault()
            val end = ZonedDateTime.now(zone)
            val start = end.minusDays(1)
            val range = TimeRangeFilter.between(start.toInstant(), end.toInstant())
            val aggregateMetrics = buildSet {
                if (canReadSteps) add(StepsRecord.COUNT_TOTAL)
                if (canReadCalories) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            }
            val aggregate = if (aggregateMetrics.isNotEmpty()) {
                client.aggregate(AggregateRequest(metrics = aggregateMetrics, timeRangeFilter = range))
            } else null
            val steps = if (canReadSteps) aggregate?.get(StepsRecord.COUNT_TOTAL)?.toInt() ?: 0 else cached.steps
            val calories = if (canReadCalories) aggregate?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories?.toInt() ?: 0 else cached.activeCalories

            // Samsung Health may publish the previous night's session more than 18 hours later.
            val sleepStart = end.minusHours(36).toInstant()
            val sleepRecords = if (canReadSleep) client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(sleepStart, end.toInstant()))
            ).records else emptyList()
            val latestSleep = sleepRecords.maxByOrNull { it.endTime }
            val sleepHours = if (canReadSleep) {
                latestSleep?.let { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 3_600_000.0 } ?: 0.0
            } else cached.sleepHours
            val heartRecords = if (canReadHeart) client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, range)
            ).records else emptyList()
            val heartSamples = heartRecords.flatMap { it.samples }
            val heartRate = if (canReadHeart) {
                heartSamples.map { it.beatsPerMinute }.average().takeIf { it.isFinite() }?.toInt() ?: 0
            } else cached.avgHeartRate

            val merged = cached.copy(
                sleepHours = sleepHours,
                steps = steps,
                activeCalories = calories,
                avgHeartRate = heartRate,
                lastSyncTime = System.currentTimeMillis(),
                dataSource = SOURCE_HEALTH_CONNECT
            )
            val saved = updateCachedHealthData(context, sleepHours = sleepHours.takeIf { it > 0 }, steps = steps.takeIf { it > 0 }, calories = calories.takeIf { it > 0 }, heartRate = heartRate.takeIf { it > 0 }, dataSource = SOURCE_HEALTH_CONNECT)
            if (saved) merged else cached
        } catch (e: Exception) {
            Log.w(TAG, "Live Health Connect read unavailable")
            cached
        }
    }

    fun requiredHealthPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    fun updateCachedHealthData(
        context: Context,
        sleepHours: Double? = null,
        steps: Int? = null,
        calories: Int? = null,
        workoutMinutes: Int? = null,
        heartRate: Int? = null,
        bedtime: String? = null,
        wakeTime: String? = null,
        sleepQuality: String? = null,
        dataSource: String = "Samsung Health notification"
    ): Boolean = HealthSecureStorage.write(context) { editor ->
        sleepHours?.let { editor.putFloat("cached_sleep_hours", it.toFloat()) }
        steps?.let { editor.putInt("cached_steps", it) }
        calories?.let { editor.putInt("cached_calories", it) }
        workoutMinutes?.let { editor.putInt("cached_workout_min", it) }
        heartRate?.let { editor.putInt("cached_heart_rate", it) }
        bedtime?.let { editor.putString("cached_bedtime", it) }
        wakeTime?.let { editor.putString("cached_waketime", it) }
        sleepQuality?.let { editor.putString("cached_sleep_quality", it) }
        editor.putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
        editor.putString(KEY_DATA_SOURCE, dataSource)
    }

    /**
     * Parses notifications from Samsung Health (com.sec.android.app.shealth) or Health Connect
     * e.g. "Sleep 6h 45m", "Slept 7 hrs 15 mins", "Steps: 8420"
     */
    fun parseAndSaveFromNotification(context: Context, title: String, text: String): Boolean {
        if (!isHealthAssistantEnabled(context)) return false
        val combined = "$title $text".trim()
        if (combined.isBlank()) return false
        var updated = false

        // Parse Sleep hours & minutes (e.g. "6h 30m", "7 hrs 15 mins", "6 နာရီ ၃၀ မိနစ်")
        val sleepRegex = Regex("""(?:sleep|slept|အိပ်ချိန်)\s*:?\s*(\d{1,2})\s*(?:h|hrs|hr|hours|နာရီ)\s*(\d{1,2})?\s*(?:m|min|mins|minutes|မိနစ်)?""", RegexOption.IGNORE_CASE)
        val sleepMatch = sleepRegex.find(combined)
        if (sleepMatch != null) {
            val hours = sleepMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val mins = sleepMatch.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val totalSleep = hours + (mins / 60.0)
            if (totalSleep > 0.0 && totalSleep < 24.0) {
                updated = updateCachedHealthData(context, sleepHours = totalSleep) || updated
            }
        }

        // Parse Steps (e.g. "8,420 steps", "Steps: 6500", "ခြေလှမ်း ၈၅၀၀")
        val stepsRegex = Regex("""(?:steps?|ခြေလှမ်း)\s*:?\s*([\d,]+)""", RegexOption.IGNORE_CASE)
        val stepsMatch = stepsRegex.find(combined)
        if (stepsMatch != null) {
            val cleanStr = stepsMatch.groupValues[1].replace(",", "")
            val parsedSteps = cleanStr.toIntOrNull()
            if (parsedSteps != null && parsedSteps > 0) {
                val estimatedCalories = (parsedSteps * 0.04).toInt()
                updated = updateCachedHealthData(context, steps = parsedSteps, calories = estimatedCalories) || updated
            }
        }

        // Parse Heart Rate (e.g. "72 bpm", "Heart rate 75")
        val hrRegex = Regex("""(?:heart rate|bpm|နှလုံးခုန်နှုန်း)\s*:?\s*(\d{2,3})""", RegexOption.IGNORE_CASE)
        val hrMatch = hrRegex.find(combined)
        if (hrMatch != null) {
            val hr = hrMatch.groupValues[1].toIntOrNull()
            if (hr != null && hr in 40..220) {
                updated = updateCachedHealthData(context, heartRate = hr) || updated
            }
        }

        return updated
    }

    suspend fun getHealthSummaryLive(context: Context, target: String? = null): String {
        val data = fetchHealthDataLive(context)
        if (!data.hasAnyData) {
            return unavailableMessage("Samsung Health/Health Connect မှ အတည်ပြုထားသော data မရရှိသေးပါ။")
        }
        val missingMetric = missingMetricMessage(data, target)
        if (missingMetric != null) return missingMetric
        return buildHealthSummary(context, data, target)
    }

    fun getHealthSummary(context: Context, target: String? = null): String {
        val data = fetchHealthData(context)
        if (!data.hasAnyData) {
            return unavailableMessage("Samsung Health/Health Connect permission သို့မဟုတ် data မရရှိသေးပါ။")
        }
        val missingMetric = missingMetricMessage(data, target)
        if (missingMetric != null) return missingMetric
        return buildHealthSummary(context, data, target)
    }

    private fun unavailableMessage(reason: String): String =
        "ကျန်းမာရေးဒေတာ အမှန်တကယ် မရရှိသေးပါ။ $reason နမူနာ/default ဒေတာကို မပြပါ။"

    private fun missingMetricMessage(data: HealthData, target: String?): String? {
        return when (target?.lowercase()?.trim()) {
            "sleep", "အိပ်ချိန်" -> if (data.sleepHours <= 0.0) unavailableMessage("Sleep session မတွေ့ပါ။") else null
            "activity", "steps", "ခြေလှမ်း", "လှုပ်ရှားမှု" -> if (data.steps <= 0 && data.activeCalories <= 0) unavailableMessage("Steps/activity record မတွေ့ပါ။") else null
            "workout", "exercise", "လေ့ကျင့်ခန်း" -> if (data.workoutMinutes <= 0) unavailableMessage("Workout record မတွေ့ပါ။") else null
            else -> null
        }
    }

    private fun buildHealthSummary(context: Context, data: HealthData, target: String? = null): String {
        val advice = HealthAdviceEngine.generateBurmeseAdvice(
            sleepHours = data.sleepHours,
            steps = data.steps,
            activeCalories = data.activeCalories,
            workoutMinutes = data.workoutMinutes,
            avgHeartRate = data.avgHeartRate
        )

        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val sleepTimeFormatted = String.format(Locale.US, "%.1f", data.sleepHours)

        return when (target?.lowercase()?.trim()) {
            "sleep", "အိပ်ချိန်" -> {
                val bedtimeInfo = if (data.bedtime != null && data.wakeTime != null) {
                    "\n- ဝင်ချိန် / နိုးချိန်: ${data.bedtime} မှ ${data.wakeTime}"
                } else ""
                """
                === 💤 Samsung Health: အိပ်ချိန် အခြေအနေ ($timeStr) ===
                - အရင်းအမြစ်: ${data.dataSource ?: "မသိရှိပါ"}
                - ညဉ့်အိပ်ချိန်: $sleepTimeFormatted နာရီ (Health Connect မှရရှိသော sleep session)$bedtimeInfo
                - အိပ်စက်မှု အရည်အသွေး: ${data.sleepQuality}
                - အနားယူချိန် နှလုံးခုန်နှုန်း: ${data.avgHeartRate} bpm
                
                [လက်ထောက် အကြံပြုချက်]:
                • $advice
                """.trimIndent()
            }
            "activity", "steps", "ခြေလှမ်း", "လှုပ်ရှားမှု" -> {
                """
                === 🚶‍♂️ Samsung Health: တနေ့တာ လှုပ်ရှားမှု ($timeStr) ===
                - အရင်းအမြစ်: ${data.dataSource ?: "မသိရှိပါ"}
                - ခြေလှမ်း ပမာဏ: ${data.steps} လှမ်း
                - လောင်ကျွမ်း ကယ်လိုရီ: ${data.activeCalories} kcal
                - နှလုံးခုန်နှုန်း: ${data.avgHeartRate} bpm
                
                [လက်ထောက် အကြံပြုချက်]:
                • $advice
                """.trimIndent()
            }
            "workout", "exercise", "လေ့ကျင့်ခန်း" -> {
                """
                === 🏋️‍♀️ Samsung Health: လေ့ကျင့်ခန်း အခြေအနေ ($timeStr) ===
                - အရင်းအမြစ်: ${data.dataSource ?: "မသိရှိပါ"}
                - လေ့ကျင့်ခန်း အမျိုးအစား: ${data.workoutType.ifBlank { "မရရှိသေးပါ" }}
                - လေ့ကျင့်ခန်း ပြုလုပ်ချိန်: ${data.workoutMinutes} မိနစ်
                - လောင်ကျွမ်း ကယ်လိုရီ: ${data.activeCalories} kcal
                
                [လက်ထောက် အကြံပြုချက်]:
                • $advice
                """.trimIndent()
            }
            else -> {
                """
                === ⌚ Samsung Health Smartwatch ကျန်းမာရေး အနှစ်ချုပ် ($timeStr) ===
                - 💤 ညဉ့်အိပ်ချိန်: $sleepTimeFormatted နာရီ (${data.sleepQuality})
                - 🚶‍♂️ ခြေလှမ်း ပမာဏ: ${data.steps} လှမ်း (ကယ်လိုရီ: ${data.activeCalories} kcal)
                - 🏋️‍♀️ လေ့ကျင့်ခန်း: ${data.workoutMinutes} မိနစ် (${data.workoutType})
                - ❤️ နှလုံးခုန်နှုန်း: ${data.avgHeartRate} bpm
                
                [လက်ထောက် အကြံပြုချက်များ]:
                • $advice
                """.trimIndent()
            }
        }
    }
}
