package com.example.voicebrainlive.routines

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.voicebrainlive.R

object AutomationApprovalNotification {
    private const val CHANNEL_ID = "automation_approvals"
    private const val CHANNEL_NAME = "Automation approvals"

    fun show(context: Context, item: AutomationApprovalQueue.PendingApproval) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(app, AutomationApprovalActivity::class.java).apply {
            putExtra(AutomationApprovalActivity.EXTRA_APPROVAL_ID, item.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            app,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Voice Brain — အတည်ပြုချက်လိုအပ်သည်")
            .setContentText("Open to review the complete action and explicitly approve")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(item.id.hashCode(), notification)
    }

    fun cancel(context: Context, id: String) {
        val manager = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(id.hashCode())
    }
}
