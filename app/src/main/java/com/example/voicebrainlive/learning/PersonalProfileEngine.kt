package com.example.voicebrainlive.learning

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Personal Profile Engine
 *
 * Conversation တိုင်းကနေ User ရဲ့ ကိုယ်ပိုင် Profile ကို extract လုပ်ပြီး
 * persistent Storage မှာ မှတ်ထားသည်။
 *
 * Categories:
 * - identity   : name, age, job, location
 * - preferences: food, music, hobbies, schedule habits
 * - routines   : daily patterns ("morning = gym", "evening = relax music")
 * - relationships: frequent contacts, family
 * - goals      : current tasks, projects, aspirations
 * - style       : how user communicates (brief/detailed, formal/casual)
 *
 * Usage:
 * - SummarizationSessionHelper မှ session end မှာ updateFromSummary() ခေါ်သည်
 * - getProfileContextString() → system instruction မှာ inject လုပ်သည်
 */
object PersonalProfileEngine {

    private const val TAG = "PersonalProfileEngine"
    private const val FILE_NAME = "personal_profile.json"
    private const val MAX_ITEMS_PER_CATEGORY = 30

    // Profile categories
    const val CAT_IDENTITY = "identity"
    const val CAT_PREFERENCES = "preferences"
    const val CAT_ROUTINES = "routines"
    const val CAT_RELATIONSHIPS = "relationships"
    const val CAT_GOALS = "goals"
    const val CAT_STYLE = "style"

    private fun getFile(context: Context): File {
        val activeProfile = try {
            val pm = com.example.voicebrainlive.ProfileManager(context)
            pm.getActiveProfile()
        } catch (e: Exception) {
            "Default User"
        }
        val safeProfileName = activeProfile.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        return File(context.filesDir, "personal_profile_${safeProfileName}.json")
    }

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
     * Set or update a profile fact under a specific category.
     * Automatically resolves conflicts / duplicate keys in the category.
     *
     * @param category  One of CAT_IDENTITY, CAT_PREFERENCES, etc.
     * @param key       The fact key (e.g. "name", "favorite_food")
     * @param value     The fact value (e.g. "မိုးမြတ်", "မုန့်ဟင်းခါး")
     */
    fun setFact(context: Context, category: String, key: String, value: String) {
        val trimKey = key.trim().take(60)
        val trimVal = value.trim().take(300)
        if (trimKey.isBlank() || trimVal.isBlank()) return
        val catName = if (category.isBlank()) autoCategorize(trimKey) else category

        try {
            val obj = load(context)
            val cat = obj.optJSONObject(catName) ?: JSONObject()

            // Conflict resolution: Check if a similar key exists (e.g. "name" vs "user name", "city" vs "နေရပ်")
            val existingKey = findMatchingKeyInCategory(cat, trimKey)
            if (existingKey != null && existingKey != trimKey) {
                cat.remove(existingKey)
            }

            cat.put(trimKey, JSONObject().apply {
                put("value", trimVal)
                put("updatedAt", System.currentTimeMillis())
            })

            // Prune if excessive
            while (cat.length() > MAX_ITEMS_PER_CATEGORY) {
                pruneOldestInCategory(cat)
            }
            obj.put(catName, cat)
            save(context, obj)
            Log.d(TAG, "setFact: [$catName] $trimKey = $trimVal")
        } catch (e: Exception) {
            Log.e(TAG, "setFact error", e)
        }
    }

    /**
     * Auto-categorize and save fact from a single key-value or raw fact string.
     */
    fun autoClassifyAndSet(context: Context, key: String, value: String) {
        val category = autoCategorize(key)
        setFact(context, category, key, value)
    }

