@file:SuppressLint("MissingPermission")

package com.example.voicebrainlive.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures raw PCM audio. 16kHz, MONO, 16-bit Little Endian.
 * VOICE_RECOGNITION (default) or VOICE_COMMUNICATION for noisy environments.
 * NoiseSuppressor, AEC, AGC for professional voice capture + soft speech.
 * Reads in chunks of 2048 bytes, Base64 encodes, passes to callback for WebSocket.
 * Also calculates live RMS amplitude for visual equalizer wave animations.
 */
class AudioRecorderHelper {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_SIZE_BYTES = 2048
    }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    /** Enable NoiseSuppressor for background noise reduction (default true). */
    var useNoiseSuppressor: Boolean = true

    /**
     * When true, use VOICE_COMMUNICATION instead of VOICE_RECOGNITION.
     * Better for: noisy environments, soft speech (တိုးတိုးပြော).
     * System applies more aggressive noise filtering + AGC for clear voice capture.
     */
    var useVoiceCommunicationForNoisyEnv: Boolean = true

    private val bufferSize by lazy {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        maxOf(min, CHUNK_SIZE_BYTES * 2)
    }

    var onAudioChunk: ((base64Audio: String) -> Unit)? = null
    var onAmplitudeChanged: ((amplitude: Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start(scope: CoroutineScope) {
        if (isRecording.getAndSet(true)) return
        recordingJob = scope.launch(Dispatchers.IO) {
            var success = false
            var attempts = 0
            val maxAttempts = 5
            var lastError: Exception? = null

            @Suppress("DEPRECATION")
            val audioSource = if (useVoiceCommunicationForNoisyEnv) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION  // Better noise filter + AGC for soft speech in noisy env
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION    // Optimized for speech (default)
            }

            while (attempts < maxAttempts && !success) {
                try {
                    val recorder = AudioRecord(
                        audioSource,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        recorder.release()
                        throw IllegalStateException("AudioRecord initialization failed")
                    }
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                        recorder.release()
                        throw IllegalStateException("AudioRecord not in RECORDING state")
                    }
                    audioRecord = recorder
                    success = true
                    Log.d("AudioStream", "AudioRecord started successfully on attempt ${attempts + 1}")
                } catch (e: Exception) {
                    attempts++
                    lastError = e
                    Log.w("AudioStream", "Attempt $attempts to start AudioRecord failed: ${e.message}")
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(200)
                    }
                }
            }

            if (!success) {
                isRecording.set(false)
                val errMsg = lastError?.message ?: "Unknown microphone error"
                Log.e("AudioStream", "All attempts to start AudioRecord failed: $errMsg")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onError?.invoke("AudioRecord error: $errMsg")
                }
                return@launch
            }

            val sessionId = audioRecord!!.audioSessionId
            if (useNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)
                    noiseSuppressor?.enabled = true
                    Log.d("AudioStream", "NoiseSuppressor enabled")
                } catch (e: Exception) {
                    Log.w("AudioStream", "NoiseSuppressor failed: ${e.message}")
                }
            }
            // AEC and AGC should only be applied to VOICE_COMMUNICATION; on VOICE_RECOGNITION it can cause hardware attenuation/silence
            if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
                        acousticEchoCanceler?.enabled = true
                        Log.d("AudioStream", "AcousticEchoCanceler enabled")
                    } catch (e: Exception) {
                        Log.w("AudioStream", "AcousticEchoCanceler failed: ${e.message}")
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        automaticGainControl = AutomaticGainControl.create(sessionId)
                        automaticGainControl?.enabled = true
                        Log.d("AudioStream", "AutomaticGainControl enabled")
                    } catch (e: Exception) {
                        Log.w("AudioStream", "AutomaticGainControl failed: ${e.message}")
                    }
                }
            }

            val byteBuffer = ByteBuffer.allocate(CHUNK_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val shortBuffer = ShortArray(CHUNK_SIZE_BYTES / BYTES_PER_SAMPLE)
            var stopReason: String? = null
            while (isActive && isRecording.get()) {
                val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1

                when {
                    read > 0 -> {
                        byteBuffer.clear()
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val sampleShort = shortBuffer[i]
                            byteBuffer.putShort(sampleShort)
                            val norm = sampleShort / 32768f
                            sumSq += norm * norm
                        }
                        val bytesRead = read * BYTES_PER_SAMPLE
                        val bytes = byteBuffer.array().copyOf(bytesRead)
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        onAudioChunk?.invoke(base64)

                        // Compute and stream RMS amplitude (0.0f - 1.0f)
                        val rms = kotlin.math.sqrt(sumSq / read).toFloat()
                        onAmplitudeChanged?.invoke(rms)
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        stopReason = "AudioRecord returned ERROR_INVALID_OPERATION"
                        break
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        stopReason = "AudioRecord returned ERROR_BAD_VALUE"
                        break
                    }
                }
            }
            if (stopReason != null && isRecording.get()) {
                isRecording.set(false)
                Log.e("AudioStream", "Microphone capture stopped: $stopReason")
                onError?.invoke(stopReason!!)
            }
        }
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        recordingJob?.cancel()
        recordingJob = null
        try { noiseSuppressor?.release() } catch (e: Exception) { Log.w("AudioStream", "NoiseSuppressor release: ${e.message}") }
        noiseSuppressor = null
        try { acousticEchoCanceler?.release() } catch (e: Exception) { Log.w("AudioStream", "AEC release: ${e.message}") }
        acousticEchoCanceler = null
        try { automaticGainControl?.release() } catch (e: Exception) { Log.w("AudioStream", "AGC release: ${e.message}") }
        automaticGainControl = null
        try {
            val recorder = audioRecord
            audioRecord = null
            if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
                recorder.stop()
            }
            recorder?.release()
        } catch (e: Exception) {
            Log.e("AudioStream", "Stop error: ${e.message}")
        }
    }

    fun isRecording(): Boolean = isRecording.get()
}
