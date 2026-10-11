package com.example.voicebrainlive

import android.content.Intent
import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID

import androidx.activity.compose.setContent
import android.view.Gravity
import android.view.MotionEvent
import com.example.voicebrainlive.ui.components.CameraNotchSoundBar
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.example.voicebrainlive.usecases.AssistantAutomationPayload
import com.example.voicebrainlive.usecases.AutomationNarrationHelper
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import com.example.voicebrainlive.usecases.OneShotTtsSpeaker
import com.example.voicebrainlive.voice.VoiceProfileManager
import com.example.voicebrainlive.voice.VoiceVerificationGate

class AssistantActivity : androidx.fragment.app.FragmentActivity(), 
    AssistantViewModel.AssistantViewModelListener,
    VoiceLiveServiceManager.Listener {

    enum class VoiceStatus { IDLE, LISTENING, SPEAKING, PROCESSING }

    private var composeStatusText by mutableStateOf("Listening...")
    private var composeTranscriptText by mutableStateOf("")
    private var composeUserInputText by mutableStateOf("")
    private var composeIsConnecting by mutableStateOf(false)
    private var composeShowRetry by mutableStateOf(false)
    private var composeVoiceStatus by mutableStateOf(VoiceStatus.IDLE)
    private var composeAudioAmplitude by mutableStateOf(0f)

    private lateinit var viewModel: AssistantViewModel

    private var isConnected = false
    private var isAutomationMode = false
    private var waveformAnimation: AnimatorSet? = null
    private var orbPulseAnimation: AnimatorSet? = null
    private var entranceAnimation: AnimatorSet? = null
    private var pendingTextInput: String? = null
    private var ttsUnified: com.example.voicebrainlive.tts.TtsUnifiedInterface? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleTimeoutRunnable: Runnable? = null
    private var silenceTimeoutRunnable: Runnable? = null
    private val automationCommandRunner by lazy { AutomationCommandRunner(this) }
    private val oneShotTtsSpeaker by lazy { OneShotTtsSpeaker(this) }
    private var automationJob: Job? = null
    private var hasPromptedOverlay = false

    private var retryCount = 0
    private val maxRetries = 3
    private var currentRetryDelay = 2000L
    private var isReconnecting = false
    private var reconnectRunnable: Runnable? = null
    private var isProcessingTurn = false
    private var wakeOrigin = false
    private var wakeGreetingSpoken = false
    private var wakeWordRestartScheduled = false

    private val permissionManager by lazy { PermissionManager(this) }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] != false
        if (micOk) {
            startAssistant()
        } else {
            if (!permissionManager.shouldShowRationale(Manifest.permission.RECORD_AUDIO)) {
                permissionManager.showSettingsNavigationDialog(
                    title = "Microphone Permission Denied",
                    message = "Microphone ခွင့်ပြုချက်ကို လုံးဝပိတ်ထားပါသည်။ အသံဖြင့် အသုံးပြုနိုင်ရန် Settings တွင် ခွင့်ပြုပေးပါ။"
                )
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    override fun onPause() {
        com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        com.example.voicebrainlive.commands.ApprovalUiLease.resume(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        wakeOrigin = intent?.getBooleanExtra("EXTRA_WAKE_ORIGIN", false) == true

        // Stop Wake Word Service to free up Microphone and prevent audio conflict/looping
        stopWakeWordService()

        // Initialize unified TTS
        ttsUnified = com.example.voicebrainlive.tts.TtsUnifiedInterface(this)

        // Lock screen ပေါ်မှာ ပွင့်နိုင်အောင် လုပ်ပေးခြင်း (အဦးဆုံး ထားရပါမယ်)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Make activity transparent and fullscreen
        setupTransparentWindow()

        setContent {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                CameraNotchSoundBar(
                    voiceStatus = composeVoiceStatus,
                    audioAmplitude = composeAudioAmplitude,
                    onTap = {
                        stopAndClose()
                    }
                )
            }
        }

        viewModel = androidx.lifecycle.ViewModelProvider(this)[AssistantViewModel::class.java]
        setupCallbacks()
        setupClickListeners()
        
        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stopAndClose()
            }
        })

        // Automation Trigger Mode: skip mic + Gemini; TTS announce -> execute -> auto-close.
        val isAutomation = intent?.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_RUN, false) == true
        if (isAutomation) {
            isAutomationMode = true
            viewModel.isAutomationMode = true
            runAutomationFromIntent(intent)
            return
        }

        // Launch from Home mic/send: handle text or start mic
        if (intent?.hasExtra(MainActivity.EXTRA_TEXT_INPUT) == true) {
            pendingTextInput = intent.getStringExtra(MainActivity.EXTRA_TEXT_INPUT)
            intent.removeExtra(MainActivity.EXTRA_TEXT_INPUT)
        }

        // Check permission and start with rationales
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissionsNeeded.all { permissionManager.hasPermission(it) }

        val optionalList = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            optionalList.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        if (!allGranted) {
            val showRationale = permissionsNeeded.any { permissionManager.shouldShowRationale(it) }
            if (showRationale) {
                permissionManager.showRationaleDialog(
                    title = "ခွင့်ပြုချက်များ လိုအပ်ပါသည်",
                    message = "အသံအသုံးပြုရန် Microphone ခွင့်ပြုချက်နှင့် နိုတီဘားတွင် ပေါ်ရန် Notification ခွင့်ပြုချက် ပေးရန် လိုအပ်ပါသည်။",
                    onPositive = {
                        val requestArray = (permissionsNeeded + optionalList).toSet().toTypedArray()
                        requestPermissionLauncher.launch(requestArray)
                    },
                    onNegative = {
                        Toast.makeText(this, "လိုအပ်သော ခွင့်ပြုချက်များ မရရှိပါ", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            } else {
                val requestArray = (permissionsNeeded + optionalList).toSet().toTypedArray()
                requestPermissionLauncher.launch(requestArray)
            }
        } else {
            // Already has RECORD_AUDIO and POST_NOTIFICATIONS
            startAssistant()
            
            // Just-in-time check/request for optional ones:
            val optionalPerms = mutableListOf<String>()
            for (perm in optionalList) {
                if (!permissionManager.hasPermission(perm)) {
                    optionalPerms.add(perm)
                }
            }
            if (optionalPerms.isNotEmpty()) {
                requestPermissionLauncher.launch(optionalPerms.toTypedArray())
            }
        }
    }

    private fun runAutomationFromIntent(intent: Intent?) {
        if (intent == null) {
            stopAndClose()
            return
        }
        val payload = AssistantAutomationPayload.fromIntent(intent)
        val alreadySpoken = payload.alreadySpoken

        showAutomationTranscript(payload.announceText)

        val taskBlock: suspend () -> Unit = automation@ {
            try {
                if (payload.isPreReminder) {
                    runOnUiThread { stopAndClose() }
                    return@automation
                }

                val prompt = AutomationNarrationHelper.buildPrompt(payload)
                if (prompt.isNotBlank() && !AutomationNarrationHelper.usesDirectExecution(payload.type)) {
                    runOnUiThread {
                        updateStatusText("AI Narrating...")
                        viewModel.geminiClient.sendTextInput(prompt)
                        startAssistant()
                    }
                    return@automation
                }

                val result = automationCommandRunner.run(
                    type = payload.type,
                    message = payload.message,
                    musicQuery = payload.musicQuery,
                    isPreReminder = payload.isPreReminder
                )

                runOnUiThread { handleAutomationResult(result, payload.announceText) }
            } catch (e: Exception) {
                Log.e("Assistant", "Automation error", e)
                runOnUiThread { stopAndClose() }
            }
        }

        if (alreadySpoken) {
            automationJob = lifecycleScope.launch(Dispatchers.IO) {
                taskBlock()
            }
        } else {
            val weakActivity = WeakReference(this@AssistantActivity)
            speakAndThen(payload.announceText, "automation_${UUID.randomUUID()}") {
                weakActivity.get()?.let { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.automationJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                            taskBlock()
                        }
                    }
                }
            }
        }
    }

    private fun showAutomationTranscript(announceText: String) {
        updateStatusText("Automation...")
        showTranscript(announceText)
    }

    private fun handleAutomationResult(result: String, announceText: String) {
        if (result.isNotBlank() && result != announceText) {
            showTranscript(result.take(350))
            speakAndThen(result, "automation_result_${UUID.randomUUID()}") {
                stopAndClose()
            }
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ stopAndClose() }, 3000L)
    }

    private fun updateStatusText(status: String) {
        composeStatusText = status
    }

    private fun showTranscript(text: String) {
        composeTranscriptText = text
    }








    private fun speakAndThen(text: String, utteranceId: String, onDone: () -> Unit) {
        // Wrap callback to prevent leak if Activity is destroyed
        val wrappedCallback = Runnable {
            if (!isFinishing && !isDestroyed) {
                onDone()
            }
        }
        oneShotTtsSpeaker.speak(text, utteranceId) {
            mainHandler.post(wrappedCallback)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_WAKE_ORIGIN", false)) {
            wakeOrigin = true
            wakeGreetingSpoken = false
        }
        
        // Stop Wake Word Service to free up Microphone and prevent audio conflict/looping
        stopWakeWordService()

        val isAutomation = intent.getBooleanExtra(MainActivity.EXTRA_AUTOMATION_RUN, false)
        if (isAutomation) {
            isAutomationMode = true
            viewModel.isAutomationMode = true
            runAutomationFromIntent(intent)
        } else {
            if (intent.hasExtra(MainActivity.EXTRA_TEXT_INPUT)) {
                val txt = intent.getStringExtra(MainActivity.EXTRA_TEXT_INPUT)
                if (isConnected) {
                    showTranscript(txt ?: "")
                    viewModel.sendTextInput(txt ?: "")
                } else {
                    pendingTextInput = txt
                }
                intent.removeExtra(MainActivity.EXTRA_TEXT_INPUT)
            }
            if (hasRecordPermission()) startAssistant()
        }
    }

    private fun setupTransparentWindow() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        window.decorView.background = null

        window.apply {
            setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            @Suppress("DEPRECATION")
            statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = android.graphics.Color.TRANSPARENT

            val density = resources.displayMetrics.density
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else (28 * density).toInt()

            val params = attributes
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = 0
            params.y = statusBarHeight + (2 * density).toInt()
            params.dimAmount = 0f
            params.format = android.graphics.PixelFormat.TRANSLUCENT
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            attributes = params
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            stopAndClose()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun setupCallbacks() {
        viewModel.biometricRequest.observe(this) { request ->
            if (request != null) {
                showBiometricPrompt(request)
            }
        }
    }

    override fun onConnected() {
        lifecycleScope.launch(Dispatchers.IO) {
            com.example.voicebrainlive.memory.BehaviorMemory.recordSessionStarted(this@AssistantActivity)
        }
        runOnUiThread {
            isConnected = true
            retryCount = 0
            currentRetryDelay = 2000L
            isReconnecting = false
            updateStatusText("Connecting...")
            composeShowRetry = false
            composeIsConnecting = true
        }
    }

    override fun onSetupComplete() {
        runOnUiThread {
            try {
                composeIsConnecting = false
                if (isAutomationMode) {
                    updateStatusText("AI Narrating...")
                    setBreathingState(true)
                } else {
                    updateStatusText("Listening...")
                    setBreathingState(false)
                    startWaveformAnimation()
                    scheduleIdleTimer()
                    scheduleSilenceTimeout()
                    mainHandler.postDelayed({ speakWakeGreetingIfNeeded() }, 250L)
                }

                pendingTextInput?.let {
                    showTranscript(it)
                    viewModel.sendTextInput(it)
                    pendingTextInput = null
                }
            } catch (e: Exception) {
                Log.e("AssistantActivity", "UI error: ${e.message}")
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            isConnected = false
            stopWaveformAnimation()
            
            val isUserClose = viewModel.geminiClient.userInitiatedDisconnect
            if (isUserClose) {
                finish()
            }
        }
    }

    override fun onReconnecting(attempt: Int, delayMs: Long) {
        runOnUiThread {
            isReconnecting = true
            updateStatusText("ပြန်ချိတ်နေပါတယ်... ($attempt/5)")
            composeIsConnecting = true
            
            // Stop audio recorder/player temporarily during reconnection
            viewModel.audioRecorder.stop()
            viewModel.audioPlayer.stop()
        }
    }

    override fun onAudioChunk(base64: String) {
        runOnUiThread {
            cancelIdleTimer()
            cancelSilenceTimeout()
            updateStatusText("Speaking...")
            composeVoiceStatus = VoiceStatus.SPEAKING
            setBreathingState(true)
            startSpeakingAnimation()
        }
    }

    override fun onAudioBytes(bytes: ByteArray) {
        runOnUiThread {
            cancelIdleTimer()
            cancelSilenceTimeout()
            updateStatusText("Speaking...")
            composeVoiceStatus = VoiceStatus.SPEAKING
            setBreathingState(true)
            startSpeakingAnimation()
        }
    }

    override fun onTranscript(text: String) {
        runOnUiThread {
            if (text.isNotBlank()) {
                cancelIdleTimer()
                composeVoiceStatus = VoiceStatus.SPEAKING
                // This is AI's response transcript. Do NOT reset silence timeout here.
                LastResponseStore.set(text)
                showTranscript(text)
            }
        }
    }

    override fun onInputTranscript(text: String) {
        runOnUiThread {
            if (text.isNotBlank()) {
                isProcessingTurn = true
                composeUserInputText = text
                composeVoiceStatus = VoiceStatus.LISTENING
                // User is recognized speaking, reset timeouts
                cancelIdleTimer()
                cancelSilenceTimeout()
                scheduleSilenceTimeout()
            }
        }
    }

    override fun onTaskStarted() {
        runOnUiThread {
            isProcessingTurn = true
            composeVoiceStatus = VoiceStatus.PROCESSING
            // User is being heard - task has started processing
            // Keep timeout active but don't close while processing
            cancelSilenceTimeout()
            updateStatusText("Processing...")
            setBreathingState(true)
        }
    }

    override fun onTurnComplete() {
        runOnUiThread {
            isProcessingTurn = false
            if (isAutomationMode || wakeOrigin) {
                Log.d("AssistantActivity", "Single-turn session complete - closing session (automation=$isAutomationMode, wakeOrigin=$wakeOrigin)")
                stopAndClose()
                return@runOnUiThread
            }

            updateStatusText("Listening...")
            composeVoiceStatus = VoiceStatus.LISTENING
            setBreathingState(false)
            // Only on complete turn (full cycle done), reschedule timers
            scheduleIdleTimer()
            scheduleSilenceTimeout()
        }
    }

    override fun onInterrupted() {
        runOnUiThread {
            isProcessingTurn = false
            if (isAutomationMode || wakeOrigin) {
                Log.d("AssistantActivity", "Single-turn session interrupted - closing session (automation=$isAutomationMode, wakeOrigin=$wakeOrigin)")
                stopAndClose()
                return@runOnUiThread
            }

            cancelIdleTimer()
            updateStatusText("Listening...")
            setBreathingState(false)
            scheduleIdleTimer()
            scheduleSilenceTimeout()
        }
    }

    override fun onError(raw: String) {
        runOnUiThread {
            isProcessingTurn = false
            com.example.voicebrainlive.ActionRecordManager.recordAction(this, "Assistant", "Error: $raw", isError = true)
            val display = if (SettingsActivity.isShowErrorDetails(this))
                raw.take(200).ifBlank { "Error" }
            else
                ErrorUtils.toUserMessage(this, raw)
            
            updateStatusText(display)
            composeIsConnecting = false
            if (isAutomationMode) {
                mainHandler.postDelayed({ stopAndClose() }, 5000L)
            } else {
                composeShowRetry = true
                Toast.makeText(this, display, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun speakResultWithTTS(text: String) {
        speakResultWithTTSInternal(text)
    }

    override fun onAmplitudeChanged(amplitude: Float) {
        runOnUiThread {
            composeAudioAmplitude = amplitude
        }
    }

    private fun setupClickListeners() {
        // Tap on scrim (dark area) to close
    }

    /** Speak status/weather result with TTS so user hears it even if Gemini doesn't generate audio. Errors also spoken in Burmese. */
    private fun speakResultWithTTSInternal(text: String) {
        if (text.isBlank()) return
        val speechText = if (text.startsWith("Error")) ErrorUtils.toUserMessage(this, text) else text
        runOnUiThread {
            ttsUnified?.speak(speechText)
        }
    }

    private fun speakWakeGreetingIfNeeded() {
        if (!wakeOrigin || wakeGreetingSpoken || isAutomationMode) return
        wakeGreetingSpoken = true
        val greeting = "မင်္ဂလာပါ၊ Luma နိုးလာပါပြီ။ ဘာလုပ်ပေးရမလဲ?"
        showTranscript(greeting)
        updateStatusText("နားထောင်နေပါတယ်...")
        val finishGreeting = {
            if (!isFinishing && !isDestroyed) {
                updateStatusText("နားထောင်နေပါတယ်...")
                startWaveformAnimation()
                scheduleSilenceTimeout()
            }
        }
        oneShotTtsSpeaker.speak(
            greeting,
            "wake_greeting_${UUID.randomUUID()}",
            onDone = finishGreeting,
            onError = {
                // Some devices expose a Burmese locale but fail to initialize the
                // one-shot engine. Reuse the app's initialized TTS path once.
                runOnUiThread { ttsUnified?.speak(greeting) }
            }
        )
    }

    private fun startAssistant() {
        if (VoiceLiveService.instance != null) {
            // Already running! Just attach listener and show UI visible
            val service = VoiceLiveService.instance!!
            service.isStartedFromPowerButton = true
            VoiceLiveService.isUiVisible = true
            VoiceLiveServiceManager.listener = this
            // Restore current service states in local UI
            this.isConnected = service.isConnected
            updateStatusText(service.currentStatusText)
            showTranscript(service.lastTranscriptText)
            composeIsConnecting = !service.isConnected

            // A wake-origin Activity may attach to an existing service. Do not
            // skip the one-time acknowledgement in that case.
            if (service.isGeminiReady) {
                if (wakeOrigin) speakWakeGreetingIfNeeded()
                startWaveformAnimation()
            }
            return
        }

        updateStatusText("Connecting...")
        composeIsConnecting = true
        setBreathingState(false)
        playEntranceAnimation()

        if (!permissionManager.hasSystemAlertWindowPermission()) {
            if (!hasPromptedOverlay) {
                hasPromptedOverlay = true
                permissionManager.showRationaleDialog(
                    title = "Overlay ခွင့်ပြုချက် လိုအပ်ပါသည်",
                    message = "Gemini Live background overlay စနစ်အတွက် 'Draw over other apps' (သို့မဟုတ်) 'Display over other apps' ခွင့်ပြုချက် ပေးရန်လိုအပ်ပါသည်။ ခွင့်ပြုပေးရန် Settings သို့ သွားမလား?",
                    onPositive = {
                        permissionManager.requestSystemAlertWindowPermission()
                    },
                    onNegative = {
                        Toast.makeText(this, "ခွင့်ပြုချက် မရရှိသဖြင့် နောက်ခံ Overlay စနစ် အလုပ်လုပ်မည်မဟုတ်ပါ", Toast.LENGTH_LONG).show()
                        startLiveVoiceService()
                    }
                )
            } else {
                Toast.makeText(this, "ခွင့်ပြုချက် မရရှိသဖြင့် နောက်ခံ Overlay စနစ် အလုပ်လုပ်မည်မဟုတ်ပါ", Toast.LENGTH_LONG).show()
                startLiveVoiceService()
            }
        } else {
            startLiveVoiceService()
        }
    }

    private fun startLiveVoiceService() {
        val serviceIntent = Intent(this, VoiceLiveService::class.java).apply {
            action = VoiceLiveService.ACTION_START
            putExtra("EXTRA_POWER_BUTTON", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        VoiceLiveService.isUiVisible = true
        VoiceLiveServiceManager.listener = this
    }
    
    private fun playEntranceAnimation() {
        composeVoiceStatus = VoiceStatus.IDLE
    }

    private fun stopAndClose() {
        try {
            cancelIdleTimer()
            cancelSilenceTimeout()
            stopService(Intent(this, VoiceLiveService::class.java))
            stopWaveformAnimation()
            if (wakeOrigin) scheduleWakeWordRestart()
        } catch (e: Exception) {
            Log.e("AssistantActivity", "stopAndClose", e)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun scheduleWakeWordRestart() {
        if (wakeWordRestartScheduled || !WakeWordPrefs.isEnabled(this)) return
        wakeWordRestartScheduled = true
        mainHandler.postDelayed({
            if (!isDestroyed && !isFinishing) return@postDelayed
            restartWakeWordService()
        }, 450L)
    }

    private fun startWaveformAnimation() {
        composeVoiceStatus = VoiceStatus.LISTENING
    }
    
    private fun startSpeakingAnimation() {
        composeVoiceStatus = VoiceStatus.SPEAKING
    }

    private fun stopWaveformAnimation() {
        composeVoiceStatus = VoiceStatus.IDLE
    }

    private fun setBreathingState(speaking: Boolean) {
        composeVoiceStatus = if (speaking) VoiceStatus.SPEAKING else VoiceStatus.IDLE
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Cancel idle timeout timer (when user gives command or AI responds). */
    private fun cancelIdleTimer() {
        idleTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        idleTimeoutRunnable = null
    }

    /** Schedule auto-close after idle timeout when in Listening state with no command. */
    private fun scheduleIdleTimer() {
        cancelIdleTimer()
        val minutes = SettingsActivity.getIdleTimeoutMinutes(this)
        if (minutes <= 0) return
        idleTimeoutRunnable = Runnable {
            if (!isFinishing && isConnected) {
                Log.d("AssistantActivity", "Idle timeout (${minutes} min) - auto closing")
                stopAndClose()
            }
        }
        mainHandler.postDelayed(idleTimeoutRunnable!!, minutes * 60 * 1000L)
    }

    /** Silence timeout in seconds: stop mic automatically if user stays silent. */
    private fun cancelSilenceTimeout() {
        silenceTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceTimeoutRunnable = null
    }

    private fun scheduleSilenceTimeout() {
        cancelSilenceTimeout()
        val seconds = SettingsActivity.getSilenceTimeoutSeconds(this)
        if (seconds <= 0) return
        silenceTimeoutRunnable = Runnable {
            if (!isFinishing && isConnected) {
                if (isProcessingTurn || com.example.voicebrainlive.tts.TtsUnifiedInterface.isAnyInstanceSpeaking() || viewModel.audioPlayer.isActuallySpeaking()) {
                    Log.d("AssistantActivity", "TTS/player is speaking or model is processing, rescheduling silence timeout")
                    scheduleSilenceTimeout()
                } else {
                    Log.d("AssistantActivity", "Silence timeout (${seconds}s) - auto closing")
                    stopAndClose()
                }
            }
        }
        mainHandler.postDelayed(silenceTimeoutRunnable!!, seconds * 1000L)
    }

    override fun onStart() {
        super.onStart()
        VoiceLiveServiceManager.listener = this
        VoiceLiveService.isUiVisible = true
        
        // Re-check permission if we returned to the activity
        if (hasRecordPermission()) {
            if (VoiceLiveService.instance != null || hasPromptedOverlay) {
                startAssistant()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (VoiceLiveServiceManager.listener == this) {
            VoiceLiveServiceManager.listener = null
        }
        if (!isFinishing) {
            Log.d("AssistantActivity", "onStop() - activity moved to background, starting overlay")
            VoiceLiveService.isUiVisible = false
        } else {
            stopService(Intent(this, VoiceLiveService::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Cancel all pending jobs first
            automationJob?.cancel()
            automationJob = null
            
            // Remove all pending callbacks from Handler
            mainHandler.removeCallbacksAndMessages(null)
            idleTimeoutRunnable = null
            silenceTimeoutRunnable = null
            reconnectRunnable = null
            
            // Cleanup TTS
            ttsUnified?.shutdown()
            ttsUnified = null
            
            // Clear ViewModel callbacks
            viewModel.clearCallbacks()
            
            // Stop animations - properly clear all listeners and cancel
            stopWaveformAnimation()
            entranceAnimation?.apply {
                cancel()
                removeAllListeners()
            }
            entranceAnimation = null
            
            // Stop audio components
            viewModel.audioRecorder.stop()
            viewModel.audioPlayer.stop()
            viewModel.phoneExecutor.invalidateApprovals()
            viewModel.geminiClient.disconnect()
            
            // Wake-origin sessions schedule their restart from stopAndClose()
            // after the assistant service has been asked to stop. This avoids
            // racing the microphone handoff and prevents duplicate recognizers.
            if (wakeOrigin) {
                // onDestroy() removed pending callbacks above, so schedule the
                // final restart only after cleanup has completed.
                wakeWordRestartScheduled = false
                scheduleWakeWordRestart()
            }

        } catch (e: Exception) {
            Log.e("AssistantActivity", "Destroy error: ${e.message}")
        }
    }

    private fun stopWakeWordService() {
        try {
            Log.d("AssistantActivity", "Stopping WakeWordListenerService to free microphone")
            stopService(Intent(this, WakeWordListenerService::class.java))
        } catch (e: Exception) {
            Log.w("AssistantActivity", "Failed to stop wake word service: ${e.message}")
        }
    }

    private fun restartWakeWordService() {
        if (WakeWordPrefs.isEnabled(this)) {
            try {
                Log.d("AssistantActivity", "Restarting WakeWordListenerService")
                val intent = Intent(this, WakeWordListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.w("AssistantActivity", "Failed to restart wake word service: ${e.message}")
            }
        }
    }

    private fun showBiometricPrompt(request: AssistantViewModel.BiometricRequest) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    request.onResult(false)
                    viewModel.clearBiometricRequest()
                }

                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    request.onResult(true)
                    viewModel.clearBiometricRequest()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(request.title)
            .setSubtitle(request.subtitle)
            .setNegativeButtonText("မလုပ်တော့ပါ")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}


@Composable
fun AssistantScreen(
    statusText: String,
    transcriptText: String,
    userInputText: String = "",
    isConnecting: Boolean,
    showRetry: Boolean,
    voiceStatus: AssistantActivity.VoiceStatus,
    onClose: () -> Unit,
    onStop: () -> Unit = onClose,
    onSendText: (String) -> Unit = {},
    onRetry: () -> Unit
) {
    var showKeyboardInput by remember { mutableStateOf(false) }
    var fallbackText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Scrim backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB8000000))
                .clickable { onClose() }
        )

        // Top Status Bar with close icon
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = Color(0xFF151D33).copy(alpha = 0.92f),
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3A60))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotColor = when (voiceStatus) {
                        AssistantActivity.VoiceStatus.LISTENING -> Color(0xFF22C7E8)
                        AssistantActivity.VoiceStatus.SPEAKING -> Color(0xFF8B5CF6)
                        AssistantActivity.VoiceStatus.PROCESSING -> Color(0xFFFBBF24)
                        else -> Color(0xFF4ADE80)
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (voiceStatus) {
                            AssistantActivity.VoiceStatus.LISTENING -> "🎙️ နားထောင်နေသည်..."
                            AssistantActivity.VoiceStatus.SPEAKING -> "🗣️ ရှင်းပြနေသည်..."
                            AssistantActivity.VoiceStatus.PROCESSING -> "🧠 စဉ်းစားနေသည်..."
                            else -> "✨ VoiceBrain Live"
                        },
                        color = Color(0xFFF8FAFC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF151D33).copy(alpha = 0.85f))
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Turn-based Conversation View (User Turn vs Assistant Turn)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // User Turn Bubble (Right/End Aligned)
                AnimatedVisibility(
                    visible = userInputText.isNotBlank(),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { 30 }),
                    exit = fadeOut()
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            color = Color(0xFF1E294B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.5f)),
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    text = "👤 သင် (You)",
                                    color = Color(0xFF22C7E8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = userInputText,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Assistant Turn Bubble (Left/Start Aligned)
                AnimatedVisibility(
                    visible = transcriptText.isNotBlank(),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { 40 }),
                    exit = fadeOut()
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                            color = Color(0xFF151D33),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3A60)),
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🤖 VoiceBrain Assistant",
                                        color = Color(0xFFA78BFA),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (voiceStatus == AssistantActivity.VoiceStatus.SPEAKING) {
                                        Text(
                                            text = "🔊 Speaking",
                                            color = Color(0xFF22C7E8),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = transcriptText,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }

            // Orb Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                AssistantFluidOrb(status = voiceStatus)
            }

            // Status message
            Text(
                text = statusText,
                color = Color(0xFFCBD5E1),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (isConnecting) {
                CircularProgressIndicator(
                    color = Color(0xFF22C7E8),
                    modifier = Modifier.padding(bottom = 12.dp).size(28.dp)
                )
            }

            // Action Controls Bar around Orb (Stop, Keyboard fallback, Retry)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop Speaking / Listening
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onStop() },
                    color = Color(0xFFEF4444).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏹️", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("ရပ်မည် (Stop)", color = Color(0xFFFCA5A5), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Keyboard Fallback Toggle
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { showKeyboardInput = !showKeyboardInput },
                    color = Color(0xFF151D33),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3A60))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⌨️", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("စာရိုက်ရန်", color = Color(0xFFCBD5E1), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // Retry Button (When Error occurs)
                if (showRetry) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { onRetry() },
                        color = Color(0xFF8B5CF6),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔄", fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("ထပ်စမ်းမည်", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Keyboard Text Fallback Input
            AnimatedVisibility(
                visible = showKeyboardInput,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0B1020))
                        .border(1.dp, Color(0xFF2A3A60), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = fallbackText,
                        onValueChange = { fallbackText = it },
                        placeholder = { Text("စာဖြင့် မေးမြန်းရန်...", color = Color(0xFF94A3B8), fontSize = 13.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF22C7E8)
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    if (fallbackText.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onSendText(fallbackText.trim())
                                    fallbackText = ""
                                    showKeyboardInput = false
                                },
                            color = Color(0xFF22C7E8)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("➔", color = Color(0xFF0B1020), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantFluidOrb(status: AssistantActivity.VoiceStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "infinite")

    val scale by animateFloatAsState(
        targetValue = when (status) {
            AssistantActivity.VoiceStatus.LISTENING -> 1.25f
            AssistantActivity.VoiceStatus.SPEAKING -> 1.5f
            AssistantActivity.VoiceStatus.PROCESSING -> 1.15f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val (glowColor, coreColors) = when (status) {
        AssistantActivity.VoiceStatus.LISTENING -> Color(0xFF22C7E8) to listOf(Color(0xFF22C7E8), Color(0xFF0284C7))
        AssistantActivity.VoiceStatus.SPEAKING -> Color(0xFF8B5CF6) to listOf(Color(0xFF8B5CF6), Color(0xFF22C7E8))
        AssistantActivity.VoiceStatus.PROCESSING -> Color(0xFFFBBF24) to listOf(Color(0xFFFBBF24), Color(0xFFEA580C))
        else -> Color(0xFF8B5CF6).copy(alpha = 0.5f) to listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        // Outer Glowing Layer (Glassmorphism + Depth)
        Box(
            modifier = Modifier
                .size(130.dp * scale * breathe)
                .blur(32.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.6f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        // Inner Core Layer
        Box(
            modifier = Modifier
                .size(84.dp * scale * breathe)
                .background(
                    brush = Brush.linearGradient(colors = coreColors),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
                contentDescription = "Mic",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
