package com.example.voicebrainlive

import android.app.Application
import android.util.Log

/**
 * Logs uncaught exceptions for crash diagnostics.
 * Required early in onCreate so crashes are captured in Logcat.
 */
open class VoiceBrainApplication : Application() {

    override fun onCreate() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FATAL_ERROR", "Crash trace: ", throwable)
            Log.e("FATAL_ERROR", "Stack: ${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        super.onCreate()

        // Keep-alive check: if user enabled Floating Bubble, ensure service is running
        if (BubblePrefs.isEnabled(this) && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this))) {
            try {
                val bubbleIntent = android.content.Intent(this, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_SHOW_BUBBLE
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(bubbleIntent)
                } else {
                    startService(bubbleIntent)
                }
            } catch (e: Exception) {
                Log.w("VoiceBrainApplication", "Failed to auto-start FloatingBubbleService: ${e.message}")
            }
        }
    }
}
