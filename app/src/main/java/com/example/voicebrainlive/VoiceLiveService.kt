package com.example.voicebrainlive


import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.voicebrainlive.utils.DraggableOverlayContainer
import com.example.voicebrainlive.utils.ServiceLifecycleOwner
import com.example.voicebrainlive.ui.components.OverlayLiveCardScreen
import com.example.voicebrainlive.ui.components.OverlayRobotCornerScreen
import com.example.voicebrainlive.ui.components.VoiceStatus

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.audio.AudioFocusHelper
import com.example.voicebrainlive.audio.AudioPlayerHelper
import com.example.voicebrainlive.audio.AudioRecorderHelper
import com.example.voicebrainlive.commands.ActionDispatcher
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.gemini.GeminiLiveClient
import com.example.voicebrainlive.memory.PersonalSystemInstructionBuilder
import com.example.voicebrainlive.memory.SimpleConversationStorage
import com.example.voicebrainlive.weather.NaturalWeatherSpeaker
import com.example.voicebrainlive.tts.TtsUnifiedInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.example.voicebrainlive.voice.VoiceProfileManager
import com.example.voicebrainlive.voice.VoiceVerificationGate

/**
 * Foreground Service that owns the Gemini Live connection and handles background overlay drawing.
 */
class VoiceLiveService : Service() {

    enum class OverlayState {
        HIDDEN,
        PILL,
        CARD
    }

    var isStartedFromPowerButton = false

    private val composeLifecycleOwner = ServiceLifecycleOwner()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var windowManager: WindowManager? = null
    private var pillView: View? = null
    private var cardView: View? = null
    private var pillComposeView: ComposeView? = null
    private var cardComposeView: ComposeView? = null

    private var pillParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    private var currentOverlayState = OverlayState.HIDDEN
    private var voiceVerificationGate: VoiceVerificationGate? = null

    // live voice session components
    var geminiClient: GeminiLiveClient? = null
        private set
    var audioRecorder: AudioRecorderHelper? = null
        private set
    var audioPlayer: AudioPlayerHelper? = null
        private set
    var phoneExecutor: PhoneCommandExecutor? = null
        private set

    var isConnected = false
        private set
    var isMuted by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var currentStatusText by mutableStateOf("Connecting...")
        private set
    var lastTranscriptText by mutableStateOf("")
        private set

    var currentNotifApp by mutableStateOf("")
        private set
    var currentNotifSender by mutableStateOf("")
        private set
    var currentNotifText by mutableStateOf("")
        private set
    var currentNotifPkg by mutableStateOf("")
        private set

    private var isNotificationSession = false
    private var currentProactiveType: String? = null
    private var isNotificationReplyExecuted = false
    private var notificationReplyAuthorized = false
    private var isNotificationAnnouncementCompleted = false
    private var hasUserSpokenInNotificationSession = false

    private var isFirstAudioChunkOfTurn = true
    private var focusUnavailableForTurn = false
    private val playbackFocusLock = Any()
    private var currentUserInput = "🎤"
    private var currentAiResponse = ""
    private val currentSessionId = "assistant_${System.currentTimeMillis()}"
    private var pendingProactiveMessage: String? = null
    
    // For early audio capture to eliminate latency when clicking the bubble
        private var isGeminiSetupComplete = false
    val isGeminiReady: Boolean get() = isGeminiSetupComplete

    private val preSetupAudioBuffer = mutableListOf<String>()
    private var micRestartAttempts = 0
    private var micRestartJob: Job? = null
    private var isServiceDestroyed = false

    private var waveAnimator: ValueAnimator? = null
    private var cardOrbAnimator1: ValueAnimator? = null
    private var cardOrbAnimator2: ValueAnimator? = null
    private var cardOrbAnimator3: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        composeLifecycleOwner.onCreate()
        Log.d(TAG, "VoiceLiveService onCreate")
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()

        // Hide persistent idle floating bubble while VoiceLiveService is active
        try {
            FloatingBubbleService.instance?.hideBubble()
            val hideBubbleIntent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_HIDE_BUBBLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(hideBubbleIntent)
            } else {
                startService(hideBubbleIntent)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Ignored bubble hide error: ${e.message}")
        }
        
