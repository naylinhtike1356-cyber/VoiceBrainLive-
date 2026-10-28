package com.example.voicebrainlive.beta

import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.ui.InteractiveBetaSettings
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.agent.background.BackgroundTasks
import com.example.voicebrainlive.agent.persistence.*
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent

open class BetaApplication : VoiceBrainApplication(), InteractiveBetaSettings {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:android.app.Activity) {
                BetaCapturePolicy.apply(a)
                BetaUi.installInsets(a)
            }
            override fun onActivityCreated(a:android.app.Activity,s:android.os.Bundle?) { a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            override fun onActivityStarted(a:android.app.Activity) {}
            override fun onActivityPaused(a:android.app.Activity) { a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            override fun onActivityStopped(a:android.app.Activity) {}
            override fun onActivitySaveInstanceState(a:android.app.Activity,s:android.os.Bundle) {}
            override fun onActivityDestroyed(a:android.app.Activity) {}
        })
    }
    override val automaticStartupAllowed = false
    override val backgroundTasks: BackgroundTasks = object : BackgroundTasks {
        override suspend fun history(): List<PersistentTaskRecord> = emptyList()
        override suspend fun reconcile(): List<PersistentTaskRecord> = emptyList()
        override suspend fun notificationsAvailable() = false
        override suspend fun previewLocalStatus(): PersistentTaskRecord = blocked()
        override suspend fun schedule(taskId: String, expectedRevision: Long): PersistentTaskRecord = blocked()
        override suspend fun pause(taskId: String, expectedRevision: Long): PersistentTaskRecord = blocked()
        override suspend fun cancel(taskId: String, expectedRevision: Long): PersistentTaskRecord = blocked()
        override suspend fun execute(taskId: String, scheduledRevision: Long): TaskExecutionOutcome = blocked()
        private fun blocked(): Nothing = throw TaskStoreException(TaskFailure.SCHEDULER_UNAVAILABLE)
    }
    open fun betaStore(): EncryptedBrainStore = AndroidBrainAlpha.store(this)
    open fun betaReads(): AgentReadPort = AndroidAgentReadPort(this)
    open fun speech(activity: BetaMainActivity): BetaSpeechPort = AndroidBetaSpeechPort(activity)
    open fun localAgent(owner: () -> Boolean, progress: (BrainTask) -> Unit) = BetaLocalAgent(betaStore(), betaReads(), BrainMemoryAccess(
        { ConfirmedMemoryContextConsent.isEnabled(this) }, { confirmedMemoryStore.list() }, System::currentTimeMillis), owner, progress = progress)
    override fun renderBetaSettings(activity: SettingsActivity) { BetaSettingsUi.install(activity) }
    override fun betaTaskStore() = betaStore()
    override fun betaHeader(activity:android.app.Activity,title:String)=BetaUi.header(activity,title)
}
