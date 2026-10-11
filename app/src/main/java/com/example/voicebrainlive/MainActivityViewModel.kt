package com.example.voicebrainlive

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicebrainlive.audio.AudioFocusHelper
import com.example.voicebrainlive.audio.AudioPlayerHelper
import com.example.voicebrainlive.audio.AudioRecorderHelper
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.gemini.GeminiLiveClient
import com.example.voicebrainlive.learning.LearnedIntentExecutor
import com.example.voicebrainlive.learning.UserUtteranceContext
import com.example.voicebrainlive.memory.BehaviorMemory
import com.example.voicebrainlive.memory.PersonalSystemInstructionBuilder
import com.example.voicebrainlive.memory.SessionContextProvider
import com.example.voicebrainlive.memory.SimpleConversationStorage
import com.example.voicebrainlive.usecases.GetProactiveContextUseCase
import com.example.voicebrainlive.usecases.GetRecentContextUseCase
import com.example.voicebrainlive.usecases.SummarizeSessionUseCase
import com.example.voicebrainlive.usecases.VectorMemorySearchUseCase
import com.example.voicebrainlive.weather.WeatherNarrative
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State representing the assistant's current lifecycle and interaction status.
 */
sealed class AssistantState {
    object Idle : AssistantState()
    object Connecting : AssistantState()
    object Connected : AssistantState()
    object Listening : AssistantState()
    object Processing : AssistantState()
    object Speaking : AssistantState()
    data class Error(val message: String) : AssistantState()
}

