package com.example.voicebrainlive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {
    @Test
    fun acceptsCanonicalEnglishPhrase() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("Hey Voice Brain"))
    }

    @Test
    fun acceptsBurmesePronunciationVariants() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("ဟေး ဗွိုက်စ် ဘရိန်း"))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("ဟေး၊ ဗိုက်စ် ဘရိန်း"))
    }

    @Test
    fun acceptsLumaWakePhraseVariants() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("Hey Luma"))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("ဟေး လူမာ"))
    }

    @Test
    fun rejectsPhraseInMiddleOfNormalSentence() {
        assertFalse(WakeWordMatcher.matchesFinalTranscript("please tell me hey voice brain now"))
        assertFalse(WakeWordMatcher.matchesFinalTranscript("please tell me hey luma now"))
    }

    @Test
    fun rejectsShortCommonWords() {
        assertFalse(WakeWordMatcher.matchesFinalTranscript("hi voice"))
        assertFalse(WakeWordMatcher.matchesFinalTranscript("တီ"))
    }

    @Test
    fun acceptsAdditionalBurmeseLumaPronunciationVariants() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("ဟေး လူမား"))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("ဟေ့ လူမာ"))
    }

    @Test
    fun rejectsLowConfidenceWakePhrase() {
        assertFalse(WakeWordMatcher.matchesFinalTranscript("ဟေး လူမာ", confidence = 0.61f))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("ဟေး လူမာ", confidence = 0.62f))
    }

    @Test
    fun acceptsMissingEngineConfidenceForCompatibility() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("Hey Luma", confidence = null))
    }

    @Test
    fun rejectsVeryLongTranscript() {
        assertFalse(WakeWordMatcher.matchesFinalTranscript("hey voice brain " + "ordinary speech ".repeat(10)))
    }

    @Test
    fun rejectsEnglishWakePhraseEmbeddedInLongerWord() {
        assertFalse(WakeWordMatcher.matchesFinalTranscript("hey lumatic"))
        assertFalse(WakeWordMatcher.matchesFinalTranscript("hey voice brainstorm"))
        assertFalse(WakeWordMatcher.matchesFinalTranscript("hey luma123"))
    }

    @Test
    fun acceptsEnglishWakePhraseFollowedBySeparateCommand() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("hey luma stop"))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("hey, voice brain, stop"))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("heyluma stop"))
    }

    @Test
    fun acceptsBurmeseCommandWithoutWordSeparator() {
        assertTrue(WakeWordMatcher.matchesFinalTranscript("\u101f\u1031\u1038 \u101c\u1030\u1019\u102c\u101b\u1015\u103a"))
        assertTrue(WakeWordMatcher.matchesFinalTranscript("hey luma\u101b\u1015\u103a"))
    }
}
