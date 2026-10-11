package com.example.voicebrainlive.commands

/**
 * Lets command handlers request a camera frame without depending on MainActivity.
 * MainActivity / AssistantActivity register [onCaptureRequested] when the voice UI is active.
 */
object VisionCaptureBridge {
    @Volatile
    var onCaptureRequested: (() -> Unit)? = null

    fun requestCapture(): Boolean = false // Agent-driven capture has no image-specific consent.
}
