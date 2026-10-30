package com.example.voicebrainlive.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.*
import com.example.voicebrainlive.R
import com.example.voicebrainlive.ui.components.DashboardItem
import com.example.voicebrainlive.ui.components.HomeDashboardScreen
import com.example.voicebrainlive.ui.components.VoiceStatus
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val viewModel: MainActivityViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as? InteractiveBetaHost)?.let { return it.betaHomeView() }
        return ComposeView(requireContext()).apply {
            setContent {
                val uiState by viewModel.uiState.collectAsState(initial = AssistantState.Idle)
                val transcript by viewModel.transcript.collectAsState(initial = "")
                val statusMessage by viewModel.statusMessage.collectAsState(initial = getString(R.string.tap_to_start))

                val voiceStatus = when (uiState) {
                    is AssistantState.Listening -> VoiceStatus.LISTENING
                    is AssistantState.Speaking -> VoiceStatus.SPEAKING
                    is AssistantState.Processing -> VoiceStatus.PROCESSING
                    else -> VoiceStatus.IDLE
                }

                val isWakeActive = WakeWordPrefs.isEnabled(requireContext())
                val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val isBatteryOptimized = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    powerManager?.isIgnoringBatteryOptimizations(requireContext().packageName) != true
                } else true

                HomeDashboardScreen(
                    statusText = statusMessage,
                    transcriptText = transcript,
                    voiceStatus = voiceStatus,
                    wakeWordActive = isWakeActive,
                    batteryOptimized = isBatteryOptimized,
                    onMicClick = {
                        performHaptic()
                        if (!NetworkUtils.isOnline(requireContext()) || !viewModel.hasUsableGeminiApiKey()) {
                            startOfflineVoice()
                        } else {
                            if (uiState is AssistantState.Listening || uiState is AssistantState.Speaking) {
                                viewModel.stopListening()
                            } else {
                                viewModel.connect(requestMic = true)
                            }
                        }
                    },
                    onTextSubmit = { text ->
                        performHaptic()
                        if (text.isNotBlank()) {
                            sendText(text)
                        }
                    },
                    onQuickAsk = {
                        performHaptic()
                        if (!NetworkUtils.isOnline(requireContext()) || !viewModel.hasUsableGeminiApiKey()) {
                            startOfflineVoice()
                        } else {
                            viewModel.connect(requestMic = true)
                        }
                    },
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

    private fun performHaptic() {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
            viewModel.onSpeakOfflineResponse(result)
        }
    }

    private fun startOfflineVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(context, getString(R.string.offline_stt_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    executeLocalText(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "my-MM")
        }
        recognizer.startListening(intent)
    }
}
