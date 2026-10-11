package com.example.voicebrainlive.memory

object MemoryFreshnessPolicy {
    const val BEHAVIOR_MAX_AGE_MS = 180L * 24L * 60L * 60L * 1000L
    const val MIN_ALIAS_CONFIDENCE = 60

    fun isFresh(updatedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        updatedAt <= 0L || (updatedAt <= now && now - updatedAt <= BEHAVIOR_MAX_AGE_MS)
}
