package com.example.voicebrainlive.audio

import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.os.Process

/**
 * Plays raw PCM from Gemini Live API. Strict config: 24kHz, MONO, 16-bit.
 * Stream: MUSIC. Includes jitter buffer to prevent choppy audio (underruns).
 */
class AudioPlayerHelper {

    companion object {
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 8 // Increased for better stability
        private const val JITTER_BUFFER_THRESHOLD = 4 // Wait for a few chunks before starting
    }

    private var audioTrack: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var isPlaying = false
    private var isActuallyPlaying = false

    private val minBufferSize by lazy {
        AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val bufferSize by lazy { minBufferSize * BUFFER_SIZE_MULTIPLIER }

    var onError: ((String) -> Unit)? = null

    fun start() {
        if (isPlaying) return
        isPlaying = true

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttrs)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            onError?.invoke("AudioTrack initialization failed")
            isPlaying = false
            return
        }
        // Do NOT call play() here. It will cause instant underrun.
        // We will call play() right before writing the first chunk.

        thread(name = "AudioPlaybackPriority") {
            // Set thread priority to AUDIO to prevent system preemption stutters
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                Log.w("AudioStream", "Could not set priority: ${e.message}")
            }

            var bufferedCount = 0
            
            while (isPlaying) {
                try {
                    val chunk = queue.poll(150, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        isActuallyPlaying = false // Underrun occurred
                        continue
                    }
                    if (chunk.isEmpty()) {
                        if (!isPlaying) break
                        continue
                    }

                    if (!isActuallyPlaying) {
                        isActuallyPlaying = true
                        // Added a tiny delay to let Gemini send a couple more chunks into the queue before we begin actual playback.
                        // This serves as a natural jitter buffer.
                        Thread.sleep(40)
                    }

                    val track = audioTrack
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try {
                                track.play()
                            } catch (e: Exception) {
                                Log.e("AudioStream", "Failed to start AudioTrack: ${e.message}")
                            }
                        }
                        track.write(chunk, 0, chunk.size)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("AudioStream", "Playback error: ${e.message}")
                }
            }
        }
    }

    fun playBase64(base64Audio: String) {
        if (base64Audio.isBlank()) return
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            if (audioBytes.isEmpty()) return
            // Ensure even alignment for PCM 16bit
            val aligned = if (audioBytes.size % 2 != 0) audioBytes.copyOf(audioBytes.size - 1) else audioBytes
            queue.put(aligned)
        } catch (e: Exception) {
            onError?.invoke("Base64 decode error: ${e.message}")
        }
    }

    fun playRaw(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val aligned = if (bytes.size % 2 != 0) bytes.copyOf(bytes.size - 1) else bytes
        queue.put(aligned)
    }

    fun clearQueue() {
        queue.clear()
        isActuallyPlaying = false
    }

    fun stop() {
        isPlaying = false
        isActuallyPlaying = false
        queue.clear()
        queue.offer(ByteArray(0)) // Wake up blocked thread
        try {
            val track = audioTrack
            audioTrack = null
            if (track?.state == AudioTrack.STATE_INITIALIZED) {
                track.stop()
            }
            track?.release()
        } catch (e: Exception) {
            Log.e("AudioStream", "Stop error: ${e.message}")
        }
    }

    fun isPlaying(): Boolean = isPlaying
    
    /** Returns true if the player is actively writing data to the AudioTrack. */
    fun isActuallySpeaking(): Boolean = isActuallyPlaying || !queue.isEmpty()
}
