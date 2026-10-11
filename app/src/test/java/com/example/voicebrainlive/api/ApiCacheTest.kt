package com.example.voicebrainlive.api

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ApiCache CacheEntry (pure Kotlin, no Android dependencies)
 * 
 * Tests cache entry operations:
 * - Expiration logic
 * - TTL remaining calculation
 * 
 * Note: JSON serialization tests require Android framework (JSONObject)
 * and should be run as instrumented tests or with Robolectric.
 */
class ApiCacheTest {

    @Test
    fun testCacheEntry_isExpired_returnsTrue() {
        // Create an expired entry (1 second ago)
        val expiresAt = System.currentTimeMillis() - 1000
        val entry = ApiCache.CacheEntry("test data", expiresAt)
        
        assertTrue("Expired entry should return true", entry.isExpired())
    }

    @Test
    fun testCacheEntry_isExpired_returnsFalse() {
        // Create an entry that expires in 5 minutes
        val expiresAt = System.currentTimeMillis() + (5 * 60 * 1000)
        val entry = ApiCache.CacheEntry("test data", expiresAt)
        
        assertFalse("Non-expired entry should return false", entry.isExpired())
    }

    @Test
    fun testCacheEntry_ttlRemaining_returnsPositiveValue() {
        // Create an entry that expires in 1 minute
        val expiresAt = System.currentTimeMillis() + 60000
        val entry = ApiCache.CacheEntry("test data", expiresAt)
        
        val remaining = entry.ttlRemaining()
        assertTrue("TTL remaining should be positive", remaining > 0)
    }

    @Test
    fun testCacheEntry_ttlRemaining_returnsZeroWhenExpired() {
        // Create an expired entry
        val expiresAt = System.currentTimeMillis() - 1000
        val entry = ApiCache.CacheEntry("test data", expiresAt)
        
        val remaining = entry.ttlRemaining()
        assertEquals("TTL remaining should be 0 when expired", 0L, remaining)
    }

    @Test
    fun testCacheEntry_justCreated_isNotExpired() {
        val entry = ApiCache.CacheEntry("new data", System.currentTimeMillis() + 10000)
        
        assertFalse("Newly created entry should not be expired", entry.isExpired())
    }

    @Test
    fun testCacheStats_dataClass() {
        val stats = ApiCache.CacheStats(
            memoryEntries = 5,
            persistentEntries = 10,
            totalSizeBytes = 1024,
            expiredEntries = 2
        )
        
        assertEquals(5, stats.memoryEntries)
        assertEquals(10, stats.persistentEntries)
        assertEquals(1024L, stats.totalSizeBytes)
        assertEquals(2L, stats.expiredEntries)
    }
}

/**
 * Test class for ApiCache TTL constants
 */
class ApiCacheConstantsTest {

    @Test
    fun testTtlConstants_arePositive() {
        assertTrue("TTL_WEATHER should be positive", ApiCache.TTL_WEATHER > 0)
        assertTrue("TTL_SEARCH should be positive", ApiCache.TTL_SEARCH > 0)
        assertTrue("TTL_NEWS should be positive", ApiCache.TTL_NEWS > 0)
        assertTrue("TTL_LOCATION should be positive", ApiCache.TTL_LOCATION > 0)
        assertTrue("TTL_DEFAULT should be positive", ApiCache.TTL_DEFAULT > 0)
    }

    @Test
    fun testTtlConstants_expectedValues() {
        // Verify TTL values match expected durations
        assertEquals(30 * 60 * 1000L, ApiCache.TTL_WEATHER)   // 30 minutes
        assertEquals(60 * 60 * 1000L, ApiCache.TTL_SEARCH)    // 1 hour
        assertEquals(15 * 60 * 1000L, ApiCache.TTL_NEWS)      // 15 minutes
        assertEquals(5 * 60 * 1000L, ApiCache.TTL_LOCATION)   // 5 minutes
        assertEquals(10 * 60 * 1000L, ApiCache.TTL_DEFAULT)   // 10 minutes
    }

    @Test
    fun testTtlConstants_orderMakesSense() {
        // Location should be shortest (most volatile)
        assertTrue("Location cache should be shortest", ApiCache.TTL_LOCATION < ApiCache.TTL_NEWS)
        // News should be shorter than weather
        assertTrue("News cache should be shorter than weather", ApiCache.TTL_NEWS < ApiCache.TTL_WEATHER)
        // Weather should be shorter than search
        assertTrue("Weather cache should be shorter than search", ApiCache.TTL_WEATHER < ApiCache.TTL_SEARCH)
    }

    @Test
    fun testPrefixConstants() {
        assertEquals("weather_", ApiCache.PREFIX_WEATHER)
        assertEquals("search_", ApiCache.PREFIX_SEARCH)
        assertEquals("news_", ApiCache.PREFIX_NEWS)
        assertEquals("location_", ApiCache.PREFIX_LOCATION)
    }
}