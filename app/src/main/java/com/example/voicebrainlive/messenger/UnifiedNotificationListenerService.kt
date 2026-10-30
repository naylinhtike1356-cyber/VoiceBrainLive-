package com.example.voicebrainlive.messenger

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.health.HealthManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Notification Engine:
 *  1. Messaging Apps (SMS, Messenger, WhatsApp, Telegram, etc.): Reads aloud, launches AI interactive session, supports RemoteInput direct replies.
 *  2. Samsung Health & Wearables: Parses sleep telemetry (hours, bedtime, wakeup time), step count, and heart rate into HealthManager.
 *  3. Weather Apps: Parses temperature, rain forecast, and provides proactive rain alerts.
 *  4. User-Selected Apps: Passes titles & content to AI proactive narration for custom selected apps.
 *
 * Setup: Settings → Notification access → Voice Brain ✓
 */
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
open class UnifiedNotificationListenerService : NotificationListenerService() {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate() {
        super.onCreate()
        // Announcements wake the native Gemini agent; no Android TTS engine.
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        instance = null
        processedKeys.clear()
        lastSenderByPackage.clear()
        super.onDestroy()
    }

    private fun initTts() {
        tts = TextToSpeech(this.applicationContext) { status ->
            isTtsReady = status == TextToSpeech.SUCCESS
            if (isTtsReady) {
                tts?.language = Locale.getDefault()
                Log.d(TAG, "TTS ready for notification announcements")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "UnifiedNotificationListener connected — SMS, Health, Weather & Apps ready")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, "UnifiedNotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val pkg = sbn.packageName
        if (pkg == packageName) return // Ignore own notifications
        if (isSensitiveApp(pkg)) {
            Log.d(TAG, "Skipping sensitive app notification: $pkg")
            return
        }

        val isMessagingApp = pkg in MESSAGING_PKGS
        val isHealthApp = pkg in HEALTH_PKGS || pkg.contains("shealth", ignoreCase = true) || pkg.contains("health", ignoreCase = true)
        val selectedApps = SettingsActivity.getSelectedNotificationApps(this)
        val isSelectedApp = pkg in selectedApps

        if (!isHealthApp && !isSelectedApp) return
        if (!isRealMessage(sbn)) return

        val sender = extractSender(sbn)
        val messageText = extractMessageText(sbn)
        val title = if (sender.isNotBlank()) sender else extractSender(sbn)
        if (title.isBlank() && messageText.isBlank()) return

        // Deduplication: Only trigger AI if EITHER the name/title or text is different
        val key = sbn.key
        val identifier = "$title|$messageText"
        if (processedKeys[key] == identifier) {
            Log.d(TAG, "Notification update detected, but content identical. Skipping trigger.")
            return
        }
        processedKeys[key] = identifier

        if (isHealthApp) {
            Log.d(TAG, "Health notification received; private content omitted")
            HealthManager.parseAndSaveFromNotification(this, title, messageText)
        }

        if (isMessagingApp) {
            val cleanSender = stripMessageCount(sender)
            lastNotifiedKey = sbn.key
            lastNotifiedPackage = pkg
            lastNotifiedSender = cleanSender
            lastNotifiedTime = sbn.postTime
            lastSenderByPackage[pkg] = CachedSender(cleanSender, messageText, sbn.postTime)
            Log.d(TAG, "Message cached for exact reply resolution; private content omitted")
            sendNotificationBroadcast(sender, messageText, pkg)
        }

        if (isSelectedApp) {
            val appLabel = try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                pkg.substringAfterLast('.')
            }

            lastNotifiedKey = sbn.key
            Log.d(TAG, "Selected notification received; private content omitted")
            if (com.example.voicebrainlive.agent.nativeapps.NativeAgentSettings.enabled(this)) {
                try {
                    com.example.voicebrainlive.notifications.AgentNotificationAudio.submit(this, sbn.key, pkg, appLabel, title, messageText)
                } catch (_: Exception) {
                    com.example.voicebrainlive.agent.nativeapps.NativeAgentSettings.status(this, "STORAGE_UNAVAILABLE")
                }
            } else {
                if (isMessagingApp) {
                    val cleanSender = stripMessageCount(sender).ifBlank { title.ifBlank { appLabel } }
                    launchAiReplySession(pkg, cleanSender, messageText)
                } else {
                    launchGeneralNotificationSession(pkg, appLabel, title, messageText)
                }
            }
        }
    }

