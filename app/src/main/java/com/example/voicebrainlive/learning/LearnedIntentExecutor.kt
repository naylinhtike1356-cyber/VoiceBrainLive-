package com.example.voicebrainlive.learning

import android.content.Context
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.memory.SmartContinuationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_VOICE_PHRASE_LEN = 4

/**
 * Runs safe commands immediately when learned patterns / behavior memory
 * are confident enough — without waiting for Gemini to interpret again.
 */
object LearnedIntentExecutor {

    data class FastPathResult(
        val resultMessage: String,
        val spokenHint: String,
        val commandType: String
    )

    suspend fun tryExecute(
        context: Context,
        userPhrase: String,
        executor: PhoneCommandExecutor
    ): FastPathResult? = withContext(Dispatchers.IO) {
        val phrase = userPhrase.trim()
        if (phrase.isBlank()) return@withContext null

        UserUtteranceContext.set(phrase)

        val analysis = SmartContinuationEngine.analyze(context, phrase) ?: return@withContext null
        if (!analysis.shouldAutoExecute) return@withContext null

        val target = analysis.suggestedTarget.takeIf { it.isNotBlank() }
        val result = executor.executeAsync(
            analysis.suggestedCommand,
            target,
            null
        )

        val unproven = listOf("NEEDS_APPROVAL:", "BLOCKED:", "FAILED:", "FAILED/OUTCOME_UNKNOWN:",
            "UNVERIFIED:", "NOT_CONFIGURED:").any(result::startsWith)
        if (!unproven) IntentPatternLearner.recordPattern(
            context = context,
            phrase = phrase,
            commandType = analysis.suggestedCommand,
            target = target ?: "",
            confirmedByUser = true
        )

        FastPathResult(
            resultMessage = result,
            spokenHint = if (unproven) result else analysis.suggestionText.ifBlank { result },
            commandType = analysis.suggestedCommand
        )
    }

    /**
     * Voice-only fast path: run only when the user previously confirmed this exact phrase mapping,
     * so we avoid double-guessing ambiguous short utterances while Gemini is still listening.
     */
    suspend fun tryExecuteConfirmedVoice(
        context: Context,
        userPhrase: String,
        executor: PhoneCommandExecutor
    ): FastPathResult? = withContext(Dispatchers.IO) {
        val phrase = userPhrase.trim()
        if (phrase.length < MIN_VOICE_PHRASE_LEN) return@withContext null

        val learned = IntentPatternLearner.getIntentForPhrase(
            context,
            phrase,
            minConfidence = IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE
        ) ?: return@withContext null
        if (!learned.confirmed) return@withContext null

        val analysis = SmartContinuationEngine.analyze(context, phrase) ?: return@withContext null
        if (!analysis.shouldAutoExecute) return@withContext null
        if (analysis.source != SmartContinuationEngine.ContinuationSource.INTENT_PATTERN) {
            return@withContext null
        }

        tryExecute(context, phrase, executor)
    }
}
