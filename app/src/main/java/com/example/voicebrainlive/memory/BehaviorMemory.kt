package com.example.voicebrainlive.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.voicebrainlive.security.VoiceSecurityHelper
import org.json.JSONObject
import java.io.File

/**
 * Behavior Memory (Enhanced with Encryption)
 * 
 * Phase II: Behavior memory - frequent contacts, last played music.
 * Phase 6: Session count + familiarity - ရင်းနှီးမှု တိုးမြှင့်, ကိုယ်ပိုင်သင်ယူမှု
 * Phase 9: Encrypted storage using VoiceSecurityHelper for sensitive data.
 * 
 * Helps AI: "ခေါ်ပေး" without name -> call most frequent; "ပြန်ဖွင့်ပေး" -> last song.
 * 
 * Security:
 * - Call history and corrections are encrypted
 * - Music preferences are encrypted
 * - Session data remains unencrypted (non-sensitive)
 */
object BehaviorMemory {

    private const val FILE_NAME = "behavior_memory.json"
    private const val ENCRYPTED_PREFS_NAME = "behavior_memory_encrypted"
    private const val MAX_CONTACTS = 20
    private const val MAX_CALLS_PER_CONTACT = 999
    private const val KEY_CALL_CORRECTIONS = "callCorrections"
    private const val KEY_LAST_CALL_ATTEMPT = "lastCallAttempt"
    private const val KEY_CALL_ALIASES = "callAliases"
    
    // Encrypted keys
    private const val ENC_CALL_COUNTS = "enc_callCounts"
    private const val ENC_LAST_CALL = "enc_lastCall"
    private const val ENC_LAST_MUSIC = "enc_lastMusic"
    private const val ENC_CALL_CORRECTIONS = "enc_callCorrections"
    private const val ENC_CALL_ALIASES = "enc_callAliases"
    private const val ENC_MUSIC_CORRECTION = "enc_musicCorrection"
    private const val ENC_MUSIC_VOLUME = "enc_musicVolume"
    private const val ENC_MUSIC_GENRE = "enc_musicGenre"

