package com.example.voicebrainlive

import android.content.Context
import android.util.Log

/**
 * Log wrapper: records to Logcat and persistent ActionRecordManager.
 * Use for debugging and app status tracking.
 */
object AppLog {
    
    @JvmStatic
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
    }

    /** Record a significant app event to both logcat and persistent diagnostic file. */
    @JvmStatic
    fun i(context: Context, tag: String, msg: String) {
        ActionRecordManager.recordAction(context, tag, msg, isError = false)
    }

    /** Record an error to both logcat and persistent diagnostic file. */
    @JvmStatic
    fun e(context: Context, tag: String, msg: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$msg: ${throwable.message}" else msg
        ActionRecordManager.recordAction(context, tag, fullMsg, isError = true)
    }
}
