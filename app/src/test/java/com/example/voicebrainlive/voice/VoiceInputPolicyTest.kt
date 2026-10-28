package com.example.voicebrainlive.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceInputPolicyTest {
    @Test fun inactiveSessionCannotStreamEvenWithInterruptionOptIn() {
        assertFalse(VoiceInputPolicy.mayForward(false, false, false, false, true))
    }
    @Test fun defaultDoesNotStreamAssistantPlayback() {
        assertFalse(VoiceInputPolicy.mayForward(true, false, true, false))
    }
    @Test fun explicitOptInAllowsUserAudioDuringAssistantPlayback() {
        assertTrue(VoiceInputPolicy.mayForward(true, false, true, false, true))
    }
    @Test fun mutedOrPausedAlwaysStopsForwarding() {
        assertFalse(VoiceInputPolicy.mayForward(true, true, false, false, true))
        assertFalse(VoiceInputPolicy.mayForward(true, true, true, false, true))
    }
    @Test fun otherSpeechNeverBecomesProviderInput() {
        assertFalse(VoiceInputPolicy.mayForward(true, false, false, true, true))
        assertFalse(VoiceInputPolicy.mayForward(true, false, true, true, true))
    }
    @Test fun normalListeningPreservesForwarding() {
        assertTrue(VoiceInputPolicy.mayForward(true, false, false, false))
    }
}
