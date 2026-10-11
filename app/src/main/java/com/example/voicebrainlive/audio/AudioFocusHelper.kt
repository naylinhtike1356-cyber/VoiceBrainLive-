package com.example.voicebrainlive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * AI ပြောချိန်မှာ သီချင်းအသံ လျော့သွားအောင် requestAudioFocus သုံးမယ်
 */
object AudioFocusHelper {

    private const val TAG = "AudioFocusHelper"

    private var currentFocusRequest: AudioFocusRequest? = null

    /**
     * AI speech စပြောချိန် ခေါ်ပါ – သီချင်းက duck သွားမယ်
     */
    fun requestForSpeech(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .build()
                currentFocusRequest = request
                val result = am.requestAudioFocus(request)
                Log.d(TAG, "requestForSpeech: result=$result")
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                Log.d(TAG, "requestForSpeech (legacy): result=$result")
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestForSpeech: ${e.message}")
            false
        }
    }

    /**
     * AI ပြောပြီးသွားချိန် ခေါ်ပါ – သီချင်းပြန် ပြန်မြှင့်မယ်
     */
    fun abandon(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentFocusRequest?.let {
                    am.abandonAudioFocusRequest(it)
                    currentFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            Log.d(TAG, "abandon: done")
        } catch (e: Exception) {
            Log.e(TAG, "abandon: ${e.message}")
        }
    }
}
