package com.example.voicebrainlive.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * API Response Cache - Caches API responses to reduce network calls
 * 
 * Features:
 * - In-memory cache with LRU eviction
 * - Persistent cache for important data (weather, etc.)
 * - TTL (Time-To-Live) based expiration
 * - Thread-safe operations
 * 
 * Usage:
 * ```
 * val cache = ApiCache(context)
 * 
 * // Store response
 * cache.put("weather_seoul", responseData, ttlMs = 30 * 60 * 1000) // 30 minutes
 * 
 * // Get cached response
 * val cached = cache.get("weather_seoul")
 * 
 * // Invalidate cache
 * cache.invalidate("weather_seoul")
 * ```
 */
class ApiCache(context: Context) {

    companion object {
        private const val TAG = "ApiCache"
        
        // Cache key prefixes
        const val PREFIX_WEATHER = "weather_"
        const val PREFIX_SEARCH = "search_"
        const val PREFIX_NEWS = "news_"
        const val PREFIX_LOCATION = "location_"
        
        // Default TTL values (milliseconds)
        const val TTL_WEATHER = 30 * 60 * 1000L      // 30 minutes
        const val TTL_SEARCH = 60 * 60 * 1000L        // 1 hour
        const val TTL_NEWS = 15 * 60 * 1000L          // 15 minutes
        const val TTL_LOCATION = 5 * 60 * 1000L       // 5 minutes
        const val TTL_DEFAULT = 10 * 60 * 1000L       // 10 minutes
        
        // Max entries in persistent cache
        const val MAX_PERSISTENT_ENTRIES = 50
    }

