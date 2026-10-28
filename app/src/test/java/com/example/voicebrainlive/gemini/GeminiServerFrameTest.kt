package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Injects frames locally; never connects to a provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiServerFrameTest {
    private class Fixture {
        val client = GeminiLiveClient("fixture-no-network")
        val epochs = field("connectionEpoch").get(client) as ConnectionEpoch
        val token = epochs.open(null)
        init { (field("voiceMetrics").get(client) as VoiceSessionMetrics).begin(token) }
        private fun field(name: String) = GeminiLiveClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun ready() = field("setupCompleteReceived").getBoolean(client)
        suspend fun frame(text: String, epoch: ConnectionEpoch.Token = token): Unit =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                GeminiLiveClient::class.java.declaredMethods.single { it.name == "handleServerMessage" }
                    .apply { isAccessible = true }.invoke(client, text, epoch, continuation)
            }
    }

    @Test fun transcriptContainingErrorAndSetupWordsIsStillExactTranscript() = runBlocking {
        val f = Fixture()
        val received = mutableListOf<String>()
        val errors = mutableListOf<String>()
        f.client.onInputTranscript = { received += it }
        f.client.onError = { errors += it }
        val authored = listOf("error", "setupComplete", "  error setupComplete မြန်မာ\r\n한국어  ")
        try {
            for (exact in authored) {
                f.frame(JSONObject().put("serverContent", JSONObject().put("inputTranscription", JSONObject().put("text", exact))).toString())
            }
            assertEquals(authored, received)
            assertTrue(errors.isEmpty())
            assertFalse(f.ready())
        } finally { f.client.disconnect() }
    }

    @Test fun falseTurnFlagsDoNotInvokeCallbacks() = runBlocking {
        val f = Fixture()
        var interrupted = 0; var completed = 0
        f.client.onInterrupted = { interrupted++ }; f.client.onTurnComplete = { completed++ }
        try {
            f.frame("{\"serverContent\":{\"interrupted\":false,\"turnComplete\":false}}")
            assertEquals(0, interrupted); assertEquals(0, completed)
            f.frame("{\"serverContent\":{\"interrupted\":true,\"turnComplete\":true}}")
            assertEquals(1, interrupted); assertEquals(1, completed)
        } finally { f.client.disconnect() }
    }

    @Test fun onlyObjectSetupAcknowledgementMakesSessionReady() = runBlocking {
        val f = Fixture()
        try {
            f.frame("{\"setupComplete\":null}"); assertFalse(f.ready())
            f.frame("{\"setupComplete\":\"yes\"}"); assertFalse(f.ready())
            f.frame("{\"setupComplete\":{}}"); assertTrue(f.ready())
        } finally { f.client.disconnect() }
    }

    @Test fun replacedSessionRejectsOldFrameCallbacks() = runBlocking {
        val f = Fixture()
        var count = 0
        f.client.onInputTranscript = { count++ }
        f.epochs.open(null)
        try {
            f.frame("{\"serverContent\":{\"inputTranscription\":{\"text\":\"old\"}}}")
            assertEquals(0, count)
            f.frame("{\"setupComplete\":{}}"); assertFalse(f.ready())
        } finally { f.client.disconnect() }
    }

    @Test fun malformedFrameIsCountedWithoutLoggingItsContent() = runBlocking {
        val f = Fixture()
        val secret = "PRIVATE-FRAME-မြန်မာ-한국어"
        ShadowLog.clear()
        try {
            f.frame(secret)
            assertEquals(1L, f.client.metricsSnapshot().dropped)
            assertTrue(ShadowLog.getLogs().none { it.msg.contains(secret) })
        } finally { f.client.disconnect() }
    }

    @Test fun serverErrorAndTranscriptAreNotWrittenToDiagnosticLogs() = runBlocking {
        val f = Fixture()
        val secret = "PRIVATE-PROVIDER-MESSAGE"
        ShadowLog.clear()
        try {
            f.frame(JSONObject().put("error", JSONObject().put("message", secret)).toString())
            f.frame(JSONObject().put("serverContent", JSONObject().put("outputTranscription", JSONObject().put("text", secret))).toString())
            assertTrue(ShadowLog.getLogs().none { it.msg.contains(secret) })
        } finally { f.client.disconnect() }
    }
}
