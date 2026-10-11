package com.example.voicebrainlive

import android.content.Context
import android.util.Log

/**
 * Handles multiple user profiles (Task 3.3).
 * Supports up to 3 active profiles, switchable via voice.
 * Keeps profile name, preferred language, and preferred music app.
 */
class ProfileManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("user_profiles", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val DEFAULT_PROFILE = "Default User"
    }

    init {
        // Initialize default profiles if empty
        if (prefs.all.isEmpty()) {
            createProfile(DEFAULT_PROFILE, "spotify")
            prefs.edit().putString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE).apply()
        }
    }

    fun getActiveProfile(): String {
        return prefs.getString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE) ?: DEFAULT_PROFILE
    }

    fun getProfilePreferredMusicApp(profileName: String): String {
        return prefs.getString("${profileName}_music_app", "spotify") ?: "spotify"
    }

    fun setProfilePreferredMusicApp(profileName: String, musicApp: String) {
        prefs.edit().putString("${profileName}_music_app", musicApp).apply()
        if (profileName == getActiveProfile()) {
            UserPreferences.set(context, "default_music_app", musicApp)
        }
    }

    fun createProfile(name: String, preferredMusicApp: String = "spotify"): Boolean {
        val allProfiles = getAllProfiles()
        if (allProfiles.size >= 3 && !allProfiles.contains(name)) {
            return false // Limit to 3 profiles
        }
        prefs.edit().putString("${name}_music_app", preferredMusicApp).apply()
        
        // Add to profiles list
        val currentList = getProfilesList()
        if (!currentList.contains(name)) {
            val newList = currentList + name
            prefs.edit().putString("profiles_list", newList.joinToString(",")).apply()
        }
        return true
    }

    fun switchProfile(name: String): String {
        val profiles = getProfilesList()
        val targetProfile = profiles.firstOrNull { it.equals(name, ignoreCase = true) }
        
        if (targetProfile != null) {
            prefs.edit().putString(KEY_ACTIVE_PROFILE, targetProfile).apply()
            
            // Sync default music app preference
            val app = getProfilePreferredMusicApp(targetProfile)
            UserPreferences.set(context, "default_music_app", app)
            
            return "$targetProfile profile သို့ ပြောင်းလိုက်ပါပြီ။"
        }
        
        // Create new if there's room
        if (profiles.size < 3) {
            createProfile(name)
            prefs.edit().putString(KEY_ACTIVE_PROFILE, name).apply()
            UserPreferences.set(context, "default_music_app", "spotify")
            return "profile အသစ် '$name' ကို ဖန်တီးပြီး ပြောင်းလိုက်ပါပြီ။"
        }
        
        return "profile ရှာမတွေ့ပါ သို့မဟုတ် profile အသစ်ပြုလုပ်ရန် နေရာမလုံလောက်ပါ။"
    }

    fun getAllProfiles(): List<String> {
        return getProfilesList()
    }

    private fun getProfilesList(): List<String> {
        val listStr = prefs.getString("profiles_list", DEFAULT_PROFILE) ?: DEFAULT_PROFILE
        return listStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
