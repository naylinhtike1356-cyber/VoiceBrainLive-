package com.example.voicebrainlive

import android.Manifest
import android.util.Base64
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.voicebrainlive.databinding.ActivitySettingsBinding
import com.example.voicebrainlive.MediaSessionHelper
import com.example.voicebrainlive.SpotifyManager
import com.example.voicebrainlive.voice.VoiceProfileManager
import com.example.voicebrainlive.messenger.MessengerApiClient

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : FragmentActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var securePrefs: SharedPreferences

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->

        if (granted && WakeWordPrefs.isEnabled(this)) {
            startWakeWordService()
            Toast.makeText(this, "Hey Voice / Voice Brain ပြောပြီး နှိုးလို့ရပါပြီ", Toast.LENGTH_SHORT).show()
        } else if (!granted) {
            WakeWordPrefs.setEnabled(this, false)
            binding.switchWakeWord.isChecked = false
        }
    }

    companion object {
        private const val PREFS_NAME = "secure_settings"
        const val KEY_GEMINI_API = "gemini_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_OPENAI_API = "openai_api_key"
        const val KEY_NOTION_TOKEN = "notion_token"
        const val KEY_NOTION_DATABASE_ID = "notion_database_id"
        const val KEY_VOICE = "voice"
        const val KEY_VOICE_PROFILE_ENABLED = "voice_profile_enabled"
        const val KEY_SHOW_ERROR_DETAILS = "show_error_details"
        const val KEY_NOISY_ENV_MODE = "noisy_env_soft_speech_mode"
        const val KEY_IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"
        const val KEY_SILENCE_TIMEOUT_VALUE = "silence_timeout_value"
        const val KEY_SILENCE_TIMEOUT_UNIT = "silence_timeout_unit"
        const val KEY_TIMEZONE_ID = "timezone_id"
        const val KEY_REMINDER_REPEAT_MINUTES = "reminder_repeat_minutes"
        const val KEY_SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
        const val KEY_SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token"
        const val KEY_SPOTIFY_PKCE_VERIFIER = "spotify_pkce_verifier"
        // Notification settings
        const val KEY_SELECTED_NOTIFICATION_APPS = "selected_notification_apps"
        const val KEY_NOTIFICATION_AUTO_TIMEOUT_SECONDS = "notification_auto_timeout_seconds"
        const val KEY_HEALTH_ASSISTANT_ENABLED = "health_assistant_enabled"
        const val KEY_HEALTH_ROUTINE_TIME = "health_routine_time"

        fun isHealthAssistantEnabled(context: Context): Boolean =
            getSecurePrefs(context).getBoolean(KEY_HEALTH_ASSISTANT_ENABLED, true)

        fun getHealthRoutineTime(context: Context): String =
            getSecurePrefs(context).getString(KEY_HEALTH_ROUTINE_TIME, "08:00") ?: "08:00"
        /** Future Silero VAD gate – see docs/PHASE_E_DEFERRED.md */
        const val KEY_VAD_ENABLED = "vad_enabled"
        const val KEY_TEST_CALL_MODE = "test_call_mode"

        fun getSelectedNotificationApps(context: Context): Set<String> {
            val prefs = getSecurePrefs(context)
            val defaultApps = setOf(
                "com.facebook.orca", "com.whatsapp", "com.google.android.apps.messaging",
                "com.samsung.android.messaging", "com.samsung.android.weather",
                "com.google.android.weather", "com.facebook.katana"
            )
            return prefs.getStringSet(KEY_SELECTED_NOTIFICATION_APPS, defaultApps) ?: defaultApps
        }

        fun getNotificationAutoTimeoutSeconds(context: Context): Int {
            return getSecurePrefs(context).getInt(KEY_NOTIFICATION_AUTO_TIMEOUT_SECONDS, 10)
        }

        fun isVadEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VAD_ENABLED, false)
        }

        fun isVoiceProfileEnabled(context: Context): Boolean =
            getSecurePrefs(context).getBoolean(KEY_VOICE_PROFILE_ENABLED, false)

        fun isTestCallMode(context: Context): Boolean =
            getSecurePrefs(context).getBoolean(KEY_TEST_CALL_MODE, false)

        /**
         * Returns SharedPreferences backed by Android Keystore.
         * API keys and tokens are encrypted with AES256-GCM.
         * Keys are stored in hardware-backed Keystore when available.
         */
        fun getSecurePrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setUserAuthenticationRequired(false) // Keys available when device locked
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("SettingsActivity", "EncryptedSharedPreferences creation failed, falling back to plaintext storage!", e)
                try {
                    com.example.voicebrainlive.ActionRecordManager.recordAction(context, "Security", "EncryptedSharedPreferences failed, fallback to plaintext: ${e.message}", isError = true)
                } catch (_: Exception) {}
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }

        fun getGeminiApiKey(context: Context): String {
            val prefs = getSecurePrefs(context)
            val savedKey = prefs.getString(KEY_GEMINI_API, null)
            return if (!savedKey.isNullOrBlank()) {
                savedKey
            } else {
                BuildConfig.GEMINI_API_KEY
            }
        }

        fun getGeminiModel(context: Context): String {
            val prefs = getSecurePrefs(context)
            val model = prefs.getString(KEY_GEMINI_MODEL, "models/gemini-3.1-flash-live-preview")
                ?: "models/gemini-3.1-flash-live-preview"
            if (model.contains("gemini-2.0-flash-exp", ignoreCase = true) || model.isBlank()) {
                val fixed = "models/gemini-3.1-flash-live-preview"
                saveGeminiModel(context, fixed)
                return fixed
            }
            return model
        }

        fun saveGeminiModel(context: Context, model: String) {
            getSecurePrefs(context).edit().putString(KEY_GEMINI_MODEL, model).apply()
        }

        /** OpenAI API key for DALL-E image generation (add OPENAI_API_KEY in gradle.properties) */
        fun getOpenAIApiKey(context: Context): String? {
            val prefs = getSecurePrefs(context)
            val savedKey = prefs.getString(KEY_OPENAI_API, null)
            return when {
                !savedKey.isNullOrBlank() -> savedKey
                !BuildConfig.OPENAI_API_KEY.isNullOrBlank() -> BuildConfig.OPENAI_API_KEY
                else -> null
            }
        }

        fun getNotionToken(context: Context): String? {
            return getSecurePrefs(context).getString(KEY_NOTION_TOKEN, null)
        }

        fun getNotionDatabaseId(context: Context): String? {
            return getSecurePrefs(context).getString(KEY_NOTION_DATABASE_ID, null)
        }

        /** v2.0: Voice for Gemini (Aoede, Charon, Fenrir, Kore, Puck, Zephyr) */
        fun getVoice(context: Context): String =
            getSecurePrefs(context).getString(KEY_VOICE, "Aoede") ?: "Aoede"

        /** When true, Toast shows raw error for debugging. */
        fun isShowErrorDetails(context: Context): Boolean =
            getSecurePrefs(context).getBoolean(KEY_SHOW_ERROR_DETAILS, false)

        /** When true, use VOICE_COMMUNICATION for better noise filter + soft speech in noisy env. */
        fun isNoisyEnvMode(context: Context): Boolean =
            getSecurePrefs(context).getBoolean(KEY_NOISY_ENV_MODE, false)

        /** Idle timeout in minutes: 0 = never auto-close. When listening with no command, auto-close after this. */
        fun getIdleTimeoutMinutes(context: Context): Int =
            getSecurePrefs(context).getInt(KEY_IDLE_TIMEOUT_MINUTES, 0)

        /** Silence timeout in seconds: 0 = never auto-stop listening. */
        fun getSilenceTimeoutSeconds(context: Context): Int {
            val prefs = getSecurePrefs(context)
            val value = prefs.getInt(KEY_SILENCE_TIMEOUT_VALUE, 3) // Faster response default
            val unit = prefs.getString(KEY_SILENCE_TIMEOUT_UNIT, "s") ?: "s"
            return when (unit) {
                "m" -> value * 60
                "h" -> value * 3600
                else -> value
            }
        }

        /** Timezone ID for reminders (e.g. Asia/Yangon). Empty = device default. */
        fun getTimezoneId(context: Context): String =
            getSecurePrefs(context).getString(KEY_TIMEZONE_ID, "") ?: ""

        fun getTimezone(context: Context): java.util.TimeZone {
            val id = getTimezoneId(context)
            return if (id.isNotBlank()) {
                java.util.TimeZone.getTimeZone(id)
            } else {
                java.util.TimeZone.getDefault()
            }
        }

        /** Reminder repeat: 0 = no repeat. When > 0, re-alert every N minutes until user dismisses. */
        fun getReminderRepeatMinutes(context: Context): Int =
            getSecurePrefs(context).getInt(KEY_REMINDER_REPEAT_MINUTES, 0)

        fun getSpotifyAccessToken(context: Context): String? =
            getSecurePrefs(context).getString(KEY_SPOTIFY_ACCESS_TOKEN, null)

        fun getSpotifyRefreshToken(context: Context): String? =
            getSecurePrefs(context).getString(KEY_SPOTIFY_REFRESH_TOKEN, null)

        fun saveSpotifyTokens(context: Context, accessToken: String, refreshToken: String?) {
            getSecurePrefs(context).edit().apply {
                putString(KEY_SPOTIFY_ACCESS_TOKEN, accessToken)
                if (refreshToken != null) putString(KEY_SPOTIFY_REFRESH_TOKEN, refreshToken)
            }.apply()
        }

        fun clearSpotifyTokens(context: Context) {
            getSecurePrefs(context).edit()
                .remove(KEY_SPOTIFY_ACCESS_TOKEN)
                .remove(KEY_SPOTIFY_REFRESH_TOKEN)
                .apply()
        }

        fun setSpotifyPkceVerifier(context: Context, verifier: String) {
            getSecurePrefs(context).edit().putString(KEY_SPOTIFY_PKCE_VERIFIER, verifier).apply()
        }

        fun getSpotifyPkceVerifier(context: Context): String? =
            getSecurePrefs(context).getString(KEY_SPOTIFY_PKCE_VERIFIER, null)

        fun clearSpotifyPkceVerifier(context: Context) {
            getSecurePrefs(context).edit().remove(KEY_SPOTIFY_PKCE_VERIFIER).apply()
        }
    }

    private fun generateSpotifyPkce(): Pair<String, String> {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        val verifier = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val challenge = android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return Pair(verifier, challenge)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        securePrefs = getSecurePrefs(this)

        loadSettings()
        setupListeners()
        binding.btnAppUpdates.text = "App updates (${BuildConfig.VERSION_NAME})"
        binding.btnAppUpdates.setOnClickListener {
            startActivity(Intent(this, com.example.voicebrainlive.updates.AppUpdateActivity::class.java))
        }
    }

    private fun loadSettings() {
        binding.etGeminiApiKey.setText(securePrefs.getString(KEY_GEMINI_API, ""))
        binding.etNotionToken.setText(securePrefs.getString(KEY_NOTION_TOKEN, ""))
        binding.etNotionDatabaseId.setText(securePrefs.getString(KEY_NOTION_DATABASE_ID, ""))
        binding.etMessengerToken.setText(securePrefs.getString(MessengerApiClient.PREF_MESSENGER_TOKEN, ""))
        updateNotificationAccessStatus()
        updateNotificationAppsSummary()
        
        val logPath = ActionRecordManager.getDiagnosticFilePath(this)
        binding.tvLogPath.text = if (logPath != null) {
            "Computer နှင့် ချိတ်ဆက်ပြီး ဤနေရာတွင် စစ်ဆေးပါ -\nInternal Storage > Android > data > $packageName > files > Logs > diagnostics.txt"
        } else {
            "မှတ်တမ်းဖိုင် ရှာမတွေ့ပါ။"
        }
        
        val spinner = binding.spinnerTheme
        val themes = listOf("Dark", "Light")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = ThemeUtils.getTheme(this)
        spinner.setSelection(if (current == "light") 1 else 0)

        val voiceSpinner = binding.spinnerVoice
        val voices = listOf("Aoede", "Charon", "Fenrir", "Kore", "Puck", "Zephyr")
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedVoice = securePrefs.getString(KEY_VOICE, "Aoede") ?: "Aoede"
        val voiceIndex = voices.indexOf(savedVoice).coerceAtLeast(0)
        voiceSpinner.setSelection(voiceIndex)

        val modelSpinner = binding.spinnerGeminiModel
        val models = listOf(
            "Gemini 3 Flash Live (Default / Unlimited)",
            "Gemini 2.5 Flash Native Audio",
            "Gemini 2.0 Flash Realtime Exp",
            "Gemini 2.0 Flash"
        )
        val modelValues = listOf(
            "models/gemini-3.1-flash-live-preview",
            "models/gemini-2.5-flash-native-audio",
            "models/gemini-2.0-flash-realtime-exp",
            "models/gemini-2.0-flash"
        )
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedModel = getGeminiModel(this)
        val modelIndex = modelValues.indexOf(savedModel).coerceAtLeast(0)
        modelSpinner.setSelection(modelIndex)

        binding.switchBubble.isChecked = BubblePrefs.isEnabled(this)
        binding.layoutBubbleTheme.visibility = if (binding.switchBubble.isChecked) View.VISIBLE else View.GONE

        val bubbleThemes = BubblePrefs.BubbleTheme.entries
        val bubbleThemeLabels = bubbleThemes.map { it.titleMm }
        binding.spinnerBubbleTheme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bubbleThemeLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentBubbleTheme = BubblePrefs.getTheme(this)
        binding.spinnerBubbleTheme.setSelection(bubbleThemes.indexOf(currentBubbleTheme).coerceAtLeast(0))

        binding.spinnerBubbleTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTheme = bubbleThemes.getOrNull(position) ?: return
                if (selectedTheme != BubblePrefs.getTheme(this@SettingsActivity)) {
                    BubblePrefs.setTheme(this@SettingsActivity, selectedTheme)
                    val intent = Intent(this@SettingsActivity, FloatingBubbleService::class.java).apply {
                        action = FloatingBubbleService.ACTION_UPDATE_THEME
                    }
                    startService(intent)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.switchWakeWord.isChecked = WakeWordPrefs.isEnabled(this)
        binding.switchVoiceProfile.isChecked = securePrefs.getBoolean(KEY_VOICE_PROFILE_ENABLED, false)
        binding.switchShowErrorDetails.isChecked = securePrefs.getBoolean(KEY_SHOW_ERROR_DETAILS, false)
        binding.switchNoisyEnv.isChecked = securePrefs.getBoolean(KEY_NOISY_ENV_MODE, false)
        binding.switchTestCallMode.isChecked = securePrefs.getBoolean(KEY_TEST_CALL_MODE, false)

        val idleSpinner = binding.spinnerIdleTimeout
        val idleOptions = listOf("ပိတ်မထား (Never)", "၂ မိနစ်", "၅ မိနစ်", "၁၀ မိနစ်")
        val idleValues = listOf(0, 2, 5, 10)
        idleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, idleOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedIdle = securePrefs.getInt(KEY_IDLE_TIMEOUT_MINUTES, 0)
        val idleIndex = idleValues.indexOf(savedIdle).coerceIn(0, idleValues.size - 1)
        idleSpinner.setSelection(idleIndex)

        val silenceUnitSpinner = binding.spinnerSilenceTimeoutUnit
        val silenceUnitOptions = listOf("စက္ကန့် (Seconds)", "မိနစ် (Minutes)", "နာရီ (Hours)")
        val silenceUnitValues = listOf("s", "m", "h")
        silenceUnitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, silenceUnitOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedSilenceValue = securePrefs.getInt(KEY_SILENCE_TIMEOUT_VALUE, 3) // Set default to 3s for faster response
        val savedSilenceUnit = securePrefs.getString(KEY_SILENCE_TIMEOUT_UNIT, "s") ?: "s"
        
        binding.etSilenceTimeout.setText(if(savedSilenceValue == 0) "" else savedSilenceValue.toString())
        val silenceUnitIndex = silenceUnitValues.indexOf(savedSilenceUnit).coerceIn(0, silenceUnitValues.size - 1)
        silenceUnitSpinner.setSelection(silenceUnitIndex)

        val tzSpinner = binding.spinnerTimezone
        val tzOptions = listOf(
            "ကိုယ်ပိုင် (Device)", "မြန်မာ (Yangon)", "ထိုင်း (Bangkok)", "စင်္ကာပူ", "ဂျပန် (Tokyo)",
            "ကိုးရီး (Seoul)", "မလေးရှား (KL)", "အိန္ဒိယ (Kolkata)", "အမေရိကး အရှေ့", "ယူကေ (London)", "ဩစတြေလျ (Sydney)"
        )
        val tzIds = listOf("", "Asia/Yangon", "Asia/Bangkok", "Asia/Singapore", "Asia/Tokyo",
            "Asia/Seoul", "Asia/Kuala_Lumpur", "Asia/Kolkata", "America/New_York", "Europe/London", "Australia/Sydney")
        tzSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tzOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedTz = securePrefs.getString(KEY_TIMEZONE_ID, "") ?: ""
        val tzIndex = tzIds.indexOf(savedTz).coerceIn(0, tzIds.size - 1)
        tzSpinner.setSelection(tzIndex)

        val repeatSpinner = binding.spinnerReminderRepeat
        val repeatOptions = listOf("မပြန်သတိပေး", "၂ မိနစ်တခါ", "၅ မိနစ်တခါ", "၁၀ မိနစ်တခါ")
        val repeatValues = listOf(0, 2, 5, 10)
        repeatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, repeatOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedRepeat = securePrefs.getInt(KEY_REMINDER_REPEAT_MINUTES, 0)
        val repeatIndex = repeatValues.indexOf(savedRepeat).coerceIn(0, repeatValues.size - 1)
        repeatSpinner.setSelection(repeatIndex)

        // Notification Auto Timeout Spinner
        val notifTimeoutSpinner = binding.spinnerNotificationAutoTimeout
        val notifTimeoutOptions = listOf("၅ စက္ကန့်", "၁၀ စက္ကန့်", "၁၅ စက္ကန့်", "၃၀ စက္ကန့်", "ပိတ်မထားပါ")
        val notifTimeoutValues = listOf(5, 10, 15, 30, 0)
        notifTimeoutSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, notifTimeoutOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedNotifTimeout = getNotificationAutoTimeoutSeconds(this)
        val notifTimeoutIndex = notifTimeoutValues.indexOf(savedNotifTimeout).coerceIn(0, notifTimeoutValues.size - 1)
        notifTimeoutSpinner.setSelection(notifTimeoutIndex)

        updateNotificationAppsSummary()

        // Spotify status
        if (SpotifyManager.hasAccessToken(this)) {
            binding.tvSpotifyStatus?.text = "Spotify ချိတ်ဆက်ပြီး - သီးချင်းအမည်ပြောပြီး ဖွင့်လို့ရမယ်"
            binding.btnSpotifyConnect?.text = "ချိတ်ဆက်ဖြုတ်မယ်"
        } else {
            binding.tvSpotifyStatus?.text = "Login လုပ်ပြီး သီးချင်းအမည်ပြောပြီး ဖွင့်လို့ရမယ်"
            binding.btnSpotifyConnect?.text = "Spotify ချိတ်ဆက်မယ်"
        }
    }

    private fun updateNotificationAppsSummary() {
        val selectedPkgs = getSelectedNotificationApps(this)
        val pm = packageManager
        val names = selectedPkgs.map { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                pkg.substringAfterLast('.')
            }
        }
        val summaryText = if (names.isEmpty()) "မည်သည့် App မျှ မရွေးထားပါ" else "ရွေးထားသော App များ (${names.size}): " + names.joinToString(", ")
        binding.tvSelectedNotificationAppsSummary.text = summaryText
    }

    private fun setupListeners() {
        setupCollapsibleCategories()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener { saveSettings() }

        binding.btnToggleGeminiKey.setOnClickListener {
            toggleApiKeyVisibilityWithBiometric()
        }

        binding.btnToggleNotionToken.setOnClickListener {
            togglePasswordVisibility(binding.etNotionToken, binding.btnToggleNotionToken)
        }

        binding.btnToggleMessengerKey.setOnClickListener {
            togglePasswordVisibility(binding.etMessengerToken, binding.btnToggleMessengerKey)
        }

        binding.btnClearMessengerToken.setOnClickListener {
            binding.etMessengerToken.text.clear()
            binding.tvPageTokenStatus.text = "Page Token: cleared"
        }

        binding.etMessengerToken.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearMessengerToken.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.btnClearMessengerToken.visibility = if (binding.etMessengerToken.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // User Token buttons
        binding.btnToggleMessengerUserKey.setOnClickListener {
            togglePasswordVisibility(binding.etMessengerUserToken, binding.btnToggleMessengerUserKey)
        }

        binding.btnClearMessengerUserToken.setOnClickListener {
            binding.etMessengerUserToken.text.clear()
            binding.tvUserTokenStatus.text = "User Token: cleared"
        }

        binding.etMessengerUserToken.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearMessengerUserToken.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.btnClearMessengerUserToken.visibility = if (binding.etMessengerUserToken.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // ─── Messenger Notification Settings ───
        binding.btnGrantNotificationAccess.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Voice Brain ကို ရွေးပြီး ဖွင့်ပေးပါ", Toast.LENGTH_LONG).show()
        }

        binding.btnSelectNotificationApps.setOnClickListener {
            showAppSelectionDialog()
        }

        // Samsung Health Assistant Controls
        binding.switchHealthAssistant.isChecked = isHealthAssistantEnabled(this)
        val initialHealthTime = getHealthRoutineTime(this)
        binding.btnSetHealthRoutineTime.text = formatTimeString(initialHealthTime)

        binding.switchHealthAssistant.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.edit().putBoolean(KEY_HEALTH_ASSISTANT_ENABLED, isChecked).apply()
            val msg = if (isChecked) "⌚ Samsung Health Assistant ဖွင့်လိုက်ပါပြီ" else "Health Assistant ပိတ်လိုက်ပါပြီ"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnHealthConnectSettings.setOnClickListener {
            try {
                startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
                Toast.makeText(this, "Health Connect တွင် VoiceBrainLive ကိုရွေးပြီး Sleep, Steps, Calories, Heart rate permission ပေးပါ", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Health Connect settings မဖွင့်နိုင်ပါ။ Samsung Health နှင့် Health Connect ကို install/update လုပ်ထားပါ", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSetHealthRoutineTime.setOnClickListener {
            showHealthTimePicker()
        }

        binding.btnVoiceTraining.setOnClickListener {
            startActivity(Intent(this, VoiceTrainingActivity::class.java))
        }
        binding.btnResetVoiceProfile.setOnClickListener {
            VoiceProfileManager(this).clearProfile()
            Toast.makeText(this, getString(R.string.voice_profile_reset), Toast.LENGTH_SHORT).show()
        }
        binding.btnClearMemory.setOnClickListener {
            showResetMemoryConfirmationDialog()
        }
        binding.btnManageMemory.setOnClickListener {
            startActivity(Intent(this, com.example.voicebrainlive.ui.MemoryManagerActivity::class.java))
        }
        binding.switchVoiceProfile.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.edit().putBoolean(KEY_VOICE_PROFILE_ENABLED, isChecked).apply()
        }
        binding.switchBubble.setOnCheckedChangeListener { _, isChecked ->
            BubblePrefs.setEnabled(this, isChecked)
            binding.layoutBubbleTheme.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                if (canDrawOverlays()) {
                    startBubbleService()
                    Toast.makeText(this, "Assistant အမြန်ခေါ်ရန် Bubble ဖွင့်ထားပါသည်", Toast.LENGTH_SHORT).show()
                } else {
                    binding.switchBubble.isChecked = false
                    binding.layoutBubbleTheme.visibility = View.GONE
                    BubblePrefs.setEnabled(this, false)
                    requestOverlayPermission()
                }
            } else {
                stopService(Intent(this, FloatingBubbleService::class.java))
            }
        }
        binding.switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val needMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                if (needMic) {
                    WakeWordPrefs.setEnabled(this, true)
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    WakeWordPrefs.setEnabled(this, true)
                    startWakeWordService()
                    Toast.makeText(this, "Hey Voice / Voice Brain ပြောပြီး နှိုးလို့ရပါပြီ (Proximity Battery Saver ဖွင့်ထားသည်)", Toast.LENGTH_SHORT).show()
                }
            } else {
                WakeWordPrefs.setEnabled(this, false)
                stopService(Intent(this, WakeWordListenerService::class.java))
            }
        }
        binding.switchShowErrorDetails.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.edit().putBoolean(KEY_SHOW_ERROR_DETAILS, isChecked).apply()
            Toast.makeText(this, if (isChecked) "အမှားအသေးစိတ် Toast မှာ ပြမည်" else "အမှား အတိုချုပ် ပြမည်", Toast.LENGTH_SHORT).show()
        }
        binding.switchNoisyEnv.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.edit().putBoolean(KEY_NOISY_ENV_MODE, isChecked).apply()
            Toast.makeText(this, if (isChecked) "ဆူညံ/တိုးတိုးပြော မုဒ် ဖွင့်ပါပြီ" else "ပုံမှန် မုဒ်", Toast.LENGTH_SHORT).show()
        }
        binding.switchTestCallMode.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.edit().putBoolean(KEY_TEST_CALL_MODE, isChecked).apply()
            val msg = if (isChecked) "📞 ဖုန်းခေါ်ဆိုမှု စမ်းသပ်မုဒ်ကို ဖွင့်လိုက်ပါပြီ (တကယ့်ဖုန်းခေါ်မည်မဟုတ်ပါ)"
                      else "စမ်းသပ်မုဒ် ပိတ်လိုက်ပါပြီ (တကယ့်ဖုန်းခေါ်ပါမည်)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

