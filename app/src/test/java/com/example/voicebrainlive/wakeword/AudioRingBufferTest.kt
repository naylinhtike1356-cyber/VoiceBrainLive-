package com.example.voicebrainlive.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingBufferTest {

    @Test
    fun ringBufferWrapsAndPreservesLatestBytes() {
        val ring = AudioRingBuffer(capacityBytes = 6)
        ring.write(byteArrayOf(1, 2, 3, 4))
        ring.write(byteArrayOf(5, 6, 7, 8))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), ring.snapshot())
    }

    @Test
    fun ringBufferHandlesSingleWriteLargerThanCapacity() {
        val ring = AudioRingBuffer(capacityBytes = 4)
        ring.write(byteArrayOf(10, 20, 30, 40, 50, 60))
        assertArrayEquals(byteArrayOf(30, 40, 50, 60), ring.snapshot())
    }

    @Test
    fun vadGateDetectsSilenceVsSpeechEnergy() {
        val silence = ByteArray(64)
        assertEquals(0f, AcousticVadGate.computeRms(silence), 0.0001f)
        assertFalse(AcousticVadGate.isSpeechLikely(silence))

        // 16-bit LE loud sample (~0.25 amplitude)
        val loud = ByteArray(64)
        for (i in loud.indices step 2) {
            loud[i] = 0x00
            loud[i + 1] = 0x20 // 8192 / 32768 = 0.25
        }
        assertTrue(AcousticVadGate.isSpeechLikely(loud))
    }
}
