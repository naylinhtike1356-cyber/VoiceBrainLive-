package com.example.voicebrainlive

import android.content.Context
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Voice fingerprint verification. Use with VoiceProfileManager for speaker ID.
 * Wake word "ဟေး ကောင်လေး" is handled by WakeWordListener.
 */
class WakeWordHelper(private val context: Context) {
    // Noise Suppression Logic would be integrated with TFLite
    fun verifyVoice(currentAudio: FloatArray, savedVoiceModel: FloatArray): Boolean {
        // Simple cosine similarity for voice fingerprinting
        if (currentAudio.size != savedVoiceModel.size) return false
        
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        
        for (i in currentAudio.indices) {
            dotProduct += currentAudio[i].toDouble() * savedVoiceModel[i].toDouble()
            normA += Math.pow(currentAudio[i].toDouble(), 2.0)
            normB += Math.pow(savedVoiceModel[i].toDouble(), 2.0)
        }
        
        val similarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))
        return similarity > 0.90 // 90% match threshold
    }

    fun isCommandValid(command: String): Boolean {
        // Security check for command validity
        return command.isNotBlank()
    }
}