package com.example.voicebrainlive.audio

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/** Framework shadow only, with plain Application; never starts app/provider/native audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AudioFocusOwnershipTest {
    private lateinit var context: Context
    private lateinit var manager: ShadowAudioManager
    private val first = Any()
    private val next = Any()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun cleanup() {
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        AudioFocusHelper.abandon(context, first)
        AudioFocusHelper.abandon(context, next)
        AudioFocusHelper.abandon(context)
    }

    @Test fun grantedRequestIsImmediateAndExactRequestIsReleased() {
        assertTrue(AudioFocusHelper.requestForSpeech(context, first))
        val request = manager.lastAudioFocusRequest.audioFocusRequest
        assertFalse(request.acceptsDelayedFocusGain())
        AudioFocusHelper.abandon(context, first)
        assertSame(request, manager.lastAbandonedAudioFocusRequest)
    }

    @Test fun wrongOwnerAndCompatibilityDefaultCannotAbandonScopedFocus() {
        assertTrue(AudioFocusHelper.requestForSpeech(context, first))
        AudioFocusHelper.abandon(context, next)
        AudioFocusHelper.abandon(context)
        assertNull(manager.lastAbandonedAudioFocusRequest)
        val actual = manager.lastAudioFocusRequest.audioFocusRequest
        AudioFocusHelper.abandon(context, first)
        assertSame(actual, manager.lastAbandonedAudioFocusRequest)
    }

    @Test fun replacedOwnerCannotReleaseFreshOwnersRequest() {
        assertTrue(AudioFocusHelper.requestForSpeech(context, first))
        val old = manager.lastAudioFocusRequest.audioFocusRequest
        assertTrue(AudioFocusHelper.requestForSpeech(context, next))
        val fresh = manager.lastAudioFocusRequest.audioFocusRequest
        assertNotSame(old, fresh)
        assertSame(old, manager.lastAbandonedAudioFocusRequest)
        AudioFocusHelper.abandon(context, first)
        assertSame(old, manager.lastAbandonedAudioFocusRequest)
        AudioFocusHelper.abandon(context, next)
        assertSame(fresh, manager.lastAbandonedAudioFocusRequest)
    }

    @Test fun deniedRequestNeverCreatesAnOwnedFocusGrant() {
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(AudioFocusHelper.requestForSpeech(context, first))
        AudioFocusHelper.abandon(context, first)
        assertNull(manager.lastAbandonedAudioFocusRequest)
    }

    @Test fun unexpectedDelayedResultIsRefusedAndPendingRequestCancelled() {
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        assertFalse(AudioFocusHelper.requestForSpeech(context, first))
        assertSame(manager.lastAudioFocusRequest.audioFocusRequest, manager.lastAbandonedAudioFocusRequest)
    }

    @Test fun failedReleaseDoesNotLoseOldOwnerOrReplaceItsRequest() {
        assertTrue(AudioFocusHelper.requestForSpeech(context, first))
        val old = manager.lastAudioFocusRequest.audioFocusRequest
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(AudioFocusHelper.requestForSpeech(context, next))
        assertSame(old, manager.lastAudioFocusRequest.audioFocusRequest)
        manager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        AudioFocusHelper.abandon(context, first)
        assertSame(old, manager.lastAbandonedAudioFocusRequest)
    }
}
