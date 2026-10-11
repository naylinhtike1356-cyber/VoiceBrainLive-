package com.example.voicebrainlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * NotificationListenerService to get MediaSession access for Spotify/YT Music.
 * When enabled in Settings → Notification access, we can use MediaController.transportControls.play()
 * instead of unreliable delay + virtual key events.
 *
 * User must enable: Settings → Apps → Special app access → Notification access → Voice Brain
 */
class MediaSessionAccessService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaSessionHelper.onListenerConnected(this)
        Log.d(TAG, "MediaSessionAccessService connected - reliable play enabled")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaSessionHelper.onListenerDisconnected()
        Log.d(TAG, "MediaSessionAccessService disconnected")
    }

    companion object {
        private const val TAG = "MediaSessionAccess"
    }
}
