package com.example.voicebrainlive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * Reads salary data from SalaryTracker app via ContentProvider.
 * Used by VoiceBrainLive to answer voice queries like "ဒီလ လစာဘယ်လောက်ရလဲ", "ယနေ့မှတ်တမ်းရှိပြီးပြီလား".
 */
object SalaryDataReader {

    private const val AUTHORITY = "com.example.salarytracker.voiceprovider"
    private const val URI_SALARY = "content://$AUTHORITY/salary"
    private const val URI_ACTIONS = "content://$AUTHORITY/actions"
    private val SALARY_URI = Uri.parse(URI_SALARY)
    private val ACTIONS_URI = Uri.parse(URI_ACTIONS)

    /**
     * Query SalaryTracker and return human-readable answer based on target.
     * @param target "" = full summary, "current_month" = this month total, "today" = today status,
     *               "yesterday" = yesterday work, "ot_this_month" = overtime this month
     */
    fun getSalaryAnswer(context: Context, target: String?): String {
        return try {
            val cursor = context.contentResolver.query(
                SALARY_URI,
                arrayOf("data"),
                null,
                null,
                null
            ) ?: return "လစာမှတ်တမ်းအက်ပ်မတွေ့ပါ။ SalaryTracker ထည့်သွင်းထားပါစေ။"
            cursor.use {
                if (!it.moveToFirst()) {
                    return "လစာမှတ်တမ်းမရှိသေးပါ။ အက်ပ်ဖွင့်ပြီး မှတ်တမ်းထည့်ပါ။"
                }
                val colIdx = it.getColumnIndex("data")
                if (colIdx < 0) return "ဒေတာဖတ်ယူရာတွင် အမှားတက်ခဲ့သည်။"
                val jsonStr = it.getString(colIdx)
                if (jsonStr.isNullOrBlank()) return "လစာမှတ်တမ်းမရှိသေးပါ။"
                parseAndAnswer(jsonStr, target?.trim()?.lowercase() ?: "")
            }
        } catch (e: SecurityException) {
            Log.e("SalaryDataReader", "Permission: ${e.message}")
            "လစာမှတ်တမ်းကို ဖတ်ယူခွင့်မရှိပါ။"
        } catch (e: Exception) {
            Log.e("SalaryDataReader", "Error: ${e.message}", e)
            "လစာမှတ်တမ်း စစ်ဆေးရာတွင် အမှားတက်ခဲ့သည်။"
        }
    }

    /**
     * Trigger SalaryTracker action via ContentProvider insert.
     * @param action "quick_add_today" or "copy_yesterday"
     * @return Success message or error string
     */
    fun insertSalaryAction(context: Context, action: String): String {
        return try {
            val values = ContentValues().apply { put("action", action) }
            val uri = context.contentResolver.insert(ACTIONS_URI, values)
            if (uri != null) {
                when (action) {
                    "quick_add_today" -> "ယနေ့မှတ်တမ်း ထည့်မယ့်အက်ပ်ကို ဖွင့်ပေးလိုက်ပါပြီ။ အက်ပ်မှာ အတည်ပြုပါ။"
                    "copy_yesterday" -> "မနေ့ကလို ထည့်မယ့်အက်ပ်ကို ဖွင့်ပေးလိုက်ပါပြီ။ အက်ပ်မှာ အတည်ပြုပါ။"
                    else -> "လုပ်ဆောင်ချက် မမှန်ပါ။"
                }
            } else {
                "လစာမှတ်တမ်းအက်ပ်မတွေ့ပါ။ SalaryTracker ထည့်သွင်းထားပါစေ။"
            }
        } catch (e: SecurityException) {
            Log.e("SalaryDataReader", "insertSalaryAction: ${e.message}")
            "လစာမှတ်တမ်းကို ပြင်ဆင်ခွင့်မရှိပါ။"
        } catch (e: Exception) {
            Log.e("SalaryDataReader", "insertSalaryAction: ${e.message}", e)
            "လစာမှတ်တမ်း ပြင်ဆင်ရာတွင် အမှားတက်ခဲ့သည်။"
        }
    }

