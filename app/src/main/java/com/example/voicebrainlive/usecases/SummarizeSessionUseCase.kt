package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.memory.SummarizationSessionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5.3: Use case for session summarization on disconnect.
 */
class SummarizeSessionUseCase(private val context: Context) {

    suspend operator fun invoke(apiKey: String) = withContext(Dispatchers.IO) {
        try {
            SummarizationSessionHelper.summarizeAndSave(context, apiKey)
        } catch (e: Exception) {
            // Log in caller
        }
    }
}
