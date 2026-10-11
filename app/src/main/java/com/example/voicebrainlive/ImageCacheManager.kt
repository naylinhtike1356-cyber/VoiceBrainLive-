package com.example.voicebrainlive

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.util.DebugLogger

/**
 * Phase 3: Performance optimization - Image Caching for Spotify and AI assets.
 * Configures Coil with a dedicated disk and memory cache.
 */
object ImageCacheManager {

    private var imageLoader: ImageLoader? = null

    fun getImageLoader(context: Context): ImageLoader {
        if (imageLoader == null) {
            imageLoader = ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder(context)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.02)
                        .build()
                }
                .crossfade(true)
                .logger(DebugLogger())
                .build()
        }
        return imageLoader!!
    }
}
