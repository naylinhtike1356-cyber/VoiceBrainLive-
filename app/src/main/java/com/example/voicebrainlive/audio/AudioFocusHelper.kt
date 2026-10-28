package com.example.voicebrainlive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/** Resource ownership for voice focus only; never an authorization/session capability. */
object AudioFocusHelper {
    private const val TAG = "AudioFocusHelper"
    private val compatibilityOwner = Any()
    private val lock = Any()
    private data class Hold(val owner: Any, val manager: AudioManager,
        val request: AudioFocusRequest?, val legacyListener: AudioManager.OnAudioFocusChangeListener?)
    private var held: Hold? = null

    /** Only an immediate GRANTED result permits playback; delayed focus is not supported. */
    fun requestForSpeech(context: Context, owner: Any = compatibilityOwner): Boolean = synchronized(lock) {
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@synchronized false
            // Release the exact previous request before replacing its resource owner.
            if (!releaseHeld()) return@synchronized false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAcceptsDelayedFocusGain(false).build()
                val result = manager.requestAudioFocus(request)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    held = Hold(owner, manager, request, null)
                    true
                } else {
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) manager.abandonAudioFocusRequest(request)
                    Log.w(TAG, "FOCUS_NOT_GRANTED")
                    false
                }
            } else {
                val listener = AudioManager.OnAudioFocusChangeListener { /* Loss routing is separate device work. */ }
                @Suppress("DEPRECATION")
                val result = manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    held = Hold(owner, manager, null, listener)
                    true
                } else {
                    Log.w(TAG, "FOCUS_NOT_GRANTED")
                    false
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "FOCUS_REQUEST_FAILED")
            false
        }
    }

    /** A delayed/stale owner cannot abandon another player's exact focus request. */
    @Suppress("UNUSED_PARAMETER")
    fun abandon(context: Context, owner: Any = compatibilityOwner) = synchronized(lock) {
        if (held?.owner === owner) releaseHeld()
        Unit
    }

    private fun releaseHeld(): Boolean {
        val current = held ?: return true
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && current.request != null) {
                current.manager.abandonAudioFocusRequest(current.request)
            } else {
                @Suppress("DEPRECATION")
                current.manager.abandonAudioFocus(current.legacyListener)
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                held = null
                true
            } else {
                Log.w(TAG, "FOCUS_RELEASE_FAILED")
                false
            }
        } catch (_: Exception) {
            Log.w(TAG, "FOCUS_RELEASE_FAILED")
            false
        }
    }
}
