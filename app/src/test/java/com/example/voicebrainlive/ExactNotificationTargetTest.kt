package com.example.voicebrainlive

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fake notifications/PendingIntents only. No messaging-app interactions or sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExactNotificationTargetTest {
    @Test fun uniqueNotificationTarget_rejectsAmbiguityAndChangedReplyToken() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildService(UnifiedNotificationListenerService::class.java).create()
        val service = controller.get()
        service.onListenerConnected()
        val shadow = shadowOf(service as android.service.notification.NotificationListenerService)
        fun notification(id: Int, token: Int, immutable: Boolean = false): StatusBarNotification {
            val reply = PendingIntent.getBroadcast(app, token, Intent("fake.reply.$token").setPackage(app.packageName),
                if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE)
            val action = Notification.Action.Builder(0, "Reply", reply)
                .addRemoteInput(RemoteInput.Builder("exact_reply").setAllowFreeFormInput(true).build()).build()
            val notification = Notification.Builder(app, "fake-channel").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice").setContentText("incoming").addAction(action).build()
            return StatusBarNotification(app.packageName, app.packageName, id, null, 0, 0, 0,
                notification, android.os.Process.myUserHandle(), 100L)
        }
        try {
            val first = notification(1, 1)
            shadow.addActiveNotification(first)
            val target = UnifiedNotificationListenerService.resolveExactReply("Alice", app.packageName)!!
            assertFalse(UnifiedNotificationListenerService.isExactReplyCurrent(target))
            assertNull(UnifiedNotificationListenerService.resolveExactReply("Bob", app.packageName))
            shadow.addActiveNotification(notification(2, 2))
            assertNull(UnifiedNotificationListenerService.resolveExactReply("Alice", app.packageName))
            service.cancelAllNotifications()
            // Same notification key/post time/action index/result key, but different reply target.
            shadow.addActiveNotification(notification(1, 3))
            assertFalse(UnifiedNotificationListenerService.isExactReplyCurrent(target))
            service.cancelAllNotifications()
            shadow.addActiveNotification(notification(1, 1))
            assertFalse(UnifiedNotificationListenerService.isExactReplyCurrent(target))
            service.cancelAllNotifications()
            shadow.addActiveNotification(notification(1, 4, immutable = true))
            assertNull(UnifiedNotificationListenerService.resolveExactReply("Alice", app.packageName))
        } finally {
            service.cancelAllNotifications()
            service.onListenerDisconnected()
            controller.destroy()
        }
    }
}
