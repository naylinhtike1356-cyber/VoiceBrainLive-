package com.example.voicebrainlive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.PresetReverb
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice Changer - Modify voice in real-time
 * v2.0 Feature
 */
class VoiceChanger(private val context: Context) {
    
    companion object {
        const val TAG = "VoiceChanger"
        
        val VOICE_EFFECTS = listOf(
            VoiceEffect("none", "Normal", "No effect"),
            VoiceEffect("chipmunk", "Chipmunk", "High-pitched voice"),
            VoiceEffect("deep", "Deep Voice", "Low-pitched voice"),
            VoiceEffect("robot", "Robot", "Robotic voice"),
            VoiceEffect("echo", "Echo", "Echo effect"),
            VoiceEffect("reverb", "Reverb", "Concert hall reverb"),
            VoiceEffect("alien", "Alien", "Strange alien voice"),
            VoiceEffect("demon", "Demon", "Scary deep voice"),
            VoiceEffect("child", "Child", "Child-like voice"),
            VoiceEffect("old", "Old", "Elderly voice")
        )
    }
    
    private var isProcessing = false
    private var currentEffect = "none"
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var reverb: PresetReverb? = null
    
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    /**
     * Start voice changer
     */
    fun start(effect: String = "none"): Boolean {
        if (isProcessing) {
            Log.d(TAG, "Already processing")
            return true
        }
        
        currentEffect = effect
        
        try {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPermission) {
                Log.w(TAG, "Cannot start voice changer without RECORD_AUDIO permission")
                return false
            }
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            // Add reverb for certain effects
            if (effect in listOf("reverb", "echo", "concert")) {
                try {
                    reverb = PresetReverb(0, audioTrack!!.audioSessionId).apply {
                        preset = PresetReverb.PRESET_LARGEHALL
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reverb error: ${e.message}")
                }
            }
            
            audioRecord?.startRecording()
            audioTrack?.play()
            
            isProcessing = true
            
            // Start processing loop
            mainScope.launch {
                processAudio()
            }
            
            Log.d(TAG, "Voice changer started with effect: $effect")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice changer: ${e.message}")
            stop()
            return false
        }
    }
    
    /**
     * Stop voice changer
     */
    fun stop() {
        isProcessing = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            
            reverb?.release()
            reverb = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping voice changer: ${e.message}")
        }
        
        Log.d(TAG, "Voice changer stopped")
    }
    
    /**
     * Change effect
     */
    fun setEffect(effect: String): Boolean {
        if (effect !in VOICE_EFFECTS.map { it.id }) {
            Log.e(TAG, "Invalid effect: $effect")
            return false
        }
        
        currentEffect = effect
        Log.d(TAG, "Effect changed to: $effect")
        
        // Restart with new effect if currently processing
        if (isProcessing) {
            stop()
            start(effect)
        }
        
        return true
    }
    
    /**
     * Get current effect
     */
    fun getCurrentEffect(): String = currentEffect
    
    /**
     * Get available effects
     */
    fun getEffects(): List<VoiceEffect> = VOICE_EFFECTS
    
    /**
     * Process audio with effects
     */
    private suspend fun processAudio() = withContext(Dispatchers.IO) {
        val bufferSize = 4096
        val buffer = ShortArray(bufferSize)
        
        while (isProcessing) {
            try {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                
                if (read > 0) {
                    // Apply effect
                    val processedBuffer = applyEffect(buffer, read)
                    
                    // Write to output
                    audioTrack?.write(processedBuffer, 0, read)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}")
                break
            }
        }
    }
    
    /**
     * Apply voice effect
     */
    private fun applyEffect(buffer: ShortArray, length: Int): ShortArray {
        return when (currentEffect) {
            "chipmunk" -> {
                // Speed up (higher pitch)
                processWithPitchShift(buffer, length, 1.5f)
            }
            "deep" -> {
                // Slow down (lower pitch)
                processWithPitchShift(buffer, length, 0.7f)
            }
            "robot" -> {
                // Add modulation
                processRobotEffect(buffer, length)
            }
            "alien" -> {
                // Unusual modulation
                processAlienEffect(buffer, length)
            }
            "demon" -> {
                // Very low pitch
                processWithPitchShift(buffer, length, 0.5f)
            }
            "child" -> {
                // High pitch
                processWithPitchShift(buffer, length, 1.3f)
            }
            "old" -> {
                // Low quality effect
                processOldEffect(buffer, length)
            }
            else -> buffer
        }
    }
    
    /**
     * Pitch shift effect
     */
    private fun processWithPitchShift(buffer: ShortArray, length: Int, factor: Float): ShortArray {
        // Simple pitch shift (not perfect, but works)
        val output = ShortArray(length)
        
        for (i in 0 until length) {
            val index = (i * factor).toInt()
            if (index < length) {
                output[i] = buffer[index]
            }
        }
        
        return output
    }
    
    /**
     * Robot effect - add vibrato
     */
    private fun processRobotEffect(buffer: ShortArray, length: Int): ShortArray {
        val output = buffer.copyOf()
        var phase = 0.0
        
        for (i in 0 until length) {
            val modulation = (Math.sin(phase) * 0.3 + 1.0).toFloat()
            output[i] = (buffer[i] * modulation).toInt().toShort()
            phase += 0.1
        }
        
        return output
    }
    
    /**
     * Alien effect
     */
    private fun processAlienEffect(buffer: ShortArray, length: Int): ShortArray {
        val output = buffer.copyOf()
        var phase = 0.0
        
        for (i in 0 until length) {
            // Multiple modulation frequencies
            val mod = (Math.sin(phase) * 0.5 + Math.sin(phase * 2.5) * 0.3 + 1.0).toFloat()
            output[i] = (buffer[i] * mod).toInt().toShort()
            phase += 0.08
        }
        
        return output
    }
    
    /**
     * Old/vintage effect
     */
    private fun processOldEffect(buffer: ShortArray, length: Int): ShortArray {
        val output = buffer.copyOf()
        
        // Reduce high frequencies (simulate low quality)
        for (i in 1 until length - 1) {
            output[i] = ((buffer[i - 1] + buffer[i] + buffer[i + 1]) / 3).toShort()
        }
        
        return output
    }
    
    /**
     * Get voice command response
     */
    fun handleVoiceCommand(command: String, target: String?): String {
        return when (command.lowercase()) {
            "start_voice_changer", "voice_changer_on" -> {
                val effect = target ?: "none"
                if (start(effect)) {
                    "Voice changer enabled with ${VOICE_EFFECTS.find { it.id == effect }?.name ?: effect} effect"
                } else {
                    "Could not start voice changer"
                }
            }
            "stop_voice_changer", "voice_changer_off" -> {
                stop()
                "Voice changer disabled"
            }
            "change_voice", "set_voice" -> {
                val effect = target ?: "none"
                if (setEffect(effect)) {
                    "Voice effect changed to ${VOICE_EFFECTS.find { it.id == effect }?.name ?: effect}"
                } else {
                    "Invalid effect. Available: ${VOICE_EFFECTS.joinToString(", ") { it.name }}"
                }
            }
            "list_voices", "voice_effects" -> {
                "Available voice effects: ${VOICE_EFFECTS.joinToString(", ") { it.name }}"
            }
            else -> "Unknown voice changer command"
        }
    }
}

data class VoiceEffect(
    val id: String,
    val name: String,
    val description: String
)
