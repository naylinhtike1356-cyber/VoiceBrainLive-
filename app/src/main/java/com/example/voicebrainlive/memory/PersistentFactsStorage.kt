package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase I.3: Persistent facts - names, birth years, preferences.
 * Facts are merged (not replaced) so we never "trade" name for birth year.
 * Key = part before ":", value = part after. Same key = update; new key = add.
 */
object PersistentFactsStorage {

    private const val FILE_NAME = "persistent_facts.json"
    private const val MAX_FACTS = 100

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): MutableMap<String, String> {
        return try {
            val file = getFile(context)
            if (!file.exists()) return mutableMapOf()
            val json = file.readText()
            if (json.isBlank()) return mutableMapOf()
            val obj = JSONObject(json)
            val factsObj = obj.optJSONObject("facts") ?: return mutableMapOf()
            val map = mutableMapOf<String, String>()
            factsObj.keys().forEach { key ->
                map[key] = factsObj.optString(key, "")
            }
            map
        } catch (e: Exception) {
            Log.e("PersistentFacts", "load", e)
            mutableMapOf()
        }
    }

    private fun save(context: Context, facts: Map<String, String>) {
        try {
            val obj = JSONObject()
            val factsObj = JSONObject()
            facts.entries.take(MAX_FACTS).forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) factsObj.put(k, v)
            }
            obj.put("facts", factsObj)
            getFile(context).writeText(obj.toString())
        } catch (e: Exception) {
            Log.e("PersistentFacts", "save", e)
        }
    }

    /**
     * Parse fact lines like "- User's name: မိုးမြတ်" or "User birth year: 1990".
     * Key = trimmed part before ":", value = trimmed part after.
     */
    private fun parseFactLine(line: String): Pair<String, String>? {
        val t = line.trim().removePrefix("-").trim()
        val idx = t.indexOf(':')
        if (idx <= 0 || idx >= t.length - 1) return null
        val key = t.substring(0, idx).trim()
        val value = t.substring(idx + 1).trim()
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }

    /**
     * Merge new fact lines into storage. Same key = update; new key = add.
     * Also synchronizes to PersonalProfileEngine.
     */
    fun mergeFacts(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val current = load(context).toMutableMap()
        var added = 0
        lines.forEach { line ->
            val parsed = parseFactLine(line)
            if (parsed != null) {
                val (key, value) = parsed
                if (key.length in 2..50 && value.length in 1..200) {
                    current[key] = value
                    added++
                }
            }
        }
        if (added > 0) {
            save(context, current)
            Log.d("PersistentFacts", "mergeFacts: +$added (total ${current.size})")
        }
        try {
            com.example.voicebrainlive.learning.PersonalProfileEngine.updateFromSummary(context, lines)
        } catch (e: Exception) {
            Log.e("PersistentFacts", "mergeFacts sync to profile error", e)
        }
    }

    /** Format for system instruction injection */
    fun getContextString(context: Context): String {
        val facts = load(context)
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .toList()
            .sortedWith(compareByDescending<Pair<String, String>> { factPriority(it.first) }.thenBy { it.first.lowercase() })
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("\n[Remembered facts - use these as context only]\n")
        facts.take(MAX_FACTS).forEach { (key, value) ->
            sb.append("- ${key.take(50)}: ${value.take(200)}\n")
        }
        sb.append("[End remembered facts]\n")
        return sb.toString()
    }

    private fun factPriority(key: String): Int {
        val normalized = key.lowercase()
        return when {
            normalized.contains("name") || normalized.contains("အမည်") -> 4
            normalized.contains("language") || normalized.contains("ဘာသာ") -> 3
            normalized.contains("preference") || normalized.contains("ကြိုက်") -> 2
            else -> 1
        }
    }

    /**
     * Get all facts as a list of Pair(Key, Value)
     */
    fun getAllFacts(context: Context): List<Pair<String, String>> {
        return load(context).toList()
    }

    /**
     * Add or update a single fact. Synchronizes with PersonalProfileEngine.
     */
    fun addOrUpdateFact(context: Context, key: String, value: String): Boolean {
                val cleanKey = key.trim().take(50)
        val cleanValue = value.trim().take(200)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return false
        val facts = load(context).toMutableMap()
        facts[cleanKey] = cleanValue

        save(context, facts)

        try {
            com.example.voicebrainlive.learning.PersonalProfileEngine.autoClassifyAndSet(context, cleanKey, cleanValue)
        } catch (e: Exception) {
            Log.e("PersistentFacts", "sync to PersonalProfileEngine error", e)
        }
        return true
    }

    /**
     * Delete a fact by key or fuzzy key match across persistent storage and profile.
     */
    fun deleteFact(context: Context, key: String): Boolean {
        val cleanKey = key.trim().lowercase()
        val facts = load(context).toMutableMap()
        val matchedKey = facts.keys.firstOrNull { it.lowercase() == cleanKey || it.lowercase().contains(cleanKey) || cleanKey.contains(it.lowercase()) }
        var deleted = false
        if (matchedKey != null) {
            facts.remove(matchedKey)
            save(context, facts)
            deleted = true
        }

        try {
            val profileDeleted = com.example.voicebrainlive.learning.PersonalProfileEngine.deleteFact(context, cleanKey)
            if (profileDeleted) deleted = true
        } catch (e: Exception) {
            Log.e("PersistentFacts", "deleteFact profile sync error", e)
        }

        return deleted
    }

    /**
     * Search facts by keyword in key or value across both facts storage and profile engine.
     */
    fun searchFacts(context: Context, query: String): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        val factList = if (q.isBlank()) getAllFacts(context) else {
            load(context).filter { (k, v) ->
                k.lowercase().contains(q) || v.lowercase().contains(q)
            }.toList()
        }

        val profileList = try {
            com.example.voicebrainlive.learning.PersonalProfileEngine.searchProfile(context, query)
        } catch (e: Exception) {
            emptyList()
        }

        // Merge and deduplicate by key
        val combined = mutableMapOf<String, String>()
        factList.forEach { (k, v) -> combined[k] = v }
        profileList.forEach { (k, v) -> if (!combined.containsKey(k)) combined[k] = v }

        return combined.toList()
    }

    /** Clear all persistent facts */
    fun clearFacts(context: Context) {
        try {
            val file = getFile(context)
            if (file.exists()) {
                file.delete()
            }
            com.example.voicebrainlive.learning.PersonalProfileEngine.clearProfile(context)
            Log.d("PersistentFacts", "Cleared all persistent facts and profile")
        } catch (e: Exception) {
            Log.e("PersistentFacts", "clearFacts error", e)
        }
    }
}
