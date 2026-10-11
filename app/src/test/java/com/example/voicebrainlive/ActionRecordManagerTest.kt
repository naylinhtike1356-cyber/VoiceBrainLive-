package com.example.voicebrainlive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRecordManagerTest {

    @Test
    fun sanitize_redactsApiKeysTokensAndPhoneNumbers() {
        val input = "url=https://example.com?key=AIzaSySecretSecretSecretSecretSecret token=Bearer abcdef1234567890abcdef1234567890 call +82 10-1234-5678"

        val sanitized = ActionRecordManager.sanitize(input)

        assertTrue(sanitized.contains("[REDACTED]"))
        assertFalse(sanitized.contains("AIzaSySecret"))
        assertFalse(sanitized.contains("abcdef1234567890abcdef1234567890"))
        assertFalse(sanitized.contains("+82 10-1234-5678"))
    }
}
