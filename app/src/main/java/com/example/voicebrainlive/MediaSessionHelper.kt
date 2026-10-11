package com.example.voicebrainlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.util.Log

/**
 * Helper to play media via MediaController.transportControls.play() when we have
 * NotificationListenerService access. More reliable than delay + virtual key events.
 */
object MediaSessionHelper {

    private const val TAG = "MediaSessionHelper"

    private val MUSIC_PACKAGES = listOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.music",
        "com.samsung.android.music"
    )

    @Volatile
    private var listenerComponent: ComponentName? = null

    fun onListenerConnected(context: Context) {
        listenerComponent = ComponentName(context, MediaSessionAccessService::class.java)
        Log.d(TAG, "NotificationListener connected - can use MediaController")
    }

    fun onListenerDisconnected() {
        listenerComponent = null
        Log.d(TAG, "NotificationListener disconnected")
    }

    /** Check if user has enabled Notification access for our app. */
    fun isNotificationAccessEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val ourComponent = ComponentName(context, MediaSessionAccessService::class.java).flattenToString()
        return enabled.contains(ourComponent)
    }

    /**
     * Try to pause via MediaController. Returns true if we sent pause, false if not possible.
     */
    @Suppress("DEPRECATION")
    fun tryPauseViaMediaController(context: Context): Boolean {
        val comp = listenerComponent ?: return false
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false

        return try {
            val controllers = sessionManager.getActiveSessions(comp)
            for (ctrl in controllers) {
                val pkg = ctrl.packageName
                if (pkg.contains("spotify", ignoreCase = true) || MUSIC_PACKAGES.any { pkg.equals(it, ignoreCase = true) }) {
                    ctrl.transportControls?.pause()
                    Log.d(TAG, "Sent pause via MediaController for $pkg")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "tryPauseViaMediaController failed: ${e.message}")
            false
        }
    }

    /** Pauses all active media sessions except the target package. */
    @Suppress("DEPRECATION")
    fun tryPauseOtherMediaSessions(context: Context, targetPackage: String): Boolean {
        val comp = listenerComponent ?: return false
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false

        return try {
            val controllers = sessionManager.getActiveSessions(comp)
            var pausedAny = false
            for (ctrl in controllers) {
                val pkg = ctrl.packageName
                if (pkg != targetPackage && (pkg.contains("spotify", ignoreCase = true) || MUSIC_PACKAGES.any { pkg.equals(it, ignoreCase = true) })) {
                    ctrl.transportControls?.pause()
                    Log.d(TAG, "Sent pause via MediaController for $pkg (target package is $targetPackage)")
                    pausedAny = true
                }
            }
            pausedAny
        } catch (e: Exception) {
            Log.e(TAG, "tryPauseOtherMediaSessions failed: ${e.message}")
            false
        }
    }

    /** Checks if Spotify or any registered music app is currently in a playing state. */
    @Suppress("DEPRECATION")
    fun isSpotifyOrMusicPlaying(context: Context): Boolean {
        val comp = listenerComponent ?: return false
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false

        return try {
            val controllers = sessionManager.getActiveSessions(comp)
            for (ctrl in controllers) {
                val pkg = ctrl.packageName
                if (pkg.contains("spotify", ignoreCase = true) || MUSIC_PACKAGES.any { pkg.equals(it, ignoreCase = true) }) {
                    val state = ctrl.playbackState?.state
                    if (state == android.media.session.PlaybackState.STATE_PLAYING) {
                        Log.d(TAG, "Active session $pkg is currently playing")
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "isSpotifyOrMusicPlaying failed: ${e.message}")
            false
        }
    }

    /**
     * Try to play via MediaController. Returns true if we sent play, false if not possible.
     * Call this after launching Spotify/search Intent - poll until we get a session.
     */
    @Suppress("DEPRECATION")
    fun tryPlayViaMediaController(context: Context, maxWaitMs: Long = 5000): Boolean {
        val comp = listenerComponent ?: return false
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false

        return try {
            val controllers = sessionManager.getActiveSessions(comp)
            for (ctrl in controllers) {
                val pkg = ctrl.packageName
                if (pkg.contains("spotify", ignoreCase = true) || MUSIC_PACKAGES.any { pkg.equals(it, ignoreCase = true) }) {
                    ctrl.transportControls?.play()
                    Log.d(TAG, "Sent play via MediaController for $pkg")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "tryPlayViaMediaController failed: ${e.message}")
            false
        }
    }

    /** Launch Settings to enable Notification access for our app. */
    fun openNotificationAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openNotificationAccessSettings failed: ${e.message}")
        }
    }
}
