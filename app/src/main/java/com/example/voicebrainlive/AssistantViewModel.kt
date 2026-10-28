package com.example.voicebrainlive

import android.app.Application
import android.util.Log
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicebrainlive.audio.AudioFocusHelper
import com.example.voicebrainlive.audio.AudioPlayerHelper
import com.example.voicebrainlive.audio.AudioRecorderHelper
import com.example.voicebrainlive.audio.PlaybackDrainLease
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.CommandSafetyPolicy

import com.example.voicebrainlive.learning.LearnedIntentExecutor
import com.example.voicebrainlive.learning.UserUtteranceContext
import com.example.voicebrainlive.weather.NaturalWeatherSpeaker
import com.example.voicebrainlive.memory.BehaviorMemory
import com.example.voicebrainlive.memory.PersonalSystemInstructionBuilder
import com.example.voicebrainlive.memory.SimpleConversationStorage
import com.example.voicebrainlive.gemini.GeminiLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.lifecycle.MutableLiveData
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 1.1: ViewModel Migration – Context Leak ကာကွယ်ရန်
 * geminiClient, audioRecorder, audioPlayer, phoneExecutor ကို ViewModel ထဲ ရွှေ့ထား
 * viewModelScope (LifecycleScope equivalent) သုံးပြီး config change မှာ survive မယ်
 */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val geminiClient: GeminiLiveClient
    val audioRecorder: AudioRecorderHelper
    val audioPlayer: AudioPlayerHelper
    var phoneExecutor: PhoneCommandExecutor
        private set

    /** Listener ကို onDestroy မှာ null လုပ်မှ Context Leak မဖြစ်ဘူး */
    var listener: AssistantViewModelListener? = null

    val biometricRequest = MutableLiveData<BiometricRequest?>()

    fun clearBiometricRequest() {
        biometricRequest.postValue(null)
    }

    data class BiometricRequest(
        val title: String,
        val subtitle: String,
        val onResult: (Boolean) -> Unit
    )

    // Common boundary opens the full preview before explicit tap and biometric authentication.
    private suspend fun runWithBiometricCheck(cmd: String, target: String?, value: String?, block: suspend () -> String): String = block()

    var isFirstAudioChunkOfTurn = true
    private var focusUnavailableForTurn = false
    private val playbackDrainLease = PlaybackDrainLease()
    private var playbackDrainJob: Job? = null
    var currentUserInput: String = "🎤"
    var currentAiResponse: String = ""
    var isAutomationMode = false
    private val currentSessionId = "assistant_${System.currentTimeMillis()}"
    private var voiceFastPathJob: Job? = null
    private var lastVoiceFastPathPhrase = ""
    private var lastVoiceFastPathAt = 0L

    fun sendTextInput(text: String) {
        currentUserInput = "⌨️ $text"
        val service = VoiceLiveService.instance
        if (service != null) {
            service.sendTextInput(text)
        } else {
            geminiClient.sendConversationInput(text)
        }
    }

    init {
        val apiKey = SettingsActivity.getGeminiApiKey(app)
        val voice = SettingsActivity.getVoice(app)
        val geminiModel = SettingsActivity.getGeminiModel(app)

        // Build personalized system instruction using all memory layers
        val basePrefs = UserPreferences.toSystemInstructionString(app)
        val systemExtra = PersonalSystemInstructionBuilder.build(app, basePrefs)

        phoneExecutor = PhoneCommandExecutor(app)
        geminiClient = GeminiLiveClient(apiKey, model = geminiModel, voice = voice, systemInstructionExtra = systemExtra) { cmd, target, value ->
            val networkCmds = listOf(
                "get_weather", "weather", "search_coupang", "search_aliexpress", "web_search", "search", "search_news",
                "send_sms", "send_messenger", "send_facebook_message", "send_whatsapp"
            )
            val result = runWithBiometricCheck(cmd, target, value) {
                if (cmd in networkCmds) {
                    try {
                        withTimeoutOrNull(15000L) {
                            phoneExecutor.executeAsync(cmd, target, value)
                        } ?: "တုံ့ပြန်ချက်ကြာလွန်းပြီး အချိန်ကုန်သွားပါပြီ။ ထပ်ကြိုးစားပါ။"
                    } catch (e: Exception) {
                        Log.e("AssistantViewModel", "Command execution error: ${e.message}")
                        "အမှားဖြစ်သွားပါပြီ: ${e.message}"
                    }
                } else {
                    phoneExecutor.executeAsync(cmd, target, value)
                }
            }
            // Weather commands သုံးပြီး သဘာဝကျကျ ပြောမည်
            if (cmd in listOf("get_battery_status", "get_wifi_status", "get_bluetooth_status")) {
                listener?.speakResultWithTTS(result)
            } else if (cmd in listOf("get_weather", "weather")) {
                // Weather data ကို parse လုပ်ပြီး NaturalWeatherSpeaker နဲ့ သဘာဝကျကျ ပြောမည်
                viewModelScope.launch(Dispatchers.Main) {
                    val weatherSpeaker = NaturalWeatherSpeaker(app)
                    if (result.startsWith("WEATHER_DATA|")) {
                        // Structured weather data ကို parse လုပ်မည်
                        val parts = result.split("|")
                        if (parts.size >= 10) {
                            val location = parts[1]
                            val temp = parts[2].toIntOrNull() ?: 0
                            val minTemp = parts[3].toIntOrNull() ?: 0
                            val maxTemp = parts[4].toIntOrNull() ?: 0
                            val weatherCode = parts[5].toIntOrNull() ?: 0
                            val isRainExpected = parts[6].toBoolean()
                            val humidity = parts[7].toIntOrNull() ?: 0
                            val windSpeed = parts[8].toDoubleOrNull() ?: 0.0
                            val rainSummary = parts.getOrNull(10).orEmpty()
                            
                            weatherSpeaker.speakWeather(
                                temperature = temp,
                                minTemp = minTemp,
                                maxTemp = maxTemp,
                                weatherCode = weatherCode,
                                location = location,
                                isRainExpected = isRainExpected,
                                humidity = humidity,
                                windSpeed = windSpeed,
                                rainSummary = rainSummary
                            )
                        } else {
                            weatherSpeaker.speakSimple(result)
                        }
                    } else {
                        // Non-structured data (forecast tomorrow, week, etc.)
                        weatherSpeaker.speakSimple(result)
                    }
                    kotlinx.coroutines.delay(12000)
                    weatherSpeaker.shutdown()
                }
            }
            result
        }
        geminiClient.onSetupSuccess = { workingModel ->
            SettingsActivity.saveGeminiModel(app, workingModel)
        }
        audioRecorder = AudioRecorderHelper().apply {
            useVoiceCommunicationForNoisyEnv = SettingsActivity.isNoisyEnvMode(app)
        }
        audioPlayer = AudioPlayerHelper().apply {
            onError = { message -> this@AssistantViewModel.listener?.onError(message) }
        }
    }

    /** viewModelScope သုံးပြီး mic start – LifecycleScope equivalent */
    fun startAudioRecorder() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioRecorder.start(viewModelScope)
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Mic start error: ${e.message}")
            }
        }
    }

    fun playAudioChunk(base64: String) {
        val player = audioPlayer
        val producer = if (!FocusMode.isActive(app)) player.captureStartedProducer() else null
        if (producer != null) {
            enqueuePlayback(player, producer) { playAudioChunk(player, producer, base64) }
        }
    }

    private fun playAudioChunk(player: AudioPlayerHelper,
        producer: com.example.voicebrainlive.audio.BoundedPlaybackController.Producer, base64: String) {
        if (!FocusMode.isActive(app)) {
            try {
                player.playBase64(producer, base64)
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "playBase64", e)
            }
        }
    }

    fun playAudioBytes(bytes: ByteArray) {
        val player = audioPlayer
        val producer = if (!FocusMode.isActive(app)) player.captureStartedProducer() else null
        if (producer != null) {
            enqueuePlayback(player, producer) { playAudioBytes(player, producer, bytes) }
        }
    }

    private fun playAudioBytes(player: AudioPlayerHelper,
        producer: com.example.voicebrainlive.audio.BoundedPlaybackController.Producer, bytes: ByteArray) {
        if (!FocusMode.isActive(app)) {
            try {
                player.playRaw(producer, bytes)
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "playRaw", e)
            }
        }
    }

    fun onTurnCompleteOrInterrupted(onDone: () -> Unit) {
        val player = audioPlayer
        // All lease mutations/registrations occur on Main, avoiding Epoch -> lease lock waits.
        viewModelScope.launch(Dispatchers.Main) {
            playbackDrainLease.replace { token ->
                playbackDrainJob?.cancel()
                playbackDrainJob = viewModelScope.launch(Dispatchers.IO) {
                    var timeout = 0
                    while (player.isActuallySpeaking() && timeout < 25000) {
                        kotlinx.coroutines.delay(100)
                        timeout += 100
                    }
                    // Approximate native drain allowance, not measured audible completion.
                    kotlinx.coroutines.delay(500)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        playbackDrainLease.withCurrent(token) {
                            AudioFocusHelper.abandon(app, player)
                            isFirstAudioChunkOfTurn = true
                            focusUnavailableForTurn = false
                            onDone()
                        }
                    }
                }
            }
        }
    }

    private fun enqueuePlayback(player: AudioPlayerHelper,
        producer: com.example.voicebrainlive.audio.BoundedPlaybackController.Producer, action: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) {
            // A queued pre-interrupt/disconnect frame cannot change focus or listener/UI state.
            if (!player.isCurrentProducer(producer) || FocusMode.isActive(app)) return@launch
            if (!preparePlaybackTurn(player)) return@launch
            action()
        }
    }

    private fun preparePlaybackTurn(player: AudioPlayerHelper): Boolean {
        var allowed = false
        playbackDrainLease.replace {
            playbackDrainJob?.cancel()
            playbackDrainJob = null
            if (focusUnavailableForTurn) return@replace
            if (isFirstAudioChunkOfTurn) {
                if (!AudioFocusHelper.requestForSpeech(app, player)) {
                    focusUnavailableForTurn = true
                    player.onError?.invoke("Audio focus unavailable; text response remains available.")
                    return@replace
                }
                isFirstAudioChunkOfTurn = false
            }
            allowed = true
        }
        return allowed
    }

    private fun cancelPlaybackDrain() {
        val player = audioPlayer
        val revoke = {
            playbackDrainLease.revoke {
                playbackDrainJob?.cancel()
                playbackDrainJob = null
                AudioFocusHelper.abandon(app, player)
                isFirstAudioChunkOfTurn = true
                focusUnavailableForTurn = false
            }
        }
        // Teardown may already have cancelled viewModelScope; Main cleanup must still execute.
        if (Looper.myLooper() == Looper.getMainLooper()) revoke()
        else viewModelScope.launch(Dispatchers.Main) { revoke() }
    }

    private fun saveConversationMemory(userText: String, assistantText: String) {
        val cleanUser = userText.trim()
        val cleanAssistant = assistantText.trim()
        if (cleanUser == "🎤" && cleanAssistant.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (cleanUser.isNotBlank() && cleanUser != "🎤") {
                SimpleConversationStorage.insert(app, currentSessionId, "user", cleanUser)
            }
            if (cleanAssistant.isNotBlank()) {
                SimpleConversationStorage.insert(app, currentSessionId, "model", cleanAssistant)
            }
        }
    }

    /** onDestroy မှာ ခေါ်ပါ – callbacks ဖြုတ်မှ Context Leak မဖြစ်ဘူး */
    fun clearCallbacks() {
        geminiClient.invalidateAgentContext()
        cancelPlaybackDrain()
                listener = null
        audioRecorder.onAudioChunk = null
        audioRecorder.onError = null
        geminiClient.onConnected = null
        geminiClient.onSetupComplete = null
        geminiClient.onDisconnected = null
        geminiClient.onReconnecting = null
        geminiClient.onAudioChunk = null
        geminiClient.onAudioBytes = null
        geminiClient.onTranscript = null
        geminiClient.onAgentTranscript = null
        geminiClient.agentFactory = null
        geminiClient.onInputTranscript = null
        geminiClient.onOutputTranscript = null
        geminiClient.onTurnComplete = null
        geminiClient.onTaskStarted = null
        geminiClient.onInterrupted = null
        geminiClient.onError = null
        geminiClient.onLog = null

    }

    override fun onCleared() {
        cancelPlaybackDrain()
        phoneExecutor.invalidateApprovals()
        super.onCleared()
        clearCallbacks()
        try {
            audioRecorder.stop()
            audioPlayer.stop()
            geminiClient.disconnect()


        } catch (e: Exception) {
            Log.e("AssistantViewModel", "onCleared", e)
        }
    }

    fun setupCallbacks(listener: AssistantViewModelListener) {
        cancelPlaybackDrain()
        this.listener = listener
        geminiClient.invalidateAgentContext()
        geminiClient.agentFactory = if (isAutomationMode) null else { session, current, display ->
            com.example.voicebrainlive.agent.brain.AndroidConversationAgent.create(app, session, { current() && !isAutomationMode }, display)
        }
        geminiClient.onAgentTranscript = { text, current -> if (current()) this.listener?.onAgentTranscript(text, current) }
        val callbackClient = geminiClient
        callbackClient.onConnected = connected@{
            if (geminiClient !== callbackClient) return@connected
            val fresh = PhoneCommandExecutor(app)
            if (!callbackClient.bindAuthorizationSession(fresh) {
                phoneExecutor.invalidateApprovals()
                phoneExecutor = fresh
            }) return@connected
            this.listener?.onConnected()
        }
        geminiClient.onSetupComplete = {
            this.listener?.onSetupComplete()
            
            if (isAutomationMode) {
                Log.d("AssistantViewModel", "Automation mode - skipping beep and audio recorder")
            } else {
                // Play a short beep to indicate it's ready to listen (follows STREAM_MUSIC volume)
                // Start audio recorder AFTER the beep finishes so Android doesn't suppress the volume
                try {
                    // Vibrate briefly
                    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val vibratorManager = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                        vibratorManager.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    }
                    if (vibrator.hasVibrator()) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(50)
                        }
                    }

                                        // Vibration is sufficient feedback; avoid audible tones during
                    // background wake-word launches and repeated recognizer restarts.
                    startAudioRecorder()

                } catch (e: Exception) {
                    Log.e("AssistantViewModel", "ToneGen/Vibrate error", e)
                    startAudioRecorder()
                }
            }
        }
        geminiClient.onDisconnected = { _ -> cancelPlaybackDrain(); audioPlayer.stop(); phoneExecutor.invalidateApprovals(); this.listener?.onDisconnected() }
        geminiClient.onReconnecting = { attempt, delayMs -> cancelPlaybackDrain(); audioPlayer.stop(); phoneExecutor.invalidateApprovals(); this.listener?.onReconnecting(attempt, delayMs) }
        geminiClient.onAudioChunk = { base64 ->
            val player = audioPlayer
            val producer = if (!FocusMode.isActive(app)) player.captureStartedProducer() else null
            if (producer != null) enqueuePlayback(player, producer) {
                this.listener?.onAudioChunk(base64)
                playAudioChunk(player, producer, base64)
            }
        }
        geminiClient.onAudioBytes = { bytes ->
            val player = audioPlayer
            val producer = if (!FocusMode.isActive(app)) player.captureStartedProducer() else null
            if (producer != null) enqueuePlayback(player, producer) {
                this.listener?.onAudioBytes(bytes)
                playAudioBytes(player, producer, bytes)
            }
        }
        geminiClient.onTranscript = { text -> 
            currentAiResponse += text
            this.listener?.onTranscript(text) 
        }
        geminiClient.onInputTranscript = { text ->
            if (text.isNotBlank()) {
                currentUserInput = text
                UserUtteranceContext.set(text)
                this.listener?.onInputTranscript(text)
                scheduleVoiceFastPath(text)
            }
        }
        geminiClient.onTaskStarted = { this.listener?.onTaskStarted() }
        geminiClient.onTurnComplete = {
            if (currentAiResponse.isNotBlank()) {
                VoiceHistory.add(app, currentUserInput, currentAiResponse.trim())
                saveConversationMemory(currentUserInput, currentAiResponse)
                currentAiResponse = ""
                currentUserInput = "🎤"
            }
            onTurnCompleteOrInterrupted {
                this.listener?.onTurnComplete()
            }
        }
        geminiClient.onInterrupted = {
            cancelPlaybackDrain()
            if (currentAiResponse.isNotBlank()) {
                VoiceHistory.add(app, currentUserInput, currentAiResponse.trim() + " (Interrupted)")
                saveConversationMemory(currentUserInput, currentAiResponse.trim() + " (Interrupted)")
                currentAiResponse = ""
                currentUserInput = "🎤"
            }
            audioPlayer.clearQueue()
            onTurnCompleteOrInterrupted {
                this.listener?.onInterrupted()
            }
        }
        geminiClient.onError = { raw -> this.listener?.onError(raw) }
        geminiClient.onLog = { _ -> }
    }

    private fun scheduleVoiceFastPath(transcript: String) {
        if (isAutomationMode) return
        voiceFastPathJob?.cancel()
        voiceFastPathJob = viewModelScope.launch {
            delay(700)
            val phrase = transcript.trim()
            if (phrase.length < 4) return@launch
            val now = System.currentTimeMillis()
            if (phrase == lastVoiceFastPathPhrase && now - lastVoiceFastPathAt < 5000) return@launch

            val result = withContext(Dispatchers.IO) {
                LearnedIntentExecutor.tryExecuteConfirmedVoice(app, phrase, phoneExecutor)
            } ?: return@launch

            lastVoiceFastPathPhrase = phrase
            lastVoiceFastPathAt = now
            listener?.speakResultWithTTS(result.spokenHint.ifBlank { result.resultMessage })
        }
    }

    fun setAudioChunkForwarder(forward: (String) -> Unit) {
        audioRecorder.onAudioChunk = forward
    }

    fun setAudioRecorderErrorHandler(handler: (String) -> Unit) {
        audioRecorder.onError = handler
    }

    interface AssistantViewModelListener {
        fun onConnected()
        fun onSetupComplete()
        fun onDisconnected()
        fun onReconnecting(attempt: Int, delayMs: Long)
        fun onAudioChunk(base64: String)
        fun onAudioBytes(bytes: ByteArray)
        fun onTranscript(text: String)
        fun onAgentTranscript(text: String, current: () -> Boolean) {}
        fun onInputTranscript(text: String)
        fun onTaskStarted()
        fun onTurnComplete()
        fun onInterrupted()
        fun onError(raw: String)
        fun speakResultWithTTS(text: String)
    }
}
