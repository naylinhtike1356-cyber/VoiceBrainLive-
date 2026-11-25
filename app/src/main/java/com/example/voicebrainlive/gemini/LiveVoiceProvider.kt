package com.example.voicebrainlive.gemini

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Model-agnostic interface for real-time multimodal voice and text AI providers.
 * Decouples ViewModels and Services from specific Gemini, OpenAI, or on-device transports.
 */
interface LiveVoiceProvider {
    var onConnected: (() -> Unit)?
    var onSetupComplete: (() -> Unit)?
    var onInitialUserTurnSent: ((text: String) -> Unit)?
    var onDisconnected: ((String) -> Unit)?
    var onAudioChunk: ((base64Audio: String) -> Unit)?
    var onAudioBytes: ((bytes: ByteArray) -> Unit)?
    var onTranscript: ((text: String) -> Unit)?
    var onAgentTranscript: ((text: String, current: () -> Boolean) -> Unit)?
    var onInputTranscript: ((text: String) -> Unit)?
    var onOutputTranscript: ((text: String) -> Unit)?
    var onTurnComplete: (() -> Unit)?
    var onTaskStarted: (() -> Unit)?
    var onInterrupted: (() -> Unit)?
    var onError: ((String) -> Unit)?
    var onLog: ((String) -> Unit)?
    var onSetupSuccess: ((String) -> Unit)?
    var onReconnecting: ((attempt: Int, delayMs: Long) -> Unit)?

    fun connect()
    fun disconnect()
    fun isConnected(): Boolean
    fun sendTextInput(text: String)
    fun sendConversationInput(text: String)
    fun sendAudioChunk(base64Audio: String)
    fun sendImageFrame(base64Image: String)
    fun sendToolResponse(callId: String, functionName: String, result: String)
}

object LiveVoiceProviderFactory {
    fun create(
        apiKey: String,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        model: String = "models/gemini-3.1-flash-live-preview",
        voice: String = "Aoede",
        systemInstructionExtra: String = "",
        automationContext: Context? = null,
        onExecutePhoneCommand: suspend (commandType: String, target: String?, value: String?) -> String = { _, _, _ -> "Not implemented" }
    ): LiveVoiceProvider = GeminiLiveClient(
        apiKey = apiKey,
        scope = scope,
        model = model,
        voice = voice,
        systemInstructionExtra = systemInstructionExtra,
        automationContext = automationContext,
        onExecutePhoneCommand = onExecutePhoneCommand
    )
}
