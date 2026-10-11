package com.example.voicebrainlive.learning

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Intent Pattern Learner
 *
 * User တကြိမ် Command ပေးလိုက်တာနဲ့ pattern မှတ်ထားပြီး
 * နောက်ကြိမ် same/similar phrase ပြောရင် confirm မမေးဘဲ ဆောင်ရွက်နိုင်မည်။
 *
 * မှတ်ထားသည့် Pattern Structure:
 * {
 *   "pattern_key": {
 *     "phrase": "ထမင်းဆိုင်",
 *     "commandType": "web_search",
 *     "lastTarget": "restaurant near me",
 *     "hits": 3,
 *     "confirmed": true,
 *     "confidence": 85,
 *     "updatedAt": 1712345678
 *   }
 * }
 *
 * Usage:
 * - PhoneCommandExecutor မှာ successful command တိုင်း recordPattern() ခေါ်ပါ
 * - getIntentForPhrase() ဖြင့် known pattern စစ်ဆေးပါ
 */
object IntentPatternLearner {

    private const val TAG = "IntentPatternLearner"
    private const val FILE_NAME = "intent_patterns.json"
    private const val MAX_PATTERNS = 200
    private const val MIN_PHRASE_LEN = 2
    private const val MAX_PHRASE_LEN = 60

