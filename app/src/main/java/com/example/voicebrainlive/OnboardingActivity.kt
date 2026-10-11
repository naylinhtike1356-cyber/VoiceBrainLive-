package com.example.voicebrainlive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.databinding.ActivityOnboardingBinding

/**
 * Onboarding: API key, Mic permission, Power button assistant.
 * Shown only on first launch; completion is stored in SharedPreferences.
 */
class OnboardingActivity : ComponentActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var currentStep = 0
    private val totalSteps = 3

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Toast.makeText(this, getString(R.string.tap_to_start), Toast.LENGTH_SHORT).show()
            goToStep(2)
        } else {
            Toast.makeText(this, getString(R.string.mic_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            ThemeUtils.applyTheme(this)
            super.onCreate(savedInstanceState)
            if (isOnboardingDone(this)) {
                startMainAndFinish()
                return
            }
            binding = ActivityOnboardingBinding.inflate(layoutInflater)
            setContentView(binding.root)

            binding.btnSkip.setOnClickListener {
            setOnboardingDone()
            startMainAndFinish()
        }

        binding.btnNext.setOnClickListener {
            if (currentStep < totalSteps - 1) {
                goToStep(currentStep + 1)
            }
        }

        binding.btnStep1Settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnStep2Allow.setOnClickListener {
            val missingPermissions = requiredVoicePermissions()
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
            if (missingPermissions.isEmpty()) {
                goToStep(2)
            } else {
                permissionLauncher.launch(missingPermissions)
            }
        }

        binding.btnStep3GetStarted.setOnClickListener {
            setOnboardingDone()
            startMainAndFinish()
        }

            updateStepIndicators()
            updateBottomButtons()
        } catch (t: Throwable) {
            Log.e("CRASH_LOG", "OnboardingActivity FATAL in onCreate", t)
            Log.e("CRASH_LOG", "Stack: ${t.stackTraceToString()}")
            t.printStackTrace()
            Toast.makeText(this, "Onboarding error: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun goToStep(step: Int) {
        if (step < 0 || step >= totalSteps) return
        currentStep = step
        binding.onboardingFlipper.setDisplayedChild(step)
        updateStepIndicators()
        updateBottomButtons()
    }

    private fun requiredVoicePermissions(): Array<String> = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    )

    private fun updateStepIndicators() {
        binding.dot1.alpha = if (currentStep == 0) 1f else 0.4f
        binding.dot2.alpha = if (currentStep == 1) 1f else 0.4f
        binding.dot3.alpha = if (currentStep == 2) 1f else 0.4f
    }

    private fun updateBottomButtons() {
        binding.btnNext.visibility = if (currentStep < totalSteps - 1) View.VISIBLE else View.GONE
    }

    private fun startMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val PREFS_ONBOARDING = "onboarding"
        private const val KEY_DONE = "onboarding_done"

        fun isOnboardingDone(context: android.content.Context): Boolean {
            return context.getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE).getBoolean(KEY_DONE, false)
        }

        fun setOnboardingDone(context: android.content.Context) {
            context.getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        }
    }

    private fun setOnboardingDone() {
        setOnboardingDone(this)
    }
}
