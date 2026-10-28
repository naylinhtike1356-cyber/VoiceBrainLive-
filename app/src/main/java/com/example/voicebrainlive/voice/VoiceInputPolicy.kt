package com.example.voicebrainlive.voice

/** Forwarding policy is not permission to start a microphone or bypass voice verification. */
object VoiceInputPolicy {
    fun mayForward(
        voiceSessionActive: Boolean,
        mutedOrPaused: Boolean,
        assistantAudioActive: Boolean,
        otherSpeechActive: Boolean,
        interruptionEnabled: Boolean = false
    ): Boolean = voiceSessionActive && !mutedOrPaused && !otherSpeechActive &&
        (!assistantAudioActive || interruptionEnabled)
}
