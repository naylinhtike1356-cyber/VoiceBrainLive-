package com.example.voicebrainlive

import com.example.voicebrainlive.usecases.AssistantAutomationPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AssistantAutomationPayload
 * 
 * Note: These tests use direct constructor calls instead of fromIntent()
 * because Intent.getStringExtra() doesn't work in local unit tests
 * without Robolectric.
 */
class AssistantAutomationPayloadTest {

    @Test
    fun payload_withExplicitValues() {
        val payload = AssistantAutomationPayload(
            type = "music",
            message = "Play focus music",
            musicQuery = "lofi mix",
            announceText = "Starting your playlist.",
            isPreReminder = true,
            alreadySpoken = false
        )

        assertEquals("music", payload.type)
        assertEquals("Play focus music", payload.message)
        assertEquals("lofi mix", payload.musicQuery)
        assertEquals("Starting your playlist.", payload.announceText)
        assertTrue(payload.isPreReminder)
        assertFalse(payload.alreadySpoken)
    }

    @Test
    fun payload_weatherFallbackAnnouncement() {
        val payload = AssistantAutomationPayload(
            type = "weather",
            message = "",
            musicQuery = "",
            announceText = "သတ်မှတ်ထားတဲ့ ရာသီဥတုကို စစ်ပြီး ပြောပြပါမယ်။",
            isPreReminder = false,
            alreadySpoken = false
        )

        assertEquals("weather", payload.type)
        assertEquals("သတ်မှတ်ထားတဲ့ ရာသီဥတုကို စစ်ပြီး ပြောပြပါမယ်။", payload.announceText)
    }

    @Test
    fun payload_musicFallbackAnnouncement() {
        val payload = AssistantAutomationPayload(
            type = "music",
            message = "",
            musicQuery = "",
            announceText = "သတ်မှတ်ထားတဲ့ သီချင်းကို စတင်ဖွင့်ပါမယ်။",
            isPreReminder = false,
            alreadySpoken = false
        )

        assertEquals("music", payload.type)
        assertEquals("သတ်မှတ်ထားတဲ့ သီချင်းကို စတင်ဖွင့်ပါမယ်။", payload.announceText)
    }

    @Test
    fun payload_defaultFallbackAnnouncement() {
        val payload = AssistantAutomationPayload(
            type = "unknown",
            message = "Custom message",
            musicQuery = "",
            announceText = "Custom message",
            isPreReminder = false,
            alreadySpoken = false
        )

        assertEquals("unknown", payload.type)
        assertEquals("Custom message", payload.announceText)
    }

    @Test
    fun testPayload_shouldSpeakFlag() {
        val silentPayload = AssistantAutomationPayload(
            type = "command",
            message = "turn_off_wifi",
            musicQuery = "",
            announceText = "",
            isPreReminder = false,
            alreadySpoken = false,
            shouldSpeak = false
        )
        assertFalse(silentPayload.shouldSpeak)

        val spokenPayload = AssistantAutomationPayload(
            type = "weather",
            message = "",
            musicQuery = "",
            announceText = "weather",
            isPreReminder = false,
            alreadySpoken = false,
            shouldSpeak = true
        )
        assertTrue(spokenPayload.shouldSpeak)
    }

    @Test
    fun testBuildCalmAnnounce_timeGreetings() {
        assertEquals("မင်္ဂလာ နံနက်ခင်းပါခင်ဗျာ", AssistantAutomationPayload.timeGreeting(7))
        assertEquals("မင်္ဂလာ နေ့လယ်ခင်းပါခင်ဗျာ", AssistantAutomationPayload.timeGreeting(13))
        assertEquals("မင်္ဂလာ ညနေခင်းပါခင်ဗျာ", AssistantAutomationPayload.timeGreeting(18))
        assertEquals("မင်္ဂလာ ညချမ်းပါခင်ဗျာ", AssistantAutomationPayload.timeGreeting(22))
    }

    @Test
    fun testBuildCalmAnnounce_reminderPhrasing() {
        val announce = AssistantAutomationPayload.buildCalmAnnounce(
            type = "reminder",
            message = "ဆေးသောက်ရန်",
            hourOfDay = 8
        )
        assertTrue(announce.contains("မင်္ဂလာ နံနက်ခင်းပါခင်ဗျာ"))
        assertTrue(announce.contains("ဆေးသောက်ရန်"))
        assertTrue(announce.contains("အေးအေးဆေးဆေး သတိပေးလိုက်ပါတယ်ခင်ဗျာ"))
    }

    @Test
    fun testBuildCalmAnnounce_weatherPhrasing() {
        val announce = AssistantAutomationPayload.buildCalmAnnounce(
            type = "weather",
            message = "",
            hourOfDay = 7
        )
        assertTrue(announce.contains("မင်္ဂလာ နံနက်ခင်းပါခင်ဗျာ"))
        assertTrue(announce.contains("ရာသီဥတု အခြေအနေလေးကို အေးဆေးသက်သာစွာ ကြားသိနိုင်ဖို့ တင်ပြပေးပါမယ်ခင်ဗျာ"))
    }

    @Test
    fun testBuildCalmAnnounce_dailyLogPhrasing() {
        val announce = AssistantAutomationPayload.buildCalmAnnounce(
            type = "daily_log",
            message = "",
            hourOfDay = 20
        )
        assertTrue(announce.contains("မင်္ဂလာ ညနေခင်းပါခင်ဗျာ"))
        assertTrue(announce.contains("လစာနဲ့ အသုံးစရိတ် မှတ်တမ်းလေးကို အေးအေးဆေးဆေး စစ်ဆေးဖော်ပြပေးပါမယ်ခင်ဗျာ"))
    }

    @Test
    fun testBuildCalmAnnounce_musicPhrasing() {
        val announce = AssistantAutomationPayload.buildCalmAnnounce(
            type = "music",
            message = "",
            musicQuery = "Lofi Hip Hop",
            hourOfDay = 14
        )
        assertTrue(announce.contains("သီချင်း Lofi Hip Hop ကို ဖွင့်ပေးပါမယ်ခင်ဗျာ"))
    }

    @Test
    fun testBuildCalmAnnounce_preReminderPhrasing() {
        val announce = AssistantAutomationPayload.buildCalmAnnounce(
            type = "reminder",
            message = "အစည်းအဝေး",
            isPreReminder = true,
            hourOfDay = 9
        )
        assertTrue(announce.contains("အေးအေးဆေးဆေး ကြိုတင်အသိပေးအပ်ပါတယ်ခင်ဗျာ"))
    }
}