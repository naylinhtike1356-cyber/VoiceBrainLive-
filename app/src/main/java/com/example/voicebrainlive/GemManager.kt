package com.example.voicebrainlive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Gems/Custom Assistant - Create different personalities for Voice Brain
 * v2.0 Feature
 */
class GemManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("gems", Context.MODE_PRIVATE)
    
    companion object {
        const val TAG = "GemManager"
        
        // Default Gems
        val DEFAULT_GEMS = listOf(
            Gem(
                id = "assistant",
                name = "General Assistant",
                description = "Helpful assistant for everyday tasks",
                systemPrompt = "You are a helpful AI assistant. Answer questions clearly and concisely.",
                avatarEmoji = "🤖",
                color = "#4CAF50"
            ),
            Gem(
                id = "teacher",
                name = "English Teacher",
                description = "Helps you learn English",
                systemPrompt = "You are an English teacher. Help the user learn English vocabulary, grammar, and pronunciation. Provide examples and corrections.",
                avatarEmoji = "📚",
                color = "#2196F3"
            ),
            Gem(
                id = "translator",
                name = "Translator",
                description = "Translates between languages",
                systemPrompt = "You are a translator. Translate accurately between languages. Keep translations natural and culturally appropriate.",
                avatarEmoji = "🌍",
                color = "#9C27B0"
            ),
            Gem(
                id = "coach",
                name = "Life Coach",
                description = "Motivates and guides you",
                systemPrompt = "You are a supportive life coach. Provide motivation, guidance, and positive encouragement. Help with productivity and mindset.",
                avatarEmoji = "💪",
                color = "#FF9800"
            ),
            Gem(
                id = "friend",
                name = "Fun Friend",
                description = "Casual chat and entertainment",
                systemPrompt = "You are a fun, friendly companion. Be conversational, use humor, and keep things light. Be supportive but casual.",
                avatarEmoji = "😄",
                color = "#E91E63"
            ),
            Gem(
                id = "expert",
                name = "Tech Expert",
                description = "Helps with technology questions",
                systemPrompt = "You are a technology expert. Help with programming, software, hardware, and tech questions. Provide clear, accurate technical guidance.",
                avatarEmoji = "💻",
                color = "#00BCD4"
            ),
            Gem(
                id = "korean",
                name = "Korean Tutor",
                description = "Helps learn Korean language",
                systemPrompt = "You are a Korean language tutor. Help with Korean vocabulary, grammar, and pronunciation. Provide Romanized readings when helpful.",
                avatarEmoji = "🇰🇷",
                color = "#F44336"
            ),
            Gem(
                id = "myanmar",
                name = "Burmese Helper",
                description = "Helps with Myanmar language and culture",
                systemPrompt = "You are a Burmese language helper. Assist with Myanmar vocabulary, phrases, and cultural context. Be patient and encouraging.",
                avatarEmoji = "🇲🇲",
                color = "#795548"
            )
        )
    }
    
    fun getAllGems(): List<Gem> {
        val gems = mutableListOf<Gem>()
        
        // Add default gems
        gems.addAll(DEFAULT_GEMS)
        
        // Add custom gems from prefs
        val customGemsJson = prefs.getString("custom_gems", "[]") ?: "[]"
        try {
            val customGems = parseCustomGems(customGemsJson)
            gems.addAll(customGems)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing custom gems: ${e.message}")
        }
        
        return gems
    }
    
    fun getGem(gemId: String): Gem? {
        return getAllGems().find { it.id == gemId }
    }
    
    fun getActiveGem(): Gem {
        val activeGemId = prefs.getString("active_gem", "assistant") ?: "assistant"
        return getGem(activeGemId) ?: DEFAULT_GEMS.first()
    }
    
    fun setActiveGem(gemId: String): Boolean {
        val gem = getGem(gemId)
        if (gem != null) {
            prefs.edit().putString("active_gem", gemId).apply()
            return true
        }
        return false
    }
    
    fun addCustomGem(gem: Gem): Boolean {
        try {
            val customGems = getAllGems().filter { it.id !in DEFAULT_GEMS.map { g -> g.id } }.toMutableList()
            customGems.add(gem)
            
            val json = customGemsToJson(customGems)
            prefs.edit().putString("custom_gems", json).apply()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding custom gem: ${e.message}")
            return false
        }
    }
    
    fun deleteCustomGem(gemId: String): Boolean {
        try {
            val customGems = getAllGems().filter { 
                it.id !in DEFAULT_GEMS.map { g -> g.id } && it.id == gemId 
            }.toMutableList()
            
            if (customGems.isEmpty()) return false
            
            customGems.removeAll { it.id == gemId }
            
            val json = customGemsToJson(customGems)
            prefs.edit().putString("custom_gems", json).apply()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting custom gem: ${e.message}")
            return false
        }
    }
    
    fun getSystemPromptForActiveGem(): String {
        return getActiveGem().systemPrompt
    }
    
    private fun parseCustomGems(json: String): List<Gem> {
        // Simple JSON parsing
        val gems = mutableListOf<Gem>()
        // This is a simplified parser - in production use proper JSON library
        return gems
    }
    
    private fun customGemsToJson(gems: List<Gem>): String {
        // Simple JSON conversion
        return "[]"
    }
}

data class Gem(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val avatarEmoji: String,
    val color: String
)
