package com.example.voicebrainlive.gemini

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No network: fake WebSocket injected before sending; API key is an invalid local fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiTextExactnessTest {
    private class FakeSocket : okhttp3.WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = okhttp3.Request.Builder().url("https://fixture.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += text; return true }
        override fun send(bytes: okio.ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private fun fixture(ready: Boolean = true): Pair<GeminiLiveClient, FakeSocket> {
        val client = GeminiLiveClient("fixture-no-network")
        val socket = FakeSocket()
        GeminiLiveClient::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client, socket)
        GeminiLiveClient::class.java.getDeclaredField("setupCompleteReceived").apply { isAccessible = true }.setBoolean(client, ready)
        return client to socket
    }

    private fun text(frame: String): String = JSONObject(frame).getJSONObject("clientContent")
        .getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")

    @Test fun originalMixedLanguagesWhitespaceAndCrlfReachSocketExactly() {
        val (client, socket) = fixture()
        val exact = "  မြန်မာစာ\r\n한국어 이름: 김민수\nEnglish MiXeD 🙂  "
        try {
            client.sendTextInput(exact)
            assertEquals(1, socket.messages.size)
            assertEquals(exact, text(socket.messages.single()))
        } finally { client.disconnect() }
    }

    @Test fun composedAndDecomposedUnicodeAreNotSilentlyNormalized() {
        val (client, socket) = fixture()
        val exact = "  e\u0301 ≠ é; \u1100\u1161 ≠ 가; နာမည်  "
        try { client.sendTextInput(exact); assertEquals(exact, text(socket.messages.single())) }
        finally { client.disconnect() }
    }

    @Test fun longAuthoredTextIsNotTruncatedForTransport() {
        val (client, socket) = fixture()
        val exact = "\r\n" + "မြန်မာ 한국어 English\r\n".repeat(2_000) + "  "
        try { client.sendTextInput(exact); assertEquals(exact, text(socket.messages.single())) }
        finally { client.disconnect() }
    }

    @Test fun whitespaceOnlyDoesNotCreateATurn() {
        val (client, socket) = fixture()
        try { client.sendTextInput(" \t\r\n  "); assertTrue(socket.messages.isEmpty()) }
        finally { client.disconnect() }
    }

    @Test fun preSetupPendingTextStoresExactPayloadAndDisconnectClearsIt() {
        val (client, socket) = fixture(ready = false)
        val pending = GeminiLiveClient::class.java.getDeclaredField("pendingTextInput").apply { isAccessible = true }
        val exact = "  မင်္ဂလာပါ\r\n안녕하세요  "
        try {
            client.sendTextInput(exact)
            assertTrue(socket.messages.isEmpty())
            assertEquals(exact, pending.get(client))
            client.disconnect()
            assertNull(pending.get(client))
        } finally { client.disconnect() }
    }
}
