package com.example.voicebrainlive

import com.example.voicebrainlive.usecases.ProactivePromptHelper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class ProactivePromptHelperTest {

    @Test
    fun buildPrompt_returnsCalendarVariant() {
        val result = ProactivePromptHelper.buildPrompt("Meeting in 10 minutes", "calendar_partner")

        assertEquals(
            "PROACTIVE CALENDAR ALERT: Meeting in 10 minutes. Skip greetings, start in Burmese.",
            result
        )
    }

    @Test
    fun buildPrompt_returnsDefaultVariant() {
        val result = ProactivePromptHelper.buildPrompt("Take a break", null)

        assertEquals(
            "CRITICAL: SKIP GREETINGS. Content: Take a break",
            result
        )
    }

    @Test
    fun buildPrompt_returnsWeatherRoutine() {
        val result = ProactivePromptHelper.buildPrompt("", "routine_weather")
        assert(result.contains("PROACTIVE WEATHER ROUTINE"))
        assert(result.contains("get_weather"))
    }

    @Test
    fun buildPrompt_returnsMusicRoutine() {
        val result = ProactivePromptHelper.buildPrompt("", "routine_music")
        assert(result.contains("PROACTIVE MUSIC ROUTINE"))
        assert(result.contains("play_music"))
    }

    @Test
    fun buildPrompt_returnsDailyLogRoutine() {
        val result = ProactivePromptHelper.buildPrompt("", "routine_daily_log")
        assert(result.contains("PROACTIVE DAILY LOG ROUTINE"))
        assert(result.contains("get_salary_data"))
    }

    @Test
    fun buildPrompt_returnsReminderRoutine() {
        val result = ProactivePromptHelper.buildPrompt("Drink water", "routine_reminder")
        assert(result.contains("PROACTIVE ROUTINE REMINDER: Drink water"))
    }

    @Test
    fun buildPrompt_returnsCommandRoutine() {
        val result = ProactivePromptHelper.buildPrompt("Mute phone", "routine_command")
        assert(result.contains("PROACTIVE ROUTINE COMMAND: Mute phone"))
    }

    @Test
    fun buildPrompt_returnsMessengerReply_withProactiveToolConstraint() {
        val payload = "sender=Mg Mg&msg=Hey bro&app=Messenger"
        val base64 = "MESSENGER_REPLY_V2:" + android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP)
        val result = ProactivePromptHelper.buildPrompt(base64, com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.PROACTIVE_TYPE_MESSENGER_REPLY)

        org.junit.Assert.assertTrue(result.contains("Mg Mg"))
        org.junit.Assert.assertTrue(result.contains("Hey bro"))
        org.junit.Assert.assertTrue(result.contains("Messenger"))
        org.junit.Assert.assertTrue(result.contains("CRITICAL: DO NOT CALL ANY TOOL"))
        org.junit.Assert.assertTrue(result.contains("STRICT ZERO-TOLERANCE RULES"))
    }

    @Test
    fun buildPrompt_handlesBurmeseAndUrlEncodingWithoutTranslating() {
        val sender = java.net.URLEncoder.encode("자자땃", "UTF-8")
        val msg = java.net.URLEncoder.encode("ဟေ့မောင်", "UTF-8")
        val app = java.net.URLEncoder.encode("Messenger", "UTF-8")
        val payload = "sender=$sender&msg=$msg&app=$app"
        val base64 = "MESSENGER_REPLY_V2:" + android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP)
        val result = ProactivePromptHelper.buildPrompt(base64, com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.PROACTIVE_TYPE_MESSENGER_REPLY)

        org.junit.Assert.assertTrue(result.contains("자자땃"))
        org.junit.Assert.assertTrue(result.contains("ဟေ့မောင်"))
        org.junit.Assert.assertTrue(result.contains("Messenger"))
        org.junit.Assert.assertTrue(result.contains("DO NOT translate the message 'ဟေ့မောင်'"))
    }

    @Test
    fun buildPrompt_returnsAppNotification_withPersonalAssistantBurmeseTone() {
        val payload = "app=KBZPay&title=ငွေလွှဲလက်ခံရရှိပါသည်&msg=50,000 ကျပ်"
        val base64 = "APP_NOTIFICATION_V1:" + android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP)
        val result = ProactivePromptHelper.buildPrompt(base64, com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.PROACTIVE_TYPE_GENERAL_NOTIFICATION)

        org.junit.Assert.assertTrue(result.contains("KBZPay"))
        org.junit.Assert.assertTrue(result.contains("PERSONAL ASSISTANT APP NOTIFICATION"))
        org.junit.Assert.assertTrue(result.contains("အစ်ကိုခင်ဗျာ"))
        org.junit.Assert.assertTrue(result.contains("CRITICAL: DO NOT CALL ANY TOOL"))
    }

    @Test
    fun buildPrompt_returnsMessengerReply_withPersonalAssistantBurmeseTone() {
        val payload = "sender=မောင်မောင်&msg=နေကောင်းလား&app=Telegram"
        val base64 = "MESSENGER_REPLY_V2:" + android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP)
        val result = ProactivePromptHelper.buildPrompt(base64, com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.PROACTIVE_TYPE_MESSENGER_REPLY)

        org.junit.Assert.assertTrue(result.contains("မောင်မောင်"))
        org.junit.Assert.assertTrue(result.contains("Telegram"))
        org.junit.Assert.assertTrue(result.contains("PERSONAL ASSISTANT MESSAGE NOTIFICATION"))
        org.junit.Assert.assertTrue(result.contains("အစ်ကိုခင်ဗျာ"))
        org.junit.Assert.assertTrue(result.contains("CRITICAL: DO NOT CALL ANY TOOL"))
    }
}
