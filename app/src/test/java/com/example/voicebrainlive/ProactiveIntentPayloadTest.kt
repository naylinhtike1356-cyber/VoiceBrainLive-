package com.example.voicebrainlive

import com.example.voicebrainlive.usecases.ProactiveIntentPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ProactiveIntentPayload
 * 
 * Note: These tests use direct constructor calls instead of fromIntent()
 * because Intent.getBooleanExtra() and Intent.getStringExtra() don't work
 * in local unit tests without Robolectric.
 */
class ProactiveIntentPayloadTest {

    @Test
    fun payload_withMessageAndType() {
        val payload = ProactiveIntentPayload(
            message = "Stand up and stretch",
            type = "location_home"
        )

        assertEquals("Stand up and stretch", payload.message)
        assertEquals("location_home", payload.type)
    }

    @Test
    fun payload_withMessageOnly() {
        val payload = ProactiveIntentPayload(
            message = "Time for a break",
            type = null
        )

        assertEquals("Time for a break", payload.message)
        assertNull(payload.type)
    }

    @Test
    fun payload_dataClass_copy() {
        val original = ProactiveIntentPayload(
            message = "Original message",
            type = "original_type"
        )

        val copied = original.copy(message = "Updated message")

        assertEquals("Updated message", copied.message)
        assertEquals("original_type", copied.type)
    }
}