package com.example.voicebrainlive.audio

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Pure JVM PCM16 queue. A token is a playback generation, never an approval capability. */
internal class ByteBoundedPcmQueue(private val maxBytes: Int, private val maxChunks: Int = 128) {
    init { require(maxBytes >= 2 && maxChunks > 0) }

    class Token internal constructor()
    enum class Admission { ACCEPTED, EMPTY, UNALIGNED, TOO_LARGE, FULL, STALE }
    data class Chunk(val token: Token, val bytes: ByteArray)
    data class Snapshot(
        val queuedBytes: Int, val queuedChunks: Int, val accepted: Long,
        val rejected: Long, val rejectedBytes: Long, val cancelledChunks: Long,
        val cancelledBytes: Long, val highWaterBytes: Int
    )

    private val monitor = Object()
    private val chunks = ArrayDeque<Chunk>()
    private var active: Token? = null
    private var bytes = 0
    private var accepted = 0L
    private var rejected = 0L
    private var rejectedBytes = 0L
    private var cancelledChunks = 0L
    private var cancelledBytes = 0L
    private var highWaterBytes = 0

    fun open(): Token = synchronized(monitor) {
        discardQueued()
        Token().also { active = it; monitor.notifyAll() }
    }

    /** Never blocks a WebSocket callback. Rejects newest work without splitting PCM frames. */
    fun offer(token: Token, pcm: ByteArray): Admission = synchronized(monitor) {
        val result = when {
            active !== token -> Admission.STALE
            pcm.isEmpty() -> Admission.EMPTY
            pcm.size % 2 != 0 -> Admission.UNALIGNED
            pcm.size > maxBytes -> Admission.TOO_LARGE
            pcm.size > maxBytes - bytes || chunks.size >= maxChunks -> Admission.FULL
            else -> Admission.ACCEPTED
        }
        if (result == Admission.ACCEPTED) {
            // Copy only after size validation; producers may reuse capture buffers.
            chunks.addLast(Chunk(token, pcm.copyOf()))
            bytes += pcm.size
            accepted++
            highWaterBytes = maxOf(highWaterBytes, bytes)
            monitor.notifyAll()
        } else {
            rejected++
            rejectedBytes += pcm.size.toLong()
        }
        result
    }

    fun poll(token: Token): Chunk? = synchronized(monitor) { removeFirst(token) }

    /** Blocking only on the playback thread. Close/open wakes waits; interruption propagates. */
    @Throws(InterruptedException::class)
    fun await(token: Token, timeoutMillis: Long): Chunk? {
        require(timeoutMillis in 0..60_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return synchronized(monitor) {
            while (active === token && chunks.isEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) break
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
            removeFirst(token)
        }
    }

    fun current(token: Token): Boolean = synchronized(monitor) { active === token }

    /** Revokes dequeued work too: consumer must check current(chunk.token) before each write. */
    fun close(token: Token): Boolean = synchronized(monitor) {
        if (active !== token) return@synchronized false
        active = null
        discardQueued()
        monitor.notifyAll()
        true
    }

    fun snapshot(): Snapshot = synchronized(monitor) {
        Snapshot(bytes, chunks.size, accepted, rejected, rejectedBytes,
            cancelledChunks, cancelledBytes, highWaterBytes)
    }

    private fun removeFirst(token: Token): Chunk? {
        if (active !== token || chunks.isEmpty()) return null
        return chunks.removeFirst().also { bytes -= it.bytes.size }
    }

    private fun discardQueued() {
        cancelledChunks += chunks.size.toLong()
        cancelledBytes += bytes.toLong()
        chunks.clear()
        bytes = 0
    }
}
