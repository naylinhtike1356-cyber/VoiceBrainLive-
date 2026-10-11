package com.example.voicebrainlive.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt
/**
 * Manages voice fingerprint for personal voice recognition (Bixby-style).
 * Trains on 5 samples, verifies incoming audio against saved profile.
 * Voice Profile 2.0: Improved noise robustness using Spectral Flatness and Band Energy.
 */
class VoiceProfileManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "voice_profile"
        private const val KEY_SAMPLE_COUNT = "sample_count"
        private const val KEY_FEATURES_PREFIX = "features_"
        private const val REQUIRED_SAMPLES = 5
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_MS = 100
        private const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000
        private const val MIN_VERIFY_SAMPLES = SAMPLE_RATE / 2  // 0.5 sec
        
        /** 
         * Stricter threshold for personal voice verification.
         * Cosine similarity 0.90+ typically means very close match.
         */
        const val MATCH_THRESHOLD = 0.92f
        
        /** Minimum RMS for speech - reject silence/too-quiet samples */
        private const val MIN_SPEECH_RMS = 0.01f
        private const val TAG = "VoiceProfile"
        private const val SAMPLE_DIR = "voice_samples"
    }

    private val samplesDir = context.getDir(SAMPLE_DIR, Context.MODE_PRIVATE)

    fun isTrained(): Boolean = prefs.getInt(KEY_SAMPLE_COUNT, 0) >= REQUIRED_SAMPLES

    fun getSampleCount(): Int = prefs.getInt(KEY_SAMPLE_COUNT, 0)

    /**
     * Add a training sample (base64 PCM 16-bit mono 16kHz).
     * Returns true when we have enough samples.
     */
    fun addTrainingSample(base64Pcm: String): Boolean {
        val bytes = try {
            Base64.decode(base64Pcm, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid base64: ${e.message}")
            return false
        }
        if (bytes.size < MIN_VERIFY_SAMPLES * 2) {
            Log.e(TAG, "Sample too short: ${bytes.size / 2} samples")
            return false
        }
        val rmsAvg = getAverageRms(bytes)
        if (rmsAvg < MIN_SPEECH_RMS) {
            Log.e(TAG, "Sample too quiet (silence?) rms=$rmsAvg - need actual speech")
            return false
        }
        val features = extractFeatures(bytes)
        val count = prefs.getInt(KEY_SAMPLE_COUNT, 0)
        
        val nextIndex = if (count >= REQUIRED_SAMPLES) 1 else count + 1
        
        // Save Features
        prefs.edit()
            .putString("${KEY_FEATURES_PREFIX}$nextIndex", features.joinToString(","))
            .putInt(KEY_SAMPLE_COUNT, nextIndex)
            .apply()
            
        // Save actual audio file (PCM)
        try {
            val file = java.io.File(samplesDir, "sample_$nextIndex.pcm")
            file.writeBytes(bytes)
            Log.d(TAG, "Saved sample file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sample file: ${e.message}")
        }

        Log.d(TAG, "Added sample $nextIndex/$REQUIRED_SAMPLES")
        return nextIndex >= REQUIRED_SAMPLES
    }

    /**
     * Verify incoming PCM bytes against saved voice profile.
     * Returns similarity 0.0..1.0. Use MATCH_THRESHOLD (0.75) for acceptance.
     */
    fun verifyVoice(audioBytes: ByteArray): Float {
        if (!isTrained()) return 0f
        if (audioBytes.size < MIN_VERIFY_SAMPLES * 2) return 0f
        if (getAverageRms(audioBytes) < MIN_SPEECH_RMS) return 0f  // silence = reject
        val features = extractFeatures(audioBytes)
        var maxSim = 0f
        for (i in 1..REQUIRED_SAMPLES) {
            val saved = prefs.getString("${KEY_FEATURES_PREFIX}$i", null) ?: continue
            val savedVec = saved.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            if (savedVec.size != features.size) continue
            val sim = cosineSimilarity(features, savedVec)
            if (sim > maxSim) maxSim = sim
        }
        return maxSim
    }

    fun clearProfile() {
        prefs.edit().clear().apply()
        samplesDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Voice profile cleared and sample files deleted")
    }

    /** Average RMS for speech detection - reject silence */
    private fun getAverageRms(bytes: ByteArray): Float {
        val samples = bytesToShorts(bytes)
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (i in samples.indices) {
            val s = samples[i] / 32768f
            sumSq += s * s
        }
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun extractFeatures(bytes: ByteArray): FloatArray {
        val samples = bytesToShorts(bytes)
        val numWindows = minOf(samples.size / WINDOW_SAMPLES, 20) // Limit to 2 sec of features for memory
        if (numWindows < 1) return FloatArray(2)
        
        val rmsValues = FloatArray(numWindows)
        val zcrValues = FloatArray(numWindows)
        val scValues = FloatArray(numWindows) // Spectral Centroid Approximation
        val sfValues = FloatArray(numWindows) // Spectral Flatness Approximation
        
        for (w in 0 until numWindows) {
            val start = w * WINDOW_SAMPLES
            val end = start + WINDOW_SAMPLES
            var sumSq = 0.0
            var zcr = 0
            var absSum = 0.0
            var freqWeightSum = 0.0
            
            // For Spectral Flatness (Geometric Mean vs Arithmetic Mean proxy)
            var logSum = 0.0
            
            for (i in start until end) {
                val s = kotlin.math.abs(samples[i] / 32768f)
                val sSq = s * s
                sumSq += sSq
                absSum += s
                if (i > start && (samples[i] >= 0) != (samples[i - 1] >= 0)) zcr++
                
                if (i > start) {
                    freqWeightSum += kotlin.math.abs(samples[i] - samples[i - 1])
                }
                
                // Add tiny constant to avoid log(0)
                logSum += kotlin.math.log10(s + 1e-6)
            }
            
            val rms = sqrt(sumSq / (end - start)).toFloat()
            rmsValues[w] = rms
            zcrValues[w] = zcr.toFloat() / (end - start)
            scValues[w] = if (absSum > 0) (freqWeightSum / absSum).toFloat() else 0f
            
            // SF proxy: pow(10, geometric_mean_of_log) / arithmetic_mean
            val geomMean = Math.pow(10.0, logSum / (end - start))
            val arithMean = absSum / (end - start)
            sfValues[w] = if (arithMean > 0) (geomMean / arithMean).toFloat() else 0f
        }
        
        val rmsAvg = rmsValues.average().toFloat()
        val rmsStd = sqrt(rmsValues.map { (it - rmsAvg).pow(2) }.average()).toFloat()
        
        val zcrAvg = zcrValues.average().toFloat()
        val scAvg = scValues.average().toFloat()
        val sfAvg = sfValues.average().toFloat() // Low SF means tonal (voice), High SF means noise

        // Distribution of RMS, SC, SF over time
        val rmsDist = interpolateDistribution(rmsValues, 8)
        val scDist = interpolateDistribution(scValues, 8)
        val sfDist = interpolateDistribution(sfValues, 8)
        
        return floatArrayOf(
            rmsAvg,
            rmsStd,
            zcrAvg,
            scAvg,
            sfAvg,
            rmsValues.maxOrNull() ?: 0f,
            scValues.maxOrNull() ?: 0f,
            sfValues.maxOrNull() ?: 0f
        ) + rmsDist + scDist + sfDist
    }

    private fun interpolateDistribution(data: FloatArray, points: Int): FloatArray {
        val result = FloatArray(points)
        if (data.isEmpty()) return result
        for (i in 0 until points) {
            val idx = (i * data.size) / points
            result[i] = data[idx]
        }
        return result
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            shorts[i] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            if (i >= b.size) break
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-9f) 0f else (dot / denom).coerceIn(-1f, 1f).let {
            (it + 1f) / 2f
        }
    }
}
