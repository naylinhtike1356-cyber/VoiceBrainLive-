package com.example.voicebrainlive.memory

object MemoryConfig {
    const val maxRecentTurns = 24
    const val maxSummariesInContext = 6
    const val contextCacheDurationMs = 30_000L
    const val vectorCacheDurationMs = 60_000L
    const val vectorSimilarityThreshold = 0.20f
    const val relevantContextThreshold = 0.30f

    /** Hard upper bound for the assembled memory prompt, excluding the live user turn. */
    const val maxContextChars = 12_000

    /** Reserved space for safety and disambiguation instructions. */
    const val policyBudgetChars = 700

    /** Per-source caps keep one noisy source from consuming the whole prompt. */
    const val factsBudgetChars = 2_000
    const val summariesBudgetChars = 2_000
    const val recentTurnsBudgetChars = 4_000
    const val semanticBudgetChars = 2_000
    const val behaviorBudgetChars = 1_200

    val semanticQueries = listOf(
        "user name birth year family friends preferences",
        "recent conversation topics discussed",
        "work schedule meetings calendar",
        "music songs played Spotify Lo-fi",
        "places locations home office",
        "user habits routine reminders",
        "corrections user corrected wanted"
    )
}
