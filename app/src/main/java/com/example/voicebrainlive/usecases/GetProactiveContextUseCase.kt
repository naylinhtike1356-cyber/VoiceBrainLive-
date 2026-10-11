package com.example.voicebrainlive.usecases

import android.content.Context
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5.3: Use case for proactive greeting context.
 * Returns context for greeting (weather, schedule, frequent contacts).
 */
class GetProactiveContextUseCase(private val context: Context) {

    suspend operator fun invoke(): String = withContext(Dispatchers.IO) {
        try {
            PhoneCommandExecutor(context).getProactiveGreetingContextAsync()
        } catch (e: Exception) {
            ""
        }
    }
}
