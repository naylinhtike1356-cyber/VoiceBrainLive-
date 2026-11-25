package com.example.voicebrainlive

/**
 * Abstraction for on-device / offline assistant engines (e.g., local rule engine,
 * Android AICore / Gemini Nano, or MediaPipe/LiteRT local LLMs).
 */
interface OfflineAssistantEngine {
    suspend fun initialize(): Boolean
    fun isReady(): Boolean
    suspend fun generateResponse(prompt: String): String
}
