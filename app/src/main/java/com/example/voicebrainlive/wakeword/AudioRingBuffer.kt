package com.example.voicebrainlive.wakeword

/**
 * Thread-safe circular PCM ring buffer (16kHz, 16-bit mono = 32,000 bytes/sec).
 * Retains the most recent [capacityBytes] of audio so trailing speech after a wake-word
 * trigger can be handed off without clipping.
 */
class AudioRingBuffer(
    val capacityBytes: Int = DEFAULT_CAPACITY_BYTES
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val BYTES_PER_SAMPLE = 2
        const val DEFAULT_DURATION_MS = 1500
        const val DEFAULT_CAPACITY_BYTES = (SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * DEFAULT_DURATION_MS) / 1000
    }

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
    }

    private val buffer = ByteArray(capacityBytes)
    private var writePos = 0
    private var totalWritten = 0L

    @Synchronized
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0 || offset < 0 || offset + length > data.size) return
        if (length >= capacityBytes) {
            System.arraycopy(data, offset + length - capacityBytes, buffer, 0, capacityBytes)
            writePos = 0
            totalWritten += length
            return
        }
        val firstPart = minOf(length, capacityBytes - writePos)
        System.arraycopy(data, offset, buffer, writePos, firstPart)
        val remaining = length - firstPart
        if (remaining > 0) {
            System.arraycopy(data, offset + firstPart, buffer, 0, remaining)
        }
        writePos = (writePos + length) % capacityBytes
        totalWritten += length
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val available = minOf(totalWritten, capacityBytes.toLong()).toInt()
        if (available == 0) return ByteArray(0)
        val out = ByteArray(available)
        if (totalWritten < capacityBytes) {
            System.arraycopy(buffer, 0, out, 0, available)
        } else {
            val tailLen = capacityBytes - writePos
            System.arraycopy(buffer, writePos, out, 0, tailLen)
            if (writePos > 0) {
                System.arraycopy(buffer, 0, out, tailLen, writePos)
            }
        }
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0
        totalWritten = 0L
        buffer.fill(0)
    }
}
