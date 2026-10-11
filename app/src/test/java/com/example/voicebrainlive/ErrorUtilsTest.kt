package com.example.voicebrainlive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ErrorUtils.toUserMessage (v1.6).
 */
class ErrorUtilsTest {

    @Test
    fun apiKeyNotValid_returnsMyanmarMessage() {
        val raw = "API key not valid or expired"
        assertEquals("API Key မမှန်ပါ။ Settings မှာ ပြန်ထည့်ပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun notionObjectNotFound_returnsMyanmarMessage() {
        val raw = "object_not_found: database not found"
        assertEquals("Notion database မတွေ့ပါ။ Database ကို integration နဲ့ share ပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun validationError_returnsMyanmarMessage() {
        val raw = "validation_error: invalid input"
        assertEquals("အချက်အလက် မမှန်ပါ။ ပြန်စစ်ပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun networkError_returnsMyanmarMessage() {
        val raw = "Network error: unable to connect"
        assertEquals("အင်တာနက် ချိတ်ဆက်ပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun unableToResolveHost_returnsMyanmarMessage() {
        val raw = "Unable to resolve host \"api.example.com\""
        assertEquals("အင်တာနက် ချိတ်ဆက်ပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun timeout_returnsMyanmarMessage() {
        val raw = "java.net.SocketTimeoutException: timeout"
        assertEquals("အချိန်ကျော်သွားပါပြီ။ ပြန်ကြိုးစားပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun closed1000_returnsMyanmarMessage() {
        val raw = "WebSocket Closed: 1000"
        assertEquals("ချိတ်ဆက်မှု ပိတ်သွားပါပြီ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun socketException_returnsMyanmarMessage() {
        val raw = "java.net.SocketException: connection abort"
        assertEquals("ချိတ်ဆက်မှု ပြတ်သွားပါပြီ။ ပြန်ကြိုးစားပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun genericError_returnsFallback() {
        val raw = "Something went wrong"
        assertEquals("အမှား ဖြစ်သွားပါပြီ။ ပြန်ကြိုးစားပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun jsonError_withApiKeyMessage_returnsApiKeyMessage() {
        val raw = """{"error":{"message":"API key invalid","code":401}}"""
        assertEquals("API Key မှားယွင်းနေပါသည်။ ပြန်လည်စစ်ဆေးပေးပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun jsonError_code404_returnsNotFound() {
        val raw = """{"error":{"message":"Not found","code":404}}"""
        assertEquals("ရှာဖွေနေသော အချက်အလက် မတွေ့ရှိပါ။", ErrorUtils.toUserMessage(raw))
    }

    @Test
    fun emptyString_returnsFallback() {
        val raw = ""
        assertEquals("အမှား ဖြစ်သွားပါပြီ။", ErrorUtils.toUserMessage(raw))
    }
}
