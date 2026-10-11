package com.example.voicebrainlive.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.voicebrainlive.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Security layer: buffers first ~1.5s of audio, verifies against VoiceProfile,
 * then forwards to Gemini only if voice matches. Re-verifies periodically so
 * even in noisy environments only the enrolled voice can give commands.
 */
class VoiceVerificationGate(
    private val context: Context,
    private val onForward: (base64Chunk: String) -> Unit,
    private val onReject: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val profileManager = VoiceProfileManager(context)
    private val buffer = mutableListOf<String>()
    private val slidingBuffer = mutableListOf<ByteArray>()
    private var verified = false
    private var rejected = false
    private var bytesSinceReverify = 0
    private val isNoisyEnv = SettingsActivity.isNoisyEnvMode(context)

    companion object {
        private const val VERIFY_BYTES = 28000  // ~1.75 sec for first check
        private fun getReverifyInterval(isNoisy: Boolean) = if (isNoisy) 24000 else 48000 // Re-verify every ~1.5s in noise, ~3s normally
        private const val TAG = "VoiceVerify"
    }

    fun feedChunk(base64: String) {
        if (!profileManager.isTrained()) {
            onForward(base64)
            return
        }
        if (rejected) return

        if (verified) {
            onForward(base64)
            val chunkBytes = Base64.decode(base64, Base64.NO_WRAP)
            synchronized(slidingBuffer) {
                slidingBuffer.add(chunkBytes)
                var total = slidingBuffer.sumOf { it.size }
                while (total > VERIFY_BYTES && slidingBuffer.size > 1) {
                    slidingBuffer.removeAt(0)
                    total = slidingBuffer.sumOf { it.size }
                }
                bytesSinceReverify += chunkBytes.size
                val interval = getReverifyInterval(isNoisyEnv)
                if (bytesSinceReverify >= interval) {
                    bytesSinceReverify = 0
                    val toVerify = synchronized(slidingBuffer) { slidingBuffer.flatMap { it.toList() }.toByteArray() }
                    if (toVerify.size >= VERIFY_BYTES) {
                        scope.launch {
                            val sim = withContext(Dispatchers.Default) {
                                profileManager.verifyVoice(toVerify)
                            }
                            if (sim < VoiceProfileManager.MATCH_THRESHOLD) {
                                rejected = true
                                onReject("voice_not_recognized")
                                Log.d(TAG, "Re-verify failed (sim=$sim) - Noise/Different voice detected")
                            }
                        }
                    }
                }
            }
            return
        }

        synchronized(buffer) {
            buffer.add(base64)
            val totalBytes = buffer.sumOf { Base64.decode(it, Base64.NO_WRAP).size }
            if (totalBytes >= VERIFY_BYTES) {
                val bytes = buffer.flatMap { Base64.decode(it, Base64.NO_WRAP).toList() }.toByteArray()
                val toForward = buffer.toList()
                buffer.clear()
                scope.launch {
                    val sim = withContext(Dispatchers.Default) {
                        profileManager.verifyVoice(bytes)
                    }
                    if (sim >= VoiceProfileManager.MATCH_THRESHOLD) {
                        verified = true
                        bytesSinceReverify = bytes.size
                        slidingBuffer.clear()
                        slidingBuffer.add(bytes)
                        toForward.forEach { onForward(it) }
                        Log.d(TAG, "Voice verified (sim=$sim)")
                    } else {
                        rejected = true
                        onReject("voice_not_recognized")
                        Log.d(TAG, "Voice rejected (sim=$sim)")
                    }
                }
            }
        }
    }

    fun reset() {
        verified = false
        rejected = false
        buffer.clear()
        synchronized(slidingBuffer) {
            slidingBuffer.clear()
            bytesSinceReverify = 0
        }
    }

}
