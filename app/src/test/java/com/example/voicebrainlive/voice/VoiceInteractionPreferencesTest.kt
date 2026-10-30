package com.example.voicebrainlive.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class VoiceInteractionPreferencesTest {
    @Test fun defaultOffAndExplicitChoiceSurvivesPreferenceReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(VoiceInteractionPreferences.interruptionEnabled(context))
        assertTrue(VoiceInteractionPreferences.setInterruptionEnabled(context, true))
        assertTrue(VoiceInteractionPreferences.interruptionEnabled(context))
        assertTrue(VoiceInteractionPreferences.setInterruptionEnabled(context, false))
        assertFalse(VoiceInteractionPreferences.interruptionEnabled(context))
    }
}
