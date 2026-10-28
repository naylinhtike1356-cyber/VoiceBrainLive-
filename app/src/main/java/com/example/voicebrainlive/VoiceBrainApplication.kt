package com.example.voicebrainlive

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Logs uncaught exceptions for crash diagnostics.
 * Required early in onCreate so crashes are captured in Logcat.
 */
open class VoiceBrainApplication : Application() {
    /** Variant-only test Application may suppress startup work; production behavior stays enabled. */
    protected open val automaticStartupAllowed: Boolean = true


    /** One new encrypted profile vault; no eager key/file IO, legacy import or real account adapter. */
    open val notionProfiles: com.example.voicebrainlive.agent.notion.NotionProfilesRepository by lazy {
        com.example.voicebrainlive.agent.notion.NotionProfilesRepository(
            com.example.voicebrainlive.agent.notion.NotionProfilesStoreFactory.create(this))
    }
    open val directNotionProfiles: com.example.voicebrainlive.agent.notion.DirectNotionProfiles by lazy {
        com.example.voicebrainlive.agent.notion.RepositoryDirectNotionProfiles(notionProfiles)
    }

    /** Closed manual simulation only; distinct lazy encrypted store, no automatic evaluation. */
    open val proactiveRules: com.example.voicebrainlive.agent.proactive.ProactiveRules by lazy {
        com.example.voicebrainlive.agent.proactive.AndroidProactiveRules(this)
    }

    /** One repository/engine for UI, native worker and startup recovery; no key/file IO here. */
    open val backgroundTasks: com.example.voicebrainlive.agent.background.BackgroundTasks by lazy {
        com.example.voicebrainlive.agent.background.AndroidBackgroundTasks(this)
    }
    private val taskRecoveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** Single RAM-only foreground planner facade; no background worker or effect authority. */
    open val foregroundAgentTasks: com.example.voicebrainlive.agent.ForegroundAgentTasks by lazy {
        com.example.voicebrainlive.agent.ForegroundAgentTasks(
            com.example.voicebrainlive.agent.AndroidAgentReadPort(this), studyProfiles = notionProfiles,
            syntheticNotionPort = com.example.voicebrainlive.agent.notion.SyntheticNotionDemoPort)
    }

    /** Separate UI-only store; never grants tool authority or imports legacy facts. */
    open val confirmedMemoryStore: com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryStore by lazy {
        com.example.voicebrainlive.memory.confirmed.AndroidConfirmedMemoryStore(this)
    }

    override fun onCreate() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FATAL_ERROR", "Crash trace: ", throwable)
            Log.e("FATAL_ERROR", "Stack: ${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        super.onCreate()
        if (!automaticStartupAllowed) return
        taskRecoveryScope.launchTaskRecovery()

        // Keep-alive check: if user enabled Floating Bubble, ensure service is running
        if (BubblePrefs.isEnabled(this) && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this))) {
            try {
                val bubbleIntent = android.content.Intent(this, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_SHOW_BUBBLE
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(bubbleIntent)
                } else {
                    startService(bubbleIntent)
                }
            } catch (e: Exception) {
                Log.w("VoiceBrainApplication", "Failed to auto-start FloatingBubbleService: ${e.message}")
            }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchTaskRecovery() {
        this.launch {
            try { backgroundTasks.reconcile() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* History UI rereads and exposes typed failure; never reset data. */ }
        }
    }
}