    /**
     * Determine category from key phrase (supports Burmese & English).
     */
    fun autoCategorize(key: String): String {
        val k = key.lowercase()
        return when {
            k.contains("name") || k.contains("နာမည်") || k.contains("အမည်") ||
                k.contains("age") || k.contains("အသက်") || k.contains("job") ||
                k.contains("အလုပ်") || k.contains("ရာထူး") || k.contains("city") ||
                k.contains("မြို့") || k.contains("နေရပ်") || k.contains("address") ||
                k.contains("လိပ်စာ") || k.contains("မွေးသက္ကရာဇ်") || k.contains("birthday") ||
                k.contains("birth") -> CAT_IDENTITY

            k.contains("prefer") || k.contains("like") || k.contains("ကြိုက်") ||
                k.contains("နှစ်သက်") || k.contains("music") || k.contains("food") ||
                k.contains("သီချင်း") || k.contains("ဟင်း") || k.contains("အစားအစာ") ||
                k.contains("အရောင်") || k.contains("color") || k.contains("hobby") ||
                k.contains("ဝါသနာ") || k.contains("movie") || k.contains("ရုပ်ရှင်") -> CAT_PREFERENCES

            k.contains("routine") || k.contains("pattern") || k.contains("daily") ||
                k.contains("မနက်") || k.contains("ညနေ") || k.contains("ညဘက်") ||
                k.contains("habit") || k.contains("အလေ့အကျင့်") || k.contains("အချိန်ဇယား") ||
                k.contains("schedule") || k.contains("alarm") || k.contains("နိုး") -> CAT_ROUTINES

            k.contains("contact") || k.contains("call") || k.contains("friend") ||
                k.contains("family") || k.contains("relationship") || k.contains("ခေါ်") ||
                k.contains("သူငယ်ချင်း") || k.contains("မိဘ") || k.contains("အမေ") ||
                k.contains("အဖေ") || k.contains("ညီ") || k.contains("မောင်") ||
                k.contains("မမ") || k.contains("အစ်ကို") || k.contains("ချစ်သူ") ||
                k.contains("ဇနီး") || k.contains("ခင်ပွန်း") -> CAT_RELATIONSHIPS

            k.contains("goal") || k.contains("project") || k.contains("plan") ||
                k.contains("want") || k.contains("လုပ်ချင်") || k.contains("ရည်မှန်းချက်") ||
                k.contains("ပရောဂျက်") || k.contains("အစီအစဉ်") || k.contains("target") -> CAT_GOALS

            k.contains("style") || k.contains("tone") || k.contains("brief") ||
                k.contains("casual") || k.contains("စကားပြောဟန်") || k.contains("လေသံ") ||
                k.contains("တိုတို") || k.contains("ယဉ်ကျေး") -> CAT_STYLE

            else -> CAT_PREFERENCES
        }
    }

    /**
     * Find matching/conflicting key in category for conflict resolution.
     */
    private fun findMatchingKeyInCategory(cat: JSONObject, key: String): String? {
        val normKey = normalizeKey(key)
        cat.keys().forEach { existing ->
            if (existing.equals(key, ignoreCase = true)) return existing
            val normExisting = normalizeKey(existing)
            if (normExisting == normKey || normExisting.contains(normKey) || normKey.contains(normExisting)) {
                return existing
            }
        }
        return null
    }

    private fun normalizeKey(key: String): String {
        return key.lowercase()
            .removePrefix("user")
            .removePrefix("user's")
            .removePrefix("my")
            .replace(Regex("[^a-zA-Z0-9\u1000-\u109F]"), "")
            .trim()
    }

