package com.example.voicebrainlive

import com.example.voicebrainlive.memory.MemoryConfig
import com.example.voicebrainlive.memory.SessionContextProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextProviderTest {
    @Test
    fun fitText_keepsTextWithinBudget() {
        val result = SessionContextProvider.fitText("a".repeat(2_000), 300)
        assertTrue(result.length <= 300)
        assertTrue(result.contains("memory truncated"))
    }

    @Test
    fun configuredBudget_hasPositiveHeadroomForPolicy() {
        assertTrue(MemoryConfig.maxContextChars > MemoryConfig.policyBudgetChars)
        assertTrue(MemoryConfig.factsBudgetChars + MemoryConfig.recentTurnsBudgetChars +
            MemoryConfig.summariesBudgetChars + MemoryConfig.behaviorBudgetChars +
            MemoryConfig.semanticBudgetChars + MemoryConfig.policyBudgetChars <=
            MemoryConfig.maxContextChars)
    }
}
