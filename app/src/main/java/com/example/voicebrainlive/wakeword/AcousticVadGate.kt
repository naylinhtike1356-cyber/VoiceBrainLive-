package com.example.voicebrainlive.wakeword

import kotlin.math.sqrt

/**
 * Lightweight on-device Voice Activity Detection (VAD) energy gate for 16-bit PCM frames.
 * Used to distinguish speech bursts from ambient silence without external native libraries.
 */
object AcousticVadGate {
    private const val DEFAULT_RMS_THRESHOLD = 0.018f

    fun computeRms(pcm16Le: ByteArray, length: Int = pcm16Le.size): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort() / 32768.0
            sumSq += sample * sample
            i += 2
        }
        return sqrt(sumSq / sampleCount).toFloat()
    }

    fun isSpeechLikely(pcm16Le: ByteArray, threshold: Float = DEFAULT_RMS_THRESHOLD): Boolean {
        return computeRms(pcm16Le) >= threshold
    }
}