/**
 * Phase 5.2: MainActivity ViewModel - Reactive StateFlow upgrade
 */
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript = _transcript.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage = _statusMessage.asStateFlow()

    fun updateTranscript(text: String) {
        _transcript.value = text
    }

    fun notifyUserChatMessage(text: String) {
        com.example.voicebrainlive.commands.ChatUiBridge.notifyUser(text)
    }

    fun updateStatus(text: String) {
        _statusMessage.value = text
    }

    fun updateState(state: AssistantState) {
        _uiState.update { state }
    }

    fun hasUsableGeminiApiKey(): Boolean {
        val key = SettingsActivity.getGeminiApiKey(app).trim()
        return key.isNotBlank() &&
            key != "YOUR_API_KEY" &&
            key != "YOUR_API_KEY_HERE" &&
            key.length >= 20
    }

    private val app = application

    lateinit var geminiClient: GeminiLiveClient
        private set
    lateinit var audioRecorder: AudioRecorderHelper
        private set
    lateinit var audioPlayer: AudioPlayerHelper
        private set
    lateinit var phoneExecutor: PhoneCommandExecutor
        private set
    lateinit var geminiNano: GeminiNanoHelper
        private set

    var isFirstAudioChunkOfTurn = true
    private var setupTimeoutJob: Job? = null
    private var memoryRefreshJob: Job? = null
    private var voiceFastPathJob: Job? = null
    private var lastVoiceFastPathPhrase = ""
    private var lastVoiceFastPathAt = 0L

    /** Listener - MainActivity က သတ်မှတ်မယ် */
    var listener: MainActivityViewModelListener? = null

    val isClientInitialized: Boolean
        get() = ::geminiClient.isInitialized

    fun getApiKey(): String = if (::geminiClient.isInitialized) geminiClient.getApiKey() else ""

    private var isInitializing = false
    private var pendingConnect = false

    // FIX: Declare variables BEFORE callbacks so they can be captured
    private var currentAiResponse = ""
    private var currentUserInput = "🎤"
    private val currentSessionId = "main_${System.currentTimeMillis()}"

    fun getChatSessionId(): String = currentSessionId

    private fun ensurePhoneExecutor(): PhoneCommandExecutor {
        if (!::phoneExecutor.isInitialized) {
            phoneExecutor = PhoneCommandExecutor(app)
        }
        return phoneExecutor
    }

    suspend fun executeLocalTextCommand(text: String): String = withContext(Dispatchers.IO) {
        UserUtteranceContext.set(text)
        val executor = ensurePhoneExecutor()
        tryFastPathCommand(text)?.let { return@withContext formatLocalCommandResult(it.resultMessage) }

        val match = OfflineCommandMatcher.match(text)
        if (match == null) {
            app.getString(R.string.please_add_api_key)
        } else {
            formatLocalCommandResult(executor.executeAsync(match.first, match.second, match.third))
        }
    }

    /**
     * Unified processor for typed text and chat messages.
     * Routes through fast-path, offline commands, or online Gemini Live API.
     */
    fun processUserText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        currentUserInput = trimmed
        UserUtteranceContext.set(trimmed)
        _transcript.value = trimmed
        com.example.voicebrainlive.commands.ChatUiBridge.notifyUser(trimmed)

        viewModelScope.launch {
            // 1. Check Fast-Path Learned Intent
            val fast = tryFastPathCommand(trimmed)
            if (fast != null) {
                val spoken = fast.spokenHint.ifBlank { fast.resultMessage }
                val display = formatLocalCommandResult(fast.resultMessage)
                _statusMessage.value = spoken
                _transcript.value = display
                com.example.voicebrainlive.commands.ChatUiBridge.notifyAssistant(spoken)
                saveConversationMemory(trimmed, spoken)
                VoiceHistory.add(app, trimmed, spoken)
                listener?.onSpeakOfflineResponse(display)
                return@launch
            }

            // 2. If Offline or No Valid Gemini Key, execute local offline commands
            if (!NetworkUtils.isOnline(app) || !hasUsableGeminiApiKey()) {
                val localResult = executeLocalTextCommand(trimmed)
                _statusMessage.value = "Offline"
                _transcript.value = localResult
                com.example.voicebrainlive.commands.ChatUiBridge.notifyAssistant(localResult)
                saveConversationMemory(trimmed, localResult)
                VoiceHistory.add(app, trimmed, localResult)
                listener?.onSpeakOfflineResponse(localResult)
                return@launch
            }

            // 3. Online with Gemini Live Client
            if (!isClientInitialized) {
                initClientWithCache()
            }
            if (!geminiClient.isConnected()) {
                connect(requestMic = false)
            }
            _uiState.update { AssistantState.Processing }
            _statusMessage.value = "Processing..."
            geminiClient.sendTextInput(trimmed)
        }
    }

    /**
     * If we already learned this phrase, run the command directly (no Gemini round-trip).
     */
    suspend fun tryFastPathCommand(text: String): LearnedIntentExecutor.FastPathResult? {
        if (!::phoneExecutor.isInitialized) return null
        return LearnedIntentExecutor.tryExecute(app, text, phoneExecutor)
    }

    private fun scheduleVoiceFastPath(transcript: String) {
        voiceFastPathJob?.cancel()
        voiceFastPathJob = viewModelScope.launch {
            delay(700)
            val state = _uiState.value
            if (state is AssistantState.Processing || state is AssistantState.Speaking) return@launch
            val phrase = transcript.trim()
            if (phrase.length < 4) return@launch
            val now = System.currentTimeMillis()
            if (phrase == lastVoiceFastPathPhrase && now - lastVoiceFastPathAt < 5000) return@launch

            val result = withContext(Dispatchers.IO) {
                LearnedIntentExecutor.tryExecuteConfirmedVoice(app, phrase, ensurePhoneExecutor())
            } ?: return@launch

            lastVoiceFastPathPhrase = phrase
            lastVoiceFastPathAt = now
            _statusMessage.value = result.spokenHint
            listener?.onSpeakOfflineResponse(formatLocalCommandResult(result.resultMessage))
            com.example.voicebrainlive.commands.ChatUiBridge.notifyAssistant(result.spokenHint)
        }
    }

    private fun formatLocalCommandResult(result: String): String {
        if (!result.startsWith("WEATHER_DATA|")) return result
        val parts = result.split("|")
        if (parts.size < 9) return result
        return WeatherNarrative.current(
            location = parts.getOrNull(1).orEmpty(),
            temperature = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            minTemp = parts.getOrNull(3)?.toIntOrNull() ?: 0,
            maxTemp = parts.getOrNull(4)?.toIntOrNull() ?: 0,
            weatherCode = parts.getOrNull(5)?.toIntOrNull() ?: 0,
            isRainExpected = parts.getOrNull(6)?.toBooleanStrictOrNull() ?: false,
            humidity = parts.getOrNull(7)?.toIntOrNull() ?: 0,
            windSpeed = parts.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
            rainSummary = parts.getOrNull(10).orEmpty()
        )
    }

    /**
     * Instant initialization using persistent cache.
     */
    fun initClientWithCache() {
        val cached = SessionContextProvider.getPersistentContext(app)
        initClientInternal(cached, "")
    }

    /**
     * Load context (memory + proactive) and initialize client.
     */
    suspend fun loadContextAndInit(): Pair<String, String> = withContext(Dispatchers.IO) {
        isInitializing = true
        val apiKey = SettingsActivity.getGeminiApiKey(app)
        val getRecent = GetRecentContextUseCase(app)
        val getProactive = GetProactiveContextUseCase(app)
        
        val memoryContext = getRecent(apiKey)
        val proactiveContext = getProactive()
        
        isInitializing = false
        Pair(memoryContext, proactiveContext)
    }

    /**
     * Initialize client with loaded context.
     */
    fun initClient(memoryContext: String, proactiveContext: String) {
        initClientInternal(memoryContext, proactiveContext)
        
        if (pendingConnect) {
            pendingConnect = false
            connect()
        }
    }

    private fun initClientInternal(memoryContext: String, proactiveContext: String) {

        if (::geminiClient.isInitialized) {
            if (geminiClient.isConnected()) return
            try { geminiClient.disconnect() } catch (_: Exception) { }
        }
        val apiKey = SettingsActivity.getGeminiApiKey(app)
        val voice = SettingsActivity.getVoice(app)
        val geminiModel = SettingsActivity.getGeminiModel(app)
        if (::phoneExecutor.isInitialized) phoneExecutor.invalidateApprovals()
        phoneExecutor = PhoneCommandExecutor(app)

        val gemPrompt = phoneExecutor.getActiveGemSystemPrompt()
        val basePrefs = UserPreferences.toSystemInstructionString(app)
        val personalInstruction = PersonalSystemInstructionBuilder.build(app, basePrefs)

        val systemExtra = personalInstruction +
            (if (memoryContext.isNotBlank()) "\n\n$memoryContext" else "") +
            (if (gemPrompt.isNotBlank()) "\n\n[Active Assistant Mode]: $gemPrompt" else "") +
            (if (proactiveContext.isNotBlank()) "\n\n[Context]: $proactiveContext" else "")

        _statusMessage.value = "Initializing memory..."


        geminiClient = GeminiLiveClient(apiKey, model = geminiModel, voice = voice, systemInstructionExtra = systemExtra) { cmd, target, value ->
            _uiState.update { AssistantState.Processing }
            val result = phoneExecutor.executeAsync(cmd, target, value)
            
            if (cmd in listOf("get_battery_status", "get_wifi_status", "get_bluetooth_status")) {
                listener?.onSpeakOfflineResponse(result)
            }
            if (cmd in listOf("switch_gem", "change_gem", "use_gem")) {
                listener?.onSyncGemUi()
            }

            result
        }
        geminiClient.onSetupSuccess = { workingModel ->
            SettingsActivity.saveGeminiModel(app, workingModel)
        }
        audioRecorder = AudioRecorderHelper().apply {
            useVoiceCommunicationForNoisyEnv = SettingsActivity.isNoisyEnvMode(app)
        }
        audioPlayer = AudioPlayerHelper()
        geminiNano = GeminiNanoHelper(app)

        setupInternalCallbacks()
        startMemoryRefreshLoop()
    }

    /**
     * Periodically refreshes the memory context and reconnects while idle
     * to keep the session fresh and warmed up. (User request: trigger when not asking).
     */
    fun startMemoryRefreshLoop() {
        memoryRefreshJob?.cancel()
        memoryRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(15 * 60 * 1000) // Every 15 mins
                if (uiState.value == AssistantState.Connected || uiState.value == AssistantState.Idle) {
                    Log.d("Memory", "Idle memory refresh triggered (Background)")
                    // Silently refresh context
                    val (mem, pro) = loadContextAndInit()
                    initClient(mem, pro)
                    connect(requestMic = false)
                }
            }
        }
    }

    private var isMicRequestedOnSetup = false

    fun connect(requestMic: Boolean = true) {
        if (isClientInitialized && geminiClient.isConnected()) {
            if (requestMic && uiState.value != AssistantState.Listening) {
                startListeningWithFeedback()
            }
            return
        }
        
        isMicRequestedOnSetup = requestMic
        if (!isClientInitialized) {
            initClientWithCache()
        }
        
        _uiState.update { AssistantState.Connecting }
        _statusMessage.value = "Establishing setup..."
        Log.d("VM_DEBUG", ">>> connect(requestMic=$requestMic) called")

        BehaviorMemory.recordSessionStarted(app)
        geminiClient.connect()
    }

    fun disconnect() {
        if (::phoneExecutor.isInitialized) phoneExecutor.invalidateApprovals()
        if (isClientInitialized) {
            setupTimeoutJob?.cancel()
            try { audioRecorder.stop() } catch (_: Exception) {}
            geminiClient.disconnect()
            audioPlayer.stop()
            _uiState.update { AssistantState.Idle }
            _statusMessage.value = "Tap to start"
        }
    }


    private fun setupInternalCallbacks() {

        val gc = geminiClient
        
        // Declare variables FIRST so they can be captured by all callbacks
        currentAiResponse = ""
        currentUserInput = "🎤"
        
        gc.onConnected = connected@{
            if (geminiClient !== gc) return@connected
            val fresh = PhoneCommandExecutor(app)
            if (!gc.bindAuthorizationSession(fresh) {
                if (::phoneExecutor.isInitialized) phoneExecutor.invalidateApprovals()
                phoneExecutor = fresh
            }) return@connected
            Log.d("VM_DEBUG", ">>> onConnected!")
            audioPlayer.start()
            _statusMessage.value = "Establishing setup..."

            setupTimeoutJob?.cancel()
            setupTimeoutJob = viewModelScope.launch {
                delay(10000)
                if (uiState.value == AssistantState.Connecting) {
                    Log.e("MainActivityVM", "Setup timed out after 10s")
                    _statusMessage.value = "Setup timed out. Try again."
                    _uiState.update { AssistantState.Idle }
                    disconnect()
                }
            }
            
            listener?.updateConnectivity(true)
        }
        
        gc.onSetupComplete = {
            Log.d("VM_DEBUG", ">>> onSetupComplete! isMicRequested=$isMicRequestedOnSetup")
            setupTimeoutJob?.cancel()
            
            if (isMicRequestedOnSetup) {
                isMicRequestedOnSetup = false
                startListeningWithFeedback()
            } else {
                _statusMessage.value = "Ready"
                _uiState.update { AssistantState.Connected }
            }
        }
        
        gc.onDisconnected = {
            if (::phoneExecutor.isInitialized) phoneExecutor.invalidateApprovals()
            Log.d("VM_DEBUG", ">>> onDisconnected!")
            // SAVE ON DISCONNECT - backup save
            if (currentAiResponse.isNotBlank() || currentUserInput != "🎤") {
                Log.d("VM_DEBUG", "    Saving on disconnect...")
                VoiceHistory.add(app, currentUserInput, currentAiResponse.trim())
                saveConversationMemory(currentUserInput, currentAiResponse)
                currentAiResponse = ""
                currentUserInput = "🎤"
            }

            setupTimeoutJob?.cancel()
            audioPlayer.stop()
            _statusMessage.value = "Disconnected"
            _uiState.update { AssistantState.Idle }
            listener?.updateConnectivity(false)
        }
        
        gc.onReconnecting = { attempt, delayMs ->
            if (::phoneExecutor.isInitialized) phoneExecutor.invalidateApprovals()
            Log.d("VM_DEBUG", ">>> onReconnecting! attempt=$attempt")
            _statusMessage.value = "Reconnecting... ($attempt/5)"
            _uiState.update { AssistantState.Connecting }
        }

        gc.onError = { message ->
            setupTimeoutJob?.cancel()
            Log.e("MainActivityVM", "Gemini Error: $message")
            _statusMessage.value = if (message == GeminiLiveClient.ERROR_API_KEY_REQUIRED) {
                app.getString(R.string.please_add_api_key)
            } else {
                ErrorUtils.toUserMessage(app, message)
            }
            _uiState.update { AssistantState.Error(message) }
            if (message.contains("Invalid API key") || message.contains("not found")) {
                disconnect()
            }
        }

        gc.onAudioChunk = { base64 -> playAudioChunk(base64) }
        gc.onAudioBytes = { bytes -> playAudioBytes(bytes) }

        gc.onTranscript = { text -> 
            Log.d("VM_DEBUG", ">>> onTranscript: ${text.take(50)}...")
            currentAiResponse += text
            _transcript.value = text 
        }
        gc.onInputTranscript = { text ->
            if (text.isNotBlank()) {
                currentUserInput = text
                UserUtteranceContext.set(text)
                com.example.voicebrainlive.commands.ChatUiBridge.notifyUser(text)
                scheduleVoiceFastPath(text)
            }
        }

        gc.onTaskStarted = {
            Log.d("VM_DEBUG", ">>> onTaskStarted!")
            _statusMessage.value = "Processing..."
            _uiState.update { AssistantState.Processing }
        }

        gc.onTurnComplete = {
            Log.d("VM_DEBUG", ">>> onTurnComplete! response len=${currentAiResponse.length}")
            val assistantText = currentAiResponse.trim()
            if (assistantText.isNotBlank()) {
                com.example.voicebrainlive.commands.ChatUiBridge.notifyAssistant(assistantText)
            }
            VoiceHistory.add(app, currentUserInput, assistantText)
            saveConversationMemory(currentUserInput, assistantText)
            currentAiResponse = ""
            currentUserInput = "🎤"
            onTurnCompleteOrInterrupted()
            _statusMessage.value = "Listening..."
        }
        
        gc.onInterrupted = {
            Log.d("VM_DEBUG", ">>> onInterrupted!")
            if (currentAiResponse.isNotBlank()) {
                VoiceHistory.add(app, currentUserInput, currentAiResponse.trim() + " (Interrupted)")
                saveConversationMemory(currentUserInput, currentAiResponse.trim() + " (Interrupted)")
                currentAiResponse = ""
                currentUserInput = "🎤"
            }
            audioPlayer.clearQueue()
            AudioFocusHelper.abandon(app)
            isFirstAudioChunkOfTurn = true
            _statusMessage.value = "Listening..."
            _uiState.update { AssistantState.Listening }
        }

        audioRecorder.onAudioChunk = { base64 ->
            if (gc.isConnected() && _uiState.value != AssistantState.Speaking && !audioPlayer.isActuallySpeaking() && listener?.isTtsSpeaking() != true) {
                gc.sendAudioChunk(base64)
            }
        }
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


    fun startListeningWithFeedback() {
        if (_uiState.value == AssistantState.Listening) return
        
        _statusMessage.value = "Listening..."
        _uiState.update { AssistantState.Listening }

        try {
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
            
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)

            viewModelScope.launch {
                delay(160)
                toneGen.release()
                startAudioRecorder()
            }
        } catch (e: Exception) {
            Log.e("MainActivityVM", "Feedback/Mic error", e)
            startAudioRecorder()
        }
    }

    fun stopListening() {
        if (_uiState.value != AssistantState.Listening && _uiState.value != AssistantState.Speaking) return
        
        try {
            audioRecorder.stop()
            audioPlayer.stop()
            _statusMessage.value = "Ready"
            _uiState.update { AssistantState.Connected }
        } catch (e: Exception) {
            Log.e("MainActivityVM", "stopListening", e)
        }
    }

    fun startAudioRecorder() {
        Log.d("VM_DEBUG", ">>> startAudioRecorder()")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioRecorder.start(viewModelScope)
            } catch (e: Exception) {
                Log.e("MainActivityVM", "Mic start: ${e.message}")
                _uiState.update { AssistantState.Error(e.message ?: "Mic Error") }
            }
        }
    }


    fun playAudioChunk(base64: String) {
        if (!FocusMode.isActive(app)) {
            _uiState.update { AssistantState.Speaking }
            if (isFirstAudioChunkOfTurn) {
                AudioFocusHelper.requestForSpeech(app)
                isFirstAudioChunkOfTurn = false
            }
            if (!audioPlayer.isPlaying()) {
                audioPlayer.start()
            }
            try { audioPlayer.playBase64(base64) } catch (e: Exception) { Log.e("MainActivityVM", "playBase64", e) }
        }
    }


    fun playAudioBytes(bytes: ByteArray) {
        if (!FocusMode.isActive(app)) {
            _uiState.update { AssistantState.Speaking }
            if (isFirstAudioChunkOfTurn) {
                AudioFocusHelper.requestForSpeech(app)
                isFirstAudioChunkOfTurn = false
            }
            if (!audioPlayer.isPlaying()) {
                audioPlayer.start()
            }
            try { audioPlayer.playRaw(bytes) } catch (e: Exception) { Log.e("MainActivityVM", "playRaw", e) }
        }
    }


    fun onTurnCompleteOrInterrupted() {
        viewModelScope.launch(Dispatchers.IO) {
            var timeout = 0
            while (audioPlayer.isActuallySpeaking() && timeout < 25000) {
                kotlinx.coroutines.delay(100)
                timeout += 100
            }
            kotlinx.coroutines.delay(300)
            AudioFocusHelper.abandon(app)
            isFirstAudioChunkOfTurn = true
            _uiState.update { AssistantState.Listening }
        }
    }


    fun summarizeOnDisconnect() {
        viewModelScope.launch {
            val apiKey = SettingsActivity.getGeminiApiKey(app)
            SummarizeSessionUseCase(app).invoke(apiKey)
        }
    }


    fun onSpeakOfflineResponse(text: String) {
        listener?.onSpeakOfflineResponse(text)
    }


    fun triggerVisionCapture() {
        listener?.onCaptureVisionFrame()
    }


    fun clearCallbacks() {
        listener = null
        audioRecorder.onAudioChunk = null
        audioRecorder.onError = null
    }


    override fun onCleared() {
        if (::phoneExecutor.isInitialized) phoneExecutor.invalidateApprovals()
        super.onCleared()
        clearCallbacks()
        memoryRefreshJob?.cancel()
        voiceFastPathJob?.cancel()
        try {
            if (::audioRecorder.isInitialized) audioRecorder.stop()
            if (::audioPlayer.isInitialized) audioPlayer.stop()
            if (::geminiClient.isInitialized) geminiClient.disconnect()
        } catch (e: Exception) {
            Log.e("MainActivityVM", "onCleared", e)
        }
    }

    interface MainActivityViewModelListener {
        fun onSpeakOfflineResponse(text: String)
        fun onSyncGemUi()
        fun onCaptureVisionFrame()
        fun updateConnectivity(connected: Boolean)
        fun isTtsSpeaking(): Boolean
    }
}
