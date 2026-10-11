package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.commands.VisionCaptureBridge

/**
 * Phase 3.1: Vision Handler - handles camera-related tools.
 * Tools: take_photo, analyze_frame
 */
class VisionHandler(private val context: Context) : CommandHandler {

    override fun getSupportedCommands(): List<String> = listOf("take_photo", "analyze_frame")

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Log.d("VisionHandler", "Executing command: $commandType")

        when (commandType.lowercase()) {
            "take_photo", "analyze_frame" -> "BLOCKED: Open Camera, capture a frame, then explicitly approve sharing the reviewed image with AI."
            else -> null
        }
    }
}
