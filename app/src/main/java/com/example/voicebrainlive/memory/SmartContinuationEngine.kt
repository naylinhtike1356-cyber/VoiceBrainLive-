package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.learning.IntentPatternLearner

/**
 * Smart Continuation Engine
 *
 * User ရဲ့ Incomplete / ambiguous request ကို
 * previous context + learned patterns + behavior memory ဖြင့် complete လုပ်ပေးသည်။
 *
 * Confidence Scoring System:
 * - 80-100% → Auto-execute (မမေးဘဲ လုပ်)
 * - 50-79%  → Suggest + confirm ("X လုပ်ပေးရမလား?")
 * - < 50%   → Ask clarification
 *
 * Examples:
 * User: "ဖွင့်ပေး" (open/play - ambiguous)
 *   → Check: last music? last app? intent pattern for "ဖွင့်"?
 *   → If last music "Lo-fi" + confidence 85% → auto-play Lo-fi
 *
 * User: "ခေါ်ပေး" (call - ambiguous)
 *   → Check: most frequent contact + last called + aliases
 *   → Suggest: "မောင်မောင် ကို ခေါ်ပေးရမလား?"
 */
object SmartContinuationEngine {

    private const val TAG = "SmartContinuationEngine"

    /**
     * Analyze a user utterance and return the best continuation suggestion.
     *
     * @param context    Android context
     * @param userPhrase The user's utterance (may be incomplete)
     * @return ContinuationResult with suggested action and confidence
     */
    fun analyze(context: Context, userPhrase: String): ContinuationResult? {
        val phrase = userPhrase.trim()
        if (phrase.isBlank()) return null

        return try {
            // Strategy 1: Check learned intent patterns first (highest priority)
            val intentMatch = IntentPatternLearner.getIntentForPhrase(
                context,
                phrase,
                minConfidence = IntentPatternLearner.CONFIDENCE_SUGGEST
            )
            if (intentMatch != null) {
                val safeConfidence = if (requiresExplicitConfirmation(intentMatch.commandType)) {
                    minOf(intentMatch.confidence, IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE - 1)
                } else {
                    intentMatch.confidence
                }
                Log.d(TAG, "IntentPattern match: '$phrase' → ${intentMatch.commandType} (${intentMatch.confidence}%)")
                return ContinuationResult(
                    originalPhrase = phrase,
                    suggestedCommand = intentMatch.commandType,
                    suggestedTarget = intentMatch.lastTarget,
                    confidence = safeConfidence,
                    source = ContinuationSource.INTENT_PATTERN,
                    suggestionText = buildSuggestionText(intentMatch.commandType, intentMatch.lastTarget, safeConfidence)
                )
            }

            // Strategy 2: Context-based continuation for specific ambiguous phrases
            val contextResult = analyzeByContext(context, phrase)
            contextResult
        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
            null
        }
    }

    /**
     * Analyze phrase based on behavior context (music, calls).
     */
    private fun analyzeByContext(context: Context, phrase: String): ContinuationResult? {
        val lower = phrase.lowercase()

        // Music-related ambiguous phrases
        val isMusicPhrase = lower.contains("ဖွင့်") || lower.contains("play") ||
            lower.contains("ပြန်ဖွင့်") || lower.contains("music") ||
            lower.contains("သီချင်း")
        if (isMusicPhrase && phrase.length < 15) {
            val lastMusic = BehaviorMemory.getLastMusic(context)
            val correctedMusic = BehaviorMemory.getLastCorrectedMusic(context)
            val preferred = correctedMusic ?: lastMusic
            if (preferred != null) {
                val confidence = if (correctedMusic != null) 85 else 72
                return ContinuationResult(
                    originalPhrase = phrase,
                    suggestedCommand = "play_spotify",
                    suggestedTarget = preferred,
                    confidence = confidence,
                    source = ContinuationSource.BEHAVIOR_MEMORY,
                    suggestionText = when {
                        confidence >= IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE ->
                            "\"$preferred\" ဖွင့်ပေးနေပါပြီ"
                        else ->
                            "\"$preferred\" ဖွင့်ပေးရမလား?"
                    }
                )
            }
        }

        // Call-related ambiguous phrases
        val isCallPhrase = lower.contains("ခေါ်") || lower.contains("call") ||
            (lower.length < 8 && lower.contains("သူ"))
        if (isCallPhrase && phrase.length < 20) {
            val lastCorrected = BehaviorMemory.getLastCorrectedContact(context)
            val lastCalled = BehaviorMemory.getLastCalledContact(context)
            val frequent = BehaviorMemory.getMostFrequentContact(context)
            val preferred = lastCorrected ?: frequent ?: lastCalled
            if (preferred != null) {
                val confidence = when {
                    lastCorrected != null -> 75
                    frequent != null -> 70
                    else -> 65
                }
                return ContinuationResult(
                    originalPhrase = phrase,
                    suggestedCommand = "make_call",
                    suggestedTarget = preferred,
                    confidence = confidence,
                    source = ContinuationSource.BEHAVIOR_MEMORY,
                    suggestionText = when {
                        confidence >= IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE ->
                            "$preferred ကို ခေါ်ပေးနေပါပြီ"
                        else ->
                            "$preferred ကို ခေါ်ပေးရမလား?"
                    }
                )
            }
        }

        return null
    }

