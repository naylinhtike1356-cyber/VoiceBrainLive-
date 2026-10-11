package com.example.voicebrainlive.learning

/**
 * Holds the latest user phrase (voice transcript or typed text) so command
 * execution can link successful actions back to how the user actually spoke.
 */
object UserUtteranceContext {
    @Volatile
    private var lastPhrase: String? = null

    fun set(phrase: String?) {
        val trimmed = phrase?.trim().orEmpty()
        lastPhrase = trimmed.takeIf { it.isNotBlank() }
    }

    fun peek(): String? = lastPhrase

    fun consume(): String? {
        val current = lastPhrase
        lastPhrase = null
        return current
    }
}