// Duplicate media notification access button removed

        binding.btnSpotifyConnect?.setOnClickListener {
            if (SettingsActivity.getSpotifyAccessToken(this) != null) {
                SettingsActivity.clearSpotifyTokens(this)
                SpotifyManager.disconnect()
                binding.tvSpotifyStatus?.text = "Spotify ချိတ်ဆက်ရန် login လုပ်ပါ"
                binding.btnSpotifyConnect?.text = "Spotify ချိတ်ဆက်မယ်"
                Toast.makeText(this, "Spotify ချိတ်ဆက်ဖြုတ်ပြီး", Toast.LENGTH_SHORT).show()
            } else {
                val clientId = BuildConfig.SPOTIFY_CLIENT_ID
                val redirectUri = "voicebrainlive://callback"
                val scope = "streaming user-modify-playback-state playlist-modify-public playlist-modify-private playlist-read-private playlist-read-collaborative"
                val (verifier, challenge) = generateSpotifyPkce()
                setSpotifyPkceVerifier(this, verifier)
                val authUrl = "https://accounts.spotify.com/authorize?client_id=$clientId&response_type=code&redirect_uri=${Uri.encode(redirectUri)}&scope=${Uri.encode(scope)}&code_challenge_method=S256&code_challenge=$challenge"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                    Toast.makeText(this, "Browser မှာ Spotify login လုပ်ပါ", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    clearSpotifyPkceVerifier(this)
                    Toast.makeText(this, "Browser ဖွင့်လို့မရပါ", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDefaultAssistant.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Digital assistant app → Voice Brain ရွေးပါ", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Settings ဖွင့်လို့မရပါ။ Default apps ကို ကိုယ်တိုင် ရှာပါ။", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnBatteryOptimization?.setOnClickListener {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Allow ကို ရွေးပါ - Bubble နှင့် ပါဝါခလုတ် ပိုတည်မြဲမည်", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(appSettings)
                Toast.makeText(this, "Apps → Voice Brain → Battery → Unrestricted ရွေးပါ", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnTestMessenger.setOnClickListener {
            val pageToken = binding.etMessengerToken.text.toString().trim()
            val userToken = binding.etMessengerUserToken.text.toString().trim()

            if (pageToken.isEmpty() && userToken.isEmpty()) {
                binding.tvPageTokenStatus.text = "Page Token: ထည့်မထားပါ"
                binding.tvUserTokenStatus.text = "User Token: ထည့်မထားပါ"
                return@setOnClickListener
            }

            // Save both tokens before testing
            securePrefs.edit().apply {
                putString(MessengerApiClient.PREF_MESSENGER_TOKEN, pageToken)
                putString(MessengerApiClient.PREF_MESSENGER_USER_TOKEN, userToken)
                apply()
            }

            binding.tvPageTokenStatus.text = "Page Token: Checking..."
            binding.tvUserTokenStatus.text = "User Token: Checking..."

            val apiClient = MessengerApiClient(this)
            apiClient.testConnection(
                pageCallback = { result ->
                    runOnUiThread { binding.tvPageTokenStatus.text = "Page Token: $result" }
                },
                userCallback = { result ->
                    runOnUiThread { binding.tvUserTokenStatus.text = "User Token: $result" }
                }
            )
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, getString(R.string.bubble_overlay_hint), Toast.LENGTH_LONG).show()
        }
    }

    private fun startBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun toggleApiKeyVisibilityWithBiometric() {
        val editText = binding.etGeminiApiKey
        val isHidden = editText.inputType == (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (!isHidden) {
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.btnToggleGeminiKey.setImageResource(android.R.drawable.ic_secure)
            editText.setSelection(editText.text.length)
            return
        }
        val biometricManager = BiometricManager.from(this)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(this)
                val prompt = BiometricPrompt(this, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            runOnUiThread {
                                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                binding.btnToggleGeminiKey.setImageResource(android.R.drawable.ic_menu_view)
                                editText.setSelection(editText.text.length)
                            }
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            runOnUiThread { Toast.makeText(this@SettingsActivity, errString.toString(), Toast.LENGTH_SHORT).show() }
                        }
                    })
                val builder = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.biometric_api_key_title))
                    .setSubtitle(getString(R.string.biometric_api_key_subtitle))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(authenticators)
                } else {
                    builder.setNegativeButtonText("Cancel")
                }
                prompt.authenticate(builder.build())
            }
            else -> {
                togglePasswordVisibility(editText, binding.btnToggleGeminiKey)
            }
        }
    }

    private fun togglePasswordVisibility(editText: android.widget.EditText, button: android.widget.ImageButton) {
        if (editText.inputType == (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            button.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            button.setImageResource(android.R.drawable.ic_secure)
        }
        editText.setSelection(editText.text.length)
    }

    private fun saveSettings() {
        val geminiKey = binding.etGeminiApiKey.text.toString().trim()
        val notionToken = binding.etNotionToken.text.toString().trim()
        val notionDbId = binding.etNotionDatabaseId.text.toString().trim()
        val messengerToken = binding.etMessengerToken.text.toString().trim()
        val messengerUserToken = binding.etMessengerUserToken.text.toString().trim()
        val theme = if (binding.spinnerTheme.selectedItemPosition == 1) "light" else "dark"
        ThemeUtils.setTheme(this, theme)
        val voices = listOf("Aoede", "Charon", "Fenrir", "Kore", "Puck", "Zephyr")
        val voice = voices.getOrElse(binding.spinnerVoice.selectedItemPosition) { "Aoede" }
        val modelValues = listOf(
            "models/gemini-3.1-flash-live-preview",
            "models/gemini-2.5-flash-native-audio",
            "models/gemini-2.0-flash-realtime-exp",
            "models/gemini-2.0-flash"
        )
        val geminiModel = modelValues.getOrElse(binding.spinnerGeminiModel.selectedItemPosition) { "models/gemini-3.1-flash-live-preview" }
        val idleValues = listOf(0, 2, 5, 10)
        val idleMinutes = idleValues.getOrElse(binding.spinnerIdleTimeout.selectedItemPosition) { 0 }
        
        val silenceValueStr = binding.etSilenceTimeout.text.toString().trim()
        val silenceValue = if (silenceValueStr.isBlank()) 0 else silenceValueStr.toIntOrNull() ?: 10
        val silenceUnitValues = listOf("s", "m", "h")
        val silenceUnit = silenceUnitValues.getOrElse(binding.spinnerSilenceTimeoutUnit.selectedItemPosition) { "s" }

        val tzIds = listOf("", "Asia/Yangon", "Asia/Bangkok", "Asia/Singapore", "Asia/Tokyo",
            "Asia/Seoul", "Asia/Kuala_Lumpur", "Asia/Kolkata", "America/New_York", "Europe/London", "Australia/Sydney")
        val timezoneId = tzIds.getOrElse(binding.spinnerTimezone.selectedItemPosition) { "" }
        val repeatValues = listOf(0, 2, 5, 10)
        val reminderRepeat = repeatValues.getOrElse(binding.spinnerReminderRepeat.selectedItemPosition) { 0 }
        val notifTimeoutValues = listOf(5, 10, 15, 30, 0)
        val notifTimeout = notifTimeoutValues.getOrElse(binding.spinnerNotificationAutoTimeout.selectedItemPosition) { 10 }

        securePrefs.edit().apply {
            putString(KEY_GEMINI_API, geminiKey)
            putString(KEY_NOTION_TOKEN, notionToken)
            putString(KEY_NOTION_DATABASE_ID, notionDbId)
            putString(MessengerApiClient.PREF_MESSENGER_TOKEN, messengerToken)
            putString(MessengerApiClient.PREF_MESSENGER_USER_TOKEN, messengerUserToken)
            putString(KEY_VOICE, voice)
            putString(KEY_GEMINI_MODEL, geminiModel)
            putBoolean(KEY_VOICE_PROFILE_ENABLED, binding.switchVoiceProfile.isChecked)
            putBoolean(KEY_SHOW_ERROR_DETAILS, binding.switchShowErrorDetails.isChecked)
            putBoolean(KEY_NOISY_ENV_MODE, binding.switchNoisyEnv.isChecked)
            putInt(KEY_IDLE_TIMEOUT_MINUTES, idleMinutes)
            putInt(KEY_SILENCE_TIMEOUT_VALUE, silenceValue)
            putString(KEY_SILENCE_TIMEOUT_UNIT, silenceUnit)
            putString(KEY_TIMEZONE_ID, timezoneId)
            putInt(KEY_REMINDER_REPEAT_MINUTES, reminderRepeat)
            putInt(KEY_NOTIFICATION_AUTO_TIMEOUT_SECONDS, notifTimeout)
            putBoolean(KEY_TEST_CALL_MODE, binding.switchTestCallMode.isChecked)
            apply()
        }

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private data class AppInfoItem(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        var isChecked: Boolean,
        val isPopular: Boolean = false
    )

    private fun showAppSelectionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_notification_apps, null, false)
        val tvSelectedCountBadge = dialogView.findViewById<android.widget.TextView>(R.id.tvSelectedCountBadge)
        val btnCloseDialog = dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseDialog)
        val etSearchApp = dialogView.findViewById<android.widget.EditText>(R.id.etSearchApp)
        val btnClearSearchApp = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClearSearchApp)
        val chipFilterAll = dialogView.findViewById<android.widget.TextView>(R.id.chipFilterAll)
        val chipFilterSelected = dialogView.findViewById<android.widget.TextView>(R.id.chipFilterSelected)
        val chipFilterPopular = dialogView.findViewById<android.widget.TextView>(R.id.chipFilterPopular)
        val chipActionSelectAll = dialogView.findViewById<android.widget.TextView>(R.id.chipActionSelectAll)
        val chipActionClearAll = dialogView.findViewById<android.widget.TextView>(R.id.chipActionClearAll)
        val rvApps = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvApps)
        val pbLoadingApps = dialogView.findViewById<android.widget.ProgressBar>(R.id.pbLoadingApps)
        val tvEmptyApps = dialogView.findViewById<android.widget.TextView>(R.id.tvEmptyApps)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancelAppSelection)
        val btnSave = dialogView.findViewById<android.widget.Button>(R.id.btnSaveAppSelection)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        btnCloseDialog.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        val currentlySelected = getSelectedNotificationApps(this).toMutableSet()

        fun updateBadge() {
            tvSelectedCountBadge.text = "ရွေးချယ်ထားသော App (${currentlySelected.size}) ခု"
        }
        updateBadge()

        val allAppsList = mutableListOf<AppInfoItem>()
        val displayedApps = mutableListOf<AppInfoItem>()
        var activeFilter = "all" // "all", "selected", "popular"
        var currentSearchQuery = ""

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = displayedApps.size

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val view = layoutInflater.inflate(R.layout.item_app_selection, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val item = displayedApps[position]
                val ivIcon = holder.itemView.findViewById<android.widget.ImageView>(R.id.ivAppIcon)
                val tvName = holder.itemView.findViewById<android.widget.TextView>(R.id.tvAppName)
                val tvPkg = holder.itemView.findViewById<android.widget.TextView>(R.id.tvAppPackage)
                val cb = holder.itemView.findViewById<android.widget.CheckBox>(R.id.cbAppSelect)

                if (item.icon != null) {
                    ivIcon.setImageDrawable(item.icon)
                } else {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                tvName.text = item.label
                tvPkg.text = item.packageName
                cb.isChecked = item.isChecked

                holder.itemView.setOnClickListener {
                    item.isChecked = !item.isChecked
                    cb.isChecked = item.isChecked
                    if (item.isChecked) {
                        currentlySelected.add(item.packageName)
                    } else {
                        currentlySelected.remove(item.packageName)
                    }
                    updateBadge()
                }
            }
        }

        rvApps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvApps.adapter = adapter

        fun filterList() {
            val q = currentSearchQuery.lowercase()
            displayedApps.clear()
            displayedApps.addAll(allAppsList.filter { item ->
                val matchesFilter = when (activeFilter) {
                    "selected" -> item.isChecked
                    "popular" -> item.isPopular
                    else -> true
                }
                val matchesSearch = if (q.isBlank()) true else {
                    item.label.lowercase().contains(q) || item.packageName.lowercase().contains(q)
                }
                matchesFilter && matchesSearch
            })
            tvEmptyApps.visibility = if (displayedApps.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }

        fun setActiveChipStyle(active: android.widget.TextView, inactives: List<android.widget.TextView>) {
            active.setBackgroundResource(R.drawable.bg_status_pill)
            active.setTextColor(android.graphics.Color.WHITE)
            active.typeface = android.graphics.Typeface.DEFAULT_BOLD
            inactives.forEach { inactive ->
                inactive.setBackgroundResource(R.drawable.bg_category_chip)
                inactive.setTextColor(ContextCompat.getColor(this, R.color.assistant_text_secondary))
                inactive.typeface = android.graphics.Typeface.DEFAULT
            }
        }

        chipFilterAll.setOnClickListener {
            activeFilter = "all"
            setActiveChipStyle(chipFilterAll, listOf(chipFilterSelected, chipFilterPopular))
            filterList()
        }

        chipFilterSelected.setOnClickListener {
            activeFilter = "selected"
            setActiveChipStyle(chipFilterSelected, listOf(chipFilterAll, chipFilterPopular))
            filterList()
        }

        chipFilterPopular.setOnClickListener {
            activeFilter = "popular"
            setActiveChipStyle(chipFilterPopular, listOf(chipFilterAll, chipFilterSelected))
            filterList()
        }

        chipActionSelectAll.setOnClickListener {
            displayedApps.forEach { item ->
                item.isChecked = true
                currentlySelected.add(item.packageName)
            }
            updateBadge()
            adapter.notifyDataSetChanged()
        }

        chipActionClearAll.setOnClickListener {
            displayedApps.forEach { item ->
                item.isChecked = false
                currentlySelected.remove(item.packageName)
            }
            updateBadge()
            adapter.notifyDataSetChanged()
        }

        btnClearSearchApp.setOnClickListener {
            etSearchApp.text.clear()
        }

        etSearchApp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                btnClearSearchApp.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                filterList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSave.setOnClickListener {
            securePrefs.edit().putStringSet(KEY_SELECTED_NOTIFICATION_APPS, currentlySelected).apply()
            updateNotificationAppsSummary()
            Toast.makeText(this, "Notification App များ သိမ်းဆည်းပြီးပါပြီ", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Fast Asynchronous App Loading on Dispatchers.IO
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherActivities = pm.queryIntentActivities(mainIntent, 0)
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val popularPkgs = setOf(
                "com.facebook.orca", "com.whatsapp", "com.google.android.apps.messaging",
                "com.samsung.android.messaging", "com.samsung.android.weather",
                "com.google.android.weather", "com.facebook.katana", "org.telegram.messenger",
                "com.viber.voip", "com.instagram.android", "com.google.android.youtube",
                "com.google.android.gm", "com.sec.android.app.shealth"
            )

            val appMap = mutableMapOf<String, AppInfoItem>()

            // 1. Add launcher activities
            for (info in launcherActivities) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                try {
                    val label = info.loadLabel(pm).toString()
                    val icon = info.loadIcon(pm)
                    val isChecked = currentlySelected.contains(pkg)
                    val isPopular = pkg in popularPkgs || isChecked
                    appMap[pkg] = AppInfoItem(pkg, label, icon, isChecked, isPopular)
                } catch (_: Exception) {}
            }

            // 2. Add other installed apps (Health, Weather, Messaging, etc.)
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName
                if (pkg == packageName || appMap.containsKey(pkg)) continue
                if (pkg.startsWith("com.android.internal") || pkg == "android") continue
                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    if (label.isBlank()) continue
                    val isChecked = currentlySelected.contains(pkg)
                    val isPopular = pkg in popularPkgs || isChecked
                    val icon = if (isPopular || isChecked) {
                        try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }
                    } else null

                    appMap[pkg] = AppInfoItem(pkg, label, icon, isChecked, isPopular)
                } catch (_: Exception) {}
            }

            // Sort: Selected first, then popular, then alphabetical by label
            val sorted = appMap.values.sortedWith(
                compareByDescending<AppInfoItem> { it.isChecked }
                    .thenByDescending { it.isPopular }
                    .thenBy { it.label.lowercase() }
            )

            withContext(Dispatchers.Main) {
                pbLoadingApps.visibility = View.GONE
                allAppsList.clear()
                allAppsList.addAll(sorted)
                filterList()
            }
        }
    }

    private fun updateNotificationAccessStatus() {
        val granted = com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
            .isNotificationAccessGranted(this)
        if (granted) {
            binding.tvNotificationAccessStatus.text = "⬤ Granted — Notification reply ready"
            binding.tvNotificationAccessStatus.setTextColor(android.graphics.Color.parseColor("#44DD88"))
        } else {
            binding.tvNotificationAccessStatus.text = "⬤ Not granted — ဖွင့်ပေးပါ"
            binding.tvNotificationAccessStatus.setTextColor(android.graphics.Color.parseColor("#FF4444"))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh notification access status whenever we come back from settings
        updateNotificationAccessStatus()
        binding.switchBubble.isChecked = BubblePrefs.isEnabled(this)
        binding.layoutBubbleTheme.visibility = if (binding.switchBubble.isChecked) View.VISIBLE else View.GONE
    }

    private fun showResetMemoryConfirmationDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("မှတ်ဉာဏ်အားလုံး ဖျက်ရန်")
            .setMessage("မှတ်ဉာဏ်များ၊ စကားပြောမှတ်တမ်းများနှင့် အလေ့အထများ အားလုံးကို ဖျက်ဆီးပြီး အသစ်ကပြန်စမှာ သေချာပါသလား?")
            .setPositiveButton("သေချာပါတယ် (Reset)") { dialog, _ ->
                dialog.dismiss()
                performMemoryReset()
            }
            .setNegativeButton("မဖျက်တော့ပါ", null)
            .show()
    }

    private fun performMemoryReset() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("မှတ်ဉာဏ်များ ဖျက်နေပါသည်...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                com.example.voicebrainlive.memory.MemoryResetHelper.resetAllMemory(this@SettingsActivity)
            }
            progressDialog.dismiss()
            Toast.makeText(this@SettingsActivity, "မှတ်ဉာဏ်ဟောင်းများ အားလုံးကို ဖျက်ဆီးလိုက်ပါပြီ။", Toast.LENGTH_LONG).show()
            loadSettings()
        }
    }

    private fun setupCollapsibleCategories() {
        setupSectionToggle(binding.headerCategoryQuickControls, binding.bodyCategoryQuickControls, binding.arrowCategoryQuickControls, binding.dividerCategoryQuickControls)
        setupSectionToggle(binding.headerCategoryVoice, binding.bodyCategoryVoice, binding.arrowCategoryVoice, binding.dividerCategoryVoice)
        setupSectionToggle(binding.headerCategoryApis, binding.bodyCategoryApis, binding.arrowCategoryApis, binding.dividerCategoryApis)
        setupSectionToggle(binding.headerCategoryMemory, binding.bodyCategoryMemory, binding.arrowCategoryMemory, binding.dividerCategoryMemory)
        setupSectionToggle(binding.headerCategoryAdvanced, binding.bodyCategoryAdvanced, binding.arrowCategoryAdvanced, binding.dividerCategoryAdvanced)

        // All sections remain collapsed by default on open for a clean, stable UI
    }

    private fun setupSectionToggle(header: View, body: View, arrow: ImageView, divider: View?) {
        header.setOnClickListener {
            if (body.visibility == View.VISIBLE) {
                body.visibility = View.GONE
                divider?.visibility = View.GONE
                arrow.setImageResource(R.drawable.ic_chevron_down)
            } else {
                body.visibility = View.VISIBLE
                divider?.visibility = View.VISIBLE
                arrow.setImageResource(R.drawable.ic_chevron_up)
            }
        }
    }

    private fun showHealthTimePicker() {
        val currentTime = getHealthRoutineTime(this)
        val parts = currentTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val timePicker = android.app.TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                val formatted = String.format(java.util.Locale.US, "%02d:%02d", selectedHour, selectedMinute)
                securePrefs.edit().putString(KEY_HEALTH_ROUTINE_TIME, formatted).apply()
                binding.btnSetHealthRoutineTime.text = formatTimeString(formatted)

                updateHealthRoutineSchedule(selectedHour, selectedMinute)
                Toast.makeText(this, "ကျန်းမာရေး အစီရင်ခံချိန် $formatted သို့ ပြောင်းလဲပြီးပါပြီ", Toast.LENGTH_SHORT).show()
            },
            hour,
            minute,
            false
        )
        timePicker.show()
    }

    private fun formatTimeString(time24: String): String {
        val parts = time24.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
        }
        return java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(cal.time)
    }

    private fun updateHealthRoutineSchedule(hour: Int, minute: Int) {
        val existing = com.example.voicebrainlive.routines.RoutineStorage.getAll(this)
            .find { it.type == "routine_health" || it.type == "health" }
        val routineItem = if (existing != null) {
            existing.copy(hour = hour, minute = minute, enabled = isHealthAssistantEnabled(this))
        } else {
            com.example.voicebrainlive.routines.RoutineStorage.RoutineItem(
                id = 8888,
                type = "routine_health",
                hour = hour,
                minute = minute,
                enabled = true,
                message = "Samsung Health Summary",
                reminderText = "ကျန်းမာရေး အစီရင်ခံချိန် ရောက်ပါပြီ"
            )
        }
        com.example.voicebrainlive.routines.RoutineStorage.saveRoutine(this, routineItem)
        com.example.voicebrainlive.routines.RoutineScheduler.schedule(this, routineItem)
    }
}
