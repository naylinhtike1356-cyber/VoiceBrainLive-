package com.example.voicebrainlive.commands

import android.os.SystemClock

/** A frozen frame, bound to one session and one resumed UI tap. Never accepted from tool args. */
internal class ImageShareConsent(
    val frame: String,
    private val destination: Any,
    private val stillCurrent: () -> Boolean,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    ttlMs: Long = 15 * 60 * 1000L
) {
    private val deadline = clock() + ttlMs
    private var spent = false

    @Synchronized fun consume(receiver: Any, tap: ApprovalUiLease.Lease?): String? {
        if (spent) return null
        spent = true // A denied/uncertain attempt also spends the frame capability.
        if (receiver !== destination || clock() >= deadline || !ApprovalUiLease.currentTap(tap) || !stillCurrent()) return null
        return frame
    }

    @Synchronized fun cancel() { spent = true }
}
