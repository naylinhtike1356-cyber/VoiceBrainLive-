package com.example.voicebrainlive

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.example.voicebrainlive.audio.AudioFocusHelper
import com.example.voicebrainlive.calendar.CalendarEventReminderHelper
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.VisionCaptureBridge
import com.example.voicebrainlive.databinding.ActivityMainBinding
import com.example.voicebrainlive.location.WiFiArrivalReceiver
import com.example.voicebrainlive.ui.MainFragment
import com.example.voicebrainlive.usecases.AssistantAutomationPayload
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import com.example.voicebrainlive.usecases.ProactivePromptHelper
import com.example.voicebrainlive.usecases.ProactiveIntentPayload
import com.example.voicebrainlive.usecases.ReusableTtsSpeaker
import kotlinx.coroutines.*
import java.util.*

class MainActivity : AppCompatActivity() {

    private var wifiArrivalReceiver: WiFiArrivalReceiver? = null
    private lateinit var binding: ActivityMainBinding
    internal val viewModel: MainActivityViewModel by viewModels()

    private val startupErrorHandler = CoroutineExceptionHandler { _, error ->
        Log.e("MainActivity", "Background initialization failed", error)
        if (!isFinishing && !isDestroyed) {
            val message = getString(R.string.startup_background_error)
            viewModel.updateStatus(message)
            viewModel.updateState(AssistantState.Error(message))
        }
    }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + startupErrorHandler)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var pendingProactiveType: String? = null
    private var isAutomationSession = false
    private var pendingAutomationRun: AutomationRun? = null
    private var pendingProactiveMessage: String? = null
    private var pendingConnectOnInit = false
    private val automationCommandRunner by lazy { AutomationCommandRunner(this) }
    private val reusableTtsSpeakerDelegate = lazy { ReusableTtsSpeaker(this) }
    private val reusableTtsSpeaker by reusableTtsSpeakerDelegate

    private val permissionManager by lazy { PermissionManager(this) }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true
        if (micOk) {
            Toast.makeText(this, getString(R.string.tap_to_start), Toast.LENGTH_SHORT).show()
        } else {
            if (!permissionManager.shouldShowRationale(Manifest.permission.RECORD_AUDIO)) {
                permissionManager.showSettingsNavigationDialog(
                    title = "Microphone Permission Required",
                    message = "ခွင့်ပြုချက်မရှိလျှင် အသံဖြင့် အသုံးပြု၍မရပါ။ Settings တွင် သွားရောက် ခွင့်ပြုပေးပါ။"
                )
            }
        }
        if (results[Manifest.permission.CALL_PHONE] == false) {
            Toast.makeText(this, getString(R.string.call_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_PROACTIVE_SPEAK = "extra_proactive_speak"
        const val EXTRA_PROACTIVE_MESSAGE = "extra_proactive_message"
        const val EXTRA_PROACTIVE_TYPE = "extra_proactive_type"
        const val EXTRA_AUTOMATION_RUN = "extra_automation_run"
        const val EXTRA_AUTOMATION_ID = "extra_automation_id"
        const val EXTRA_AUTOMATION_TYPE = "extra_automation_type"
        const val EXTRA_AUTOMATION_MESSAGE = "extra_automation_message"
        const val EXTRA_AUTOMATION_MUSIC_QUERY = "extra_automation_music_query"
        const val EXTRA_AUTOMATION_ANNOUNCE = "extra_automation_announce"
        const val EXTRA_AUTOMATION_IS_PRE = "extra_automation_is_pre"
        const val EXTRA_AUTOMATION_ALREADY_SPOKEN = "extra_automation_already_spoken"
        const val EXTRA_AUTOMATION_REMINDER_TEXT = "extra_automation_reminder_text"
        const val EXTRA_AUTOMATION_SPEAK = "extra_automation_speak"
        const val EXTRA_TEXT_INPUT = "extra_text_input"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            ActionRecordManager.recordAction(this, "MainActivity", "App onCreate started - Initializing UI")
            ThemeUtils.applyTheme(this)
            super.onCreate(savedInstanceState)
            
            AppLog.d("MainActivity", "Theme applied")
            
            if (!OnboardingActivity.isOnboardingDone(this)) {
                ActionRecordManager.recordAction(this, "MainActivity", "Redirecting to Onboarding")
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            ActionRecordManager.recordAction(this, "MainActivity", "UI Inflation complete")

            if (savedInstanceState == null) {
                supportFragmentManager.commit {
                    replace(R.id.mainContentFrame, MainFragment())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            handleAutomationIntent(intent)
            handleProactiveIntent(intent)

            updateOfflineBanner()
            showBatteryOptimizationPromptOnce()
            
            if (WakeWordPrefs.isEnabled(this)) {
                startWakeWordService()
            }

            // Instant init using cache
            viewModel.initClientWithCache()

            mainScope.launch {
                val (memoryContext, proactiveContext) = viewModel.loadContextAndInit()
                runOnUiThread {
                    VisionCaptureBridge.onCaptureRequested = {
                        (supportFragmentManager.findFragmentById(R.id.mainContentFrame) as? MainFragment)
                            ?.captureVisionFrame()
                    }
                    viewModel.listener = object : MainActivityViewModel.MainActivityViewModelListener {
                        override fun onSpeakOfflineResponse(text: String) { speakOfflineResponse(text) }
                        override fun onSyncGemUi() { /* UI refresh */ }
                        override fun onCaptureVisionFrame() {
                            VisionCaptureBridge.requestCapture()
                        }
                        override fun updateConnectivity(connected: Boolean) {
                            // optional additional sync
                        }
                        override fun isTtsSpeaking(): Boolean {
                            return reusableTtsSpeaker.isSpeaking()
                        }
                    }
                    // This will update the client if not already connected, or just keep it for next time
                    viewModel.initClient(memoryContext, proactiveContext)
                    
                    if (pendingConnectOnInit && !isFinishing) {
                        pendingConnectOnInit = false
                        if (!NetworkUtils.isOnline(this@MainActivity)) {
                            pendingProactiveMessage?.let { msg ->
                                speakOfflineResponse(msg)
                            }
                        } else {
                            viewModel.connect(requestMic = true)
                            pendingProactiveMessage?.let { msg ->
                                mainHandler.postDelayed({ processProactivePrompt(msg, pendingProactiveType) }, 1000)
                            }
                        }
                    } else if (!isFinishing && !isAutomationSession) {
                        // Phase 1.1: Background setup (Memory triggering/Activation) while idle
                        if (NetworkUtils.isOnline(this@MainActivity)) {
                            viewModel.connect(requestMic = false)
                        }
                    }
                }
            }
            checkAndRequestPermissions()
        } catch (t: Throwable) {
            Log.e("MainActivity", "FATAL in onCreate", t)
            finish()
        }
    }

    private fun startWakeWordService() {
        if (!permissionManager.hasPermission(Manifest.permission.RECORD_AUDIO)) return
        try {
            val wwIntent = Intent(this, WakeWordListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(wwIntent)
            } else {
                startService(wwIntent)
            }
        } catch (_: Exception) { }
    }

    private fun updateTranscript(text: String) {
        viewModel.updateTranscript(text)
    }

    private fun updateStatus(text: String) {
        viewModel.updateStatus(text)
    }

    private fun handleProactiveIntent(intent: Intent?) {
        val payload = ProactiveIntentPayload.fromIntent(intent) ?: return
        isAutomationSession = true
        pendingProactiveType = payload.type
        intent?.removeExtra(EXTRA_PROACTIVE_SPEAK)

        if (!NetworkUtils.isOnline(this)) {
            speakOfflineResponse(payload.message)
            return
        }

        if (viewModel.isClientInitialized) {
            if (!viewModel.geminiClient.isConnected()) {
                viewModel.connect()
            }
            processProactivePrompt(payload.message, payload.type)
        } else {
            pendingProactiveMessage = payload.message
            pendingConnectOnInit = true
        }
    }

    private fun processProactivePrompt(proactive: String, pType: String?) {
        val prompt = ProactivePromptHelper.buildPrompt(proactive, pType)
        viewModel.geminiClient.sendTextInput(prompt)
        updateTranscript(proactive.take(100))
    }

    private fun handleAutomationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOMATION_RUN, false) != true) return
        isAutomationSession = true
        val id = intent.getIntExtra(EXTRA_AUTOMATION_ID, -1)
        val payload = AssistantAutomationPayload.fromIntent(intent)
        pendingAutomationRun = AutomationRun(
            id = id,
            type = payload.type.ifBlank { "reminder" },
            message = payload.message,
            announce = payload.announceText,
            musicQuery = payload.musicQuery,
            isPreReminder = payload.isPreReminder
        )
        intent.removeExtra(EXTRA_AUTOMATION_RUN)
        mainHandler.post { runAutomationIfPending() }
    }

    private fun runAutomationIfPending() {
        val run = pendingAutomationRun ?: return
        pendingAutomationRun = null
        mainScope.launch {
            speakAnnouncementIfNeeded(run.announce)
            val result = withContext(Dispatchers.IO) { executeAutomationRun(run) }
            deliverOfflineResult(result)
        }
    }

    private suspend fun executeAutomationRun(run: AutomationRun): String {
        return automationCommandRunner.run(
            type = run.type,
            message = run.message,
            musicQuery = run.musicQuery,
            isPreReminder = run.isPreReminder
        )
    }

    private fun speakAnnouncementIfNeeded(announce: String) {
        if (announce.isNotBlank()) {
            speakOfflineResponse(announce)
        }
    }

    private fun deliverOfflineResult(result: String) {
        updateTranscript(result)
        speakOfflineResponse(result)
    }

    private fun speakOfflineResponse(text: String) {
        if (text.isBlank()) return

        val speechText = normalizeOfflineSpeech(text)
        reusableTtsSpeaker.speak(speechText, "main_offline_response")
    }

    private fun normalizeOfflineSpeech(text: String): String {
        return if (text.startsWith("Error", ignoreCase = true)) {
            ErrorUtils.toUserMessage(this, text)
        } else {
            text
        }
    }

    private fun updateOfflineBanner() {
        if (!NetworkUtils.isOnline(this)) {
            updateStatus("Offline mode")
            showToast("Offline mode active")
        } else if (viewModel.statusMessage.value.isBlank()) {
            updateStatus(getString(R.string.tap_to_start))
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }

    private fun showBatteryOptimizationPromptOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences("main_prompts", MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompt_shown", false)) return

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
        showToast(
            "Disable battery optimization for better wake word and routine reliability.",
            Toast.LENGTH_LONG
        )
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = mutableListOf<String>()
        if (!permissionManager.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missingPermissions += Manifest.permission.RECORD_AUDIO
        }
        if (!permissionManager.hasPermission(Manifest.permission.READ_CONTACTS)) {
            missingPermissions += Manifest.permission.READ_CONTACTS
        }
        if (!permissionManager.hasPermission(Manifest.permission.CALL_PHONE)) {
            missingPermissions += Manifest.permission.CALL_PHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !permissionManager.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missingPermissions.isNotEmpty()) {
            val showRationale = missingPermissions.any { permissionManager.shouldShowRationale(it) }
            if (showRationale) {
                permissionManager.showRationaleDialog(
                    title = "ခွင့်ပြုချက် လိုအပ်ခြင်း",
                    message = "Voice Brain စနစ် အပြည့်အဝ အသုံးပြုနိုင်ရန် လိုအပ်သော ခွင့်ပြုချက်များကို ပေးပါ။",
                    onPositive = {
                        requestPermissionLauncher.launch(missingPermissions.toTypedArray())
                    }
                )
            } else {
                requestPermissionLauncher.launch(missingPermissions.toTypedArray())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Turn screen on and show over lock screen if this is a proactive/automation wake-up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        handleAutomationIntent(intent)
        handleProactiveIntent(intent)
    }

    override fun onPause() {
        com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
        super.onPause()
    }

    override fun onResume() {
        com.example.voicebrainlive.commands.ApprovalUiLease.resume(this)
        super.onResume()
        // Ensure Floating Bubble remains active if enabled
        if (BubblePrefs.isEnabled(this) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
            if (FloatingBubbleService.instance == null && VoiceLiveService.instance == null) {
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
                    Log.w("MainActivity", "Failed to keep floating bubble active: ${e.message}")
                }
            }
        }

        // Ensure screen wakes up and shows over keyguard if launched for proactive speak/automation
        val currentIntent = intent
        if (currentIntent != null && (currentIntent.hasExtra(EXTRA_PROACTIVE_SPEAK) || currentIntent.getBooleanExtra(EXTRA_AUTOMATION_RUN, false))) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val service = VoiceLiveService.instance
        if (service != null) {
            if (service.isStartedFromPowerButton) {
                // Redirect to AssistantActivity so the user sees the transparent overlay
                val intent = Intent(this, AssistantActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } else {
                // Session was started in-app, stop the background service and resume locally
                stopService(Intent(this, VoiceLiveService::class.java))
                mainHandler.postDelayed({
                    viewModel.connect(requestMic = true)
                }, 300)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If the voice session is active locally in MainActivityViewModel, transition it to background service!
        val isVoiceActive = viewModel.isClientInitialized && viewModel.geminiClient.isConnected()
        if (isVoiceActive) {
            // Stop local connection to free up microphone and websocket
            viewModel.disconnect()

            if (!permissionManager.hasPermission(Manifest.permission.RECORD_AUDIO)) return
            
            // Start VoiceLiveService in background
            val serviceIntent = Intent(this, VoiceLiveService::class.java).apply {
                action = VoiceLiveService.ACTION_START
                putExtra("EXTRA_POWER_BUTTON", false)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                VoiceLiveService.isUiVisible = false
            } catch (error: Exception) {
                Log.w("MainActivity", "Background voice handoff unavailable", error)
                viewModel.updateStatus(getString(R.string.background_voice_unavailable))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        VisionCaptureBridge.onCaptureRequested = null
        mainScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        viewModel.clearCallbacks()
        if (reusableTtsSpeakerDelegate.isInitialized()) reusableTtsSpeaker.shutdown()
    }

    private data class AutomationRun(
        val id: Int,
        val type: String,
        val message: String,
        val announce: String,
        val musicQuery: String,
        val isPreReminder: Boolean,
    )
}
