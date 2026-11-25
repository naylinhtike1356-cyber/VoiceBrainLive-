package com.example.voicebrainlive.gemini

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.GeminiNanoHelper
import com.example.voicebrainlive.OfflineAssistantEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveVoiceProviderTest {

    @Test
    fun factoryCreatesDisconnectedGeminiLiveProvider() {
        val provider: LiveVoiceProvider = LiveVoiceProviderFactory.create(apiKey = "test-key")
        assertFalse(provider.isConnected())
        var errorReceived: String? = null
        provider.onError = { errorReceived = it }
        provider.connect()
        assertTrue(errorReceived == GeminiLiveClient.ERROR_API_KEY_REQUIRED)
    }

    @Test
    fun offlineAssistantEngineHandlesLocalQueries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val engine: OfflineAssistantEngine = GeminiNanoHelper(app)
        assertTrue(engine.initialize())
        assertTrue(engine.isReady())
        val mathReply = engine.generateResponse("5 + 3")
        assertTrue(mathReply.contains("8"))
    }
}
