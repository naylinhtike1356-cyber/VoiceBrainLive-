package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real parser + actors fed only local fake sockets; no provider/device connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiOrderedPipelineTest {
    private class Socket : WebSocket {
        val sent = mutableListOf<String>()
        var closes = 0
        override fun request() = Request.Builder().url("https://fixture.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean { closes++; return true }
        override fun cancel() = Unit
    }
    private class Fixture(execute: suspend (String, String?, String?) -> String = { _, _, _ -> "ok" }) {
        val client = GeminiLiveClient("fixture-no-network", CoroutineScope(Dispatchers.Unconfined + SupervisorJob()), onExecutePhoneCommand = execute)
        val epochs = field("connectionEpoch").get(client) as ConnectionEpoch
        val epoch = epochs.open(null)
        val socket = Socket()
        init {
            field("webSocket").set(client, socket)
            (field("voiceMetrics").get(client) as VoiceSessionMetrics).begin(epoch)
        }
        private fun field(name: String) = GeminiLiveClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun socketReplacement(replacement: Socket) { field("webSocket").set(client, replacement) }
        val pipeline: OrderedVoiceTransport = GeminiLiveClient::class.java.getDeclaredMethod("transportFor", ConnectionEpoch.Token::class.java, WebSocket::class.java)
            .apply { isAccessible = true }.invoke(client, epoch, socket) as OrderedVoiceTransport
        fun frame(text: String) = pipeline.offerFrame(text)
        fun close() = client.disconnect()
    }
    private fun tool(id: Any? = "a", name: String = "execute_phone_command", command: String = "get_battery") = JSONObject()
        .put("toolCall", JSONObject().put("functionCalls", org.json.JSONArray().put(JSONObject().apply {
            if (id != null) put("id", id)
            put("name", name); put("args", JSONObject().put("command_type", command))
        }))).toString()

    @Test fun suspendedToolAllowsInterruptionTranscriptsAndCompletionInOrder() = runBlocking {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val f = Fixture { _, _, _ -> calls++; release.await(); "done" }
        val events = mutableListOf<String>()
        f.client.onInputTranscript = { events += it }
        f.client.onInterrupted = { events += "interrupted" }
        f.client.onTurnComplete = { events += "complete" }
        try {
            f.frame(tool()); f.frame(tool())
            assertEquals(1, calls); assertTrue(f.client.sessionSnapshot().toolRunning)
            f.frame("""{"serverContent":{"interrupted":true,"inputTranscription":{"text":"  မြန်မာ 한국어 English  "},"turnComplete":true}}""")
            assertEquals(listOf("interrupted", "  မြန်မာ 한국어 English  ", "complete"), events)
            assertTrue(f.socket.sent.isEmpty()); release.complete(Unit)
            assertEquals(1, f.socket.sent.size); assertFalse(f.client.sessionSnapshot().toolRunning)
            assertEquals(1L, f.client.metricsSnapshot().timing[VoiceSessionMetrics.Work.TOOL_PREPARATION]?.count)
            assertEquals(1L, f.client.metricsSnapshot().timing[VoiceSessionMetrics.Work.TOOL_EXECUTION]?.count)
        } finally { f.close() }
    }

    @Test fun interruptionPrecedesAudioAndDiscardsSameFrameAudio() = runBlocking {
        val f = Fixture(); val events = mutableListOf<String>()
        f.client.onAudioChunk = { events += "audio" }; f.client.onInterrupted = { events += "interrupted" }
        try {
            f.frame("""{"serverContent":{"interrupted":true,"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AA=="}}]}}}""")
            assertEquals(listOf("interrupted"), events)
            f.frame("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AA=="}}]}}}""")
            assertEquals(listOf("interrupted", "audio"), events)
        } finally { f.close() }
    }

    @Test fun callbacksCannotAdmitLaterPartsAfterReentrantEpochReplacement() = runBlocking {
        val f = Fixture(); var audio = 0; var transcript = 0
        f.client.onAudioChunk = { audio++; f.epochs.open(null) }
        f.client.onTranscript = { transcript++ }
        try {
            f.frame("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm","data":"AA=="}},{"inlineData":{"mimeType":"audio/pcm","data":"AA=="}},{"text":"stale"}]}}}""")
            assertEquals(1, audio); assertEquals(0, transcript)
        } finally { f.close() }
    }

    @Test fun capturedSocketCannotBeReplacedByMutableSocketAtResponseTime() = runBlocking {
        val release = CompletableDeferred<Unit>(); val f = Fixture { _, _, _ -> release.await(); "done" }
        val replacement = Socket()
        try {
            f.frame(tool()); f.socketReplacement(replacement); release.complete(Unit)
            assertEquals(1, f.socket.sent.size); assertTrue(replacement.sent.isEmpty())
        } finally { f.close() }
    }

    @Test fun providerCancellationBeforeArrivalAndMissingOrGuessedIdentityNeverExecute() = runBlocking {
        var calls = 0; val f = Fixture { _, _, _ -> calls++; "done" }
        try {
            f.frame("""{"toolCallCancellation":{"ids":["future"]}}""")
            f.frame(tool("future")); f.frame(tool(null)); f.frame(tool(123)); f.frame(tool("unknown", "guessed_tool"))
            assertEquals(0, calls); assertTrue(f.socket.sent.isEmpty())
        } finally { f.close() }
    }

    @Test fun rawUndocumentedBinaryPayloadAndNonAudioMimeNeverBecomeAudio() = runBlocking {
        val f = Fixture(); var chunks = 0; var raw = 0
        f.client.onAudioChunk = { chunks++ }; f.client.onAudioBytes = { raw++ }
        try {
            f.frame(ByteArray(100) { 1 }.toByteString().utf8())
            f.frame("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AA=="}}]}}}""")
            assertEquals(0, chunks); assertEquals(0, raw); assertEquals(1L, f.client.metricsSnapshot().dropped)
        } finally { f.close() }
    }

    @Test fun stateTracksActualSetupOutputCompletionAndDisconnectBoundaries() = runBlocking {
        val f = Fixture()
        try {
            f.frame("""{"setupComplete":{}}"""); assertEquals(GeminiLiveClient.SessionState.READY, f.client.sessionSnapshot().state)
            f.frame("""{"serverContent":{"modelTurn":{"parts":[{"text":"hello"}]}}}""")
            assertEquals(GeminiLiveClient.SessionState.MODEL_OUTPUT, f.client.sessionSnapshot().state)
            f.frame("""{"serverContent":{"turnComplete":true}}""")
            assertEquals(GeminiLiveClient.SessionState.READY, f.client.sessionSnapshot().state)
            f.close(); assertEquals(GeminiLiveClient.SessionState.CLOSED, f.client.sessionSnapshot().state)
        } finally { f.close() }
    }
}
