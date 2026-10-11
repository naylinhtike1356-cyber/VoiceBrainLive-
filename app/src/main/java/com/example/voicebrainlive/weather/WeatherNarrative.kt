package com.example.voicebrainlive.weather

/**
 * Converts raw weather numbers into short, natural Burmese speech.
 *
 * The assistant should sound like a person describing the weather, not like it
 * is reading an API response.
 */
object WeatherNarrative {

    fun current(
        location: String,
        temperature: Int,
        minTemp: Int,
        maxTemp: Int,
        weatherCode: Int,
        isRainExpected: Boolean,
        humidity: Int,
        windSpeed: Double,
        rainSummary: String = ""
    ): String {
        val rainLine = when {
            rainSummary.isNotBlank() -> rainSummary
            isRainExpected || isRainCode(weatherCode) -> "မိုးရွာနိုင်တာမို့ ထီးယူသွားတာကောင်းမယ်။"
            else -> "မိုးကတော့ မရွာနိုင်ချေများပါတယ်။"
        }

        return listOf(
            "${location.ifBlank { "ဒီနေရာ" }} မှာ အခု ${temperature}°C လောက်ရှိနေတယ်။",
            temperatureFeeling(temperature),
            conditionPhrase(weatherCode, temperature),
            temperatureRangeAdvice(temperature, minTemp, maxTemp),
            windPhrase(temperature, windSpeed),
            humidityPhrase(humidity),
            rainLine
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun dayForecast(
        location: String,
        dayLabel: String,
        minTemp: Int,
        maxTemp: Int,
        weatherCode: Int,
        rainSummary: String = ""
    ): String {
        val rainLine = rainSummary.ifBlank {
            if (isRainCode(weatherCode)) "မိုးရွာနိုင်တာမို့ ထီးယူသွားတာကောင်းမယ်။" else "မိုးအတွက်တော့ စိုးရိမ်စရာနည်းပါတယ်။"
        }

        return listOf(
            "${location.ifBlank { "ဒီနေရာ" }} မှာ $dayLabel ${conditionPhrase(weatherCode, (minTemp + maxTemp) / 2)}",
            "အပူချိန်က $minTemp ကနေ $maxTemp°C လောက်ရှိမယ်။",
            rangeFeeling(minTemp, maxTemp),
            rainLine
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun specificHour(
        location: String,
        hour: Int,
        temperature: Int,
        weatherCode: Int,
        rainProbability: Int
    ): String {
        val rainLine = when {
            rainProbability >= 70 -> "မိုးရွာနိုင်ချေ မြင့်တယ် (${rainProbability}%)၊ ထီးယူသွားပါ။"
            rainProbability >= 40 -> "မိုးရွာနိုင်ချေ နည်းနည်းရှိတယ် (${rainProbability}%)။"
            else -> "မိုးရွာနိုင်ချေ နည်းပါတယ်။"
        }
        return listOf(
            "${location.ifBlank { "ဒီနေရာ" }} မှာ ${hourLabel(hour)} လောက် ${temperature}°C ရှိမယ်။",
            conditionPhrase(weatherCode, temperature),
            temperatureFeeling(temperature),
            rainLine
        ).joinToString(" ")
    }

    fun summarizeRainWindowsFromHourly(hourlyRainTimes: List<HourlyForecasts>, dayLabel: String): String {
        val hours = hourlyRainTimes
            .filter { it.rainProbability >= 55 }
            .map { it.hour to it.rainProbability }
        return summarizeRainWindows(hours, dayLabel)
    }

    fun summarizeRainWindows(hoursWithProbability: List<Pair<Int, Int>>, dayLabel: String): String {
        if (hoursWithProbability.isEmpty()) return ""
        val sorted = hoursWithProbability.sortedBy { it.first }
        val windows = mutableListOf<Pair<Int, Int>>()
        var start = sorted.first().first
        var end = start

        sorted.drop(1).forEach { (hour, _) ->
            if (hour <= end + 1) {
                end = hour
            } else {
                windows.add(start to end)
                start = hour
                end = hour
            }
        }
        windows.add(start to end)

        val text = windows.take(3).joinToString(", ") { (from, to) ->
            if (from == to) "${hourLabel(from)} လောက်"
            else "${hourLabel(from)} ကနေ ${hourLabel(to)} လောက်"
        }
        return "မိုးက $dayLabel $text မှာ ရွာနိုင်ချေမြင့်တယ်။"
    }

    fun hourLabel(hour: Int): String {
        val h = ((hour % 24) + 24) % 24
        return when (h) {
            0 -> "ည ၁၂ နာရီ"
            in 1..4 -> "မနက်အစော $h နာရီ"
            in 5..11 -> "မနက် $h နာရီ"
            12 -> "နေ့လယ် ၁၂ နာရီ"
            in 13..16 -> "နေ့လယ် ${h - 12} နာရီ"
            in 17..18 -> "ညနေ ${h - 12} နာရီ"
            else -> "ည ${h - 12} နာရီ"
        }
    }

    private fun temperatureFeeling(temp: Int): String = when {
        temp <= 3 -> "တော်တော်အေးနေတယ်၊ အနွေးထည်ထူထူလိုမယ်။"
        temp <= 10 -> "အေးနေတယ်၊ အပြင်ထွက်ရင် အနွေးထည်ယူသွားပါ။"
        temp <= 17 -> "လေအေးအေးနဲ့ နည်းနည်းအေးတဲ့ဘက်ပါ။"
        temp <= 25 -> "အပူချိန်က နေထိုင်လို့ကောင်းတဲ့အနေအထားပါ။"
        temp <= 30 -> "နည်းနည်းပူတဲ့ဘက်ရောက်နေတယ်။"
        else -> "ပူနေတယ်၊ ရေများများသောက်ပြီး နေပူထဲကြာကြာမနေပါနဲ့။"
    }

    private fun rangeFeeling(minTemp: Int, maxTemp: Int): String = when {
        maxTemp <= 10 -> "တစ်နေ့လုံး အေးမယ့်ဘက်ပါ။"
        minTemp <= 8 && maxTemp <= 18 -> "မနက်ပိုင်းနဲ့ ညပိုင်း အေးနိုင်တယ်။"
        maxTemp >= 32 -> "နေ့လယ်ပိုင်း ပူနိုင်တယ်၊ ရေသောက်ဖို့ မမေ့ပါနဲ့။"
        maxTemp - minTemp >= 10 -> "မနက်နဲ့နေ့လယ် အပူချိန်ကွာနိုင်လို့ အဝတ်အစားကို လွယ်လွယ်ချွတ်ဝတ်လို့ရအောင်စီစဉ်ပါ။"
        else -> ""
    }

    private fun temperatureRangeAdvice(temp: Int, minTemp: Int, maxTemp: Int): String {
        if (minTemp == 0 && maxTemp == 0) return ""
        return when {
            temp - minTemp <= 2 -> "ဒီနေ့အနိမ့်ဆုံးနားမှာရှိနေလို့ အေးနိုင်တယ်။"
            maxTemp - temp <= 2 -> "အခုက ဒီနေ့အပူဆုံးအချိန်နားရောက်နေတယ်။"
            else -> "ဒီနေ့ $minTemp ကနေ $maxTemp°C ကြားလောက်သွားမယ်။"
        }
    }

    private fun conditionPhrase(code: Int, temp: Int): String = when (code) {
        0 -> if (temp >= 30) "နေက တောက်တောက်ပပထွက်ပြီး ပူတဲ့ဘက်ပါ။" else "ကောင်းကင်ကြည်ပြီး နေသာနေတယ်။"
        in 1..3 -> "တိမ်နည်းနည်းရှိပေမယ့် သာမန်အခြေအနေပါ။"
        in 45..48 -> "မြူနည်းနည်းရှိနိုင်တယ်၊ သွားလာရင် မြင်ကွင်းကိုသတိထားပါ။"
        in 51..67 -> "မိုးဖွဲဖွဲကနေ မိုးရွာနိုင်တဲ့အခြေအနေပါ။"
        in 71..77 -> "နှင်းကျနိုင်တယ်၊ လမ်းချော်နိုင်လို့သတိထားပါ။"
        in 80..82 -> "မိုးသီးသန့် ရွာနိုင်တဲ့အခြေအနေပါ။"
        in 85..86 -> "နှင်းသီးသန့် ကျနိုင်တယ်။"
        in 95..99 -> "မိုးကြိုးမုန်တိုင်းဖြစ်နိုင်လို့ အပြင်သွားရင်သတိထားပါ။"
        else -> "ရာသီဥတုက သာမန်အခြေအနေပါ။"
    }

    private fun humidityPhrase(humidity: Int): String = when {
        humidity <= 0 -> ""
        humidity >= 85 -> "စိုထိုင်းဆမြင့်လို့ ချွေးထွက်လွယ်နိုင်တယ်။"
        humidity <= 30 -> "လေက ခြောက်သွေ့နည်းနည်းဖြစ်နေတယ်။"
        else -> ""
    }

    private fun windPhrase(temp: Int, windSpeed: Double): String = when {
        windSpeed >= 40.0 -> "လေကြမ်းနေတယ်၊ အပြင်သွားရင်သတိထားပါ။"
        windSpeed >= 25.0 -> "လေတော်တော်တိုက်နေတယ်။"
        windSpeed >= 12.0 && temp <= 15 -> "လေအေးတိုက်နေတယ်။"
        windSpeed >= 12.0 -> "လေတိုက်နေတာ သိသာတယ်။"
        windSpeed >= 5.0 && temp <= 12 -> "လေပါနည်းနည်းရှိလို့ ပိုအေးသလိုခံစားရနိုင်တယ်။"
        else -> ""
    }

    private fun isRainCode(code: Int): Boolean =
        code in 51..67 || code in 80..82 || code in 95..99
}
