package com.example.voicebrainlive.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ApiCachePersistenceTest {
    private lateinit var context: Context
    private lateinit var cache: ApiCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = ApiCache(context)
        cache.clear()
    }

    @Test
    fun normalKeysAreCountedAndUtf8SizeIsReported() {
        val data = "\u1019\u103c\u1014\u103a\u1019\u102c"
        cache.put("weather_seoul", data)
        cache.put("search_android", "abc")
        val stats = cache.getStats()
        assertEquals(2, stats.persistentEntries)
        assertEquals(data.toByteArray(Charsets.UTF_8).size.toLong() + 3, stats.totalSizeBytes)
        assertEquals(data, ApiCache(context).get("weather_seoul"))
    }

    @Test
    fun fullCacheEvictsEarliestExpiringEntriesAndKeepsLatest() {
        repeat(ApiCache.MAX_PERSISTENT_ENTRIES) { index ->
            cache.put("weather_$index", "value_$index", ttlMs = 60_000L + index * 60_000L)
        }
        cache.put("news_latest", "latest", ttlMs = 10_000_000L)
        val reloaded = ApiCache(context)
        assertEquals(41, reloaded.getStats().persistentEntries)
        repeat(10) { assertNull(reloaded.get("weather_$it")) }
        assertEquals("value_49", reloaded.get("weather_49"))
        assertEquals("latest", reloaded.get("news_latest"))
    }

    @Test
    fun replacementAtCapacityDoesNotEvictOtherEntries() {
        repeat(ApiCache.MAX_PERSISTENT_ENTRIES) { cache.put("search_$it", "original") }
        cache.put("search_0", "updated")
        val reloaded = ApiCache(context)
        assertEquals(ApiCache.MAX_PERSISTENT_ENTRIES, reloaded.getStats().persistentEntries)
        assertEquals("updated", reloaded.get("search_0"))
        assertEquals("original", reloaded.get("search_49"))
    }

    @Test
    fun prefixInvalidationPreservesOtherCategoriesOnReload() {
        cache.put("weather_seoul", "weather")
        cache.put("news_today", "news")
        cache.invalidateByPrefix(ApiCache.PREFIX_WEATHER)
        assertNull(cache.get("weather_seoul"))
        val reloaded = ApiCache(context)
        assertNull(reloaded.get("weather_seoul"))
        assertEquals("news", reloaded.get("news_today"))
    }

    @Test
    fun expiredPersistentEntryIsRemovedOnRead() {
        cache.put("weather_expired", "outdated", ttlMs = -1000L)

        val reloaded = ApiCache(context)
        assertNull(reloaded.get("weather_expired"))
        assertFalse(context.getSharedPreferences("api_cache", Context.MODE_PRIVATE)
            .contains("weather_expired"))
        assertEquals(0, reloaded.getStats().persistentEntries)
    }

    @Test
    fun malformedPersistentEntryIsRemovedWithoutLosingHealthyEntries() {
        cache.put("news_today", "news")
        context.getSharedPreferences("api_cache", Context.MODE_PRIVATE).edit()
            .putString("weather_corrupt", "not valid JSON").apply()

        val reloaded = ApiCache(context)
        assertNull(reloaded.get("weather_corrupt"))
        assertFalse(context.getSharedPreferences("api_cache", Context.MODE_PRIVATE)
            .contains("weather_corrupt"))
        assertEquals("news", reloaded.get("news_today"))
        assertEquals(1, reloaded.getStats().persistentEntries)
    }
}
