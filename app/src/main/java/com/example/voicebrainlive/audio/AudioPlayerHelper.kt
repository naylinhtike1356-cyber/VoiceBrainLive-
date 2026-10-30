package com.example.voicebrainlive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Base64

/** Bounded worker-owned playback preserving source APIs and PCM/media-volume configuration. */
class AudioPlayerHelper {
    companion object {
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        internal const val BUFFER_SIZE_MULTIPLIER = 8
    }

    @Volatile var onError: ((String) -> Unit)? = null
    private val controller = BoundedPlaybackController(
        sinkFactory = { AndroidPcmSink() },
        onWorkerStart = { try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {} },
        onError = { onError?.invoke("Audio playback error: ${it.name}") }
    )

    fun start() {
        if (controller.start() == BoundedPlaybackController.Start.RETIRING) {
            onError?.invoke("Audio playback is still stopping; retry when cleanup finishes.")
        }
    }
    fun playBase64(base64Audio: String) {
        val producer = controller.captureProducer() ?: return
        playBase64(producer, base64Audio)
    }
    internal fun captureProducer(): BoundedPlaybackController.Producer? = controller.captureProducer()
    internal fun isCurrentProducer(producer: BoundedPlaybackController.Producer): Boolean =
        controller.current(producer)
    /** Call only from a current provider callback, before listener/UI/async work. */
    internal fun captureStartedProducer(): BoundedPlaybackController.Producer? {
        if (!isPlaying()) start()
        return captureProducer()
    }
    internal fun playBase64(producer: BoundedPlaybackController.Producer, base64Audio: String) {
        if (!controller.current(producer)) return
        if (base64Audio.isBlank()) return
        // Bound decode allocation, including whitespace; base64 transport is normally unwrapped.
        val maxEncodedChars = ((controller.maxFrameBytes() + 2) / 3) * 4 + 64
        if (base64Audio.length > maxEncodedChars) {
            controller.rejectEncodedInput()
            onError?.invoke("Audio frame exceeds playback limit.")
            return
        }
        try {
            playRaw(producer, Base64.decode(base64Audio, Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            controller.rejectEncodedInput()
            onError?.invoke("Invalid audio encoding.")
        }
    }
    fun playRaw(bytes: ByteArray) {
        val producer = controller.captureProducer() ?: return
        playRaw(producer, bytes)
    }
    internal fun playRaw(producer: BoundedPlaybackController.Producer, bytes: ByteArray) {
        if (!controller.current(producer)) return
        if (bytes.isEmpty()) return
        if (bytes.size > controller.maxFrameBytes()) {
            controller.offer(producer, bytes) // Queue records rejection without copying.
            onError?.invoke("Audio frame exceeds playback limit.")
            return
        }
        // Keep the original helper's PCM16 alignment behavior for compatibility.
        val aligned = if (bytes.size % 2 != 0) bytes.copyOf(bytes.size - 1) else bytes
        if (aligned.isNotEmpty()) {
            val admission = controller.offer(producer, aligned)
            if (admission == ByteBoundedPcmQueue.Admission.FULL) onError?.invoke("Audio buffer full; frame dropped.")
        }
    }
    fun clearQueue() { controller.clear() }
    fun stop() { controller.stop() }
    fun isPlaying(): Boolean = controller.isRunning()
    fun isActuallySpeaking(): Boolean = controller.isSpeaking()
    internal fun diagnostics(): BoundedPlaybackController.Snapshot = controller.snapshot()
}

/** Captured once by worker; never stored in a mutable shared helper field. */
private class AndroidPcmSink : PcmPlaybackSink {
    private val track: AudioTrack
    init {
        val minimum = AudioTrack.getMinBufferSize(AudioPlayerHelper.SAMPLE_RATE,
            AudioPlayerHelper.CHANNEL_CONFIG, AudioPlayerHelper.AUDIO_FORMAT)
        check(minimum > 0) { "Audio buffer unavailable" }
        val candidate = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioPlayerHelper.AUDIO_FORMAT)
                .setSampleRate(AudioPlayerHelper.SAMPLE_RATE).setChannelMask(AudioPlayerHelper.CHANNEL_CONFIG).build())
            .setBufferSizeInBytes(Math.multiplyExact(minimum, AudioPlayerHelper.BUFFER_SIZE_MULTIPLIER))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            error("Audio track unavailable")
        }
        track = candidate
    }
    override fun play() { track.play() }
    override fun write(bytes: ByteArray, offset: Int, count: Int): Int =
        track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun pause() { track.pause() }
    override fun flush() { track.flush() }
    override fun stop() { track.stop() }
    override fun release() { track.release() }
}
