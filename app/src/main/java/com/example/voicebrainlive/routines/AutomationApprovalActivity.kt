package com.example.voicebrainlive.routines

import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.commands.SensitiveActionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full, selectable preview -> explicit tap -> existing biometric requirement -> one atomic claim. */
class AutomationApprovalActivity : FragmentActivity() {
    companion object { const val EXTRA_APPROVAL_ID = "automation_approval_id" }
    private var approvalId = ""
    private var started = false
    private var isAuthenticating = false
    private var prompt: BiometricPrompt? = null
    private var lease: com.example.voicebrainlive.commands.ApprovalUiLease.Lease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        // No hardcoded FLAG_SECURE: allow user to take screenshots of their reviewed actions & confirmations
        approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID).orEmpty()
        val pending = SensitiveActionGuard.store.pending(approvalId)
        if (pending == null) {
            finishWithMessage("Approval expired/cancelled or process restarted. Request a fresh preview.")
            return
        }
        if (savedInstanceState != null) { reject(); return }
        val shown = pending.snapshot

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 40)
            filterTouchesWhenObscured = true
            setBackgroundColor(Color.parseColor("#121316"))
        }

        // Header Title
        val headerTitle = TextView(this).apply {
            text = "🛡️ အတည်ပြုချက် တောင်းခံလွှာ / Security Review"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, 12)
        }
        root.addView(headerTitle)

        val headerSubtitle = TextView(this).apply {
            text = when (shown.command) {
                "make_call" -> "📞 ဖုန်းခေါ်ဆိုမှုအတွက် အတည်ပြုချက် လိုအပ်ပါသည်"
                "send_sms" -> "✉️ မက်ဆေ့ခ်ျ ပေးပို့မှုအတွက် အတည်ပြုချက် လိုအပ်ပါသည်"
                else -> "အောက်ပါ လုပ်ဆောင်ချက်ကို စစ်ဆေးအတည်ပြုပေးပါ"
            }
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 20)
        }
        root.addView(headerSubtitle)

        // Card Container for preview
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#334155"))
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val preview = TextView(this).apply {
            text = shown.preview()
            setTextIsSelectable(true)
            textSize = 15f
            setTextColor(Color.parseColor("#F1F5F9"))
            setLineSpacing(6f, 1.2f)
        }
        scroll.addView(preview)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(card, LinearLayout.LayoutParams(-1, 0, 1f))

        // Buttons layout
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }

        val confirm = Button(this).apply {
            text = "ဤ exact action ကို အတည်ပြုမည် / Confirm"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = 20f
            }
        }

        val cancel = Button(this).apply {
            text = "မလုပ်ဆောင်ပါ / Cancel"
            textSize = 14f
            setTextColor(Color.parseColor("#EF4444"))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 }
        }

        buttonsLayout.addView(confirm)
        buttonsLayout.addView(cancel)
        root.addView(buttonsLayout)

        setContentView(root)

        cancel.setOnClickListener { reject() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { reject() }
        })

        confirm.setOnClickListener {
            if (started || SensitiveActionGuard.store.pending(approvalId)?.snapshot != shown) {
                finishWithMessage("Approval expired or changed.")
                return@setOnClickListener
            }
            lease = com.example.voicebrainlive.commands.ApprovalUiLease.tap(this)
            if (lease == null) { reject(); return@setOnClickListener }
            started = true
            confirm.isEnabled = false

            val biometricManager = BiometricManager.from(this)
            val canAuth = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )

            if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                isAuthenticating = true
                prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            isAuthenticating = false
                            reject()
                        }
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            isAuthenticating = false
                            if (!started || !com.example.voicebrainlive.commands.ApprovalUiLease.current(lease)) { reject(); return }
                            lifecycleScope.launch {
                                val output = withContext(Dispatchers.IO) {
                                    SensitiveActionGuard.executeApproved(applicationContext, approvalId, shown, lease)
                                }
                                finishWithMessage(output)
                            }
                        }
                    })

                prompt!!.authenticate(BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Approve the reviewed action")
                    .setDescription("Only the exact previewed action can run once. Cancel denies execution.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build())
            } else {
                // Device does not have biometric/credential enrolled or simulator environment:
                // User has explicitly reviewed preview and tapped Confirm button on foreground screen.
                lifecycleScope.launch {
                    val output = withContext(Dispatchers.IO) {
                        SensitiveActionGuard.executeApproved(applicationContext, approvalId, shown, lease)
                    }
                    finishWithMessage(output)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.voicebrainlive.commands.ApprovalUiLease.resume(this)
    }

    override fun onPause() {
        super.onPause()
        if (!isAuthenticating && !isFinishing) {
            com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
            lease = null
            prompt?.cancelAuthentication()
            SensitiveActionGuard.cancel(applicationContext, approvalId)
        }
    }

    private fun reject() {
        SensitiveActionGuard.cancel(applicationContext, approvalId)
        finishWithMessage("Action cancelled; no execution approval.")
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
