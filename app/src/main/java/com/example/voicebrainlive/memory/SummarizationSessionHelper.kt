package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.learning.PersonalProfileEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase I.2: Summarization Layer.
 * When session ends (disconnect), summarizes last N turns via Gemini text API and saves.
 * SessionContextProvider uses these summaries for richer context injection.
 *
 * Expert: AI က အဖြေပြန်ပေးတိုင်း တန်းလုပ်မနေပါနဲ့။ Disconnect မှာသာ Background Dispatchers.IO သုံးပြီး အနှစ်ချုပ်ခိုင်းပါ။
 */
object SummarizationSessionHelper {

    private const val MAX_SUMMARIES = 12
    private const val TURNS_TO_SUMMARIZE = 30
    private const val FILE_NAME = "session_summaries.json"
    private const val MODEL = "gemini-2.0-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun loadSummaries(context: Context): MutableList<String> {
        return try {
            val file = getFile(context)
            if (!file.exists()) return mutableListOf()
            val json = file.readText()
            if (json.isBlank()) return mutableListOf()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toMutableList()
        } catch (e: Exception) {
            Log.e("Summarization", "loadSummaries", e)
            mutableListOf()
        }
    }

    private fun saveSummaries(context: Context, summaries: List<String>) {
        try {
            val arr = JSONArray()
            summaries.takeLast(MAX_SUMMARIES).forEach { arr.put(it) }
            getFile(context).writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("Summarization", "saveSummaries", e)
        }
    }

    /**
     * Summarize last N turns and append to summaries. Call from Dispatchers.IO.
     */
    suspend fun summarizeAndSave(context: Context, apiKey: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.length < 20) return@withContext
        val turns = SimpleConversationStorage.getRecentForContext(context, TURNS_TO_SUMMARIZE)
        if (turns.size < 2) return@withContext
        val convText = turns.joinToString("\n") { t ->
            val label = if (t.role == "user") "User" else "Assistant"
            "$label: ${t.content}"
        }
        val prompt = """Analyze this conversation and output in FOUR sections. Preserve EVERY detail.

SECTION 1 - FACTS (HIGH PRIORITY):
List each specific fact on its own line. Format: - Key: value
Include: names, birth years, birth dates, numbers, preferences, corrections.
ALSO extract: (1) User corrections e.g. "မဟုတ်ဘူး X လုပ်ခဲ့" → User corrected: wanted X. (2) Preferences e.g. "ငါ X ကြိုက်တယ်", "X ဖွင့်ခဲ့". (3) Identity e.g. job, location, family.
Example: - User's name: မိုးမြတ်
Example: - User birth year: 1990
Example: - User preference: likes Lo-fi when studying
Example: - User routine: morning gym at 6am
Extract ALL facts. Do NOT skip.

SECTION 2 - INTENT PATTERNS (what short phrases the user uses for what actions):
List only clear phrase→action mappings observed in this conversation.
Format: - "phrase" → action_type
Example: - "ထမင်းဆိုင်" → web_search for restaurants
Example: - "ကိုမောင်" → make_call to Ko Maung
Example: - "lo fi" → play_spotify Lo-fi beats
Only include CONFIRMED patterns from this conversation.

SECTION 3 - PERSONAL STYLE:
1-2 sentences about how user communicates (brief/detailed, formal/casual, Burmese/English mix, direct/indirect).

SECTION 4 - BRIEF SUMMARY:
2-3 sentences overview of main topics discussed.

Conversation:
$convText

Output:"""
        val response = callGeminiSummarize(apiKey, prompt, maxTokens = 1024) ?: return@withContext
        // Parse all 4 sections
        val (factLines, intentPatterns, personalStyle, summaryText) = parseFourSections(response)
        if (factLines.isNotEmpty()) {
            PersistentFactsStorage.mergeFacts(context, factLines)
            // Also feed into PersonalProfileEngine for categorical storage
            PersonalProfileEngine.updateFromSummary(context, factLines)
        }
        if (intentPatterns.isNotEmpty()) {
            // Store intent patterns via IntentPatternLearner
            storeIntentPatterns(context, intentPatterns)
        }
        if (personalStyle.isNotBlank()) {
            PersonalProfileEngine.setFact(context, PersonalProfileEngine.CAT_STYLE, "communication_style", personalStyle)
        }
        if (summaryText.isNotBlank()) {
            val summaries = loadSummaries(context)
            summaries.add(summaryText.trim())
            saveSummaries(context, summaries)
            // Store embedding for semantic search
            val summaryToStore = summaryText.trim()
            VectorStore.store(context, apiKey, summaryToStore, type = "summary")
            factLines.take(10).forEach { fact ->
                if (fact.length in 5..200) VectorStore.store(context, apiKey, fact, type = "fact")
            }
            SessionContextProvider.invalidateCache(context)
            Log.d("Memory", "SummarizationSessionHelper: saved summary + ${factLines.size} facts + ${intentPatterns.size} patterns + style (${summaries.size} summaries)")
        }
        if (factLines.isNotEmpty() || intentPatterns.isNotEmpty() || personalStyle.isNotBlank()) {
            SessionContextProvider.invalidateCache(context)
        }
    }

    /** Store intent patterns parsed from summarization */
    private fun storeIntentPatterns(context: Context, patterns: List<String>) {
        patterns.forEach { line ->
            // Pattern: "phrase" → action_type details
            val match = Regex("\"([^\"]+)\"\\s*→\\s*([\\w_]+)\\s*(.*)").find(line)
            if (match != null) {
                val phrase = match.groupValues[1].trim()
                val action = match.groupValues[2].trim()
                val details = match.groupValues[3].trim()
                if (phrase.isNotBlank() && action.isNotBlank()) {
                    com.example.voicebrainlive.learning.IntentPatternLearner.recordPattern(
                        context = context,
                        phrase = phrase,
                        commandType = action,
                        target = details.take(100),
                        confirmedByUser = false
                    )
                    Log.d("Memory", "storeIntentPattern: '$phrase' → $action")
                }
            }
        }
    }
    private data class ParsedSections(
        val factLines: List<String>,
        val intentPatterns: List<String>,
        val personalStyle: String,
        val summaryText: String
    )

    private fun parseFourSections(response: String): ParsedSections {
        val lines = response.lines()
        val factLines = mutableListOf<String>()
        val intentPatterns = mutableListOf<String>()
        val styleLines = mutableListOf<String>()
        val summaryLines = mutableListOf<String>()

        var section = 0 // 0=none, 1=facts, 2=intents, 3=style, 4=summary
        for (line in lines) {
            val l = line.trim()
            when {
                l.contains("SECTION 1", ignoreCase = true) || (l.contains("FACTS", ignoreCase = true) && !l.contains("SECTION")) ->
                    section = 1
                l.contains("SECTION 2", ignoreCase = true) || l.contains("INTENT PATTERN", ignoreCase = true) ->
                    section = 2
                l.contains("SECTION 3", ignoreCase = true) || l.contains("PERSONAL STYLE", ignoreCase = true) ->
                    section = 3
                l.contains("SECTION 4", ignoreCase = true) || l.contains("BRIEF SUMMARY", ignoreCase = true) ->
                    section = 4
                l.isBlank() || l.startsWith("Example") -> { /* skip */ }
                section == 1 && (l.startsWith("-") || l.contains(":")) ->
                    factLines.add(l)
                section == 2 && l.startsWith("-") && l.contains("→") ->
                    intentPatterns.add(l.removePrefix("-").trim())
                section == 3 ->
                    styleLines.add(l)
                section == 4 ->
                    summaryLines.add(l)
                // Fallback: fact-like line even outside section 1
                section == 0 && l.startsWith("-") && l.contains(":") && l.length in 5..150 ->
                    factLines.add(l)
            }
        }
        return ParsedSections(
            factLines = factLines,
            intentPatterns = intentPatterns,
            personalStyle = styleLines.joinToString(" ").trim().take(300),
            summaryText = summaryLines.joinToString(" ").trim().take(400)
        )
    }

    /** Legacy compatibility: parse into (factLines, summaryText) only */
    @Suppress("unused")
    private fun parseFactsAndSummary(response: String): Pair<List<String>, String> {
        val s = parseFourSections(response)
        return s.factLines to s.summaryText
    }

    private fun callGeminiSummarize(apiKey: String, prompt: String, maxTokens: Int = 512): String? {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", maxTokens)
                    put("temperature", 0.2)
                })
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("Summarization", "Gemini API error: ${response.code} ${response.body?.string()}")
                return null
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
        } catch (e: Exception) {
            Log.e("Summarization", "callGeminiSummarize", e)
            null
        }
    }

    /** Get last summaries for context injection. Call from Dispatchers.IO. */
    fun getSummaries(context: Context, limit: Int = 4): List<String> {
        return loadSummaries(context).takeLast(limit)
    }

    /** Clear all session summaries */
    fun clearSummaries(context: Context) {
        try {
            val file = getFile(context)
            if (file.exists()) {
                file.delete()
            }
            Log.d("Summarization", "Cleared all session summaries")
        } catch (e: Exception) {
            Log.e("Summarization", "clearSummaries error", e)
        }
    }
}
