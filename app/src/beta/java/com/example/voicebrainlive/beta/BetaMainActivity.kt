package com.example.voicebrainlive.beta

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.R
import com.example.voicebrainlive.*
import com.example.voicebrainlive.ui.*
import com.example.voicebrainlive.ui.components.*
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryActivity
import com.example.voicebrainlive.agent.brain.AgentActivityInboxActivity

/** Actual MainFragment/HomeDashboardScreen navigation, with a local-only controller boundary. */
class BetaMainActivity : AppCompatActivity(), InteractiveBetaHost {
    private var foreground = false
    private var appliedTheme = ""
    private var appliedLanguage = ""
    private var epoch by mutableStateOf(0L)

    private var dialog: AlertDialog? = null
    private var micActive by mutableStateOf(false)
    private var speech: BetaSpeechPort? = null
    private var status by mutableStateOf("Local tasks ready · AI setup required")
    private var transcript by mutableStateOf("")
    private var pendingGoal: String? = null // RAM only; never Intent/saved-state/history injection.
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permission callback never starts capture. This also rejects stale grants after navigation/recreation.
        if (foreground) status = if (granted) "Microphone allowed. Tap mic again to start on-device recognition." else "Microphone denied. Text planning is available."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        appliedLanguage=BetaWords.language(this);appliedTheme=ThemeUtils.getTheme(this);ThemeUtils.applyTheme(this); super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(com.example.voicebrainlive.databinding.ActivityMainBinding.inflate(layoutInflater).root)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.mainContentFrame, MainFragment()).commit()
        onBackPressedDispatcher.addCallback(this) {
            val main=supportFragmentManager.findFragmentById(R.id.mainContentFrame) as? MainFragment
            if(main?.childFragmentManager?.findFragmentById(R.id.fragmentHost) is BetaConversationFragment)
                findViewById<View>(R.id.navHome).performClick()
            else {isEnabled=false;onBackPressedDispatcher.onBackPressed()}
        }
    }
    override fun onResume() { super.onResume(); foreground = true; epoch++; if(ThemeUtils.getTheme(this)!=appliedTheme||BetaWords.language(this)!=appliedLanguage)recreate() }
    override fun onPause() { foreground = false; epoch++; dialog?.dismiss(); dialog = null; stopMic(); transcript = ""; pendingGoal = null; super.onPause() }
    override fun onDestroy() { speech?.cancel(); speech = null; super.onDestroy() }
    private fun open(type: Class<*>) { if (foreground) startActivity(Intent(this,type).setPackage(packageName)) }
    override fun betaSettings() = open(SettingsActivity::class.java)
    override fun betaNavLabels()=listOf("home","chat","tasks","memory","settings").map{BetaWords.get(this,it)}
    override fun betaMemory() = open(ConfirmedMemoryActivity::class.java)
    override fun betaTasks() = open(BetaTaskBoardActivity::class.java)
    override fun betaInbox() = open(AgentActivityInboxActivity::class.java)
    override fun betaGuide() = open(BetaHelpActivity::class.java)
    override fun betaHomeView(): View = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val light=ThemeUtils.getTheme(this@BetaMainActivity)=="light"
            MaterialTheme(colorScheme=if(light) lightColorScheme() else darkColorScheme()) {
                Surface(modifier=Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                        Column(Modifier.widthIn(max=720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(BetaWords.get(this@BetaMainActivity,"greeting"),fontSize=20.sp,lineHeight=30.sp)
                            Text(BetaWords.get(this@BetaMainActivity,"off"),fontSize=14.sp)
                            var recent by remember { mutableStateOf<List<String>>(emptyList()) }
                            var waiting by remember { mutableStateOf(0) }
                            var unavailable by remember { mutableStateOf(false) }
                            LaunchedEffect(epoch) {
                                val loaded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){runCatching{(application as BetaApplication).betaStore().list()}}
                                loaded.fold({rows->
                                    waiting=rows.count{it.state==com.example.voicebrainlive.agent.persistence.PersistentTaskState.WAITING_FOR_APPROVAL}
                                    val zone=java.time.ZoneId.systemDefault();val today=java.time.LocalDate.now(zone)
                                    recent=BetaLocalTasks.visible(rows).filter{java.time.Instant.ofEpochMilli(it.definition.plan.createdAtMillis).atZone(zone).toLocalDate()==today}.take(3)
                                        .map{BetaLocalTasks.title(it)}
                                },{unavailable=true})
                            }
                            Button(onClick={showConversation("")},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(BetaWords.get(this@BetaMainActivity,"chat_action"))}
                            Text(BetaWords.get(this@BetaMainActivity,"today"),fontSize=16.sp)
                            if(unavailable)Text("Storage unavailable. Open Tasks to retry.",fontSize=14.sp)
                            else if(recent.isEmpty())Text(BetaWords.get(this@BetaMainActivity,"empty"),fontSize=14.sp)
                            else recent.forEach{Text("• $it",fontSize=14.sp,lineHeight=22.sp)}
                            TextButton(onClick={betaTasks()},modifier=Modifier.heightIn(min=48.dp)){Text(BetaWords.get(this@BetaMainActivity,"tasks"))}
                            TextButton(onClick={betaInbox()},modifier=Modifier.heightIn(min=48.dp)){Text("${BetaWords.get(this@BetaMainActivity,"approvals")} · $waiting")}
                            TextButton(onClick={betaMemory()},modifier=Modifier.heightIn(min=48.dp)){Text(BetaWords.get(this@BetaMainActivity,"memory"))}

                        }
                    }
                }
            }
        }
    }
    override fun betaConversation() = BetaConversationFragment()
    fun takePendingGoal(): String? = pendingGoal.also { pendingGoal = null }
    fun showConversation(text: String) {
        if (!foreground || supportFragmentManager.isStateSaved) return
        stopMic(); pendingGoal = text.take(2000)
        (supportFragmentManager.findFragmentById(R.id.mainContentFrame) as? MainFragment)?.openInteractiveConversation()
    }
    fun stopMic() { epoch++; speech?.cancel(); micActive = false }
    fun requestMic() {
        if (!foreground) return
        if (micActive) { stopMic(); status="Microphone stopped."; return }
        val port = speech ?: (application as BetaApplication).speech(this).also { speech=it }
        if (!port.available()) { status="On-device speech not available (Android 12+ service/language required). Type a goal; no cloud fallback."; return }
        val owner=epoch
        dialog=AlertDialog.Builder(this).setTitle("Use on-device microphone?")
            .setMessage("Use the installed on-device speech recognizer. Audio stays on your device; BETA does not record or upload it. You can edit the words before sending. This is voice input, not an AI voice conversation. Leaving the screen stops listening.")
            .setNegativeButton("Cancel",null).setPositiveButton("Use microphone") { _,_->
                if (!foreground || epoch!=owner) return@setPositiveButton
                if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.RECORD_AUDIO); return@setPositiveButton
                }
                micActive=true; status="Starting on-device microphone"
                port.start(getSharedPreferences("beta_options",MODE_PRIVATE).getString("speech_language","my-MM") ?: "my-MM",
                    { value -> if(foreground && epoch==owner){micActive=false;transcript=value;showConversation(value)} },
                    { message -> if(foreground && epoch==owner){status=message;android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_SHORT).show();if(!message.startsWith("Listening")&&!message.startsWith("Processing")) micActive=false} })
            }.show()
        dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
