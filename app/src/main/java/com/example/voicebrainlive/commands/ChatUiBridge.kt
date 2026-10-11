package com.example.voicebrainlive.commands

/**
 * Delivers live user/assistant text to an embedded [com.example.voicebrainlive.ui.ChatFragment].
 */
object ChatUiBridge {
    @Volatile
    var onUserMessage: ((String) -> Unit)? = null

    @Volatile
    var onAssistantMessage: ((String) -> Unit)? = null

    fun notifyUser(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        onUserMessage?.invoke(trimmed)
    }

    fun notifyAssistant(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        onAssistantMessage?.invoke(trimmed)
    }
}