    // In-memory cache (LRU)
    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 100 // Max 100 entries in memory
        }
    }
    
    // Persistent cache
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("api_cache", Context.MODE_PRIVATE)
    }
    
    /**
     * Get cached response by key
     * 
     * @param key Cache key
     * @return Cached response or null if not found/expired
     */
    @Synchronized
    fun get(key: String): String? {
        // Check memory cache first
        val memoryEntry = memoryCache[key]
        if (memoryEntry != null && !memoryEntry.isExpired()) {
            Log.d(TAG, "Cache HIT (memory): $key")
            return memoryEntry.data
        } else if (memoryEntry != null) {
            memoryCache.remove(key)
            Log.d(TAG, "Cache EXPIRED (memory): $key")
        }
        
        // Check persistent cache
        val cachedJson = prefs.getString(key, null)
        if (cachedJson != null) {
            try {
                val entry = CacheEntry.fromJson(cachedJson)
                if (!entry.isExpired()) {
                    // Promote to memory cache
                    memoryCache[key] = entry
                    Log.d(TAG, "Cache HIT (persistent): $key")
                    return entry.data
                } else {
                    // Remove expired entry
                    prefs.edit().remove(key).apply()
                    Log.d(TAG, "Cache EXPIRED (persistent): $key")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cached entry: ${e.message}")
                prefs.edit().remove(key).apply()
            }
        }
        
        Log.d(TAG, "Cache MISS: $key")
        return null
    }

    /**
     * Store response in cache
     * 
     * @param key Cache key
     * @param data Response data
     * @param ttlMs Time-to-live in milliseconds
     * @param persistent Whether to persist to disk (default: true)
     */
    @Synchronized
    fun put(key: String, data: String, ttlMs: Long = TTL_DEFAULT, persistent: Boolean = true) {
        val entry = CacheEntry(data, System.currentTimeMillis() + ttlMs)
        
        // Store in memory cache
        memoryCache[key] = entry
        
        // Store in persistent cache if requested
        if (persistent) {
            try {
                // Limit persistent cache size
                if (!prefs.contains(key) && prefs.all.size >= MAX_PERSISTENT_ENTRIES) {
                    cleanOldestEntries()
                }
                
                prefs.edit().putString(key, entry.toJson()).apply()
                Log.d(TAG, "Cached: $key (TTL: ${ttlMs / 1000}s)")
            } catch (e: Exception) {
                Log.e(TAG, "Error caching entry: ${e.message}")
            }
        }
    }

    /**
     * Invalidate cached entry
     * 
     * @param key Cache key to invalidate
     */
    @Synchronized
    fun invalidate(key: String) {
        memoryCache.remove(key)
        prefs.edit().remove(key).apply()
        Log.d(TAG, "Invalidated: $key")
    }

    /**
     * Invalidate all entries with a prefix
     * 
     * @param prefix Key prefix to invalidate
     */
    @Synchronized
    fun invalidateByPrefix(prefix: String) {
        val keysToRemove = memoryCache.keys.filter { it.startsWith(prefix) }.toList()
        keysToRemove.forEach { memoryCache.remove(it) }
        
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach { editor.remove(it) }
        editor.apply()
        
        Log.d(TAG, "Invalidated ${keysToRemove.size} entries with prefix: $prefix")
    }

    /**
     * Clear all cache
     */
    @Synchronized
    fun clear() {
        memoryCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All cache cleared")
    }

    /**
     * Get cache statistics
     */
    @Synchronized
    fun getStats(): CacheStats {
        val entries = prefs.all
        val persistentCount = entries.size
        var totalSize = 0L
        var expiredCount = 0L
        
        entries.values.forEach { value ->
            try {
                val entry = CacheEntry.fromJson(value.toString())
                totalSize += entry.data.toByteArray(Charsets.UTF_8).size
                if (entry.isExpired()) expiredCount++
            } catch (e: Exception) {
                expiredCount++
            }
        }
        
        return CacheStats(
            memoryEntries = memoryCache.size,
            persistentEntries = persistentCount,
            totalSizeBytes = totalSize,
            expiredEntries = expiredCount
        )
    }

    /**
     * Remove oldest entries if cache is full
     */
    private fun cleanOldestEntries() {
        val editor = prefs.edit()
        val allEntries = prefs.all
            .filterValues { it is String }
            .mapNotNull { (key, value) ->
                try {
                    val entry = CacheEntry.fromJson(value.toString())
                    if (entry.isExpired()) {
                        editor.remove(key)
                        null
                    } else {
                        key to entry
                    }
                } catch (e: Exception) {
                    editor.remove(key)
                    null
                }
            }
            .sortedBy { (_, entry) -> entry.expiresAt }
        
        // Keep only the newest MAX_PERSISTENT_ENTRIES - 10 entries
        val toRemove = allEntries.take(maxOf(0, allEntries.size - (MAX_PERSISTENT_ENTRIES - 10)))
        toRemove.forEach { (key, _) ->
            editor.remove(key)
        }
        editor.apply()
        
        Log.d(TAG, "Cleaned ${toRemove.size} old cache entries")
    }

    /**
     * Cache entry data class
     */
    data class CacheEntry(
        val data: String,
        val expiresAt: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
        
        fun ttlRemaining(): Long = maxOf(0, expiresAt - System.currentTimeMillis())
        
        fun toJson(): String {
            return JSONObject().apply {
                put("data", data)
                put("expiresAt", expiresAt)
            }.toString()
        }
        
        companion object {
            fun fromJson(json: String): CacheEntry {
                val obj = JSONObject(json)
                return CacheEntry(
                    data = obj.getString("data"),
                    expiresAt = obj.getLong("expiresAt")
                )
            }
        }
    }

    /**
     * Cache statistics
     */
    data class CacheStats(
        val memoryEntries: Int,
        val persistentEntries: Int,
        val totalSizeBytes: Long,
        val expiredEntries: Long
    ) {
        override fun toString(): String {
            return "CacheStats(memory=$memoryEntries, persistent=$persistentEntries, " +
                   "size=${totalSizeBytes / 1024}KB, expired=$expiredEntries)"
        }
    }
}

/**
 * Extension function to get cached API response or fetch if not cached
 */
suspend fun <T> ApiCache.getOrFetch(
    key: String,
    ttlMs: Long = ApiCache.TTL_DEFAULT,
    fetcher: suspend () -> T
): T {
    val cached = get(key)
    if (cached != null) {
        @Suppress("UNCHECKED_CAST")
        return cached as T
    }
    
    val result = fetcher()
    put(key, result.toString(), ttlMs)
    return result
}
