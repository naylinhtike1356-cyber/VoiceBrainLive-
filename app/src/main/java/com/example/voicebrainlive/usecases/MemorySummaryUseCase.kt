package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.memory.SummarizationSessionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Legacy wrapper for session memory learning.
 *
 * Kept for callers that still reference MemorySummaryUseCase, but routes through
 * the real summarization pipeline instead of storing canned placeholder facts.
 */
class MemorySummaryUseCase(private val context: Context) {

    suspend fun invoke(apiKey: String) = withContext(Dispatchers.IO) {
        SummarizationSessionHelper.summarizeAndSave(context, apiKey)
    }
}
