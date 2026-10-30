package com.example.voicebrainlive.privatefixture

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.voicebrainlive.BuildConfig
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.agent.AgentTasksActivity
import com.example.voicebrainlive.agent.brain.AgentActivityInboxActivity
import com.example.voicebrainlive.agent.notion.NotionProfilesActivity
import com.example.voicebrainlive.agent.proactive.ProactiveRulesActivity
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryActivity
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent
import com.example.voicebrainlive.updates.AppUpdateActivity

/** Offline fixture navigation only. No production MainActivity, OAuth, permission requests or external intents. */
open class PrivateTestScreen : AppCompatActivity() {
    protected lateinit var page: LinearLayout
    protected var epoch = 0L
    protected var foreground = false
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this); super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); isSaveEnabled = false }
        setContentView(ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(page) })
        label("VoiceBrainLive TEST · OFFLINE / SIMULATION", "private_test_banner")
        label("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n$packageName\nNo live AI/Notion, calls, SMS, messages, microphone or background jobs. Synthetic inputs only.", "private_test_identity")
    }
    override fun onResume() { super.onResume(); epoch++; foreground = true }
    override fun onPause() { foreground = false; epoch++; super.onPause() }
    protected fun label(value: String, id: String) = TextView(this).apply {
        text = value; tag = id; textSize = 17f; setPadding(4, 10, 4, 10); isSaveEnabled = false; isSaveFromParentEnabled = false
    }.also { page.addView(it) }
    protected fun button(value: String, id: String, action: () -> Unit) = Button(this).apply {
        text = value; tag = id; contentDescription = value; minHeight = (48 * resources.displayMetrics.density).toInt(); setOnClickListener { if (this@PrivateTestScreen.foreground) action() }
    }.also { page.addView(it) }
    protected fun open(type: Class<*>) { startActivity(Intent(this, type).setPackage(packageName)) }
}
class PrivateTestHomeActivity : PrivateTestScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label("Private first-run Home harness. The production Home/voice-service launcher is intentionally unavailable. The Agent, Memory, Inbox and About screens below are the existing application screens using a fresh TEST sandbox.", "private_home_scope")
        button("Conversation · offline simulation", "private_conversation") { open(PrivateTestConversationActivity::class.java) }
        button("Agent Tasks · existing local/mock UI", "private_agent") { open(AgentTasksActivity::class.java) }
        button("Memory · TEST records only", "private_memory") { open(ConfirmedMemoryActivity::class.java) }
        button("Activity & Approval Inbox · no execution", "private_inbox") { open(AgentActivityInboxActivity::class.java) }
        button("Notion profiles · synthetic fixtures only", "private_profiles") { open(NotionProfilesActivity::class.java) }
        button("Review rules · manual simulation only", "private_rules") { open(ProactiveRulesActivity::class.java) }
        button("Settings · private offline options", "private_settings") { open(PrivateTestSettingsActivity::class.java) }
    }
}
class PrivateTestSettingsActivity : PrivateTestScreen() {
    private var disclosure: AlertDialog? = null
    private lateinit var consent: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button("Back", "private_settings_back") { finish() }
        button("Memory & Personalization", "private_settings_memory") { open(ConfirmedMemoryActivity::class.java) }
        button("About & Updates · existing screen", "private_settings_about") { open(AppUpdateActivity::class.java) }
        consent = label("", "private_context_status")
        button("Review confirmed-memory context consent", "private_context_review") {
            val owner = epoch
            disclosure = AlertDialog.Builder(this).setTitle("TEST memory context review")
                .setMessage("This APK has no Internet permission. Context can only personalize local simulated planning; it cannot be sent by this offline build. This consent belongs to the TEST package only.\n\n" + ConfirmedMemoryContextConsent.DISCLOSURE)
                .setPositiveButton("Enable for TEST after review") { _, _ -> if (foreground && epoch == owner) { ConfirmedMemoryContextConsent.setAfterReview(this, true); renderConsent() } }
                .setNegativeButton("Keep unchanged", null).show().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        button("Disable TEST memory context", "private_context_off") { ConfirmedMemoryContextConsent.setAfterReview(this, false); renderConsent() }
        label("No credential entry, network enable switch, installer or live provider configuration exists here. About & Updates stays NOT CONFIGURED.", "private_settings_safety")
    }
    override fun onResume() { super.onResume(); renderConsent() }
    override fun onPause() { disclosure?.dismiss(); disclosure = null; super.onPause() }
    private fun renderConsent() { consent.text = "Confirmed-memory context: " + if (ConfirmedMemoryContextConsent.isEnabled(this)) "enabled for TEST local simulation" else "OFF" }
}
