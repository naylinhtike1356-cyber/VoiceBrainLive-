package com.example.voicebrainlive.routines

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.commands.SensitiveActionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full, selectable preview -> explicit tap -> existing biometric requirement -> one atomic claim. */
class AutomationApprovalActivity : FragmentActivity() {
    companion object { const val EXTRA_APPROVAL_ID = "automation_approval_id" }
    private var approvalId = ""
    private var started = false
    private var prompt: BiometricPrompt? = null
    private var lease: com.example.voicebrainlive.commands.ApprovalUiLease.Lease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            setPadding(24, 24, 24, 24)
            filterTouchesWhenObscured = true
        }
        val scroll = ScrollView(this)
        val preview = TextView(this).apply {
            text = shown.preview()
            setTextIsSelectable(true)
            textSize = 17f
        }
        scroll.addView(preview)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val confirm = Button(this).apply { text = "ဤ exact action ကို အတည်ပြုမည် / Confirm" }
        val cancel = Button(this).apply { text = "မလုပ်ဆောင်ပါ / Cancel" }
        root.addView(confirm)
        root.addView(cancel)
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
            // A generic biometric result alone never starts a different action.
            prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { reject() }
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
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
                .setNegativeButtonText("Cancel")
                .build())
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.voicebrainlive.commands.ApprovalUiLease.resume(this)
    }

    override fun onPause() {
        com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
        lease = null
        prompt?.cancelAuthentication()
        SensitiveActionGuard.cancel(applicationContext, approvalId)
        super.onPause()
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
