package com.example.voicebrainlive

import com.example.voicebrainlive.weather.WeatherNarrative
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherNarrativeTest {

    @Test
    fun current_includesNaturalTemperatureWindAndRainTiming() {
        val result = WeatherNarrative.current(
            location = "Seoul",
            temperature = 8,
            minTemp = 5,
            maxTemp = 14,
            weatherCode = 2,
            isRainExpected = false,
            humidity = 45,
            windSpeed = 18.0,
            rainSummary = "မိုးက ဒီနေ့ ညနေ ၅ နာရီလောက်မှာ ရွာနိုင်ချေမြင့်တယ်။"
        )

        assertTrue(result.contains("အေး"))
        assertTrue(result.contains("လေအေး"))
        assertTrue(result.contains("ညနေ ၅ နာရီ"))
    }

    @Test
    fun summarizeRainWindows_groupsConsecutiveHours() {
        val result = WeatherNarrative.summarizeRainWindows(
            listOf(9 to 70, 10 to 65, 14 to 80),
            "မနက်ဖြန်"
        )

        assertTrue(result.contains("မနက် 9 နာရီ ကနေ မနက် 10 နာရီ"))
        assertTrue(result.contains("နေ့လယ် 2 နာရီ"))
    }
}
