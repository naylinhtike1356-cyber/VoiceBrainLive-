package com.example.voicebrainlive.agent.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import kotlinx.coroutines.CancellationException

/** Native queue entry only. The app-owned engine authenticates metadata and claims before reads. */
class LocalStatusWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val input = BackgroundWorkInput.parse(inputData) ?: return Result.failure()
        val tasks = (applicationContext as? VoiceBrainApplication)?.backgroundTasks ?: return Result.failure()
        return try {
            val outcome = tasks.execute(input.taskId, input.scheduledRevision)
            // A worker success is never exposed as task completion; history uses persisted record state.
            if (outcome.record?.state == PersistentTaskState.COMPLETED) Result.success() else Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
        // No Result.retry: consumed or uncertain attempts require explicit fresh review.
    }
}