    private fun hasReplyAction(sbn: StatusBarNotification): Boolean {
        val actions = sbn.notification?.actions ?: return false
        return actions.any { action ->
            action.remoteInputs?.isNotEmpty() == true ||
            (action.title?.toString()?.lowercase()?.let {
                it.contains("reply") || it.contains("ပြန်စာ") || it.contains("send") || it.contains("direct")
            } == true)
        }
    }

    private fun isSensitiveApp(packageName: String): Boolean {
        val sensitivePackages = setOf(
            "com.kbstar.kbbank",
            "com.shinhan.sbanking",
            "com.hanabank.ebk.channel.android.hananbank",
            "com.wooribank.smart.nbank",
            "com.nh.smartbank",
            "com.lastpass.lpandroid",
            "com.bitwarden.app",
            "com.google.android.apps.authenticator2"
        )
        return packageName in sensitivePackages ||
               packageName.contains("bank", ignoreCase = true) ||
               packageName.contains("password", ignoreCase = true) ||
               packageName.contains("banking", ignoreCase = true)
    }

    private fun launchAiReplySession(packageName: String, sender: String, messageText: String) {
        val appLabel = when {
            packageName.contains("orca") || packageName.contains("facebook") -> "Messenger"
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("viber") -> "Viber"
            packageName.contains("line") -> "Line"
            packageName.contains("messaging") || packageName.contains("mms") -> "SMS Message"
            else -> {
                try {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    "Message"
                }
            }
        }

        lastNotifiedPackage = packageName
        lastNotifiedSender = sender
        lastNotifiedTime = System.currentTimeMillis()

        val payload = "sender=$sender&msg=$messageText&pkg=$packageName&app=$appLabel"
        val base64Payload = "MESSENGER_REPLY_V2:" + android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP)