    /**
     * Get a fact from the profile.
     */
    fun getFact(context: Context, category: String, key: String): String? {
        return try {
            val cat = load(context).optJSONObject(category) ?: return null
            val matchedKey = findMatchingKeyInCategory(cat, key) ?: key
            cat.optJSONObject(matchedKey)
                ?.optString("value", "")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Update profile from summarization output.
     * Parses fact lines of format "- Category/Key: Value" or "- Key: Value"
     *
     * Called by SummarizationSessionHelper after each session end.
     */
    fun updateFromSummary(context: Context, factLines: List<String>) {
        if (factLines.isEmpty()) return
        var updated = 0
        factLines.forEach { line ->
            val parsed = parseProfileLine(line) ?: return@forEach
            val (cat, key, value) = parsed
            setFact(context, cat, key, value)
            updated++
        }
        Log.d(TAG, "updateFromSummary: updated $updated items")
    }

    /**
     * Parse a fact line from AI summarization output.
     */
    private fun parseProfileLine(line: String): Triple<String, String, String>? {
        val t = line.trim().removePrefix("-").removePrefix("•").trim()
        val colonIdx = t.indexOf(':')
        if (colonIdx <= 0 || colonIdx >= t.length - 1) return null
        val rawKey = t.substring(0, colonIdx).trim()
        val value = t.substring(colonIdx + 1).trim()
        if (rawKey.isBlank() || value.isBlank()) return null

        val category = autoCategorize(rawKey)
        val cleanKey = rawKey.removePrefix("user's").removePrefix("User's")
            .removePrefix("user").removePrefix("User")
            .removePrefix("my").removePrefix("My")
            .trim().trimStart('\'').trim()

        val safeKey = cleanKey.take(60).ifBlank { rawKey.take(60) }
        return Triple(category, safeKey, value.take(300))
    }

    /**
     * Build a rich context string for AI system instruction.
     * Groups facts by category and prioritizes identity + preferences.
     */
    fun getProfileContextString(context: Context): String {
        return try {
            val obj = load(context)
            if (obj.length() == 0) return ""

            val sb = StringBuilder("\n[Personal Profile - Deep Knowledge About User]\n")
            var hasContent = false

            fun appendCategory(catKey: String, headerTitle: String) {
                val cat = obj.optJSONObject(catKey) ?: return
                if (cat.length() > 0) {
                    val entries = mutableListOf<String>()
                    cat.keys().forEach { k ->
                        val v = cat.optJSONObject(k)?.optString("value", "") ?: ""
                        if (v.isNotBlank()) entries.add("  • $k: $v")
                    }
                    if (entries.isNotEmpty()) {
                        sb.append("$headerTitle:\n")
                        entries.forEach { sb.append("$it\n") }
                        hasContent = true
                    }
                }
            }

            appendCategory(CAT_IDENTITY, "Identity & Core Info")
            appendCategory(CAT_PREFERENCES, "User Preferences & Likes")
            appendCategory(CAT_ROUTINES, "Daily Habits & Routines")
            appendCategory(CAT_RELATIONSHIPS, "Key People & Relationships")
            appendCategory(CAT_GOALS, "Current Goals & Active Projects")
            appendCategory(CAT_STYLE, "Communication Tone & Style")

            if (!hasContent) return ""
            sb.append("[End personal profile]\n")
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "getProfileContextString error", e)
            ""
        }
    }

    /**
     * Get User's display name (for personalized greeting/persona).
     */
    fun getUserName(context: Context): String? {
        return getFact(context, CAT_IDENTITY, "name")
            ?: getFact(context, CAT_IDENTITY, "user's name")
            ?: getFact(context, CAT_IDENTITY, "user name")
            ?: getFact(context, CAT_IDENTITY, "နာမည်")
            ?: getFact(context, CAT_IDENTITY, "အမည်")
    }

    /**
     * Delete a fact across all categories by key keyword.
     */
    fun deleteFact(context: Context, keyQuery: String): Boolean {
        val q = keyQuery.trim().lowercase()
        if (q.isBlank()) return false
        return try {
            val obj = load(context)
            var deleted = false
            val categories = listOf(CAT_IDENTITY, CAT_PREFERENCES, CAT_ROUTINES, CAT_RELATIONSHIPS, CAT_GOALS, CAT_STYLE)
            categories.forEach { catKey ->
                val cat = obj.optJSONObject(catKey) ?: return@forEach
                val matchedKey = cat.keys().asSequence().firstOrNull {
                    it.lowercase().contains(q) || q.contains(it.lowercase())
                }
                if (matchedKey != null) {
                    cat.remove(matchedKey)
                    obj.put(catKey, cat)
                    deleted = true
                }
            }
            if (deleted) {
                save(context, obj)
                Log.d(TAG, "deleteFact: deleted matching '$keyQuery'")
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "deleteFact error", e)
            false
        }
    }

    /**
     * Search all profile facts by query.
     */
    fun searchProfile(context: Context, query: String): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        return try {
            val obj = load(context)
            val results = mutableListOf<Pair<String, String>>()
            val categories = listOf(CAT_IDENTITY, CAT_PREFERENCES, CAT_ROUTINES, CAT_RELATIONSHIPS, CAT_GOALS, CAT_STYLE)
            categories.forEach { catKey ->
                val cat = obj.optJSONObject(catKey) ?: return@forEach
                cat.keys().forEach { k ->
                    val v = cat.optJSONObject(k)?.optString("value", "") ?: ""
                    if (q.isBlank() || k.lowercase().contains(q) || v.lowercase().contains(q)) {
                        results.add(k to v)
                    }
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get all facts in a category (for debug / settings display).
     */
    fun getCategoryFacts(context: Context, category: String): Map<String, String> {
        return try {
            val cat = load(context).optJSONObject(category) ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            cat.keys().forEach { k ->
                val v = cat.optJSONObject(k)?.optString("value", "") ?: ""
                if (v.isNotBlank()) result[k] = v
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Clear entire profile (user requested reset).
     */
    fun clearProfile(context: Context) {
        try {
            getFile(context).delete()
            Log.d(TAG, "Profile cleared")
        } catch (e: Exception) {
            Log.e(TAG, "clearProfile error", e)
        }
    }

    /** Remove the oldest entry in a category JSON object */
    private fun pruneOldestInCategory(cat: JSONObject) {
        var oldestKey: String? = null
        var oldestTs = Long.MAX_VALUE
        cat.keys().forEach { k ->
            val ts = cat.optJSONObject(k)?.optLong("updatedAt", Long.MAX_VALUE) ?: Long.MAX_VALUE
            if (ts < oldestTs) { oldestTs = ts; oldestKey = k }
        }
        oldestKey?.let { cat.remove(it) }
    }
}
