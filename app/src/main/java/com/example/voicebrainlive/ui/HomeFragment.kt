package com.example.voicebrainlive.ui

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.*
import com.example.voicebrainlive.R
import com.example.voicebrainlive.ui.components.DashboardItem
import com.example.voicebrainlive.ui.components.HomeDashboardScreen
import com.example.voicebrainlive.ui.components.HomeConnectionState
import com.example.voicebrainlive.ui.components.VoiceStatus
import com.example.voicebrainlive.ui.theme.VoiceBrainLiveTheme
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val viewModel: MainActivityViewModel by activityViewModels()
    private var hasGeminiKey by mutableStateOf(false)
    private var online by mutableStateOf(false)
    private var offlineRecognizer: SpeechRecognizer? = null
    private var offlineListening by mutableStateOf(false)
    private val offlineMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (view == null) return@registerForActivityResult
        if (granted) startOfflineVoice()
        else viewModel.updateStatus(getString(R.string.mic_permission_required))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as? InteractiveBetaHost)?.let { return it.betaHomeView() }
        val appContext = requireContext().applicationContext
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        online = NetworkUtils.isOnline(appContext)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                VoiceBrainLiveTheme(darkTheme = true, dynamicColor = false) {
                    DisposableEffect(connectivity) {
                        val callback = object : ConnectivityManager.NetworkCallback() {
                            private fun refresh() {
                                post { online = NetworkUtils.isOnline(appContext) }
                            }
                            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
                            override fun onLost(network: Network) = refresh()
                        }
                        connectivity?.registerDefaultNetworkCallback(callback)
                        onDispose { connectivity?.unregisterNetworkCallback(callback) }
                    }
                    val uiState by viewModel.uiState.collectAsState(initial = AssistantState.Idle)
                    val transcript by viewModel.transcript.collectAsState(initial = "")
                    val statusMessage by viewModel.statusMessage.collectAsState(initial = getString(R.string.tap_to_start))

                    val voiceStatus = when (if (offlineListening) AssistantState.Listening else uiState) {
                        is AssistantState.Listening -> VoiceStatus.LISTENING
                        is AssistantState.Speaking -> VoiceStatus.SPEAKING
                        is AssistantState.Processing -> VoiceStatus.PROCESSING
                        else -> VoiceStatus.IDLE
                    }
                    val connectionState = when {
                        !online -> HomeConnectionState.OFFLINE
                        !hasGeminiKey -> HomeConnectionState.SETUP_REQUIRED
                        uiState is AssistantState.Connecting -> HomeConnectionState.CONNECTING
                        uiState is AssistantState.Error -> HomeConnectionState.ERROR
                        else -> HomeConnectionState.READY
                    }
                    val onVoiceAction = {
                        performHaptic()
                        when {
                            offlineListening -> stopOfflineVoice()
                            uiState is AssistantState.Listening || uiState is AssistantState.Speaking ->
                                viewModel.stopListening()
                            uiState is AssistantState.Connecting || uiState is AssistantState.Processing -> Unit
                            !NetworkUtils.isOnline(appContext) || !viewModel.hasUsableGeminiApiKey() -> startOfflineVoice()
                            else -> viewModel.connect(requestMic = true)
                        }
                    }

                    val isWakeActive = WakeWordPrefs.isEnabled(requireContext())
                    val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    val isBatteryOptimized = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        powerManager?.isIgnoringBatteryOptimizations(requireContext().packageName) != true
                    } else true

                    val pendingTasks = remember(uiState) { loadPendingTasks(requireContext()) }

                    HomeDashboardScreen(
                        statusText = statusMessage,
                        connectionState = connectionState,
                        onSetupClick = {
                            startActivity(SettingsActivity.geminiSetupIntent(requireContext()))
                        },
                        transcriptText = transcript,
                        voiceStatus = voiceStatus,
                        wakeWordActive = isWakeActive,
                        batteryOptimized = isBatteryOptimized,
                        pendingTasks = pendingTasks,
                        onTaskClick = { task ->
                            performHaptic()
                            startActivity(Intent(context, AutomationActivity::class.java))
                        },
                        onViewAllTasks = {
                            performHaptic()
                            startActivity(Intent(context, AutomationActivity::class.java))
                        },
                        onMicClick = onVoiceAction,
                        onTextSubmit = { text ->
                            performHaptic()
                            if (text.isNotBlank()) {
                                sendText(text)
                            }
                        },
                        onQuickAsk = onVoiceAction,
                        onQuickRemember = {
                            performHaptic()
                            startActivity(Intent(context, com.example.voicebrainlive.ui.MemoryManagerActivity::class.java))
                        },
                        onQuickAutomate = {
                            performHaptic()
                            startActivity(Intent(context, AutomationActivity::class.java))
                        },
                        onStatusCardClick = {
                            performHaptic()
                            startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        dashboardItems = listOf(
                            DashboardItem("🔔", "သတိပေးချက်များ", R.drawable.ic_reminder) {
                                performHaptic()
                                startActivity(Intent(context, RemindersActivity::class.java))
                            },
                            DashboardItem("📝", "မှတ်စုနှင့် Notion", R.drawable.ic_notes) {
                                performHaptic()
                                startActivity(Intent(context, NotesActivity::class.java))
                            },
                            DashboardItem("👥", "အဆက်အသွယ်များ", R.drawable.ic_contacts) {
                                performHaptic()
                                startActivity(Intent(context, ContactsActivity::class.java))
                            },
                            DashboardItem("🕒", "ပြောဆိုမှတ်တမ်း", R.drawable.ic_history) {
                                performHaptic()
                                startActivity(Intent(context, HistoryActivity::class.java))
                            }
                        )
                    )
                }
            }
        }
    }

    private fun performHaptic() {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun loadPendingTasks(context: Context): List<com.example.voicebrainlive.ui.components.PendingTaskCardModel> {
        val list = mutableListOf<com.example.voicebrainlive.ui.components.PendingTaskCardModel>()
        try {
            // 1. Reminders
            val reminders = ReminderHelper.getReminders(context)
                .filter { !it.isPast }
                .sortedBy { it.triggerTime }
            reminders.take(4).forEach { r ->
                list.add(
                    com.example.voicebrainlive.ui.components.PendingTaskCardModel(
                        id = "rem_${r.id}",
                        title = r.message.ifBlank { "သတိပေးချက်" },
                        subtitle = "အချိန်ရောက်လျှင် သတိပေးချက် အသံထွက်မည်",
                        timeLabel = r.formatTime(context),
                        categoryLabel = "သတိပေးချက်",
                        statusLabel = "အချိန်စောင့်ဆိုင်းဆဲ",
                        icon = "⏰",
                        type = "reminder"
                    )
                )
            }

            // 2. Active Routines
            val routines = com.example.voicebrainlive.routines.RoutineStorage.getAll(context)
                .filter { it.enabled }
                .sortedBy { com.example.voicebrainlive.routines.RoutineScheduler.nextTriggerTime(it.hour, it.minute, it.dayOfWeek) }
            routines.take(3).forEach { rt ->
                val nextTime = com.example.voicebrainlive.routines.RoutineScheduler.nextTriggerTime(rt.hour, rt.minute, rt.dayOfWeek)
                val diffMin = ((nextTime - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                val timeStr = if (diffMin < 60) "နောက် $diffMin မိနစ်တွင်" else rt.timeLabel()
                val icon = when (rt.type) {
                    "weather" -> "🌤️"
                    "music" -> "🎵"
                    "daily_log" -> "📊"
                    else -> "⚙️"
                }
                val title = when (rt.type) {
                    "weather" -> "ရာသီဥတု သတင်း ရူတင်း"
                    "music" -> if (rt.musicQuery.isNotBlank()) "သီချင်း: ${rt.musicQuery}" else "သီချင်း ရူတင်း"
                    "daily_log" -> "လစာမှတ်တမ်း စစ်ဆေးမှု"
                    else -> rt.message.ifBlank { "ပုံမှန် ရူတင်း" }
                }
                list.add(
                    com.example.voicebrainlive.ui.components.PendingTaskCardModel(
                        id = "rt_${rt.id}",
                        title = title,
                        subtitle = "အလိုအလျောက် ဆောင်ရွက်မည့် အစီအစဉ်",
                        timeLabel = timeStr,
                        categoryLabel = "ရူတင်း",
                        statusLabel = "သတ်မှတ်ထားသည်",
                        icon = icon,
                        type = "routine"
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "loadPendingTasks", e)
        }
        return list
    }

    private fun openNotionDatabase() {
        val dbId = SettingsActivity.getNotionDatabaseId(requireContext())
            ?.replace("-", "")?.replace("\u2013", "")?.replace("\u2014", "")?.trim()

        if (dbId.isNullOrBlank()) {
            Toast.makeText(context, "Notion Database ID မရှိသေးပါ၊ Settings တွင် ထည့်သွင်းပေးပါ", Toast.LENGTH_SHORT).show()
            startActivity(Intent(context, SettingsActivity::class.java))
            return
        }

        val url = "https://www.notion.so/$dbId"
        val customSchemeUrl = "notion://www.notion.so/$dbId"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("notion.id")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(customSchemeUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e3: Exception) {
                    Toast.makeText(context, "Notion ကို ဖွင့်၍မရပါ: ${e3.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendText(text: String) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }

        viewModel.processUserText(text)
    }

    private fun executeLocalText(text: String) {
        lifecycleScope.launch {
            val result = viewModel.executeLocalTextCommand(text)
            viewModel.updateStatus(result)
            viewModel.onSpeakOfflineResponse(result)
        }
    }

    override fun onResume() {
        super.onResume()
        hasGeminiKey = viewModel.hasUsableGeminiApiKey()
    }

    override fun onStop() {
        stopOfflineVoice()
        super.onStop()
    }

    private fun stopOfflineVoice() {
        val recognizer = offlineRecognizer
        offlineRecognizer = null
        offlineListening = false
        recognizer?.destroy()
    }

    private fun startOfflineVoice() {
        if (offlineRecognizer != null) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            offlineMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            viewModel.updateStatus(getString(R.string.offline_stt_unavailable))
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        offlineRecognizer = recognizer
        offlineListening = true
        viewModel.updateStatus("ဖုန်းအမိန့်ကို နားထောင်နေသည်")
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (offlineRecognizer !== recognizer) return
                stopOfflineVoice()
                viewModel.updateStatus("အသံအမိန့် မရရှိပါ။ ပြန်ပြောပါ သို့မဟုတ် စာရိုက်ပါ။")
            }
            override fun onResults(results: Bundle?) {
                if (offlineRecognizer !== recognizer) return
                stopOfflineVoice()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    executeLocalText(text)
                } else {
                    viewModel.updateStatus("အသံအမိန့် မရရှိပါ။ ပြန်ပြောပါ သို့မဟုတ် စာရိုက်ပါ။")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM")
        }
        try {
            recognizer.startListening(intent)
        } catch (_: RuntimeException) {
            stopOfflineVoice()
            viewModel.updateStatus(getString(R.string.offline_stt_unavailable))
        }
    }
}