    /**
     * Build human-readable suggestion text for intent pattern matches.
     */
    private fun buildSuggestionText(command: String, target: String, confidence: Int): String {
        val targetDisplay = if (target.isNotBlank()) "\"$target\"" else ""
        val effectiveConfidence = if (requiresExplicitConfirmation(command)) {
            minOf(confidence, IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE - 1)
        } else {
            confidence
        }
        return when {
            effectiveConfidence >= IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE -> {
                when (command) {
                    "make_call" -> "$targetDisplay ကို ခေါ်ပေးနေပါပြီ"
                    "play_spotify" -> "$targetDisplay ဖွင့်ပေးနေပါပြီ"
                    "web_search", "search" -> "$targetDisplay ရှာပေးနေပါပြီ"
                    "send_sms" -> "$targetDisplay ကို SMS ပို့ပေးနေပါပြီ"
                    "set_alarm" -> "alarm ထားပေးနေပါပြီ"
                    else -> "$command $targetDisplay ဆောင်ရွက်ပေးနေပါပြီ"
                }
            }
            else -> {
                when (command) {
                    "make_call" -> "$targetDisplay ကို ခေါ်ပေးရမလား?"
                    "play_spotify" -> "$targetDisplay ဖွင့်ပေးရမလား?"
                    "web_search", "search" -> "$targetDisplay ရှာပေးရမလား?"
                    "send_sms" -> "$targetDisplay ကို SMS ပို့ပေးရမလား?"
                    "set_alarm" -> "alarm ထားပေးရမလား?"
                    else -> "$command $targetDisplay လုပ်ပေးရမလား?"
                }
            }
        }
    }

    /**
     * Generate a context string for the AI system instruction describing
     * smart continuation capabilities.
     */
    fun getContextInstructionString(): String {
        return """

[Smart Continuation - When user's phrase is short or ambiguous]
- Memory is context only; it is not permission to call, message, delete, or create calendar actions.
- Never convert a music/search/weather phrase into a phone call.
- Check their learned intent patterns first and execute directly if confidence ≥ 80%
- For calls: suggest most frequent/last corrected contact, then wait for explicit confirmation
- For music: use last played / corrected music only when the phrase is clearly about music
- For repeats ("ထပ်", "ဒါပဲ", "ပြန်လုပ်"): repeat the last action
- When confidence 50-79%: suggest once with a short question, then execute if user confirms
- Avoid asking "ဘာလုပ်ပေးရမလဲ?" repeatedly - use context to infer and act
[End smart continuation]"""
    }

    /**
     * Continuation result
     */
    data class ContinuationResult(
        val originalPhrase: String,
        val suggestedCommand: String,
        val suggestedTarget: String,
        val confidence: Int,
        val source: ContinuationSource,
        val suggestionText: String
    ) {
        val shouldAutoExecute: Boolean
            get() = confidence >= IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE &&
                !requiresExplicitConfirmation(suggestedCommand)
        val shouldSuggest: Boolean get() = confidence in IntentPatternLearner.CONFIDENCE_SUGGEST until IntentPatternLearner.CONFIDENCE_AUTO_EXECUTE
    }

    private fun requiresExplicitConfirmation(command: String): Boolean =
        com.example.voicebrainlive.commands.CommandSafetyPolicy.requiresConfirmation(command)

    enum class ContinuationSource {
        INTENT_PATTERN,  // From IntentPatternLearner
        BEHAVIOR_MEMORY, // From BehaviorMemory (call/music)
        LAST_ACTION,     // Repeat last action
        PERSONAL_PROFILE // From PersonalProfileEngine
    }
}
