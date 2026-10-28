package com.example.voicebrainlive.privatefixture

import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.background.BackgroundTasks
import com.example.voicebrainlive.agent.brain.AndroidBrainAlpha
import com.example.voicebrainlive.agent.brain.EncryptedBrainStore
import com.example.voicebrainlive.agent.persistence.*

/** Compiled only into privateTest. No startup queue, provider connection, data import or legacy launcher. */
open class PrivateTestApplication : VoiceBrainApplication() {
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
    /** Native encrypted test-package store. Host key/byte fakes exist in testPrivateTest only. */
    open fun conversationStore(): EncryptedBrainStore = AndroidBrainAlpha.store(this)
}
