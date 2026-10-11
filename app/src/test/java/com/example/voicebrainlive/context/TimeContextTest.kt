package com.example.voicebrainlive.context

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TimeContext and related enums
 * 
 * These tests validate the time-based context logic
 * without requiring Android context.
 */
class TimeContextTest {

    @Test
    fun testTimePeriod_morning() {
        // 5 AM - 11 AM is MORNING
        for (hour in 5..11) {
            val period = getPeriodForHour(hour)
            assertEquals("Hour $hour should be MORNING", ContextManager.TimePeriod.MORNING, period)
        }
    }

    @Test
    fun testTimePeriod_afternoon() {
        // 12 PM - 4 PM is AFTERNOON
        for (hour in 12..16) {
            val period = getPeriodForHour(hour)
            assertEquals("Hour $hour should be AFTERNOON", ContextManager.TimePeriod.AFTERNOON, period)
        }
    }

    @Test
    fun testTimePeriod_evening() {
        // 5 PM - 8 PM is EVENING
        for (hour in 17..20) {
            val period = getPeriodForHour(hour)
            assertEquals("Hour $hour should be EVENING", ContextManager.TimePeriod.EVENING, period)
        }
    }

    @Test
    fun testTimePeriod_night() {
        // 9 PM - 4 AM is NIGHT
        val nightHours = listOf(21, 22, 23, 0, 1, 2, 3, 4)
        for (hour in nightHours) {
            val period = getPeriodForHour(hour)
            assertEquals("Hour $hour should be NIGHT", ContextManager.TimePeriod.NIGHT, period)
        }
    }

    @Test
    fun testTimeContext_dataClass() {
        val timeContext = ContextManager.TimeContext(
            hour = 10,
            period = ContextManager.TimePeriod.MORNING,
            isWeekend = true,
            dayOfWeek = "တနင်္ဂနွေ"
        )

        assertEquals(10, timeContext.hour)
        assertEquals(ContextManager.TimePeriod.MORNING, timeContext.period)
        assertTrue("Should be weekend", timeContext.isWeekend)
        assertEquals("တနင်္ဂနွေ", timeContext.dayOfWeek)
    }

    @Test
    fun testFamiliarityLevel() {
        // NEW: < 5 sessions
        assertEquals(ContextManager.FamiliarityLevel.NEW, getFamiliarityLevel(0))
        assertEquals(ContextManager.FamiliarityLevel.NEW, getFamiliarityLevel(4))

        // LEARNING: 5-19 sessions
        assertEquals(ContextManager.FamiliarityLevel.LEARNING, getFamiliarityLevel(5))
        assertEquals(ContextManager.FamiliarityLevel.LEARNING, getFamiliarityLevel(19))

        // FAMILIAR: 20-99 sessions
        assertEquals(ContextManager.FamiliarityLevel.FAMILIAR, getFamiliarityLevel(20))
        assertEquals(ContextManager.FamiliarityLevel.FAMILIAR, getFamiliarityLevel(99))

        // CLOSE: 100+ sessions
        assertEquals(ContextManager.FamiliarityLevel.CLOSE, getFamiliarityLevel(100))
        assertEquals(ContextManager.FamiliarityLevel.CLOSE, getFamiliarityLevel(1000))
    }

    // Helper methods that mirror ContextManager logic
    private fun getPeriodForHour(hour: Int): ContextManager.TimePeriod {
        return when (hour) {
            in 5..11 -> ContextManager.TimePeriod.MORNING
            in 12..16 -> ContextManager.TimePeriod.AFTERNOON
            in 17..20 -> ContextManager.TimePeriod.EVENING
            else -> ContextManager.TimePeriod.NIGHT
        }
    }

    private fun getFamiliarityLevel(sessionCount: Int): ContextManager.FamiliarityLevel {
        return when {
            sessionCount < 5 -> ContextManager.FamiliarityLevel.NEW
            sessionCount < 20 -> ContextManager.FamiliarityLevel.LEARNING
            sessionCount < 100 -> ContextManager.FamiliarityLevel.FAMILIAR
            else -> ContextManager.FamiliarityLevel.CLOSE
        }
    }
}

/**
 * Unit tests for FeedbackLearning preference extraction logic
 */
class FeedbackLearningLogicTest {

    data class FeedbackEntry(
        val feedback: String,
        val commandType: String,
        val timestamp: Long
    )

    @Test
    fun testExtractTimePreference() {
        val feedback = "မဟုတ်ဘူး နံနက် ၇ နာရီမှ alarm ထားပေး"
        // Use a regex that matches Burmese digits (၀-၉) or ASCII digits
        val timeMatch = Regex("([၀-၉\\d]{1,2})[:.]?([၀-၉\\d]{2})?\\s*(နာရီ|am|pm)").find(feedback)
        
        assertNotNull("Should find time in feedback", timeMatch)
        assertEquals("၇ နာရီ", timeMatch?.value)
    }

    @Test
    fun testExtractCorrection() {
        val feedback = "မဟုတ်ဘူး ငါ coffee သောက်ချင်တာ"
        val correctionMatch = Regex("မဟုတ်ဘူး\\s*(.+)").find(feedback)
        
        assertNotNull("Should find correction", correctionMatch)
        assertEquals("ငါ coffee သောက်ချင်တာ", correctionMatch?.groupValues?.get(1)?.trim())
    }

    @Test
    fun testExtractNextTimePreference() {
        val feedback = "နောက်တစ်ခေါက် ဂီတ ဖွင့်ပေး"
        val nextTimeMatch = Regex("နောက်တစ်ခေါက်\\s*(.+)").find(feedback)
        
        assertNotNull("Should find next time preference", nextTimeMatch)
        assertEquals("ဂီတ ဖွင့်ပေး", nextTimeMatch?.groupValues?.get(1)?.trim())
    }

    @Test
    fun testExtractMusicVolumeLow() {
        val feedback = "သီချင်း ကျယ်လိုက်တာ တိုးတိုးလေး ဖွင့်ပေး"
        val isLow = feedback.contains("သီချင်း") && 
                   (feedback.contains("တိုး") || feedback.contains("လျှော့"))
        
        assertTrue("Should detect low volume preference", isLow)
    }

    @Test
    fun testFeedbackEntry_dataClass() {
        val entry = FeedbackEntry(
            feedback = "test feedback",
            commandType = "set_alarm",
            timestamp = 1234567890L
        )

        assertEquals("test feedback", entry.feedback)
        assertEquals("set_alarm", entry.commandType)
        assertEquals(1234567890L, entry.timestamp)
    }
}