        // Stop wake word service to free mic
        try {
            stopService(Intent(this, WakeWordListenerService::class.java))
        } catch (e: Exception) {
            Log.d(TAG, "Ignored stop service error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_START -> {
                var proactiveMsg: String? = null
                val startWithCard = intent.getBooleanExtra("EXTRA_START_WITH_CARD", false)
                if (intent.hasExtra("EXTRA_POWER_BUTTON")) {
                    isStartedFromPowerButton = intent.getBooleanExtra("EXTRA_POWER_BUTTON", false)
                }
                    if (intent.hasExtra("proactive_message")) {
                        val rawMsg = intent.getStringExtra("proactive_message") ?: ""
                        val type = intent.getStringExtra("proactive_type")
                        currentNotifApp = intent.getStringExtra("extra_app_label") ?: ""
                        currentNotifSender = intent.getStringExtra("extra_sender") ?: ""
                        currentNotifText = intent.getStringExtra("extra_message_text") ?: ""
                        currentNotifPkg = intent.getStringExtra("extra_package") ?: ""
                        currentProactiveType = type
                        isNotificationSession = !type.isNullOrBlank() && (
                            type == com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.PROACTIVE_TYPE_MESSENGER_REPLY ||
                            type == com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.PROACTIVE_TYPE_GENERAL_NOTIFICATION
                        )
                        isNotificationReplyExecuted = false
                        notificationReplyAuthorized = false
                        isNotificationAnnouncementCompleted = false
                        hasUserSpokenInNotificationSession = false
                        proactiveMsg = if (!type.isNullOrBlank()) {

                            com.example.voicebrainlive.usecases.ProactivePromptHelper.buildPrompt(rawMsg, type)
                        } else {
                            rawMsg
                        }
                    } else {
                        // User manual session (e.g. from Robot Bubble or manual UI launch)
                        isNotificationSession = false
                        currentProactiveType = null
                        isNotificationReplyExecuted = false
                        notificationReplyAuthorized = false
                        isNotificationAnnouncementCompleted = false
                        hasUserSpokenInNotificationSession = false
                        currentNotifApp = ""
                        currentNotifSender = ""
                        currentNotifText = ""
                        currentNotifPkg = ""
                        cancelSilenceTimeout()
                        cancelIdleTimer()
                    }
                // Every new manual/notification handoff revokes old agent context/review before reusing the socket.
                geminiClient?.invalidateAgentContext()
                if (geminiClient == null) {
                    initLiveSession(proactiveMsg)
                } else if (proactiveMsg != null) {
                    // Send to existing session
                    serviceScope.launch {
                        geminiClient?.sendTextInput(proactiveMsg)
                    }
                }
                if (startWithCard && Settings.canDrawOverlays(this)) {
                    updateOverlay(OverlayState.CARD)
                } else {
                    updateOverlayVisibility()
                }
            }
            ACTION_SHOW_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    updateOverlay(OverlayState.CARD)
                } else {
                    Toast.makeText(this, "Draw over other apps permission is required for overlay", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_STOP -> {
                stopLiveSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        phoneExecutor?.invalidateApprovals()
        isServiceDestroyed = true
        micRestartJob?.cancel()
        super.onDestroy()

        try {
            composeLifecycleOwner.onDestroy()
            Log.d(TAG, "VoiceLiveService onDestroy")
            stopLiveSession()
            hideOverlays()
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error in VoiceLiveService onDestroy cleanup: ${e.message}")
        } finally {
            instance = null

            // Re-show floating bubble if enabled in settings
            if (BubblePrefs.isEnabled(this)) {
                try {
                    val bubbleIntent = Intent(this, FloatingBubbleService::class.java).apply {
                        action = FloatingBubbleService.ACTION_SHOW_BUBBLE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(bubbleIntent)
                    } else {
                        startService(bubbleIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore floating bubble: ${e.message}")
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "voice_live_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Voice Assistant Live",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val showOverlayIntent = Intent(this, VoiceLiveService::class.java).apply {
            action = ACTION_SHOW_OVERLAY
        }
        val pendingIntent = PendingIntent.getService(
            this, 0,
            showOverlayIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon = try {
            android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) { null }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Voice Assistant Active")
            .setContentText("နိုတီကုဒ် နှိပ်ပြီး controller ဖွင့်ရန်")
            .setSmallIcon(R.drawable.ic_live_waves)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)

        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }

        val notification = notificationBuilder.build()

        val serviceType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serviceType != 0) {
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initLiveSession(initialMessage: String? = null) {
        pendingProactiveMessage = initialMessage
        Log.w(TAG, "Initializing live session")
        val apiKey = SettingsActivity.getGeminiApiKey(this)
        val voice = SettingsActivity.getVoice(this)
        val geminiModel = SettingsActivity.getGeminiModel(this)

        val basePrefs = UserPreferences.toSystemInstructionString(this)
        val systemExtra = PersonalSystemInstructionBuilder.build(this, basePrefs)

        phoneExecutor = PhoneCommandExecutor(this)
        audioRecorder = AudioRecorderHelper().apply {
            useVoiceCommunicationForNoisyEnv = SettingsActivity.isNoisyEnvMode(this@VoiceLiveService)
        }
        audioPlayer = AudioPlayerHelper().apply {
            onError = { message ->
                serviceScope.launch(Dispatchers.Main) {
                    currentStatusText = message
                    VoiceLiveServiceManager.listener?.onError(message)
                    updateOverlayUi()
                }
            }
        }

        val useVoiceProfile = SettingsActivity.isVoiceProfileEnabled(this) &&
            VoiceProfileManager(this).isTrained()
        if (useVoiceProfile) {
            voiceVerificationGate = VoiceVerificationGate(
                context = this,
                onForward = { base64 ->
                    val player = audioPlayer
                    if (player != null && com.example.voicebrainlive.voice.VoiceInputPolicy.mayForward(
                            audioRecorder?.isRecording() == true && isConnected,
                            isMuted || isPaused, player.isActuallySpeaking(), TtsUnifiedInterface.isAnyInstanceSpeaking(),
                            com.example.voicebrainlive.voice.VoiceInteractionPreferences.interruptionEnabled(this@VoiceLiveService))) {
                        geminiClient?.sendAudioChunk(base64)
                    }
                },
                onReject = { _ ->
                    serviceScope.launch(Dispatchers.Main) {
                        lastTranscriptText = "ငါ မင်းကို မှတ်မိဘူး"
                        updateOverlayUi()
                        VoiceLiveServiceManager.listener?.onTranscript("ငါ မင်းကို မှတ်မိဘူး")
                        Toast.makeText(this@VoiceLiveService, "ငါ မင်းကို မှတ်မိဘူး", Toast.LENGTH_SHORT).show()
                    }
                },
                scope = serviceScope
            )
        }

        geminiClient = GeminiLiveClient(apiKey, model = geminiModel, voice = voice, systemInstructionExtra = systemExtra) { cmd, target, value ->
            Log.d(TAG, "Command received: $cmd, target=$target, value=$value")
            val replyCmds = setOf(
                "send_messenger", "send_whatsapp", "send_sms", "send_facebook_message", "messenger", "whatsapp"
            )
            var effectiveValue = value

            if (cmd in replyCmds) {
                if (isNotificationSession) {
                    // Wait briefly (up to 500ms) in case user speech transcript is in flight
                    if (!hasUserSpokenInNotificationSession && (currentUserInput.isBlank() || currentUserInput == "🎤")) {
                        var waited = 0
                        while (!hasUserSpokenInNotificationSession && (currentUserInput.isBlank() || currentUserInput == "🎤") && waited < 500) {
                            delay(50)
                            waited += 50
                        }
                    }

                    // 1. STRICT ZERO-UNSOLICITED SENDING ON SILENCE
                    if (!hasUserSpokenInNotificationSession || currentUserInput.isBlank() || currentUserInput == "🎤") {
                        Log.w(TAG, "Blocked unsolicited reply in notification session: user is silent / has not spoken")
                        return@GeminiLiveClient "FAILED: အသုံးပြုသူက စာပြန်ရန် အသံဖြင့် မပြောဆိုထားပါ။ မည်သည့်စာမျှ အလိုအလျောက် ပို့၍မရပါ။"
                    }

                    // 2. DISMISSALS / CANCELLATION
                    val userSpeech = currentUserInput.trim().lowercase()
                    val burmeseDismissals = listOf(
                        "မပို့နဲ့", "မပြောနဲ့", "မပြောပါနဲ့", "ရပြီ", "တော်ပြီ", "တော်ပါပြီ",
                        "ပိတ်လိုက်", "ပိတ်တော့", "မလိုဘူး", "မလိုတော့ဘူး", "မပြောတော့ဘူး", "ကျေးဇူး"
                    )
                    val englishDismissalPhrases = listOf(
                        "don't reply", "dont reply", "don't send", "dont send"
                    )
                    val lowerWords = userSpeech.split("\\s+".toRegex()).map { it.trim(',', '.', '!', '?', '"', '\'') }
                    val isDismissed = burmeseDismissals.any { userSpeech.contains(it) } ||
                            englishDismissalPhrases.any { userSpeech.contains(it) } ||
                            lowerWords.any { it in setOf("no", "stop", "cancel") }

                    if (isDismissed && !ActionDispatcher.isExplicitReplyRequest(currentUserInput)) {
                        Log.w(TAG, "Blocked reply in notification session: user dismissed ($currentUserInput)")
                        return@GeminiLiveClient "FAILED: အသုံးပြုသူက စာမပို့ရန် ပယ်ဖျက်ထားပါသည်။"
                    }

                    // 3. USER AUTHORIZATION / INTENT
                    val isExplicit = notificationReplyAuthorized || ActionDispatcher.isExplicitReplyRequest(currentUserInput)
                    if (!isExplicit) {
                        Log.w(TAG, "Blocked reply in notification session: user did not command to reply ($currentUserInput)")
                        return@GeminiLiveClient "FAILED: မက်ဆင်ဂျာ notification ရောက်ရုံဖြင့် စာမပို့ပါ။ သင်က ပြန်စာပို့ရန် အမိန့်ပေးပါ။"
                    }

                    // 4. EXACT USER WORDS ENFORCEMENT
                    // Extract the user's actual spoken words from the microphone transcript
                    val cleanedUserSpeech = ActionDispatcher.cleanReplyMessage(currentUserInput)
                    val recipient = currentNotifSender.takeIf { it.isNotBlank() } ?: target

                    var candidate = cleanedUserSpeech
                    if (!recipient.isNullOrBlank() && candidate != null) {
                        val currentCand = candidate
                        val recipientPrefixes = listOf(
                            "$recipient ကို ", "$recipient ဆီကို ", "$recipient ဆီ ",
                            "$recipient ကို", "$recipient ဆီကို", "$recipient ဆီ"
                        )
                        for (rp in recipientPrefixes) {
                            if (currentCand.startsWith(rp, ignoreCase = true)) {
                                candidate = currentCand.substring(rp.length).trim()
                                break
                            }
                        }
                    }

                    if (!candidate.isNullOrBlank()) {
                        effectiveValue = candidate
                    } else if (!value.isNullOrBlank()) {
                        effectiveValue = value
                    }

                    if (effectiveValue.isNullOrBlank()) {
                        Log.w(TAG, "Blocked reply in notification session: empty reply message")
                        return@GeminiLiveClient "FAILED: ပို့လိုသော စာသားကို အသုံးပြုသူက ပြောဆိုထားခြင်းမရှိပါ။"
                    }

                    // 5. ECHO GUARD: Never send the incoming notification message back to sender
                    if (currentNotifText.isNotBlank()) {
                        val normReply = effectiveValue.trim().trim('"', '\'', '“', '”').replace("\\s+".toRegex(), " ")
                        val normNotif = currentNotifText.trim().trim('"', '\'', '“', '”').replace("\\s+".toRegex(), " ")
                        if (normReply.equals(normNotif, ignoreCase = true)) {
                            Log.w(TAG, "Blocked echo reply: message is identical to incoming notification")
                            return@GeminiLiveClient "FAILED: အဝင်စာတိုအား echo ပြန်ပို့ခြင်းကို ပိတ်ပင်ထားပါသည်။"
                        }
                    }

                    isNotificationReplyExecuted = true
                    notificationReplyAuthorized = false
                } else {
                    // Manual user session
                    if (effectiveValue.isNullOrBlank() && currentUserInput.isNotBlank() && currentUserInput != "🎤") {
                        effectiveValue = ActionDispatcher.cleanReplyMessage(currentUserInput)
                    } else if (!effectiveValue.isNullOrBlank()) {
                        effectiveValue = effectiveValue
                    }
                }
            }

            val effectiveTarget = if (cmd in replyCmds && target.isNullOrBlank() && currentNotifSender.isNotBlank()) {
                currentNotifSender
            } else {
                target
            }

            val networkCmds = listOf(
                "get_weather", "weather", "search_coupang", "search_aliexpress", "web_search", "search", "search_news",
                "send_sms", "send_messenger", "send_facebook_message", "send_whatsapp"
            )
            val result = try {
                phoneExecutor?.executeAsync(cmd, target, value) ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed: ${e.message}")
                "FAILED: ${e.message}"
            }

            // Speak battery / wifi status
            if (cmd in listOf("get_battery_status", "get_wifi_status", "get_bluetooth_status")) {
                VoiceLiveServiceManager.listener?.speakResultWithTTS(result)
            } else if (cmd in listOf("get_weather", "weather")) {
                serviceScope.launch(Dispatchers.Main) {
                    val weatherSpeaker = NaturalWeatherSpeaker(this@VoiceLiveService)
                    if (result.startsWith("WEATHER_DATA|")) {
                        val parts = result.split("|")
                        if (parts.size >= 10) {
                            weatherSpeaker.speakWeather(
                                temperature = parts[2].toIntOrNull() ?: 0,
                                minTemp = parts[3].toIntOrNull() ?: 0,
                                maxTemp = parts[4].toIntOrNull() ?: 0,
                                weatherCode = parts[5].toIntOrNull() ?: 0,
                                location = parts[1],
                                isRainExpected = parts[6].toBoolean(),
                                humidity = parts[7].toIntOrNull() ?: 0,
                                windSpeed = parts[8].toDoubleOrNull() ?: 0.0,
                                rainSummary = parts.getOrNull(10).orEmpty()
                            )
                        } else {
                            weatherSpeaker.speakSimple(result)
                        }
                    } else {
                        weatherSpeaker.speakSimple(result)
                    }
                    delay(12000)
                    weatherSpeaker.shutdown()
                }
            }
            result
        }

        setupClientCallbacks()
        audioPlayer?.start()
        startAudioRecorder() // Start capturing audio immediately to buffer early speech
        geminiClient?.connect()
    }

    private var idleTimeoutRunnable: Runnable? = null
    private var silenceTimeoutRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun cancelIdleTimer() {
        idleTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        idleTimeoutRunnable = null
    }

    private fun scheduleIdleTimer() {
        cancelIdleTimer()
        // Disabled auto-timeout based on user request (သူ့အလိုလို မပျောက်စေဘဲ ဖြစ်ချင်တယ်).
        // Let the user manually close the service.
    }

    private fun cancelSilenceTimeout() {
        silenceTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceTimeoutRunnable = null
    }

    private fun scheduleNotificationAutoClose(delayMs: Long) {
        cancelSilenceTimeout()
        silenceTimeoutRunnable = Runnable {
            if (audioPlayer?.isActuallySpeaking() == true || TtsUnifiedInterface.isAnyInstanceSpeaking() == true) {
                Log.d(TAG, "Audio playback still in progress, extending auto-close check...")
                mainHandler.postDelayed(silenceTimeoutRunnable!!, 1000L)
                return@Runnable
            }
            Log.d(TAG, "Notification session auto-closing cleanly after announcement/reply.")
            stopLiveSessionAndClose()
        }
        mainHandler.postDelayed(silenceTimeoutRunnable!!, delayMs)
    }

    private fun stopLiveSessionAndClose() {
        try {
            phoneExecutor?.invalidateApprovals()
            geminiClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting client on auto-close", e)
        }
        stopLiveSession()
        hideOverlays()
        stopSelf()
    }

    private fun scheduleSilenceTimeout() {
        cancelSilenceTimeout()
        if (isNotificationSession) {
            val timeoutSeconds = SettingsActivity.getNotificationAutoTimeoutSeconds(this).coerceIn(6, 30)
            scheduleNotificationAutoClose(timeoutSeconds * 1000L)
            return
        }

        // For manual user sessions (clicking the doll / robot bubble):
        // Auto-close on silence is disabled so the user has full control and is not cut off.
    }

    private fun isExplicitReplyRequest(text: String): Boolean {
        return ActionDispatcher.isExplicitReplyRequest(text)
    }

    private fun setupClientCallbacks() {

        val client = geminiClient ?: return
        // Manual conversation only. Notification sessions cannot arm/persist agent proposals.
        if (!isNotificationSession) client.agentFactory = { session, current, display ->
            com.example.voicebrainlive.agent.brain.AndroidConversationAgent.create(this, session, { current() && !isNotificationSession }, display)
        }

        client.onConnected = connected@{
            if (geminiClient !== client) return@connected
            val fresh = PhoneCommandExecutor(this)
            if (!client.bindAuthorizationSession(fresh) {
                phoneExecutor?.invalidateApprovals()
                phoneExecutor = fresh
            }) return@connected
            isConnected = true
            currentStatusText = "Connected"
            Log.d(TAG, "Gemini Connected")
            VoiceLiveServiceManager.listener?.onConnected()
            updateOverlayUi()
        }

        client.onSetupComplete = {
            isGeminiSetupComplete = true
            currentStatusText = "Listening..."
            Log.d(TAG, "Gemini Setup Complete. Flushing buffered audio: ${preSetupAudioBuffer.size} chunks")
            VoiceLiveServiceManager.listener?.onSetupComplete()
            updateOverlayUi()
            
            // Flush buffered audio captured while connecting
            val bufferedAudio = preSetupAudioBuffer.toList()
            preSetupAudioBuffer.clear()
            for (base64 in bufferedAudio) {
                val gate = voiceVerificationGate
                if (gate != null) {
                    gate.feedChunk(base64)
                } else {
                    geminiClient?.sendAudioChunk(base64)
                }
            }
            
            if (isNotificationSession) {
                scheduleSilenceTimeout()
            } else {
                cancelIdleTimer()
                cancelSilenceTimeout()
            }
            
            pendingProactiveMessage?.let { msg ->
                serviceScope.launch {
                    geminiClient?.sendTextInput(msg)
                    pendingProactiveMessage = null
                }
            }
        }

        client.onDisconnected = {
            releasePlaybackFocus(audioPlayer)
            audioPlayer?.stop()
            phoneExecutor?.invalidateApprovals()
            isConnected = false
            isGeminiSetupComplete = false
            preSetupAudioBuffer.clear()
            currentStatusText = "Disconnected"
            Log.d(TAG, "Gemini Disconnected")
            VoiceLiveServiceManager.listener?.onDisconnected()
            updateOverlayUi()
        }

        client.onReconnecting = { attempt, delayMs ->
            releasePlaybackFocus(audioPlayer)
            audioPlayer?.stop()
            phoneExecutor?.invalidateApprovals()
            isConnected = false
            isGeminiSetupComplete = false
            preSetupAudioBuffer.clear()
            val delaySeconds = delayMs / 1000
            currentStatusText = getString(R.string.reconnecting_status_format, delaySeconds, attempt, 5)
            Log.d(TAG, "Gemini Reconnecting: attempt $attempt in $delaySeconds s")
            updateOverlayUi()
        }

        client.onAudioChunk = audio@{ base64 ->
            // Preserve this helper/generation before any listener or UI work.
            val player = audioPlayer
            val producer = if (!FocusMode.isActive(this)) player?.captureStartedProducer() else null
            if (player == null || producer == null || !preparePlaybackFocus(player)) return@audio
            cancelIdleTimer()
            cancelSilenceTimeout()
            currentStatusText = "Speaking..."
            VoiceLiveServiceManager.listener?.onAudioChunk(base64)
            playAudioChunk(player, producer, base64)
            updateOverlayUi()
        }

        client.onAudioBytes = audio@{ bytes ->
            val player = audioPlayer
            val producer = if (!FocusMode.isActive(this)) player?.captureStartedProducer() else null
            if (player == null || producer == null || !preparePlaybackFocus(player)) return@audio
            cancelIdleTimer()
            cancelSilenceTimeout()
            currentStatusText = "Speaking..."
            VoiceLiveServiceManager.listener?.onAudioBytes(bytes)
            playAudioBytes(player, producer, bytes)
            updateOverlayUi()
        }

        client.onAgentTranscript = { text, current ->
            // Do not add private previews/drafts to currentAiResponse, VoiceHistory or conversation summarization.
            currentStatusText = "Agent activity"
            if (current()) VoiceLiveServiceManager.listener?.onAgentTranscript(text, current)
            updateOverlayUi()
        }

        client.onTranscript = { text ->
            currentAiResponse += text
            lastTranscriptText = text
            currentStatusText = "Speaking..."
            VoiceLiveServiceManager.listener?.onTranscript(text)
            updateOverlayUi()
        }

                client.onInputTranscript = { text ->
            if (text.isNotBlank()) {
                val normalized = text.trim().lowercase()
                hasUserSpokenInNotificationSession = true
                if (isNotificationSession && isExplicitReplyRequest(normalized)) {
                    notificationReplyAuthorized = true
                    Log.d(TAG, "Explicit notification reply intent detected")
                }
                currentUserInput = text

                lastTranscriptText = text
                currentStatusText = "Listening..."
                cancelIdleTimer()
                cancelSilenceTimeout()
                if (isNotificationSession) {
                    val lower = text.trim().lowercase()
                    val dismissPhrases = listOf(
                        "ရပြီ", "မလိုဘူး", "မလိုတော့ဘူး", "ပိတ်လိုက်", "ပိတ်တော့",
                        "တော်ပြီ", "တော်ပါပြီ", "ကျေးဇူး", "မပြောတော့ဘူး",
                        "no", "cancel", "stop", "close", "done"
                    )
                    if (dismissPhrases.any { lower.contains(it) }) {
                        Log.d(TAG, "Notification session dismissed by user speech")
                        scheduleNotificationAutoClose(300L)
                    } else {
                        val timeoutSeconds = SettingsActivity.getNotificationAutoTimeoutSeconds(this).coerceIn(6, 30)
                        scheduleNotificationAutoClose(timeoutSeconds * 1000L)
                    }
                }
                VoiceLiveServiceManager.listener?.onInputTranscript(text)
                updateOverlayUi()
            }
        }

        client.onTaskStarted = {
            currentStatusText = "Processing..."
            cancelSilenceTimeout()
            VoiceLiveServiceManager.listener?.onTaskStarted()
            updateOverlayUi()
        }

        client.onTurnComplete = {
            if (currentAiResponse.isNotBlank()) {
                VoiceHistory.add(this, currentUserInput, currentAiResponse.trim())
                saveConversationMemory(currentUserInput, currentAiResponse)
                currentAiResponse = ""
                currentUserInput = "🎤"
            }
            synchronized(playbackFocusLock) {
                isFirstAudioChunkOfTurn = true
                focusUnavailableForTurn = false
            }
            currentStatusText = "Listening..."

            if (isNotificationSession) {
                if (isNotificationReplyExecuted) {
                    // Reply was sent and confirmation ("ပို့ပေးလိုက်ပါပြီ") finished speaking
                    scheduleNotificationAutoClose(1000L)
                } else {
                    // Announcement completed - stay quiet, listen attentively, and wait
                    // for the user's question, follow-up, or command before closing cleanly
                    isNotificationAnnouncementCompleted = true
                    val timeoutSeconds = SettingsActivity.getNotificationAutoTimeoutSeconds(this).coerceIn(6, 30)
                    scheduleNotificationAutoClose(timeoutSeconds * 1000L)
                }
            } else {
                cancelIdleTimer()
                cancelSilenceTimeout()
            }
            VoiceLiveServiceManager.listener?.onTurnComplete()
            updateOverlayUi()
        }

        client.onInterrupted = {
            if (currentAiResponse.isNotBlank()) {
                VoiceHistory.add(this, currentUserInput, currentAiResponse.trim() + " (Interrupted)")
                saveConversationMemory(currentUserInput, currentAiResponse.trim() + " (Interrupted)")
                currentAiResponse = ""
                currentUserInput = "🎤"
            }
            audioPlayer?.clearQueue()
            releasePlaybackFocus(audioPlayer)
            isFirstAudioChunkOfTurn = true
            currentStatusText = "Listening..."
            if (isNotificationSession) {
                val timeoutSeconds = SettingsActivity.getNotificationAutoTimeoutSeconds(this).coerceIn(6, 30)
                scheduleNotificationAutoClose(timeoutSeconds * 1000L)
            } else {
                cancelIdleTimer()
                cancelSilenceTimeout()
            }
            VoiceLiveServiceManager.listener?.onInterrupted()
            updateOverlayUi()
        }

        client.onError = { raw ->
            currentStatusText = "Error"
            lastTranscriptText = raw
            Log.e(TAG, "Voice provider error received")
            VoiceLiveServiceManager.listener?.onError(raw)
            updateOverlayUi()

            // Offline TTS fallback for notification announcements if Gemini connection fails
            if (isNotificationSession && pendingProactiveMessage != null) {
                val fallbackAnnounce = if (currentNotifApp.isNotBlank() && currentNotifText.isNotBlank()) {
                    "$currentNotifApp ကနေ $currentNotifSender ပို့ထားတဲ့ '$currentNotifText' ဆိုတဲ့ စာဝင်လာပါတယ်"
                } else if (currentNotifText.isNotBlank()) {
                    currentNotifText
                } else {
                    ""
                }
                if (fallbackAnnounce.isNotBlank()) {
                    pendingProactiveMessage = null
                    com.example.voicebrainlive.usecases.OneShotTtsSpeaker(this@VoiceLiveService).speak(
                        fallbackAnnounce,
                        "notif_fallback"
                    ) {
                        scheduleNotificationAutoClose(2000L)
                    }
                }
            }
        }

                audioRecorder?.onAudioChunk = { base64 ->
            micRestartAttempts = 0
            if (!isMuted && !isPaused) {

                if (audioPlayer != null && com.example.voicebrainlive.voice.VoiceInputPolicy.mayForward(
                        audioRecorder?.isRecording() == true && geminiClient === client,
                        isMuted || isPaused, audioPlayer?.isActuallySpeaking() == true, TtsUnifiedInterface.isAnyInstanceSpeaking(),
                        com.example.voicebrainlive.voice.VoiceInteractionPreferences.interruptionEnabled(this@VoiceLiveService))) {
                    // Check RMS for user voice activity during notification sessions
                    try {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                        var sumSq = 0.0
                        for (i in 0 until bytes.size step 2) {
                            val lo = bytes[i].toInt() and 0xFF
                            val hi = bytes[i + 1].toInt()
                            val sample = ((hi shl 8) or lo).toShort() / 32768f
                            sumSq += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sumSq / (bytes.size / 2)).toFloat()
                        if (isNotificationSession && rms > 0.025f) {
                            mainHandler.post {
                                cancelSilenceTimeout()
                                val timeoutSeconds = SettingsActivity.getNotificationAutoTimeoutSeconds(this@VoiceLiveService).coerceIn(6, 30)
                                scheduleNotificationAutoClose(timeoutSeconds * 1000L)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "RMS math error: ${e.message}")
                    }

                    if (!isGeminiSetupComplete) {
                        // Buffer while connecting (max ~8 seconds to prevent memory leak)
                        if (preSetupAudioBuffer.size < 125) {
                            preSetupAudioBuffer.add(base64)
                        }
                    } else if (isConnected) {
                        val gate = voiceVerificationGate
                        if (gate != null) {
                            gate.feedChunk(base64)
                        } else {
                            client.sendAudioChunk(base64)
                        }
                    }
                }
            }
        }

                audioRecorder?.onAmplitudeChanged = { amp ->
            if (!isMuted && !isPaused) {
                VoiceLiveServiceManager.listener?.onAmplitudeChanged(amp)
            }
        }

        audioRecorder?.onError = { msg ->
            Log.e(TAG, "Recorder Error: $msg")
            currentStatusText = "Microphone error"
            lastTranscriptText = "Microphone error: $msg"
            VoiceLiveServiceManager.listener?.onError("Microphone error: $msg")
            updateOverlayUi()
            // AudioRecord can stop on Samsung after an audio-route transition.
            // Retry only a small bounded number of times; never create a restart loop.
            if (micRestartAttempts < 2 && !isMuted && !isPaused && !isServiceDestroyed) {
                micRestartAttempts++
                micRestartJob?.cancel()
                micRestartJob = serviceScope.launch {
                    delay(1500L * micRestartAttempts)
                    if (!isServiceDestroyed && audioRecorder?.isRecording() != true) {
                        startAudioRecorder()
                    }
                }
            }
        }

    }

    private fun startAudioRecorder() {
        serviceScope.launch(Dispatchers.IO) {
                    try {
            if (audioRecorder?.isRecording() == true) return@launch
            audioRecorder?.start(serviceScope)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recorder: ${e.message}")
            }
        }
    }

    private fun preparePlaybackFocus(player: AudioPlayerHelper): Boolean = synchronized(playbackFocusLock) {
        if (focusUnavailableForTurn) return@synchronized false
        if (isFirstAudioChunkOfTurn) {
            if (!AudioFocusHelper.requestForSpeech(this, player)) {
                focusUnavailableForTurn = true
                player.onError?.invoke("Audio focus unavailable; text response remains available.")
                return@synchronized false
            }
            isFirstAudioChunkOfTurn = false
        }
        true
    }

    private fun releasePlaybackFocus(player: AudioPlayerHelper?) = synchronized(playbackFocusLock) {
        if (player != null) AudioFocusHelper.abandon(this, player)
        isFirstAudioChunkOfTurn = true
        focusUnavailableForTurn = false
    }

    private fun playAudioChunk(player: AudioPlayerHelper,
        producer: com.example.voicebrainlive.audio.BoundedPlaybackController.Producer, base64: String) {
        if (!FocusMode.isActive(this)) {
            try {
                player.playBase64(producer, base64)
            } catch (e: Exception) {
                Log.e(TAG, "playBase64 error: ${e.message}")
            }
        }
    }

    private fun playAudioBytes(player: AudioPlayerHelper,
        producer: com.example.voicebrainlive.audio.BoundedPlaybackController.Producer, bytes: ByteArray) {
        if (!FocusMode.isActive(this)) {
            try {
                player.playRaw(producer, bytes)
            } catch (e: Exception) {
                Log.e(TAG, "playRaw error: ${e.message}")
            }
        }
    }

    private fun saveConversationMemory(user: String, assistant: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val cleanUser = user.replace(Regex("^[🎤⌨️]\\s*"), "").trim()
                val cleanAssistant = assistant.trim()
                if (cleanUser.isNotBlank() && cleanAssistant.isNotBlank()) {
                    SimpleConversationStorage.insert(this@VoiceLiveService, currentSessionId, "user", cleanUser)
                    SimpleConversationStorage.insert(this@VoiceLiveService, currentSessionId, "model", cleanAssistant)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Memory save error: ${e.message}")
            }
        }
    }

    fun sendTextInput(text: String) {
        currentUserInput = "⌨️ $text"
        geminiClient?.sendConversationInput(text)
    }

    fun toggleMute() {
        isMuted = !isMuted
        updateOverlayUi()
    }

    fun togglePause() {
        isPaused = !isPaused
        if (isPaused) {
            releasePlaybackFocus(audioPlayer)
            audioRecorder?.stop()
            audioPlayer?.stop()
            currentStatusText = "Paused"
        } else {
            audioPlayer?.start()
            startAudioRecorder()
            currentStatusText = "Listening..."
        }
        updateOverlayUi()
    }

    fun stopLiveSession() {
        Log.w(TAG, "Stopping live session")
        releasePlaybackFocus(audioPlayer)
        try {
            audioRecorder?.stop()
            audioPlayer?.stop()
            phoneExecutor?.invalidateApprovals()
            geminiClient?.disconnect()
            voiceVerificationGate?.reset()
            voiceVerificationGate = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping helpers: ${e.message}")
        }
        geminiClient = null
        audioRecorder = null
        audioPlayer = null
        isConnected = false

        // Restart Wake Word Service
        if (WakeWordPrefs.isEnabled(this)) {
            try {
                Log.d(TAG, "Restarting WakeWordListenerService")
                val intent = Intent(this, WakeWordListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restart wake word service: ${e.message}")
            }
        }
    }

    // --- Overlay Manager ---

    fun updateOverlayVisibility() {
        serviceScope.launch(Dispatchers.Main) {
            if (isUiVisible) {
                updateOverlay(OverlayState.HIDDEN)
            } else {
                if (Settings.canDrawOverlays(this@VoiceLiveService)) {
                    updateOverlay(OverlayState.PILL)
                } else {
                    updateOverlay(OverlayState.HIDDEN)
                }
            }
        }
    }

    
    @SuppressLint("ClickableViewAccessibility")
    private fun updateOverlay(state: OverlayState) {
        if (currentOverlayState == state) return
        hideOverlays()
        currentOverlayState = state

        if (state == OverlayState.HIDDEN) return

        if (state == OverlayState.PILL) {
            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(composeLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(composeLifecycleOwner)
                setViewTreeViewModelStoreOwner(composeLifecycleOwner)
                setContent {
                    val status = currentStatusText
                    val voiceStatus = when {
                        status == "Listening..." -> VoiceStatus.LISTENING
                        status == "Speaking..." -> VoiceStatus.SPEAKING
                        status == "Processing" || status == "Connecting..." -> VoiceStatus.PROCESSING
                        else -> VoiceStatus.IDLE
                    }
                    OverlayRobotCornerScreen(
                        statusText = status,
                        voiceStatus = voiceStatus,
                        onExpand = { updateOverlay(OverlayState.CARD) }
                    )
                }
            }
            pillComposeView = composeView

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START // Top Left
                x = 0
                y = 0 // Close to the edge for a true corner feel
            }
            pillParams = params

            val container = DraggableOverlayContainer(this).apply {
                setViewTreeLifecycleOwner(composeLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(composeLifecycleOwner)
                setViewTreeViewModelStoreOwner(composeLifecycleOwner)
                setup(windowManager!!, params) {
                    updateOverlay(OverlayState.CARD)
                }
                addView(composeView)
            }
            pillView = container

            try {
                windowManager?.addView(container, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add pill overlay", e)
            }
        } else if (state == OverlayState.CARD) {
            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(composeLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(composeLifecycleOwner)
                setViewTreeViewModelStoreOwner(composeLifecycleOwner)
                setContent {
                    val status = currentStatusText
                    val voiceStatus = when {
                        status == "Listening..." -> VoiceStatus.LISTENING
                        status == "Speaking..." -> VoiceStatus.SPEAKING
                        status == "Processing" || status == "Connecting..." -> VoiceStatus.PROCESSING
                        else -> VoiceStatus.IDLE
                    }
                    val transcript = lastTranscriptText
                    
                    OverlayLiveCardScreen(
                        statusText = status,
                        voiceStatus = voiceStatus,
                        isMuted = isMuted,
                        isPaused = isPaused,
                        notifApp = currentNotifApp,
                        notifSender = currentNotifSender,
                        notifText = currentNotifText,
                        lastTranscript = transcript,
                        onMuteClick = { toggleMute() },
                        onPauseClick = { togglePause() },
                        onCloseClick = { 
                            stopLiveSession()
                            stopSelf()
                        },
                        onActionLive = {
                            val targetActivity = if (isStartedFromPowerButton) {
                                AssistantActivity::class.java
                            } else {
                                MainActivity::class.java
                            }
                            val intent = Intent(this@VoiceLiveService, targetActivity).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                        },
                        onActionVideo = { Toast.makeText(this@VoiceLiveService, "Video Live feature coming soon!", Toast.LENGTH_SHORT).show() },
                        onActionScreenshare = { Toast.makeText(this@VoiceLiveService, "Screenshare feature coming soon!", Toast.LENGTH_SHORT).show() },
                        onMinimize = { updateOverlay(OverlayState.PILL) }
                    )
                }
            }
            cardComposeView = composeView
            cardView = composeView

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 60
            }
            cardParams = params
            try {
                windowManager?.addView(composeView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add card overlay", e)
            }
        }
    }

    private fun updateOverlayUi() {
        // Compose automatically reacts to state changes
    }


    

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun hideOverlays() {
        try {
            pillView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.d(TAG, "Ignored remove pillView: ${e.message}")
        }
        try {
            cardView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.d(TAG, "Ignored remove cardView: ${e.message}")
        }
        pillView = null
        cardView = null
        pillComposeView = null
        cardComposeView = null
        pillParams = null
        cardParams = null
        currentOverlayState = OverlayState.HIDDEN
    }

    companion object {
        const val ACTION_START = "com.example.voicebrainlive.START_LIVE"
        const val ACTION_STOP = "com.example.voicebrainlive.STOP_LIVE"
        const val ACTION_SHOW_OVERLAY = "com.example.voicebrainlive.SHOW_OVERLAY"
        private const val NOTIFICATION_ID = 9003
        private const val TAG = "VoiceLiveService"

        var instance: VoiceLiveService? = null
            private set

        var isUiVisible: Boolean = false
            set(value) {
                field = value
                instance?.updateOverlayVisibility()
            }
    }
}

/**
 * Manager to hook up updates from background VoiceLiveService to front-end elements.
 */
object VoiceLiveServiceManager {
    interface Listener {
        fun onConnected()
        fun onSetupComplete()
        fun onDisconnected()
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
        fun onAmplitudeChanged(amplitude: Float) {}
    }

    var listener: Listener? = null
}