    private fun parseAndAnswer(jsonStr: String, target: String): String {
        val json = JSONObject(jsonStr)
        val timeData = json.optJSONArray("timeData") ?: org.json.JSONArray()
        val monthlyExtras = json.optJSONArray("monthlyExtras") ?: org.json.JSONArray()
        val settings = json.optJSONObject("settings") ?: JSONObject()
        val baseRate = settings.optDouble("regular_base", 0.0).takeIf { it > 0 }
            ?: settings.optDouble("regular_base_rate", 0.0).takeIf { it > 0 }
            ?: 10030.0

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH)
        val monthKey = String.format(Locale.US, "%04d-%02d", year, month + 1)

        val t = target
        val effectiveTarget = when {
            t in listOf("today") || t.contains("ယနေ့") -> "today"
            t in listOf("yesterday") || t.contains("မနေ့") -> "yesterday"
            t in listOf("ot_this_month", "overtime") || t.contains("အချိန်ပို") -> "ot_this_month"
            t in listOf("current_month", "this_month", "ဒီလ") || t.contains("ဒီလ") || t.contains("လစာဘယ်လောက်") || t.contains("လစာပြပေး") || t.contains("လစာ") -> "current_month"
            else -> t
        }
        return when (effectiveTarget) {
            "today" -> getTodayStatus(timeData, today)
            "yesterday" -> getYesterdayWork(timeData, today)
            "ot_this_month", "overtime" -> getOtThisMonth(timeData, monthKey)
            "current_month", "this_month", "ဒီလ" -> getCurrentMonthSummary(timeData, monthlyExtras, monthKey, baseRate, settings)
            else -> getFullSummary(timeData, monthlyExtras, monthKey, baseRate, settings)
        }
    }

    private fun getTodayStatus(timeData: org.json.JSONArray, today: String): String {
        var totalPay = 0.0
        var totalMs = 0L
        for (i in 0 until timeData.length()) {
            val e = timeData.getJSONObject(i)
            if (e.optString("date") == today) {
                val type = e.optString("type", "")
                if (type.contains("base") || type.contains("ot")) {
                    totalPay += getPay(e)
                    totalMs += getDurationMs(e)
                }
            }
        }
        return if (totalMs > 0) {
            "ယနေ့မှတ်တမ်း ရှိပြီးပါပြီ။ ${formatDuration(totalMs)} လုပ်ပြီး လုပ်ခ ${formatKrw(totalPay)} ရရှိပါသည်။"
        } else {
            "ယနေ့မှတ်တမ်း မရှိသေးပါ။ ပြက္ခဒိန်မှ ထည့်ပါ။"
        }
    }

    private fun getYesterdayWork(timeData: org.json.JSONArray, today: String): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        var totalPay = 0.0
        var totalMs = 0L
        for (i in 0 until timeData.length()) {
            val e = timeData.getJSONObject(i)
            if (e.optString("date") == yesterday) {
                val type = e.optString("type", "")
                if (type.contains("base") || type.contains("ot")) {
                    totalPay += getPay(e)
                    totalMs += getDurationMs(e)
                }
            }
        }
        return if (totalMs > 0) {
            "မနေ့က ${formatDuration(totalMs)} လုပ်ပြီး လုပ်ခ ${formatKrw(totalPay)} ရခဲ့ပါတယ်။"
        } else {
            "မနေ့က အလုပ်မှတ်တမ်း မရှိပါ။"
        }
    }

    private fun getOtThisMonth(timeData: org.json.JSONArray, monthKey: String): String {
        var otMs = 0L
        var otPay = 0.0
        for (i in 0 until timeData.length()) {
            val e = timeData.getJSONObject(i)
            if (!e.optString("date", "").startsWith(monthKey)) continue
            val type = e.optString("type", "")
            if (type.contains("_ot")) {
                otMs += getDurationMs(e)
                otPay += getPay(e)
            }
        }
        return if (otMs > 0) {
            "ဒီလအတွင်း အချိန်ပို ${formatDuration(otMs)} လုပ်ပြီး အချိန်ပိုလုပ်ခ ${formatKrw(otPay)} ရပါတယ်။"
        } else {
            "ဒီလအတွင်း အချိန်ပို မှတ်တမ်းမရှိသေးပါ။"
        }
    }

    private fun getCurrentMonthSummary(
        timeData: org.json.JSONArray,
        monthlyExtras: org.json.JSONArray,
        monthKey: String,
        baseRate: Double,
        settings: JSONObject
    ): String {
        val normalDays = mutableSetOf<String>()
        val holidayDays = mutableSetOf<String>()
        var basePay = 0.0
        var otPay = 0.0
        var leavePay = 0.0
        var bonusPay = 0.0
        var otherPay = 0.0
        var totalBaseMs = 0L   // ပုံမှန်နာရီသာ (regular_base + holiday_base, OT မပါ)
        var totalOtMs = 0L     // အချိန်ပိုသာ
        var weeklyHolidays = settings.optInt("weekly_holidays", 4)
        for (i in 0 until monthlyExtras.length()) {
            val ex = monthlyExtras.getJSONObject(i)
            if (ex.optString("month") == monthKey) {
                weeklyHolidays = ex.optInt("weekly_holidays", weeklyHolidays)
            }
        }
        for (i in 0 until timeData.length()) {
            val e = timeData.getJSONObject(i)
            if (!e.optString("date", "").startsWith(monthKey)) continue
            val type = e.optString("type", "")
            val date = e.optString("date")
            val dur = getDurationMs(e)
            when {
                type == "regular_base" || type.contains("regular_base") -> {
                    normalDays.add(date)
                    basePay += getPay(e)
                    totalBaseMs += dur
                }
                type == "holiday_base" || type.contains("holiday_base") -> {
                    holidayDays.add(date)
                    basePay += getPay(e)
                    totalBaseMs += dur
                }
                type.contains("_ot") -> {
                    if (!normalDays.contains(date) && !holidayDays.contains(date)) normalDays.add(date)
                    otPay += getPay(e)
                    totalOtMs += dur
                }
                type == "paid_leave" || type == "paid_public_holiday" -> leavePay += getPay(e)
                type == "bonus" -> bonusPay += getPay(e)
                type == "other_allowance" -> otherPay += getPay(e)
            }
        }
        val weeklyHolidayPay = weeklyHolidays * 8 * baseRate
        val total = basePay + otPay + leavePay + bonusPay + otherPay + weeklyHolidayPay
        val totalWorkDays = normalDays.size + holidayDays.size
        return buildFullSummary(
            totalWorkDays = totalWorkDays,
            normalDays = normalDays.size,
            holidayDays = holidayDays.size,
            totalWorkMs = totalBaseMs,
            totalOtMs = totalOtMs,
            totalSalary = total,
            monthKey = monthKey
        )
    }

    /** Support durationMs and duration_ms (snake_case) from different export formats. */
    private fun getDurationMs(e: org.json.JSONObject): Long {
        if (e.has("durationMs")) return e.optLong("durationMs", 0)
        if (e.has("duration_ms")) return e.optLong("duration_ms", 0)
        return 0L
    }

    private fun getPay(e: org.json.JSONObject): Double = e.optDouble("pay", 0.0)

    /** စကရင်မှာ ပြသလို ၁-၅ အချက်အလက် ပြည့်စုံပြန်ဖြေ */
    private fun buildFullSummary(
        totalWorkDays: Int,
        normalDays: Int,
        holidayDays: Int,
        totalWorkMs: Long,
        totalOtMs: Long,
        totalSalary: Double,
        monthKey: String
    ): String {
        val totalHrs = totalWorkMs / 3600000
        val totalMin = (totalWorkMs % 3600000) / 60000
        val otHrs = totalOtMs / 3600000
        val otMin = (totalOtMs % 3600000) / 60000

        val salaryNum = kotlin.math.round(totalSalary).toLong()
        val krwBurmese = formatKrwBurmese(salaryNum)

        val parts = mutableListOf<String>()
        parts.add("၁။ စုစုပေါင်းရက် $totalWorkDays ရက်")
        parts.add(
            if (holidayDays > 0) "၂။ ပိတ်ရက် $holidayDays ရက် ရှိပါတယ်"
            else "၂။ ပိတ်ရက် မရှိပါ"
        )
        parts.add(
            if (totalMin > 0) "၃။ နာရီစုစုပေါင်း $totalHrs နာရီ $totalMin မိနစ်"
            else "၃။ နာရီစုစုပေါင်း $totalHrs နာရီ"
        )
        parts.add(
            if (otMin > 0) "၄။ အချိန်ပို $otHrs နာရီ $otMin မိနစ်"
            else "၄။ အချိန်ပို $otHrs နာရီ"
        )
        parts.add("၅။ လစာ $krwBurmese")

        return "${monthKey} အကျဉ်းချုပ်။ " + parts.joinToString("။ ") + "။"
    }

    /** မြန်မာလို လစာကိန်းဖတ်ခြင်း - သိန်း/သောင်း/ထောင်/ရာ။ ဥပမာ ၁,၆၂၇,၃၆၈ -> တစ်ဆယ့်ခြောက်သိန်း နှစ်သောင်း ခုနှစ်ထောင် သုံးရာ ခြောက်ဆယ် ရှစ်ဝမ် */
    private fun formatKrwBurmese(n: Long): String {
        if (n <= 0) return "သုည ဝမ်"
        val digits = arrayOf("", "တစ်", "နှစ်", "သုံး", "လေး", "ငါး", "ခြောက်", "ခုနှစ်", "ရှစ်", "ကိုး")
        val tens = arrayOf("", "တစ်ဆယ်", "နှစ်ဆယ်", "သုံးဆယ်", "လေးဆယ်", "ငါးဆယ်", "ခြောက်ဆယ်", "ခုနှစ်ဆယ်", "ရှစ်ဆယ်", "ကိုးဆယ်")
        fun readUpTo99(x: Int): String {
            if (x == 0) return ""
            if (x < 10) return digits[x]
            if (x in 10..19) return if (x == 10) "တစ်ဆယ်" else "တစ်ဆယ့်${digits[x % 10]}"
            val t = x / 10
            val o = x % 10
            return if (o == 0) tens[t] else "${tens[t]}့${digits[o]}"
        }
        fun readUpTo999(x: Int): String {
            if (x == 0) return ""
            val h = x / 100
            val r = x % 100
            val hundredStr = when (h) {
                0 -> ""
                1 -> "တစ်ရာ"
                else -> "${digits[h]}ရာ"
            }
            return when {
                h == 0 -> readUpTo99(r)
                r == 0 -> hundredStr
                else -> hundredStr + "့" + readUpTo99(r)
            }
        }
        fun readUnit(value: Int, unitName: String): String {
            return when {
                value < 10 -> "${digits[value]}$unitName"
                value == 10 -> "တစ်ဆယ်$unitName"
                value in 11..19 -> "တစ်ဆယ့်${digits[value % 10]}$unitName"
                else -> readUpTo999(value) + unitName
            }
        }
        fun readLastPartWithWon(x: Int): String {
            if (x == 0) return ""
            if (x < 10) return "${digits[x]}ဝမ်"
            if (x in 10..19) return (if (x == 10) "တစ်ဆယ်" else "တစ်ဆယ့်${digits[x % 10]}") + "ဝမ်"
            val t = x / 10
            val o = x % 10
            return if (o == 0) "${tens[t]}ဝမ်" else "${tens[t]} ${digits[o]}ဝမ်"
        }
        var x = n
        val segments = mutableListOf<String>()
        val units = listOf(
            100_000L to "သိန်း",
            10_000L to "သောင်း",
            1_000L to "ထောင်",
            100L to "ရာ",
            1L to ""
        )
        for ((div, unit) in units) {
            if (x < div) continue
            val v = (x / div).toInt()
            x %= div
            if (v == 0) continue
            val part = when (unit) {
                "သိန်း" -> readUnit(v, "သိန်း")
                "သောင်း" -> readUnit(v, "သောင်း")
                "ထောင်" -> readUnit(v, "ထောင်")
                "ရာ" -> readUpTo99(v) + "ရာ"
                else -> readLastPartWithWon(v)
            }
            segments.add(part)
        }
        if (x > 0) segments.add(readLastPartWithWon(x.toInt()))
        return segments.joinToString(" ")
    }

    private fun getFullSummary(
        timeData: org.json.JSONArray,
        monthlyExtras: org.json.JSONArray,
        monthKey: String,
        baseRate: Double,
        settings: JSONObject
    ): String {
        return getCurrentMonthSummary(timeData, monthlyExtras, monthKey, baseRate, settings)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0 နာရီ"
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = (totalMin % 60).toInt()
        return if (m > 0) "${h} နာရီ ${m} မိနစ်" else "${h} နာရီ"
    }

    private fun formatKrw(amount: Double): String {
        val n = amount.toLong()
        return NumberFormat.getNumberInstance(Locale.US).format(n) + " ဝမ်"
    }
}
