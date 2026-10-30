package com.example.voicebrainlive.voice

import android.app.Application
import android.os.Looper
import com.example.voicebrainlive.VoiceLiveService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentSpeechTest {
    @Test fun blockedBackgroundLaunchOffersOpenActionWithoutTtsFallback() {
        val app = RuntimeEnvironment.getApplication()
        val owner = Any()
        var errors = 0
        var done = 0
        val blocked = object : android.content.ContextWrapper(app) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                throw SecurityException("Background microphone launch blocked")
            }
        }
        try {
            assertFalse(AgentSpeech.speak(blocked, "private announcement", owner, { errors++ }, { done++ }))
            assertEquals(1, errors)
            assertEquals(1, done)
            assertFalse(AgentSpeech.isSpeaking)
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            val notice = Shadows.shadowOf(manager).getNotification(94640)
            assertNotNull(notice)
            assertFalse(notice.extras.toString().contains("private announcement"))
            val open = Shadows.shadowOf(notice.contentIntent).savedIntent
            assertEquals(com.example.voicebrainlive.AssistantActivity::class.java.name, open.component!!.className)
        } finally { AgentSpeech.stop(owner) }
    }
    @Test fun wakesAgentWithQuotedDataAndNoTtsEngine() {
        val app = RuntimeEnvironment.getApplication()
        val owner = Any()
        var done = 0
        try {
            assertTrue(AgentSpeech.speak(app, "မင်္ဂလာပါ", owner, onDone = { done++ }))
            val intent = Shadows.shadowOf(app).nextStartedService
            assertEquals(VoiceLiveService.ACTION_START, intent.action)
            assertTrue(intent.getBooleanExtra("EXTRA_START_WITH_CARD", false))
            assertTrue(intent.getStringExtra("proactive_message")!!.contains("Do not execute tools"))
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            AgentSpeech.complete("stale", true)
            assertEquals(0, done)
            AgentSpeech.complete(intent.getStringExtra(AgentSpeech.EXTRA_TICKET), true)
            assertEquals(1, done)
            assertFalse(AgentSpeech.isSpeaking)
        } finally { AgentSpeech.stop(owner) }
    }
    @Test fun timeoutReportsFailureOnceWithoutTtsFallback() {
        val app = RuntimeEnvironment.getApplication()
        val owner = Any()
        var errors = 0
        var done = 0
        try {
            AgentSpeech.speak(app, "hello", owner, { errors++ }, { done++ })
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
            assertEquals(1, errors)
            assertEquals(1, done)
            assertFalse(AgentSpeech.isSpeaking)
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        } finally { AgentSpeech.stop(owner) }
    }
}
