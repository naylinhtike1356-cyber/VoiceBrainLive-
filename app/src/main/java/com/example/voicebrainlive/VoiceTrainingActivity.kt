package com.example.voicebrainlive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.audio.AudioRecorderHelper
import com.example.voicebrainlive.databinding.ActivityVoiceTrainingBinding
import com.example.voicebrainlive.voice.VoiceProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Bixby-style Voice Profile training: record 5 samples of user's voice.
 */
class VoiceTrainingActivity : ComponentActivity() {

    private lateinit var binding: ActivityVoiceTrainingBinding
    private lateinit var profileManager: VoiceProfileManager
    private lateinit var audioRecorder: AudioRecorderHelper
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recordedChunks = AtomicReference<List<String>>(emptyList())

    private val samplePhrases = listOf(
        "ဟယ်လို ဗြူးဘရိန်း",
        "အချိန်နဲ့ ရာသီဥတုပြပေး",
        "ယနေ့ ရက်စွဲပြောပါ",
        "ကျေးဇူးတင်ပါတယ်",
        "ဗြူးဘရိန်း ပြီးပါပြီ"
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecordingSample() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applyTheme(this)
        binding = ActivityVoiceTrainingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }
        profileManager = VoiceProfileManager(this)
        audioRecorder = AudioRecorderHelper()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnResetProfile?.setOnClickListener {
            profileManager.clearProfile()
            updateProgress()
            updateSamplePhrase()
            binding.tvStatus.text = getString(R.string.voice_profile_reset)
            Toast.makeText(this, getString(R.string.voice_profile_reset), Toast.LENGTH_SHORT).show()
        }
        binding.btnRecord.setOnClickListener {
            if (!hasMicPermission()) {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (profileManager.getSampleCount() >= 5) {
                Toast.makeText(this, "မှတ်သားပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                startRecordingSample()
            }
        }

        updateProgress()
        updateSamplePhrase()
    }

    private fun updateSamplePhrase() {
        val idx = profileManager.getSampleCount()
        binding.tvSamplePhrase.text = samplePhrases.getOrElse(idx) { samplePhrases.last() }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startRecordingSample() {
        val count = profileManager.getSampleCount()
        binding.tvStatus.text = "နားထောင်နေသည်… (၂ စက္ကန့်)"
        binding.recordAnimationView.visibility = android.view.View.VISIBLE
        startPulseAnimation()
        recordedChunks.set(emptyList())

        audioRecorder.useVoiceCommunicationForNoisyEnv = true // Extra noise suppression
        audioRecorder.onAudioChunk = { base64 ->
            val list = recordedChunks.get().toMutableList()
            list.add(base64)
            recordedChunks.set(list)
        }
        audioRecorder.onError = { 
            binding.tvStatus.text = it
            stopPulseAnimation()
        }
        audioRecorder.start(scope)

        scope.launch {
            delay(2000) // Increased to 2s for better accuracy
            stopAndSave()
        }
    }

    private fun startPulseAnimation() {
        binding.recordAnimationView.animate()
            .scaleX(1.4f)
            .scaleY(1.4f)
            .alpha(0f)
            .setDuration(800)
            .withEndAction {
                if (audioRecorder.isRecording()) {
                    binding.recordAnimationView.scaleX = 1f
                    binding.recordAnimationView.scaleY = 1f
                    binding.recordAnimationView.alpha = 1f
                    startPulseAnimation()
                }
            }.start()
    }

    private fun stopPulseAnimation() {
        binding.recordAnimationView.animate().cancel()
        binding.recordAnimationView.visibility = android.view.View.GONE
    }

    private fun stopAndSave() {
        audioRecorder.stop()
        stopPulseAnimation()
        binding.tvStatus.text = "အသံဖိုင် သိမ်းဆည်းနေပါသည်…"
        
        val chunks = recordedChunks.get()
        val bytes = chunks.map { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            .reduceOrNull { a, b -> a + b } ?: ByteArray(0)
        
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        
        scope.launch(Dispatchers.Default) {
            val ok = profileManager.addTrainingSample(base64)
            withContext(Dispatchers.Main) {
                updateProgress()
                updateSamplePhrase()
                if (ok) {
                    binding.tvStatus.text = "အားလုံး ပြီးပါပြီ!"
                    Toast.makeText(this@VoiceTrainingActivity, "အသံမှတ်သားမှု အောင်မြင်ပါသည်", Toast.LENGTH_SHORT).show()
                    delay(800)
                    finish()
                } else {
                    val n = profileManager.getSampleCount()
                    binding.tvStatus.text = if (bytes.size < 16000) {
                        "အသံတိုးလွန်းနေသည် သို့မဟုတ် တိုလွန်းနေသည်"
                    } else {
                        "နမူနာ ($n/5) သိမ်းဆည်းပြီးပါပြီ။ နောက်တစ်ခု ဆက်ပြောပါ။"
                    }
                }
            }
        }
    }

    private fun updateProgress() {
        val n = profileManager.getSampleCount()
        binding.progressSamples.progress = n
        binding.tvProgress.text = "$n / ၅ မှတ်သားပြီး"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { audioRecorder.stop() } catch (_: Exception) {}
    }
}
