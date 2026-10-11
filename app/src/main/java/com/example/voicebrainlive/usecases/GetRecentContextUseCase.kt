package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.memory.SessionContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5.3: Use case for recent conversation context.
 * Returns memory + summaries + semantic context for system prompt.
 */
class GetRecentContextUseCase(private val context: Context) {

    suspend operator fun invoke(apiKey: String?): String = withContext(Dispatchers.IO) {
        try {
            SessionContextProvider.getRecentContext(context, apiKey)
        } catch (e: Exception) {
            ""
        }
    }
}
