package com.example.voicebrainlive.commands.handlers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.messenger.MessengerApiClient
import com.example.voicebrainlive.messenger.MessengerNotificationReplyService

/**
 * Handler for messaging-related commands (SMS, Messenger, WhatsApp).
 * Migrated logic from PhoneCommandExecutor.
 */
class MessagingHandler(
    private val context: Context,
    private val messengerApiClient: MessengerApiClient
) : CommandHandler {

    override fun getSupportedCommands(): List<String> = listOf(
        "send_sms", 
        "send_messenger", "send_facebook_message", "messenger",
        "send_whatsapp", "whatsapp"
    )

    private val approvalSession = java.util.UUID.randomUUID().toString()
    override suspend fun execute(commandType: String, target: String?, value: String?): String? =
        com.example.voicebrainlive.commands.SensitiveActionGuard.run(context, approvalSession, commandType, target, value) {
            executeUnchecked(commandType, target, value).orEmpty()
        }

    private suspend fun executeUnchecked(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "send_sms" -> sendSms(target ?: "", value ?: "")
            "send_messenger", "send_facebook_message", "messenger" -> sendMessengerUnified(
                message = value?.takeIf { it.isNotBlank() } ?: target ?: "",
                recipientHint = if (value?.isNotBlank() == true) target else null
            )
            "send_whatsapp", "whatsapp" -> sendMessagingApp(
                message = value?.takeIf { it.isNotBlank() } ?: target ?: "",
                recipientHint = if (value?.isNotBlank() == true) target else null,
                packageId = "com.whatsapp",
                appName = "WhatsApp"
            )
            else -> null
        }
    }

    private fun sendSms(phoneOrName: String, message: String): String {
        val cleanMsg = com.example.voicebrainlive.commands.ActionDispatcher.cleanReplyMessage(message) ?: message.trim()
        if (cleanMsg.isBlank()) return "FAILED: Please say the message to send"
        
        val smsPackages = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
        )
        if (com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.isEnabled()) {
            val sentViaNotif = try {
                val notifSender = phoneOrName.takeIf { it.isNotBlank() }
                    ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifSender
                    ?: com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender
                com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.trySendReply(
                    recipientHint = notifSender,
                    message = cleanMsg,
                    packageFilter = smsPackages
                )
            } catch (e: Exception) {
                false
            }
            if (sentViaNotif) {
                return "SUCCESS: SMS ကနေ စာတိုက်ရိုက် ပို့ပြီးပါပြီ။"
            }
        }

        val phone = phoneOrName.trim().replace(Regex("[^0-9+]"), "")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED && phone.length >= 8) {
            return try {
                @Suppress("DEPRECATION")
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(cleanMsg)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(phone, null, cleanMsg, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                }
                "SUCCESS: စာပို့ပြီးပါပြီ။ $phone ဆီ ပို့ခဲ့တယ်။"
            } catch (e: Exception) {
                Log.e("MessagingHandler", "sendSms SmsManager error: ${e.message}")
                openSmsComposeFallback(phone, cleanMsg, isFallback = true)
            }
        } else {
            return openSmsComposeFallback(phone, cleanMsg, isFallback = false)
        }
    }

    private fun openSmsComposeFallback(phone: String, message: String, isFallback: Boolean): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("smsto:$phone")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Send SMS"))
            if (isFallback) "FAILED: ပို့မရလို့ SMS app ဖွင့်ပေးလိုက်ပါပြီ။ ပို့ခလုပ် နှိပ်ပေးပါ။"
            else "FAILED: SMS ခွင့်မရသေးပါ။ Settings မှာ SEND_SMS ခွင့်ပြုပေးပါ။"
        } catch (e: Exception) {
            Log.e("MessagingHandler", "sendSms intent error: ${e.message}")
            "FAILED: Could not open SMS app."
        }
    }

    private fun sendMessengerUnified(message: String, recipientHint: String?): String {
        val cleanMsg = com.example.voicebrainlive.commands.ActionDispatcher.cleanReplyMessage(message) ?: message.trim()
        if (cleanMsg.isBlank()) return "FAILED: Messenger က ဘာ ပြောချင်လဲ ပြောပေးပါ။"

        // (1) Notification Reply (Most reliable for Personal Messenger)
        if (com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.isEnabled()) {
            val targetPkg = com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedPackage
                ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifPkg
            val pkgFilter = if (!targetPkg.isNullOrBlank()) setOf(targetPkg) else com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.MESSAGING_PKGS
            val effectiveRecipient = recipientHint?.takeIf { it.isNotBlank() }
                ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifSender
                ?: com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender
            val sent = try {
                com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.trySendReply(effectiveRecipient, cleanMsg, pkgFilter)
            } catch (e: Exception) { 
                Log.e("MessagingHandler", "Reply error", e)
                false 
            }
            if (sent) return "SUCCESS: Messenger ကတစ်ဆင့် (Notification မှ) စာတိုက်ရိုက် ပို့ပြီးပါပြီ။"
        }

        // (2) API (Only if configured for Page)
        val token = SettingsActivity.trySecurePrefs(context)?.getString(MessengerApiClient.PREF_MESSENGER_TOKEN, "")
        val userToken = SettingsActivity.trySecurePrefs(context)?.getString(MessengerApiClient.PREF_MESSENGER_USER_TOKEN, "")
        if ((!token.isNullOrBlank() || !userToken.isNullOrBlank()) && !recipientHint.isNullOrBlank()) {
            return messengerApiClient.sendMessageSync(recipientHint, cleanMsg)
        }

        // (3) Fallback - App Intent
        return sendMessagingApp(cleanMsg, recipientHint, "com.facebook.orca", "Messenger")
    }

    private fun sendMessagingApp(message: String, recipientHint: String? = null, packageId: String, appName: String): String {
        val cleanMsg = com.example.voicebrainlive.commands.ActionDispatcher.cleanReplyMessage(message) ?: message.trim()
        if (cleanMsg.isBlank()) return "FAILED: $appName က ဘာ ပြောချင်လဲ ပြောပေးပါ။"

        val effectiveRecipient = recipientHint?.takeIf { it.isNotBlank() }
            ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifSender
            ?: com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender
        val sent = try {
            com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.trySendReply(effectiveRecipient, cleanMsg, packageFilter = setOf(packageId))
        } catch (e: Exception) { false }
        
        if (sent) return "SUCCESS: $appName က တိုက်ရိုက် စာပို့ပြီးပါပြီ။"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cleanMsg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(packageId)
        }
        return try {
            context.startActivity(intent)
            val hint = effectiveRecipient?.takeIf { it.isNotBlank() }?.let { " ($it ကို)" } ?: ""
            "FAILED: စာတိုက်ရိုက် ပို့မရပါ။ $appName ဖွင့်ပေးလိုက်ပါပြီ။$hint လူရွေးပြီး ပို့ခလုပ် နှိပ်ပေးပါ။"
        } catch (e: Exception) {
            Log.e("MessagingHandler", "sendMessagingApp fallback error: ${e.message}")
            "FAILED: $appName ဖွင့်မရပါ။ App ထဲ့ထားမထား စစ်ကြည့်ပါ။"
        }
    }
}
