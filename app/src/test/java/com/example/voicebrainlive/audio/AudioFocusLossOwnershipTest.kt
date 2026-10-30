package com.example.voicebrainlive.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class AudioFocusLossOwnershipTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val manager get()=shadowOf(app.getSystemService(AudioManager::class.java))
    private val first=Any();private val next=Any()
    @After fun cleanup(){AudioFocusHelper.abandon(app,first);AudioFocusHelper.abandon(app,next)}
    @Test fun currentOwnerReceivesLoss(){
        var lost=0;manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(AudioFocusHelper.requestForSpeechWithLoss(app,first){lost++})
        requireNotNull(manager.lastAudioFocusRequest.listener).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1,lost)
    }
    @Test fun staleLossCannotStopReplacementOwner(){
        var oldLost=0;var nextLost=0;manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        AudioFocusHelper.requestForSpeechWithLoss(app,first){oldLost++};val stale=requireNotNull(manager.lastAudioFocusRequest.listener)
        AudioFocusHelper.requestForSpeechWithLoss(app,next){nextLost++};stale.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(0,oldLost);assertEquals(0,nextLost)
        requireNotNull(manager.lastAudioFocusRequest.listener).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1,nextLost)
    }
}