    private fun getFile(context: Context): File {
        val activeProfile = try {
            val pm = com.example.voicebrainlive.ProfileManager(context)
            pm.getActiveProfile()
        } catch (e: Exception) {
            "Default User"
        }
        val safeProfileName = activeProfile.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        return File(context.filesDir, "behavior_memory_${safeProfileName}.json")
    }
    
    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val activeProfile = try {
            val pm = com.example.voicebrainlive.ProfileManager(context)
            pm.getActiveProfile()
        } catch (e: Exception) {
            "Default User"
        }
        val safeProfileName = activeProfile.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        return context.getSharedPreferences("behavior_memory_encrypted_${safeProfileName}", Context.MODE_PRIVATE)
    }
    
    private fun getSecurityHelper(context: Context): VoiceSecurityHelper {
        return VoiceSecurityHelper(context)
    }

    /**
     * Load behavior memory from file (non-sensitive data)
     */
    private fun load(context: Context): JSONObject {
        return try {
            val file = getFile(context)
            if (!file.exists()) return JSONObject()
            val json = file.readText()
            if (json.isBlank()) return JSONObject()
            JSONObject(json)
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "load", e)
            JSONObject()
        }
    }

    /**
     * Save behavior memory to file (non-sensitive data)
     */
    private fun save(context: Context, obj: JSONObject) {
        try {
            getFile(context).writeText(obj.toString())
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "save", e)
        }
    }
    
    /**
     * Load encrypted data from SharedPreferences
     */
    private fun loadEncrypted(context: Context, key: String): String? {
        return try {
            val security = getSecurityHelper(context)
            val prefs = getEncryptedPrefs(context)
            val encrypted = prefs.getString(key, null)
            if (encrypted != null && encrypted.isNotEmpty()) {
                security.decryptVoiceData(encrypted)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "loadEncrypted: $key", e)
            null
        }
    }
    
    /**
     * Save encrypted data to SharedPreferences
     */
    private fun saveEncrypted(context: Context, key: String, value: String) {
        try {
            val security = getSecurityHelper(context)
            val encrypted = security.encryptVoiceData(value)
            if (encrypted.isNotEmpty()) {
                getEncryptedPrefs(context).edit().putString(key, encrypted).apply()
            }
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "saveEncrypted: $key", e)
        }
    }
    
    /**
     * Load encrypted JSON object
     */
    private fun loadEncryptedJson(context: Context, key: String): JSONObject? {
        val data = loadEncrypted(context, key)
        return if (data != null) {
            try { JSONObject(data) } catch (e: Exception) { null }
        } else {
            null
        }
    }
    
    /**
     * Save JSON object as encrypted data
     */
    private fun saveEncryptedJson(context: Context, key: String, obj: JSONObject) {
        saveEncrypted(context, key, obj.toString())
    }

    /** Call when user successfully initiates a call. nameOrPhone = what user said (e.g. "John" or "09...") */
    fun recordCall(context: Context, nameOrPhone: String) {
        if (nameOrPhone.isBlank()) return
        val key = nameOrPhone.trim().lowercase()
        if (key.length < 2) return
        try {
            val obj = load(context)
            val contacts = obj.optJSONObject("callCounts") ?: JSONObject()
            val count = contacts.optInt(key, 0) + 1
            contacts.put(key, minOf(count, MAX_CALLS_PER_CONTACT))
                        obj.put("callCounts", contacts)
            obj.put("lastCall", nameOrPhone.trim())
            obj.put("lastCallAt", System.currentTimeMillis())
            save(context, obj)

            Log.d("BehaviorMemory", "recordCall: $key (count=$count)")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordCall", e)
        }
    }

    /** Call when user plays music on Spotify. query = song/playlist/artist name */
    fun recordMusicPlayed(context: Context, query: String) {
        if (query.isBlank()) return
        try {
            val obj = load(context)
            obj.put("lastMusic", query.take(100).trim())
            obj.put("lastMusicAt", System.currentTimeMillis())
            save(context, obj)

            Log.d("BehaviorMemory", "recordMusicPlayed: ${query.take(50)}")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordMusicPlayed", e)
        }
    }

    /** Get most frequently called contact name (for "ခေါ်ပေး" without specifying who) */
    fun getMostFrequentContact(context: Context): String? {
        return try {
            val obj = load(context)
            val contacts = obj.optJSONObject("callCounts") ?: return null
            var maxCount = 0
            var bestKey: String? = null
            contacts.keys().forEach { key ->
                val c = contacts.optInt(key, 0)
                if (c > maxCount) {
                    maxCount = c
                    bestKey = key
                }
            }
            bestKey?.takeIf { maxCount >= 2 } // Need at least 2 calls to be "frequent"
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "getMostFrequentContact", e)
            null
        }
    }

    private fun normalizeContactKey(value: String): String = value.trim().lowercase()

    fun aliasKey(value: String): String = normalizeContactKey(value).replace(Regex("\\s+"), "")

    /** Clear all call aliases and corrections - use when data becomes corrupted */
    fun clearCallAliasesAndCorrections(context: Context) {
        try {
            val obj = load(context)
            obj.remove(KEY_CALL_ALIASES)
            obj.remove(KEY_CALL_CORRECTIONS)
            obj.remove(KEY_LAST_CALL_ATTEMPT)
            obj.remove("lastCallCorrection")
            save(context, obj)
            Log.d("BehaviorMemory", "Cleared call aliases and corrections")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "clearCallAliases", e)
        }
    }

    /** Clear all stored behavior memory data (both json and encrypted prefs) */
    fun clearAll(context: Context) {
        try {
            val file = getFile(context)
            if (file.exists()) {
                file.delete()
            }
            getEncryptedPrefs(context).edit().clear().apply()
            Log.d("BehaviorMemory", "Cleared all behavior memory data")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "clearAll error", e)
        }
    }

    fun recordCallAttempt(context: Context, nameOrPhone: String) {
        if (nameOrPhone.isBlank()) return
        try {
            val obj = load(context)
            obj.put(KEY_LAST_CALL_ATTEMPT, nameOrPhone.trim())
            save(context, obj)
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordCallAttempt", e)
        }
    }

    fun getCorrectedCallTarget(context: Context, heardInput: String): String? {
        if (heardInput.isBlank()) return null
        return try {
            val obj = load(context)
                        val alias = obj.optJSONObject(KEY_CALL_ALIASES)
                ?.optJSONObject(aliasKey(heardInput))
            val remembered = alias
                ?.takeIf {
                    it.optInt("confidence", 0) >= MemoryFreshnessPolicy.MIN_ALIAS_CONFIDENCE &&
                        MemoryFreshnessPolicy.isFresh(it.optLong("updatedAt", 0L))
                }
                ?.optString("target", "")
                ?.takeIf { it.isNotBlank() }

            if (remembered != null) return remembered
            val corrections = obj.optJSONObject(KEY_CALL_CORRECTIONS) ?: return null
            corrections.optString(normalizeContactKey(heardInput), "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "getCorrectedCallTarget", e)
            null
        }
    }

    /** Get last played music (for "ပြန်ဖွင့်ပေး") */
    fun getLastMusic(context: Context): String? {
        return try {
            val obj = load(context)
            obj.optString("lastMusic", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Phase 3: Get last called contact (for "ဒီလူ ခေါ်ပေး" / "that person") */
    fun getLastCalledContact(context: Context): String? {
        return try {
            val obj = load(context)
            obj.optString("lastCall", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Learning from Corrections: User said "မဟုတ်ဘူး၊ အောင်ကို ခေါ်ခဲ့" -> store correction */
    fun recordCallCorrection(context: Context, nameOrPhone: String) {
        if (nameOrPhone.isBlank()) return
        try {
            val obj = load(context)
            val corrected = nameOrPhone.trim()
            obj.put("lastCallCorrection", corrected)

            val lastAttempt = obj.optString(KEY_LAST_CALL_ATTEMPT, "").takeIf { it.isNotBlank() }
            if (lastAttempt != null && normalizeContactKey(lastAttempt) != normalizeContactKey(corrected)) {
                val corrections = obj.optJSONObject(KEY_CALL_CORRECTIONS) ?: JSONObject()
                corrections.put(normalizeContactKey(lastAttempt), corrected)
                obj.put(KEY_CALL_CORRECTIONS, corrections)
                rememberCallAliasInternal(obj, lastAttempt, corrected, 100, true)
            }

            obj.put(KEY_LAST_CALL_ATTEMPT, "")
            save(context, obj)
            Log.d("BehaviorMemory", "recordCallCorrection: $nameOrPhone (from=$lastAttempt)")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordCallCorrection", e)
        }
    }

    fun rememberCallAlias(
        context: Context,
        spokenInput: String,
        resolvedTarget: String,
        confidencePercent: Int,
        confirmedByUser: Boolean = false
    ) {
        if (spokenInput.isBlank() || resolvedTarget.isBlank()) return
        try {
            val obj = load(context)
            rememberCallAliasInternal(obj, spokenInput, resolvedTarget, confidencePercent, confirmedByUser)
            save(context, obj)
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "rememberCallAlias", e)
        }
    }

    private fun rememberCallAliasInternal(
        obj: JSONObject,
        spokenInput: String,
        resolvedTarget: String,
        confidencePercent: Int,
        confirmedByUser: Boolean
    ) {
        val normalizedSpoken = aliasKey(spokenInput)
        if (normalizedSpoken.length < 2) return
        val aliases = obj.optJSONObject(KEY_CALL_ALIASES) ?: JSONObject()
        val previous = aliases.optJSONObject(normalizedSpoken) ?: JSONObject()
        val hits = previous.optInt("hits", 0) + 1
        aliases.put(normalizedSpoken, JSONObject().apply {
            put("target", resolvedTarget.trim())
            put("confidence", confidencePercent.coerceIn(0, 100))
            put("confirmed", confirmedByUser || previous.optBoolean("confirmed", false))
            put("hits", hits)
            put("updatedAt", System.currentTimeMillis())
        })
        obj.put(KEY_CALL_ALIASES, aliases)
    }

    fun getRememberedCallAlias(context: Context, spokenInput: String): String? {
        if (spokenInput.isBlank()) return null
        return try {
                            load(context)
                .optJSONObject(KEY_CALL_ALIASES)
                ?.optJSONObject(aliasKey(spokenInput))
                ?.takeIf {
                    it.optInt("confidence", 0) >= MemoryFreshnessPolicy.MIN_ALIAS_CONFIDENCE &&
                        MemoryFreshnessPolicy.isFresh(it.optLong("updatedAt", 0L))
                }
                ?.optString("target", "")
                ?.takeIf { it.isNotBlank() }

        } catch (e: Exception) {
            Log.e("BehaviorMemory", "getRememberedCallAlias", e)
            null
        }
    }

    /** Get last corrected contact - when user said "မဟုတ်ဘူး X ခေါ်ခဲ့", use X next time */
    fun getLastCorrectedContact(context: Context): String? {
        return try {
            val obj = load(context)
            obj.optString("lastCallCorrection", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Learning from Corrections: User corrected music - "မဟုတ်ဘူး၊ ဒီသီးချင်း ဖွင့်ခဲ့" */
    fun recordMusicCorrection(context: Context, query: String) {
        if (query.isBlank()) return
        try {
            val obj = load(context)
            obj.put("lastMusicCorrection", query.take(100).trim())
            save(context, obj)
            Log.d("BehaviorMemory", "recordMusicCorrection: ${query.take(50)}")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordMusicCorrection", e)
        }
    }

    fun getLastCorrectedMusic(context: Context): String? {
        return try {
            load(context).optString("lastMusicCorrection", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    /** Phase 2: Save metadata about notes saved to memory - user preference/frequency tracker. */
    fun recordNoteSaved(context: Context, content: String) {
        if (content.isBlank()) return
        try {
            val obj = load(context)
            val count = obj.optInt("notesSavedCount", 0) + 1
            obj.put("notesSavedCount", count)
            obj.put("lastNoteSaved", content.take(100).trim())
            save(context, obj)
            Log.d("BehaviorMemory", "recordNoteSaved: count=$count")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordNoteSaved", e)
        }
    }

    /** ရင်းနှီးမှု: Increment when user connects. Used for familiarity + proactive. */
    fun recordSessionStarted(context: Context) {
        try {
            val obj = load(context)
            val count = obj.optInt("sessionCount", 0) + 1
            obj.put("sessionCount", minOf(count, 9999))
            obj.put("lastSessionTs", System.currentTimeMillis())
            save(context, obj)
            Log.d("BehaviorMemory", "recordSessionStarted: sessionCount=$count")
        } catch (e: Exception) {
            Log.e("BehaviorMemory", "recordSessionStarted", e)
        }
    }

    fun getSessionCount(context: Context): Int {
        return try {
            load(context).optInt("sessionCount", 0)
        } catch (e: Exception) { 0 }
    }



    /** User Profile: music volume preference - "low" / "normal". Proactive: သီးချင်းတိုးတိုးနားထောင်တာ ကြိုက်တယ် */
    fun getMusicVolumePreference(context: Context): String? {
        return try {
            load(context).optString("musicVolumePreference", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    fun setMusicVolumePreference(context: Context, pref: String) {
        try {
            val obj = load(context)
            obj.put("musicVolumePreference", pref.lowercase().trim())
            save(context, obj)
        } catch (e: Exception) { Log.e("BehaviorMemory", "setMusicVolumePreference", e) }
    }

    /** User Profile: preferred music genre/category for "သီးချင်းဖွင့်ပေး" without spec */
    fun getPreferredMusicGenre(context: Context): String? {
        return try {
            load(context).optString("preferredMusicGenre", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    fun setPreferredMusicGenre(context: Context, genre: String) {
        try {
            val obj = load(context)
            obj.put("preferredMusicGenre", genre.trim())
            save(context, obj)
        } catch (e: Exception) { Log.e("BehaviorMemory", "setPreferredMusicGenre", e) }
    }

    /** String for system instruction - AI uses this context. Includes User Profile, call aliases, and call frequency. */
    fun getContextString(context: Context): String {
        val sb = StringBuilder()
        val sessionCount = getSessionCount(context)
        if (sessionCount >= 5) {
            sb.append("\n[Familiarity - User has used this assistant $sessionCount+ times. Be warmer, more casual, and proactively suggest based on their habits when relevant.]\n")
        }
        // User Profile: preferences for proactive behavior
        val volumePref = getMusicVolumePreference(context)
        val preferredGenre = getPreferredMusicGenre(context)
        if (volumePref == "low") {
            sb.append("\n[User Preference] User prefers low volume when playing music. When play_spotify: suggest or apply lower volume proactively.\n")
        }
        if (preferredGenre != null) {
            sb.append("\n[User Preference] User often likes: $preferredGenre. When they say 'သီးချင်းဖွင့်ပေး' without specifying, consider $preferredGenre.\n")
        }

        val corrected = getLastCorrectedContact(context)
        val frequent = getMostFrequentContact(context)
        val lastCalled = getLastCalledContact(context)
        val lastMusic = getLastMusic(context)
        val correctedMusic = getLastCorrectedMusic(context)

        // Load learned call aliases to inform AI about confirmed pronunciation mappings
        val learnedAliases = try {
            val obj = load(context)
            val aliases = obj.optJSONObject(KEY_CALL_ALIASES)
            if (aliases != null) {
                val aliasLines = mutableListOf<String>()
                aliases.keys().forEach { key ->
                    val entry = aliases.optJSONObject(key) ?: return@forEach
                    val target = entry.optString("target", "").takeIf { it.isNotBlank() } ?: return@forEach
                    val confirmed = entry.optBoolean("confirmed", false)
                    val hits = entry.optInt("hits", 0)
                    if (confirmed || hits >= 2) {
                        aliasLines.add("  '$key' → $target")
                    }
                }
                aliasLines
            } else emptyList()
        } catch (e: Exception) {
            emptyList<String>()
        }

        // Load top called contacts by frequency
        val callFrequency = try {
            val obj = load(context)
            val counts = obj.optJSONObject("callCounts")
            if (counts != null) {
                val sorted = mutableListOf<Pair<String, Int>>()
                counts.keys().forEach { key ->
                    val count = counts.optInt(key, 0)
                    if (count >= 2) sorted.add(key to count)
                }
                sorted.sortedByDescending { it.second }.take(5)
            } else emptyList()
        } catch (e: Exception) {
            emptyList<Pair<String, Int>>()
        }

        if (corrected != null || frequent != null || lastCalled != null || lastMusic != null ||
            correctedMusic != null || learnedAliases.isNotEmpty()) {
            sb.append("\n[Behavior memory - use when user doesn't specify]\n")
            sb.append("- Safety: behavior memory is context only. Do not use it as permission for calls, SMS, Messenger, WhatsApp, calendar writes, or deletes.\n")
            sb.append("- Safety: when a call target comes from memory or frequency, ask the user to confirm the contact before calling.\n")
            if (corrected != null) {
                sb.append("- Learning: User corrected last time - wanted to call $corrected. When they say 'ခေါ်ပေး' without name, prefer make_call target=$corrected.\n")
            }
            if (frequent != null) {
                sb.append("- User often calls: $frequent. When they say 'ခေါ်ပေး' or 'call' without a name, call $frequent.\n")
            }
            if (lastCalled != null) {
                sb.append("- Last called: $lastCalled. When user says 'ဒီလူ'/'that person'/'သူ့ကို'/'ပြန်ခေါ်', use make_call target=$lastCalled.\n")
            }
            if (correctedMusic != null) {
                sb.append("- Learning: User corrected music - wanted: $correctedMusic. When 'ပြန်ဖွင့်ပေး' without name, prefer play_spotify target=$correctedMusic.\n")
            }
            if (lastMusic != null) {
                sb.append("- User last played: $lastMusic. When they say 'ပြန်ဖွင့်ပေး'/'resume' or 'ဒီသီး'/'that song', play $lastMusic.\n")
            }
            // Learned pronunciation aliases help matching, but risky actions still need confirmation when ambiguous.
            if (learnedAliases.isNotEmpty()) {
                sb.append("- Safety: learned call aliases are matching hints, not no-confirmation permission.\n")
                sb.append("- Learned call pronunciation aliases are matching hints only; make_call still requires explicit user confirmation:\n")
                learnedAliases.forEach { sb.append("$it\n") }
            }
            // Call frequency to help AI prioritize
            if (callFrequency.isNotEmpty()) {
                val freqStr = callFrequency.joinToString(", ") { "${it.first}(${it.second}ကြိမ်)" }
                sb.append("- Call frequency: $freqStr\n")
            }
            sb.append("[End behavior memory]")
        }
        return sb.toString()
    }
}