    // Confidence thresholds
    const val CONFIDENCE_AUTO_EXECUTE = 80   // ≥ 80% = execute without asking
    const val CONFIDENCE_SUGGEST = 50         // 50-79% = suggest and confirm
    // < 50% = ask clarification

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): JSONObject {
        return try {
            val file = getFile(context)
            if (!file.exists()) return JSONObject()
            val text = file.readText()
            if (text.isBlank()) return JSONObject()
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "load error", e)
            JSONObject()
        }
    }

    private fun save(context: Context, obj: JSONObject) {
        try {
            getFile(context).writeText(obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save error", e)
        }
    }

    /**
     * Normalize phrase for key generation:
     * "ထမင်းဆိုင် ရှာ" → "ထမင်းဆိုင်ရှာ"
     */
    private fun toPhraseKey(phrase: String): String =
        phrase.trim().lowercase().replace(Regex("\\s+"), "")

    /**
     * Record a successful command pattern.
     *
     * @param phrase     User ရဲ့ original utterance (e.g. "ထမင်းဆိုင်")
     * @param commandType Command type executed (e.g. "web_search", "make_call")
     * @param target     Command target (e.g. "restaurant near me", "မောင်မောင်")
     * @param confirmedByUser true = user explicitly confirmed this mapping
     */
    fun recordPattern(
        context: Context,
        phrase: String,
        commandType: String,
        target: String = "",
        confirmedByUser: Boolean = false
    ) {
        val trimmedPhrase = phrase.trim()
        if (trimmedPhrase.length < MIN_PHRASE_LEN || trimmedPhrase.length > MAX_PHRASE_LEN) return
        if (commandType.isBlank()) return

        try {
            val obj = load(context)
            val key = toPhraseKey(trimmedPhrase)

            val existing = obj.optJSONObject(key) ?: JSONObject()
            val hits = existing.optInt("hits", 0) + 1
            val wasConfirmed = existing.optBoolean("confirmed", false)

            // Confidence: starts at 60, +10 per hit (capped at 95), +20 if confirmed
            val baseConfidence = minOf(60 + (hits - 1) * 10, 95)
            val confidence = if (confirmedByUser || wasConfirmed) minOf(baseConfidence + 20, 100) else baseConfidence

            obj.put(key, JSONObject().apply {
                put("phrase", trimmedPhrase)
                put("commandType", commandType)
                if (target.isNotBlank()) put("lastTarget", target.take(150))
                put("hits", hits)
                put("confirmed", confirmedByUser || wasConfirmed)
                put("confidence", confidence)
                put("updatedAt", System.currentTimeMillis())
            })

            // Prune if too large
            if (obj.length() > MAX_PATTERNS) pruneOldest(obj)

            save(context, obj)
            Log.d(TAG, "recordPattern: '$trimmedPhrase' → $commandType (hits=$hits, confidence=$confidence%)")
        } catch (e: Exception) {
            Log.e(TAG, "recordPattern error", e)
        }
    }

    /**
     * Look up intent for a user phrase.
     * Returns IntentMatch or null if unknown / low confidence.
     *
     * @param phrase The user's utterance
     * @param minConfidence Minimum confidence to return a result (default 50)
     */
    fun getIntentForPhrase(
        context: Context,
        phrase: String,
        minConfidence: Int = CONFIDENCE_SUGGEST
    ): IntentMatch? {
        val trimmed = phrase.trim()
        if (trimmed.length < MIN_PHRASE_LEN) return null

        return try {
            val obj = load(context)
            val key = toPhraseKey(trimmed)

            // Exact match first
            val exact = obj.optJSONObject(key)
            if (exact != null) {
                val confidence = exact.optInt("confidence", 0)
                if (confidence >= minConfidence) {
                    return IntentMatch(
                        phrase = trimmed,
                        commandType = exact.optString("commandType", ""),
                        lastTarget = exact.optString("lastTarget", ""),
                        confidence = confidence,
                        confirmed = exact.optBoolean("confirmed", false),
                        hits = exact.optInt("hits", 0)
                    )
                }
            }

            // Partial / substring match (find patterns that contain this phrase)
            var bestMatch: IntentMatch? = null
            var bestScore = 0
            obj.keys().forEach { k ->
                val entry = obj.optJSONObject(k) ?: return@forEach
                val storedPhrase = entry.optString("phrase", "")
                val confidence = entry.optInt("confidence", 0)
                if (confidence < minConfidence) return@forEach

                // Check if user phrase contains or is contained in stored phrase
                val isSubstringMatch = storedPhrase.contains(trimmed, ignoreCase = true) ||
                    trimmed.contains(storedPhrase, ignoreCase = true)
                if (isSubstringMatch && confidence > bestScore) {
                    bestScore = confidence
                    bestMatch = IntentMatch(
                        phrase = storedPhrase,
                        commandType = entry.optString("commandType", ""),
                        lastTarget = entry.optString("lastTarget", ""),
                        confidence = confidence - 15, // penalize partial match
                        confirmed = entry.optBoolean("confirmed", false),
                        hits = entry.optInt("hits", 0)
                    )
                }
            }
            bestMatch
        } catch (e: Exception) {
            Log.e(TAG, "getIntentForPhrase error", e)
            null
        }
    }

    /**
     * Get all patterns formatted as a context string for AI system instruction.
     * Only includes patterns with confidence >= 60 or hits >= 2.
     */
    fun getPatternContextString(context: Context): String {
        return try {
            val obj = load(context)
            if (obj.length() == 0) return ""

            val lines = mutableListOf<String>()
            obj.keys().forEach { k ->
                val entry = obj.optJSONObject(k) ?: return@forEach
                val confidence = entry.optInt("confidence", 0)
                val hits = entry.optInt("hits", 0)
                if (confidence < 60 && hits < 2) return@forEach

                val phrase = entry.optString("phrase", "")
                val cmd = entry.optString("commandType", "")
                val target = entry.optString("lastTarget", "")
                val confirmed = entry.optBoolean("confirmed", false)
                if (phrase.isBlank() || cmd.isBlank()) return@forEach

                val desc = if (target.isNotBlank()) "$cmd (target: $target)" else cmd
                val flag = if (confirmed) "✓" else "(${hits}x)"
                lines.add("  '$phrase' → $desc $flag")
            }

            if (lines.isEmpty()) return ""

            val sb = StringBuilder("\n[Learned User Intent Patterns - use directly without asking]\n")
            lines.take(30).forEach { sb.append("$it\n") }
            sb.append("[End intent patterns]")
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "getPatternContextString error", e)
            ""
        }
    }

    /**
     * Confirm a pattern mapping explicitly (user said "Yes that's right").
     */
    fun confirmPattern(context: Context, phrase: String) {
        val key = toPhraseKey(phrase)
        try {
            val obj = load(context)
            val entry = obj.optJSONObject(key) ?: return
            entry.put("confirmed", true)
            entry.put("confidence", minOf(entry.optInt("confidence", 70) + 20, 100))
            entry.put("updatedAt", System.currentTimeMillis())
            obj.put(key, entry)
            save(context, obj)
            Log.d(TAG, "confirmPattern: '$phrase'")
        } catch (e: Exception) {
            Log.e(TAG, "confirmPattern error", e)
        }
    }

    /**
     * Remove a pattern (user said "That's wrong").
     */
    fun removePattern(context: Context, phrase: String) {
        val key = toPhraseKey(phrase)
        try {
            val obj = load(context)
            obj.remove(key)
            save(context, obj)
            Log.d(TAG, "removePattern: '$phrase'")
        } catch (e: Exception) {
            Log.e(TAG, "removePattern error", e)
        }
    }

    /**
     * Clear all stored intent patterns
     */
    fun clearPatterns(context: Context) {
        try {
            val file = getFile(context)
            if (file.exists()) {
                file.delete()
            }
            Log.d(TAG, "Cleared all intent patterns")
        } catch (e: Exception) {
            Log.e(TAG, "clearPatterns error", e)
        }
    }

    /**
     * Delete a single learned pattern by phrase.
     */
    fun deletePattern(context: Context, phrase: String): Boolean {
        return try {
            val obj = load(context)
            val key = toPhraseKey(phrase)
            if (obj.has(key)) {
                obj.remove(key)
                save(context, obj)
                true
            } else {
                val foundKey = obj.keys().asSequence().firstOrNull { it.contains(key) || key.contains(it) }
                if (foundKey != null) {
                    obj.remove(foundKey)
                    save(context, obj)
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "deletePattern error", e)
            false
        }
    }

    /**
     * Get all stored patterns (for debugging / settings display).
     */
    fun getAllPatterns(context: Context): List<IntentMatch> {
        return try {
            val obj = load(context)
            val list = mutableListOf<IntentMatch>()
            obj.keys().forEach { k ->
                val entry = obj.optJSONObject(k) ?: return@forEach
                list.add(IntentMatch(
                    phrase = entry.optString("phrase", ""),
                    commandType = entry.optString("commandType", ""),
                    lastTarget = entry.optString("lastTarget", ""),
                    confidence = entry.optInt("confidence", 0),
                    confirmed = entry.optBoolean("confirmed", false),
                    hits = entry.optInt("hits", 0)
                ))
            }
            list.sortedByDescending { it.confidence }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Remove oldest (lowest hits) entries when limit exceeded */
    private fun pruneOldest(obj: JSONObject) {
        val entries = mutableListOf<Pair<String, Long>>()
        obj.keys().forEach { k ->
            val ts = obj.optJSONObject(k)?.optLong("updatedAt", 0L) ?: 0L
            entries.add(k to ts)
        }
        // Remove oldest 20
        entries.sortedBy { it.second }.take(20).forEach { (k, _) -> obj.remove(k) }
    }

    /**
     * Intent Match result
     */
    data class IntentMatch(
        val phrase: String,
        val commandType: String,
        val lastTarget: String,
        val confidence: Int,
        val confirmed: Boolean,
        val hits: Int
    ) {
        val shouldAutoExecute: Boolean get() = confidence >= CONFIDENCE_AUTO_EXECUTE
        val shouldSuggest: Boolean get() = confidence in CONFIDENCE_SUGGEST until CONFIDENCE_AUTO_EXECUTE
    }
}
