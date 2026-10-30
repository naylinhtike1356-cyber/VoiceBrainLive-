package com.example.voicebrainlive.agent.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import com.example.voicebrainlive.agent.persistence.TaskNotificationPort
import com.example.voicebrainlive.agent.persistence.validTaskId

/** OS submission acceptance only; not proof of visible/native device delivery. */
class AgentTaskNotifications(context: Context) : TaskNotificationPort {
    private val app = context.applicationContext
    override suspend fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return false
            val channel = manager.getNotificationChannel(CHANNEL)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }
    override suspend fun publish(taskId: String, state: PersistentTaskState): Boolean {
        if (!validTaskId(taskId) || !isAvailable()) return false
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = app.getSystemService(NotificationManager::class.java) ?: return false
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Local agent tasks", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val review = PendingIntent.getActivity(app, 0, reviewIntent(app, taskId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Local agent task")
                .setContentText("Task status: ${state.name}. Open task history to review.")
                .setContentIntent(review).setAutoCancel(true).setOnlyAlertOnce(true).build()
            // Explicit tag plus fixed numeric ID avoids hashed task destination collisions.
            // Recheck at submission: permission may have changed since availability was read.
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            NotificationManagerCompat.from(app).notify(taskId, 1, notification)
            true
        } catch (_: SecurityException) { false }
        catch (_: Exception) { false }
    }
    companion object {
        const val CHANNEL = "voicebrain_agent_local_tasks"
        fun reviewIntent(context: Context, taskId: String): Intent {
            require(validTaskId(taskId))
            return Intent(context, ScheduledAgentTasksActivity::class.java).apply {
                action = "com.example.voicebrainlive.REVIEW_LOCAL_AGENT_TASK"
                data = Uri.Builder().scheme("voicebrain-task").authority("review").appendPath(taskId).build()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
