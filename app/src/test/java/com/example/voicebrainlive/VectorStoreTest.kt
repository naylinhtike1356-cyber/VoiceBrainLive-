package com.example.voicebrainlive

import com.example.voicebrainlive.memory.VectorStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for VectorStore logic (Cosine Similarity).
 */
class VectorStoreTest {

    @Test
    fun testCosineSimilarity() {
        val a = floatArrayOf(1.0f, 0.0f, 0.0f)
        val b = floatArrayOf(1.0f, 0.0f, 0.0f)
        val c = floatArrayOf(0.0f, 1.0f, 0.0f)
        val d = floatArrayOf(-1.0f, 0.0f, 0.0f)

        // Same vector = 1.0
        assertEquals(1.0f, VectorStore.cosineSimilarity(a, b), 0.001f)
        
        // Orthogonal = 0.0
        assertEquals(0.0f, VectorStore.cosineSimilarity(a, c), 0.001f)
        
        // Opposite = -1.0
        assertEquals(-1.0f, VectorStore.cosineSimilarity(a, d), 0.001f)
    }

    @Test
    fun testCosineSimilarityEdgeCases() {
        val a = floatArrayOf(1.0f, 1.0f)
        val b = floatArrayOf(1.0f)
        val zero = floatArrayOf(0.0f, 0.0f)

        // Different sizes = 0.0
        assertEquals(0.0f, VectorStore.cosineSimilarity(a, b), 0.001f)
        
        // Zero vector = 0.0
        assertEquals(0.0f, VectorStore.cosineSimilarity(a, zero), 0.001f)
    }
}
