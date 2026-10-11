package com.example.voicebrainlive.messenger

import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi

/**
 * MessengerNotificationReplyService:
 * Legacy / Alias service extending UnifiedNotificationListenerService for backward compatibility.
 * All static helper methods and companion properties are delegated to UnifiedNotificationListenerService.
 */
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class MessengerNotificationReplyService : UnifiedNotificationListenerService() {

    companion object {
        val MESSAGING_PKGS = UnifiedNotificationListenerService.MESSAGING_PKGS
        val HEALTH_PKGS = UnifiedNotificationListenerService.HEALTH_PKGS
        val WEATHER_PKGS = UnifiedNotificationListenerService.WEATHER_PKGS

        const val PROACTIVE_TYPE_MESSENGER_REPLY = UnifiedNotificationListenerService.PROACTIVE_TYPE_MESSENGER_REPLY
        const val PROACTIVE_TYPE_GENERAL_NOTIFICATION = UnifiedNotificationListenerService.PROACTIVE_TYPE_GENERAL_NOTIFICATION

        const val ACTION_MESSENGER_INCOMING = UnifiedNotificationListenerService.ACTION_MESSENGER_INCOMING
        const val EXTRA_SENDER = UnifiedNotificationListenerService.EXTRA_SENDER
        const val EXTRA_MESSAGE = UnifiedNotificationListenerService.EXTRA_MESSAGE
        const val EXTRA_PACKAGE = UnifiedNotificationListenerService.EXTRA_PACKAGE

        var instance: MessengerNotificationReplyService?
            get() = UnifiedNotificationListenerService.instance as? MessengerNotificationReplyService
            set(value) {
                UnifiedNotificationListenerService.instance = value
            }

        fun isEnabled(): Boolean = UnifiedNotificationListenerService.isEnabled()

        fun isNotificationAccessGranted(context: Context): Boolean =
            UnifiedNotificationListenerService.isNotificationAccessGranted(context)

        fun getLastSender(packageId: String): String? =
            UnifiedNotificationListenerService.getLastSender(packageId)

        fun getLastMessage(packageId: String = "com.facebook.orca"): String? =
            UnifiedNotificationListenerService.getLastMessage(packageId)

        fun getLastNotificationSummary(packageId: String = "com.facebook.orca"): String? =
            UnifiedNotificationListenerService.getLastNotificationSummary(packageId)

        fun trySendReply(
            recipientHint: String?,
            message: String,
            packageFilter: Set<String> = MESSAGING_PKGS
        ): Boolean = UnifiedNotificationListenerService.trySendReply(recipientHint, message, packageFilter)

        fun extractSender(sbn: StatusBarNotification): String =
            UnifiedNotificationListenerService.extractSender(sbn)

        fun extractMessageText(sbn: StatusBarNotification): String =
            UnifiedNotificationListenerService.extractMessageText(sbn)
    }
}
