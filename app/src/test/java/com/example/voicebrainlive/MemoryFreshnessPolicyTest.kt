package com.example.voicebrainlive

import com.example.voicebrainlive.memory.MemoryFreshnessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFreshnessPolicyTest {
    @Test
    fun unknownTimestamp_isBackwardCompatibleAsFresh() {
        assertTrue(MemoryFreshnessPolicy.isFresh(0L, now = 1_000L))
    }

    @Test
    fun recentMemory_isFresh() {
        assertTrue(MemoryFreshnessPolicy.isFresh(1_000L, now = 1_000L + 1_000L))
    }

    @Test
    fun oldMemory_isStale() {
        assertFalse(MemoryFreshnessPolicy.isFresh(1L, now = 1L + MemoryFreshnessPolicy.BEHAVIOR_MAX_AGE_MS + 1L))
    }

    @Test
    fun futureTimestamp_isNotFresh() {
        assertFalse(MemoryFreshnessPolicy.isFresh(1_001L, now = 1_000L))
    }

    @Test
    fun extremeFutureTimestamp_isNotFresh() {
        assertFalse(MemoryFreshnessPolicy.isFresh(Long.MAX_VALUE, now = 1_000L))
    }

    @Test
    fun memoryAtMaximumAge_isFresh() {
        assertTrue(MemoryFreshnessPolicy.isFresh(1_000L,
            now = 1_000L + MemoryFreshnessPolicy.BEHAVIOR_MAX_AGE_MS))
    }

    @Test
    fun clockRollback_doesNotAcceptTimestampThroughOverflow() {
        assertFalse(MemoryFreshnessPolicy.isFresh(1_000L, now = Long.MIN_VALUE))
    }

    @Test
    fun confidenceBoundary_isExplicit() {
        assertTrue(MemoryFreshnessPolicy.MIN_ALIAS_CONFIDENCE >= 50)
        assertTrue(MemoryFreshnessPolicy.MIN_ALIAS_CONFIDENCE <= 100)
    }
}
