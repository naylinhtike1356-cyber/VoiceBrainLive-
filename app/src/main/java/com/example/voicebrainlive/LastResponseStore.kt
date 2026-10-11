package com.example.voicebrainlive

/**
 * Stores last assistant response for "ပြန်ပြောပါ" (repeat) feature.
 */
object LastResponseStore {
    @Volatile
    var lastAssistantText: String = ""
        private set

    fun set(text: String) {
        if (text.isNotBlank()) lastAssistantText = text
    }

    fun get(): String = lastAssistantText
}