        launchPyitTineHtaungOverlaySession(
            proactiveMsg = base64Payload,
            proactiveType = PROACTIVE_TYPE_MESSENGER_REPLY,
            appLabel = appLabel,
            sender = sender,
            messageText = messageText,
            packageName = packageName,
            fallbackText = "$appLabel: $sender က '$messageText' လို့ ပို့လိုက်ပါတယ်။"
        )
    }

    private fun launchGeneralNotificationSession(
        packageName: String,
        appLabel: String,
        title: String,
        messageText: String
    ) {
        lastNotifiedPackage = packageName
        lastNotifiedSender = title
        lastNotifiedTime = System.currentTimeMillis()

        val payload = "app=$appLabel&title=$title&msg=$messageText"
        val base64Payload = "APP_NOTIFICATION_V1:" + android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.NO_WRAP)

        launchPyitTineHtaungOverlaySession(
            proactiveMsg = base64Payload,
            proactiveType = PROACTIVE_TYPE_GENERAL_NOTIFICATION,
            appLabel = appLabel,
            sender = title,
            messageText = messageText,
            packageName = packageName,
            fallbackText = "$appLabel မှ အသိပေးချက်: $title $messageText"
        )
    }

    private fun launchPyitTineHtaungOverlaySession(
        proactiveMsg: String,
        proactiveType: String,
        appLabel: String,
        sender: String,
        messageText: String,
        packageName: String,
        fallbackText: String
    ) {
        try {
            val serviceIntent = Intent(this, com.example.voicebrainlive.VoiceLiveService::class.java).apply {
                action = com.example.voicebrainlive.VoiceLiveService.ACTION_START
                putExtra("EXTRA_START_WITH_CARD", true)
                putExtra("proactive_message", proactiveMsg)
                putExtra("proactive_type", proactiveType)
                putExtra("extra_app_label", appLabel)
                putExtra("extra_sender", sender)
                putExtra("extra_message_text", messageText)
                putExtra("extra_package", packageName)
            }

            val liveInstance = com.example.voicebrainlive.VoiceLiveService.instance
            if (liveInstance != null) {
                liveInstance.onStartCommand(serviceIntent, 0, 0)
                Log.d(TAG, "Dispatched proactive notification directly to existing VoiceLiveService instance for '$appLabel' ($sender)")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "Launched Pyit Tine Htaung AI Mascot Overlay session for '$appLabel' ($sender)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android blocked the native agent overlay")
            showAgentOpenNotification()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.key?.let { processedKeys.remove(it); com.example.voicebrainlive.notifications.AgentNotificationAudio.removed(it) }
    }

    private fun speakOut(text: String) {
        if (com.example.voicebrainlive.voice.AgentSpeech.ENABLED) return
        if (!isTtsReady || tts == null) {
            initTts()
            return
        }
        tts?.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg_announce")
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
        Log.d(TAG, "TTS speaking: ${text.take(80)}")
    }

    private fun showAgentOpenNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val channel = "agent_open_required"
        manager.createNotificationChannel(android.app.NotificationChannel(channel, "Agent အသိပေးချက်", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, com.example.voicebrainlive.AssistantActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(94640, androidx.core.app.NotificationCompat.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Agent ကို ဖွင့်ရန် နှိပ်ပါ")
                .setContentText("Android က နောက်ခံမှ စတင်ခွင့်မပေးပါ။")
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Notification permission can be revoked. */ }
    }

    private fun sendNotificationBroadcast(sender: String, message: String, packageName: String) {
        val intent = Intent(ACTION_MESSENGER_INCOMING).apply {
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PACKAGE, packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent for AI awareness: $sender → ${message.take(40)}")
    }

    companion object {
        private const val TAG = "UnifiedNotifListener"

        val MESSAGING_PKGS = setOf(
            "com.facebook.orca",
            "com.facebook.katana",
            "com.facebook.mlite",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.plus",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.viber.voip",
            "jp.naver.line.android"
        )

        val HEALTH_PKGS = setOf(
            "com.sec.android.app.shealth",
            "androidx.health.connect.client",
            "com.google.android.apps.healthdata",
            "com.google.android.apps.fitness"
        )

        val WEATHER_PKGS = setOf(
            "com.samsung.android.weather",
            "com.sec.android.daemonapp",
            "com.google.android.weather",
            "com.google.android.apps.weather",
            "com.weather.Weather",
            "com.accuweather.android",
            "com.yahoo.mobile.client.android.weather"
        )

        const val PROACTIVE_TYPE_MESSENGER_REPLY = "messenger_reply"
        const val PROACTIVE_TYPE_GENERAL_NOTIFICATION = "app_notification"

        const val ACTION_MESSENGER_INCOMING = "com.example.voicebrainlive.MESSENGER_INCOMING"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PACKAGE = "package"

        @Volatile
        var instance: UnifiedNotificationListenerService? = null

        internal fun isAnnouncementCurrent(key:String,pkg:String,title:String,text:String):Boolean = try {
            instance?.activeNotifications.orEmpty().any {
                it.key==key && it.packageName==pkg && extractSender(it).take(100)==title && extractMessageText(it).take(480)==text
            }
        } catch (_:Exception) { false }

        @Volatile
        var lastNotifiedKey: String? = null

        @Volatile
        var lastNotifiedPackage: String? = null

        @Volatile
        var lastNotifiedSender: String? = null

        @Volatile
        var lastNotifiedTime: Long = 0L

        private val lastSenderByPackage = ConcurrentHashMap<String, CachedSender>()
        private val processedKeys = ConcurrentHashMap<String, String>()
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L

        data class CachedSender(val name: String, val lastMessage: String, val time: Long)

        fun isEnabled(): Boolean = instance != null

        fun isNotificationAccessGranted(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }

        fun getLastSender(packageId: String): String? {
            val cached = lastSenderByPackage[packageId] ?: return null
            if (System.currentTimeMillis() - cached.time > CACHE_MAX_AGE_MS) return null
            return cached.name
        }

        fun getLastMessage(packageId: String = "com.facebook.orca"): String? {
            val cached = lastSenderByPackage[packageId] ?: return null
            if (System.currentTimeMillis() - cached.time > CACHE_MAX_AGE_MS) return null
            return cached.lastMessage.takeIf { it.isNotBlank() }
        }

        fun getLastNotificationSummary(packageId: String = "com.facebook.orca"): String? {
            val cached = lastSenderByPackage[packageId] ?: return null
            if (System.currentTimeMillis() - cached.time > CACHE_MAX_AGE_MS) return null
            return if (cached.lastMessage.isNotBlank()) {
                "${cached.name} က '${cached.lastMessage}' ဆိုပို့လိုက်တယ်"
            } else {
                "${cached.name} ထံမှ message ရောက်ဆဲ"
            }
        }

        fun trySendReply(
            recipientHint: String?,
            message: String,
            packageFilter: Set<String> = MESSAGING_PKGS
        ): Boolean = false // No verifiable provider-owned conversation identity; no fallback.

        fun extractSender(sbn: StatusBarNotification): String {
            val extras = sbn.notification?.extras ?: return ""
            val title = extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
            if (title.isNotBlank()) return stripMessageCount(title)

            val bigTitle = extras.getCharSequence("android.bigTitle")?.toString()?.trim() ?: ""
            if (bigTitle.isNotBlank()) return stripMessageCount(bigTitle)

            val subText = extras.getCharSequence("android.subText")?.toString()?.trim() ?: ""
            return stripMessageCount(subText)
        }

        private fun stripMessageCount(name: String): String {
            return name
                .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
                .replace(Regex("^Chat with "), "")
                .trim()
        }

        fun extractMessageText(sbn: StatusBarNotification): String {
            val extras = sbn.notification?.extras ?: return ""
            @Suppress("DEPRECATION")
            val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArray("android.messages", android.os.Bundle::class.java)
            } else {
                extras.getParcelableArray("android.messages")
            }
            if (messages != null && messages.isNotEmpty()) {
                val last = messages.last()
                if (last is android.os.Bundle) {
                    val text = last.getCharSequence("text")?.toString()?.trim()
                    if (!text.isNullOrBlank()) return text
                }
            }
            val bigText = extras.getCharSequence("android.bigText")?.toString()?.trim()
            if (!bigText.isNullOrBlank()) return bigText
            val text = extras.getCharSequence("android.text")?.toString()?.trim()
            if (!text.isNullOrBlank()) return text
            return ""
        }

        private fun matchesRecipient(sbn: StatusBarNotification, hint: String): Boolean {
            fun CharSequence?.norm() = this?.toString()?.lowercase()?.trim() ?: ""
            val title = sbn.notification?.extras?.getCharSequence("android.title").norm()
            val bigTitle = sbn.notification?.extras?.getCharSequence("android.bigTitle").norm()
            val subText = sbn.notification?.extras?.getCharSequence("android.subText").norm()
            val cleanHint = stripHonorifics(hint.lowercase().trim())
            
            if (cleanHint.isBlank()) return true

            val targets = listOf(title, bigTitle, subText).map { stripHonorifics(it) }.filter { it.isNotBlank() }
            return targets.any { t ->
                t.contains(cleanHint) || cleanHint.contains(t) ||
                t.replace(" ", "").contains(cleanHint.replace(" ", "")) ||
                cleanHint.replace(" ", "").contains(t.replace(" ", ""))
            }
        }

        private fun stripHonorifics(name: String): String {
            return name
                .replace(Regex("^(ကို|မောင်|မ|ဦး|ဒေါ်|အစ်ကို|အစ်မ|ဆရာ|ဆရာမ)\\s*"), "")
                .replace(Regex("^(ko|maung|mg|ma|u|daw|sayar)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        private fun tryInjectReply(
            service: NotificationListenerService,
            sbn: StatusBarNotification,
            message: String
        ): Boolean {
            // 1. Direct actions on notification
            if (tryInjectReplyFromActions(service, sbn.notification.actions, message)) {
                return true
            }

            // 2. WearableExtender actions (some messaging apps attach reply here)
            try {
                val wearableExtender = androidx.core.app.NotificationCompat.WearableExtender(sbn.notification)
                for (action in wearableExtender.actions) {
                    val remoteInputs = action.remoteInputs ?: continue
                    if (remoteInputs.isEmpty()) continue
                    val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    val results = android.os.Bundle()
                    for (ri in remoteInputs) {
                        results.putCharSequence(ri.resultKey, message)
                    }
                    androidx.core.app.RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                    val pendingIntent = action.actionIntent ?: continue
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        androidx.core.app.RemoteInput.setResultsSource(fillIn, androidx.core.app.RemoteInput.SOURCE_FREE_FORM_INPUT)
                    }
                    pendingIntent.send(service, 0, fillIn)
                    Log.d(TAG, "WearableExtender RemoteInput injected for action: ${action.title}")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "WearableExtender check: ${e.message}")
            }

            // 3. Child notifications from same package (for bundled/grouped notifications)
            try {
                val active = service.activeNotifications ?: return false
                val isSummary = (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
                val children = active.filter {
                    it.packageName == sbn.packageName && it.key != sbn.key &&
                    (it.groupKey == sbn.groupKey || isSummary)
                }.sortedByDescending { it.postTime }
                for (child in children) {
                    if (tryInjectReplyFromActions(service, child.notification.actions, message)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Child notification check: ${e.message}")
            }

            return false
        }

        data class ExactReplyTarget(val key: String, val packageName: String, val sender: String,
            val postTime: Long, val actionIndex: Int, val resultKey: String,
            val actionIntent: android.app.PendingIntent)

        /** Exactly one explicit sender and one direct reply action; no recent/group fallback. */
        fun resolveExactReply(sender: String, packageName: String): ExactReplyTarget? = try {
            val service = instance
            val matches = service?.activeNotifications.orEmpty().filter {
                it.packageName == packageName && extractSender(it) == sender &&
                    (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0
            }
            val notification = matches.singleOrNull()
            if (notification == null) null else {
                val actions = notification.notification.actions.orEmpty().mapIndexedNotNull { index, action ->
                    val input = action.remoteInputs?.singleOrNull()?.takeIf { it.allowFreeFormInput }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || input == null ||
                        action.actionIntent?.creatorPackage != packageName || action.actionIntent.isImmutable) null
                    else index to input.resultKey
                }
                actions.singleOrNull()?.let { (index, key) ->
                    ExactReplyTarget(notification.key, packageName, sender, notification.postTime, index, key,
                        notification.notification.actions[index].actionIntent)
                }
            }
        } catch (_: Exception) { null }

        // RemoteInput PendingIntent extras are provider-mutable, even with unchanged token identity.
        // No provider-owned recipient/revision adapter exists: all generic replies fail closed.
        fun isExactReplyCurrent(target: ExactReplyTarget): Boolean = false

        fun sendExactReply(target: ExactReplyTarget, message: String): Boolean = false

        private fun tryInjectReplyFromActions(
            service: NotificationListenerService,
            actions: Array<android.app.Notification.Action>?,
            message: String
        ): Boolean {
            if (actions == null || actions.isEmpty()) return false
            val sortedActions = actions.sortedByDescending { action ->
                val title = action.title?.toString()?.lowercase() ?: ""
                when {
                    title.contains("reply") || title.contains("ပြန်စာ") || title.contains("ပို့") -> 10
                    title.contains("send") || title.contains("direct") -> 5
                    action.remoteInputs?.isNotEmpty() == true -> 2
                    else -> 0
                }
            }

            for (action in sortedActions) {
                val remoteInputs = action.remoteInputs ?: continue
                if (remoteInputs.isEmpty()) continue

                val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                val results = android.os.Bundle()
                for (ri in remoteInputs) {
                    results.putCharSequence(ri.resultKey, message)
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
                }

                try {
                    action.actionIntent?.send(service, 0, fillIn)
                    Log.d(TAG, "RemoteInput injected for action: ${action.title}")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "actionIntent.send failed: ${e.message}")
                }
            }
            return false
        }

        private fun isRealMessage(sbn: StatusBarNotification): Boolean {
            val notif = sbn.notification ?: return false
            val extras = notif.extras ?: return false

            if ((notif.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0) return false
            if ((notif.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0) return false

            val title = extras.getCharSequence("android.title")?.toString()?.lowercase() ?: ""
            val text = extras.getCharSequence("android.text")?.toString()?.lowercase() ?: ""
            val subtext = extras.getCharSequence("android.subText")?.toString()?.lowercase() ?: ""
            
            val ignoreList = listOf(
                "displaying over other apps", "is running", "active", "sending", "was sent", 
                "backup", "uploading", "စစ်ဆေးနေသည်", "အသုံးပြုနေသည်", "ပို့လိုက်ပြီ", "ပို့နေသည်",
                "checking for new messages", "tap for more info"
            )
            
            if (ignoreList.any { title.contains(it) || text.contains(it) || subtext.contains(it) }) return false
            if (title.isBlank() && text.isBlank()) return false
            return true
        }
    }
}
