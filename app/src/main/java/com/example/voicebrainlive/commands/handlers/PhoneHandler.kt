package com.example.voicebrainlive.commands.handlers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.ErrorUtils
import com.example.voicebrainlive.learning.FeedbackLearning
import com.example.voicebrainlive.learning.IntentPatternLearner
import com.example.voicebrainlive.learning.UserUtteranceContext
import com.example.voicebrainlive.memory.BehaviorMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Phone Handler - Handles phone-related commands
 * - make_call, call, call_contact
 * - send_sms, send_messenger, send_whatsapp
 * - add_contact, edit_contact, list_contacts
 * - add_calendar_event
 * 
 * This handler extracts phone-related functionality from PhoneCommandExecutor
 * to improve code maintainability and reduce file size.
 */
class PhoneHandler(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "PhoneHandler"
        private var pendingSmsRecipientName: String? = null
        private var pendingSmsRecipientNumber: String? = null
        private var pendingSmsMessage: String? = null
        private var pendingPhoneSearchQuery: String? = null
    }

    /**
     * Execute phone command
     */
    private val approvalSession = java.util.UUID.randomUUID().toString()
    suspend fun execute(commandType: String, target: String?, value: String?): String =
        com.example.voicebrainlive.commands.SensitiveActionGuard.run(context, approvalSession, commandType, target, value) {
            executeUnchecked(commandType, target, value).orEmpty()
        }

    private suspend fun executeUnchecked(commandType: String, target: String?, value: String?): String = withContext(Dispatchers.IO) {
        when (commandType.lowercase()) {
            "make_call", "call", "call_contact" -> makeCall(target ?: "", value)
            "record_correction", "save_correction" -> recordCorrection(target ?: "", value ?: "")
            "add_contact" -> addQuickContact(target ?: "", value ?: "")
            "edit_contact", "update_contact" -> editQuickContact(target ?: "", value ?: "")
            "list_contacts" -> listQuickContacts()
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
            "add_calendar_event" -> addCalendarEvent(target ?: "", value)
            else -> "Unknown phone command: " + commandType
        }
    }

    // ===== QUICK CONTACTS =====
    private fun addQuickContact(name: String, phoneNumber: String): String {
        if (name.isBlank() || phoneNumber.isBlank()) {
            return "Please provide both name and phone number"
        }
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        com.example.voicebrainlive.commands.QuickContactStore.write(prefs) { putString(name.lowercase().trim(), cleanNumber) }
        return "Added quick contact: $name -> $cleanNumber"
    }

    private fun editQuickContact(contactName: String, newPhoneNumber: String): String {
        if (contactName.isBlank()) {
            return "Please tell me which contact to edit"
        }
        if (newPhoneNumber.isBlank()) {
            return "Please tell me the new phone number"
        }
        val cleanNumber = newPhoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.length < 8) {
            return "Invalid phone number"
        }
        val existingKey = findContactKeyByFuzzyMatch(contactName)
        if (existingKey == null) {
            return "Contact not found: $contactName. Add it first or check the name."
        }
        com.example.voicebrainlive.commands.QuickContactStore.write(prefs) { putString(existingKey, cleanNumber) }
        val displayName = existingKey.replaceFirstChar { it.uppercase() }
        return "Updated $displayName to $cleanNumber"
    }

    private fun findContactKeyByFuzzyMatch(name: String): String? {
        val searchName = name.lowercase().trim()
        val allContacts = prefs.all
        if (allContacts.isEmpty()) return null
        allContacts.entries.forEach { (key, _) ->
            val keyNormalized = key.lowercase().trim()
            if (keyNormalized == searchName) return key
        }
        allContacts.entries.forEach { (key, _) ->
            val keyNormalized = key.lowercase().trim()
            if (keyNormalized.contains(searchName) || searchName.contains(keyNormalized)) return key
        }
        val searchNoSpaces = searchName.replace(" ", "")
        allContacts.entries.forEach { (key, _) ->
            val keyNoSpaces = key.lowercase().trim().replace(" ", "")
            if (keyNoSpaces == searchNoSpaces) return key
        }
        return null
    }

    fun getQuickContact(name: String): String? {
        val searchName = name.lowercase().trim()
        if (searchName.isBlank()) return null

        prefs.getString(searchName, null)?.let { return it }

        val searchNoSpace = searchName.replace(" ", "")
        val normQuery = normalizeBurmese(searchName)
        var bestMatch: String? = null
        var bestScore = 0

        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val keyLower = key.lowercase().trim()
            val keyNoSpace = keyLower.replace(" ", "")
            val normKey = normalizeBurmese(keyLower)
            val score = when {
                keyLower == searchName -> 2000
                keyNoSpace == searchNoSpace -> 1980
                normKey == normQuery -> 1960
                keyLower.startsWith(searchName) -> 1800
                normKey.startsWith(normQuery) -> 1780
                keyLower.contains(searchName) -> 1600
                normKey.contains(normQuery) && normQuery.length >= 2 -> 1550
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = value
            }
        }

        return if (bestScore >= 1550) bestMatch else null
    }

    fun listQuickContacts(): String {
        val contacts = prefs.all.filter { it.value is String }
        if (contacts.isEmpty()) {
            return "Quick Contacts မရှိသေးပါ။ နာမည်နဲ့ ဖုန်းနံပါတ် ပြောပြီး contact အသစ် ထည့်နိုင်ပါတယ်။"
        }
        val names = contacts.keys.sorted().joinToString(", ")
        return if (names.length <= 180) {
            "Quick Contacts မှာ $names ရှိပါတယ်။ စုစုပေါင်း ${contacts.size} ယောက်ပါ။"
        } else {
            val displayName = names.take(150) + "..."
            "Quick Contacts မှာ $displayName ရှိပါတယ်။ စုစုပေါင်း ${contacts.size} ယောက်ပါ။"
        }
    }


    private fun isPositiveResponse(input: String): Boolean {
        val lower = input.lowercase().trim()
        val userUtterance = com.example.voicebrainlive.learning.UserUtteranceContext.peek()?.lowercase() ?: ""
        return lower == "yes" || lower == "y" || lower == "ok" || lower == "okay" ||
               lower.contains("ဟုတ်") || lower.contains("ခေါ်") || lower.contains("ဆက်") ||
               lower.contains("ပို့") || lower.contains("အေး") || lower.contains("အင်း") ||
               userUtterance == "yes" || userUtterance == "y" || userUtterance == "ok" || userUtterance == "okay" ||
               userUtterance.contains("ဟုတ်") || userUtterance.contains("ခေါ်") || userUtterance.contains("ဆက်") ||
               userUtterance.contains("ပို့") || userUtterance.contains("အေး") || userUtterance.contains("အင်း")
    }

    private fun isNegativeResponse(input: String): Boolean {
        val lower = input.lowercase().trim()
        val userUtterance = com.example.voicebrainlive.learning.UserUtteranceContext.peek()?.lowercase() ?: ""
        return lower == "no" || lower == "n" || lower == "cancel" ||
               lower.contains("မခေါ်") || lower.contains("မဆက်") || lower.contains("မပို့") ||
               lower.contains("မဟုတ်") || lower.contains("မလုပ်") || lower.contains("ဖျက်") ||
               userUtterance == "no" || userUtterance == "n" || userUtterance == "cancel" ||
               userUtterance.contains("မခေါ်") || userUtterance.contains("မဆက်") || userUtterance.contains("မပို့") ||
               userUtterance.contains("မဟုတ်") || userUtterance.contains("မလုပ်") || userUtterance.contains("ဖျက်")
    }

    private fun sendSms(phoneOrName: String, message: String): String {
        val spokenInput = phoneOrName.trim()
        val isNegative = isNegativeResponse(spokenInput)
        val isPositive = isPositiveResponse(spokenInput)

        // 1. Handle pending phone search permission
        if (pendingPhoneSearchQuery != null) {
            val query = pendingPhoneSearchQuery!!
            val msg = pendingSmsMessage ?: message
            if (isPositive) {
                pendingPhoneSearchQuery = null
                pendingSmsMessage = null
                
                val matches = findDeviceContactsScored(query.lowercase().trim(), query.replace(" ", ""), normalizeBurmese(query))
                if (matches.isEmpty()) {
                    return "Phone contacts ထဲမှာလည်း '$query' ကို ရှာမတွေ့ပါဘူး။"
                }
                val topMatch = matches.first()
                
                val closeMatches = matches.filter { it.score >= matches.first().score - 50 }
                if (closeMatches.size > 1) {
                    val options = closeMatches.take(3).map { match ->
                        val hasDuplicateName = closeMatches.count { it.name == match.name } > 1
                        if (hasDuplicateName) {
                            "${match.name} (${match.number})"
                        } else {
                            match.name
                        }
                    }.joinToString(" သို့မဟုတ် ")
                    pendingPhoneSearchQuery = query
                    pendingSmsMessage = msg
                    return "Phone contacts ထဲမှာ '$query' နဲ့ ပတ်သက်ပြီး ဆင်တူသူများ တွေ့ရပါသည်: $options။ ဘယ်သူ့ကို စာပို့ချင်ပါသလဲ?"
                }
                
                pendingSmsRecipientName = topMatch.name
                pendingSmsRecipientNumber = topMatch.number
                pendingSmsMessage = msg
                return "Phone contacts ထဲရှိ '${topMatch.name}' ကို စာပို့ရမလား? (ဟုတ်တယ် သို့မဟုတ် မဟုတ်ဘူး ဟု ပြောပါ)"
            } else if (isNegative) {
                pendingPhoneSearchQuery = null
                pendingSmsMessage = null
                return "စာပို့ခြင်းကို ဖျက်သိမ်းလိုက်ပါပြီ။"
            } else {
                pendingPhoneSearchQuery = null
                pendingSmsMessage = null
            }
        }

        // 2. Handle pending send SMS confirmation
        if (pendingSmsRecipientNumber != null) {
            val recipientName = pendingSmsRecipientName ?: pendingSmsRecipientNumber ?: ""
            val recipientNum = pendingSmsRecipientNumber!!
            val msg = pendingSmsMessage ?: message
            if (isPositive) {
                pendingSmsRecipientName = null
                pendingSmsRecipientNumber = null
                pendingSmsMessage = null
                return sendSmsToPhysicalNumber(normalizePhoneNumber(recipientNum), msg)
            } else if (isNegative) {
                pendingSmsRecipientName = null
                pendingSmsRecipientNumber = null
                pendingSmsMessage = null
                return "စာပို့ခြင်းကို ဖျက်သိမ်းလိုက်ပါပြီ။"
            }
        }

        val cleanMsg = com.example.voicebrainlive.commands.ActionDispatcher.cleanReplyMessage(message) ?: message.trim()
        if (cleanMsg.isBlank()) return "FAILED: စာပို့ဖို့ message ပြောပေးပါ။"

        val smsPackages = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
        )
        if (com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.isEnabled()) {
            val sentViaNotif = try {
                val notifSender = spokenInput.takeIf { it.isNotBlank() }
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
                val who = spokenInput.ifBlank { "မက်ဆေ့ခ်ျ" }
                return "SUCCESS: SMS ကနေ $who ဆီ စာတိုက်ရိုက် ပို့ပြီးပါပြီ။"
            }
        }

        var effectiveInput = removeContactSourceModifier(spokenInput)
        val forcePhoneContacts = shouldPreferDeviceContacts(spokenInput)

        val cleaned = effectiveInput.replace(Regex("[^0-9+]"), "")
        if (cleaned.length >= 8) {
            val finalPhone = normalizePhoneNumber(cleaned)
            Log.d(TAG, "SMS to raw number: $finalPhone")
            return sendSmsToPhysicalNumber(finalPhone, cleanMsg)
        }

        // Search Quick Contacts ONLY first
        val matches = findContactsScored(effectiveInput, quickOnly = true)

        if (matches.isNotEmpty()) {
            val topMatch = matches.first()
            val finalPhone = normalizePhoneNumber(topMatch.number)
            
            val closeMatches = matches.filter { it.score >= matches.first().score - 50 }
            if (closeMatches.size > 1) {
                val options = closeMatches.take(3).map { match ->
                    val hasDuplicateName = closeMatches.count { it.name == match.name } > 1
                    if (hasDuplicateName) {
                        "${match.name} (${match.number})"
                    } else {
                        match.name
                    }
                }.joinToString(" သို့မဟုတ် ")
                return "အမြန်ကွန်တက်ထဲမှာ '$spokenInput' နဲ့ ပတ်သက်ပြီး ဆင်တူသူများ တွေ့ရပါသည်: $options။ ဘယ်သူ့ကို စာပို့ချင်ပါသလဲ?"
            }
            
            // Send directly!
            return sendSmsToPhysicalNumber(finalPhone, cleanMsg)
        } else {
            // Not found in Quick Contacts. Ask if we should search Phone Contacts.
            pendingPhoneSearchQuery = effectiveInput
            pendingSmsMessage = cleanMsg
            return "အမြန်ကွန်တက်ထဲမှာ '$spokenInput' ကို ရှာမတွေ့ပါ။ ဖုန်း Contacts ထဲမှာ ရှာပေးရမလား?"
        }
    }

    private fun sendSmsToPhysicalNumber(phone: String, message: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            return try {
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(message)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(phone, null, message, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                }
                "SUCCESS: စာပို့ပြီးပါပြီ။ $phone ဆီ ပို့ခဲ့တယ်။"
            } catch (e: Exception) {
                Log.e(TAG, "sendSms SmsManager error: ${e.message}")
                openSmsComposeFallback(phone, message, isFallback = true)
            }
        } else {
            return openSmsComposeFallback(phone, message, isFallback = false)
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
            Log.e(TAG, "sendSms error: ${e.message}")
            "FAILED: Could not open SMS: ${e.message}"
        }
    }

    /** Messenger စနစ်ပေါင်းစုံ - Notification Reply, API, သို့မဟုတ် App Intent ဖြင့် ပို့သည်။ */
    private fun sendMessengerUnified(message: String, recipientHint: String?): String {
        val cleanMsg = com.example.voicebrainlive.commands.ActionDispatcher.cleanReplyMessage(message) ?: message.trim()
        if (cleanMsg.isBlank()) return "FAILED: Messenger က ဘာ ပြောချင်လဲ ပြောပေးပါ။"

        // (၁) Notification Reply - အနီးစပ်ဆုံး notification ရှိရင် အရင်သုံး
        if (com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.isEnabled()) {
            val targetPkg = com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedPackage
                ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifPkg
            val pkgFilter = if (!targetPkg.isNullOrBlank()) setOf(targetPkg) else com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.MESSAGING_PKGS
            val effectiveRecipient = recipientHint?.takeIf { it.isNotBlank() }
                ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifSender
                ?: com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender
            val sent = try {
                com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.trySendReply(effectiveRecipient, cleanMsg, pkgFilter)
            } catch (e: Exception) { false }
            if (sent) return "SUCCESS: Messenger ကတစ်ဆင့် (Notification မှ) စာတိုက်ရိုက် ပို့ပြီးပါပြီ။"
        }

        // (၂) API - Token ရှိပြီး recipient ပါမှ PSID lookup + API ဖြင့် ပို့မည်
        val token = com.example.voicebrainlive.SettingsActivity.getSecurePrefs(context).getString(com.example.voicebrainlive.messenger.MessengerApiClient.PREF_MESSENGER_TOKEN, "")
        if (!token.isNullOrBlank() && !recipientHint.isNullOrBlank()) {
            return com.example.voicebrainlive.messenger.MessengerApiClient(context).sendMessageSync(recipientHint, cleanMsg)
        }

        // (၃) Fallback - App Intent
        return sendMessagingApp(cleanMsg, recipientHint, "com.facebook.orca", "Messenger")
    }

    /** Messenger/WhatsApp စာပို့ရန်။ Notification Reply API ဖြင့် တိုက်ရိုက်ပို့ကြည့်၊ မရရင် Intent fallback။ */
    private fun sendMessagingApp(message: String, recipientHint: String? = null, packageId: String, appName: String): String {
        val cleanMsg = com.example.voicebrainlive.commands.ActionDispatcher.cleanReplyMessage(message) ?: message.trim()
        if (cleanMsg.isBlank()) return "FAILED: $appName က ဘာ ပြောချင်လဲ ပြောပေးပါ။"

        // (၁) Notification Reply API - notification တက်နေရင် RemoteInput ဖြင့် တိုက်ရိုက်ပို့
        val effectiveRecipient = recipientHint?.takeIf { it.isNotBlank() }
            ?: com.example.voicebrainlive.VoiceLiveService.instance?.currentNotifSender
            ?: com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.lastNotifiedSender
        val sent = try {
            com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.trySendReply(
                effectiveRecipient, cleanMsg, packageFilter = setOf(packageId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "NotificationReply trySendReply", e)
            false
        }
        if (sent) return "SUCCESS: $appName က တိုက်ရိုက် စာပို့ပြီးပါပြီ။"

        // (၂) Fallback - Intent ဖြင့် app ဖွင့်ပြီး pre-fill
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cleanMsg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(packageId)
        }
        return try {
            context.startActivity(intent)
            val hint = effectiveRecipient?.takeIf { it.isNotBlank() }?.let { " ($it ကို)" } ?: ""
            val notifTip = if (!com.example.voicebrainlive.messenger.UnifiedNotificationListenerService.isEnabled())
                " Settings > Notification access မှာ Voice Brain ဖွင့်ထားမှ တိုက်ရိုက်ပို့နိုင်ပါမည်။"
            else ""
            "FAILED: စာတိုက်ရိုက် ပို့မရပါ။ $appName ဖွင့်ပေးလိုက်ပါပြီ။$hint လူရွေးပြီး ပို့ခလုပ် နှိပ်ပေးပါ။$notifTip"
        } catch (e: Exception) {
            Log.e(TAG, "sendMessagingApp ($appName) error: ${e.message}")
            try {
                intent.setPackage(null)
                context.startActivity(Intent.createChooser(intent, "$appName / Share"))
                "FAILED: $appName မတွေ့ပါ။ Share app ဖွင့်ပေးလိုက်ပါပြီ။ $appName ကို ရွေးပြီး ပို့ပေးပါ။"
            } catch (e2: Exception) {
                Log.e(TAG, "sendMessagingApp fallback error: ${e2.message}")
                "FAILED: $appName ဖွင့်မရပါ။ App ထည့်ထားမထား စစ်ကြည့်ပါ။"
            }
        }
    }

    private fun addCalendarEvent(title: String, dateTimeStr: String?): String {
        val beginTime = parseTimeInput(context, dateTimeStr ?: "")?.timeInMillis
            ?: (System.currentTimeMillis() + 60 * 60 * 1000)
        val endTime = beginTime + 60 * 60 * 1000

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Events.DTSTART, beginTime)
                    put(android.provider.CalendarContract.Events.DTEND, endTime)
                    put(android.provider.CalendarContract.Events.TITLE, title.ifBlank { "Event" })
                    put(android.provider.CalendarContract.Events.CALENDAR_ID, 1)
                    put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                }
                val uri = context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    return "ပြက္ခဒိန်မှာ '$title' ကို တိုက်ရိုက် မှတ်သားပြီးပါပြီ။"
                }
            } catch (e: Exception) {
                Log.e(TAG, "addCalendarEvent direct insert error: ${e.message}")
            }
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            setData(android.provider.CalendarContract.Events.CONTENT_URI)
            putExtra(android.provider.CalendarContract.Events.TITLE, title.ifBlank { "Event" })
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "ပြက္ခဒိန်ကို ဖွင့်ပေးလိုက်ပါပြီ။ '$title' ကို ဆက်လက်ထည့်သွင်းပါ။"
        } catch (e: Exception) {
            Log.e(TAG, "addCalendarEvent error: ${e.message}")
            "ပြက္ခဒိန် ဖွင့် မရပါ။"
        }
    }

    /**
     * Make a phone call. 
     * Safety-first approach:
     * - Exact Quick Contact match → call immediately
     * - Fuzzy match (alias/correction) → return confirmation message, DON'T auto-call
     * - No match → show available contacts
     */
        private fun makeCall(target: String, value: String?): String {
        var input = listOfNotNull(target.trim(), value?.trim()).joinToString(" ").trim()
        var effectiveInput = removeContactSourceModifier(input)
        val forcePhoneContacts = shouldPreferDeviceContacts(input)

        if (looksLikeNonCallRequest(input)) {
            return "\u1012\u102b\u1000 \u1016\u102f\u1014\u103e\u102a\u1038\u1001\u1031\u102b\u103a\u1010\u1032\u1037 command \u1019\u101f\u102f\u1010\u103a\u1014\u102d\u102f\u1004\u103a\u1015\u102b\u104b \u101e\u102e\u1001\u103b\u1004\u103a\u1038\u104a YouTube\u104a \u101b\u103e\u102c\u1016\u103d\u1031\u1019\u103e\u102f request \u1016\u103c\u1005\u103a\u101b\u1004\u103a \u1021\u1032\u1012\u102e\u1021\u1010\u102d\u102f\u1004\u103a\u1038 \u1015\u103c\u1014\u103a\u1015\u103c\u1031\u102c\u1015\u1031\u1038\u1015\u102b\u104b \u1016\u102f\u1014\u103a\u1038\u1001\u1031\u102b\u103aမ\u101c\u102f\u1015\u103a\u1015\u102b\u104b"
        }
        
        if (input.isNotBlank() && isThatPersonReference(input)) {
            val lastCalled = BehaviorMemory.getLastCalledContact(context)
            if (lastCalled != null) {
                input = lastCalled
            }
        }
        
        if (input.isBlank()) {
            val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "makeCall blank intent error: ${e.message}")
            }
            return "\u1016\u102f\u1014\u103a\u1038\u1001\u1031\u102b\u103a\u1019\u1032\u1037 \u1014\u102c\u1019\u100a\u103a \u1015\u103c\u1031\u102c\u1015\u1031\u1038\u1015\u102b"
        }

        var searchInput = effectiveInput
        val directNumber = extractDialableNumber(effectiveInput)
        if (directNumber != null) {
            val finalPhone = normalizePhoneNumber(directNumber)
            Log.d(TAG, "Call to raw number: $finalPhone")
            BehaviorMemory.recordCallAttempt(context, searchInput)
            return proceedWithCall(finalPhone, finalPhone)
        }

        val rememberedTarget = BehaviorMemory.getRememberedCallAlias(context, searchInput)
        val correctedTarget = BehaviorMemory.getCorrectedCallTarget(context, searchInput)
        val trustedTarget = rememberedTarget ?: correctedTarget

        if (trustedTarget != null && !sameContactQuery(trustedTarget, effectiveInput)) {
            Log.d(TAG, "Trusted call memory found: '$input' -> '$trustedTarget'")
            effectiveInput = trustedTarget
            searchInput = effectiveInput
        }

        val rememberedNumber = extractDialableNumber(effectiveInput)
        if (rememberedNumber != null) {
            val finalPhone = normalizePhoneNumber(rememberedNumber)
            Log.d(TAG, "Call to remembered raw number: $finalPhone")
            BehaviorMemory.recordCallAttempt(context, input)
            return proceedWithCall(finalPhone, finalPhone)
        }
        
        if (rememberedTarget != null && !sameContactQuery(rememberedTarget, effectiveInput)) {
            Log.d(TAG, "Alias found: '$input' -> '$rememberedTarget' (need confirmation)")
            return "'$input' \u1006\u102d\u102f\u1010\u102c '$rememberedTarget' \u1000\u102d\u102f \u1001\u1031\u102b\u103a\u1001\u103b\u1004\u103a\u1010\u102c\u101c\u102d\u102f\u1037 \u1019\u103eတ\u103a\u1019\u102d\u1011\u102c\u1038\u1015\u102b\u1010\u101a\u103a\u104b \u1019\u103e\u1014\u103a\u1015\u102b\u101e\u101c\u102cး? \u101f\u102f\u1010\u103a\u101bင် '\u101f\u102fတ်\u1010\u101a်' \u1015\u103c\u1031\u102c\u1015\u102b\u104a \u1019\u103e\u102cး\u101bင် '\u1019\u101f\u102f\u1010\u103a\u1018\u1030း\u104a [\u1014\u102c\u1019\u100a်] \u1001\u1031\u102b်\u1001\u1032့' \u1015\u103c\u1031\u102c\u1015\u102b\u104b"
        }
        
        if (correctedTarget != null && !sameContactQuery(correctedTarget, effectiveInput)) {
            Log.d(TAG, "Correction found: '$input' -> '$correctedTarget' (need confirmation)")
            return "'$input' \u1000\u102d\u102f\u1001\u1031\u102b\u103a\u101bင် '$correctedTarget' \u1006\u102e \u1001\u1031\u102b\u103a\u1001\u1032\u1037\u1010\u1032့ \u1019\u103e\u1010\u103a\u1010\u101a\u103a\u1038\u101b\u103e\u102d\u1015\u102b\u1010\u101a\u103a\u104b \u1010\u1000\u101a\u103a \u1012\u102e\u101e\u1030\u1000\u102d\u102f \u1001\u1031\u102b\u103a\u1001\u103b\u1004\u103a\u1010\u102c\u101c\u102c\u1038?"
        }

        val matches = if (forcePhoneContacts) {
            findDeviceContactsScored(effectiveInput.lowercase().trim(), effectiveInput.replace(" ", ""), normalizeBurmese(effectiveInput))
        } else {
            findContactsScored(effectiveInput)
        }

        if (matches.isEmpty()) {
            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            val permissionTip = if (!hasPermission) {
                " ဖုန်းထဲက Contacts တွေကို ရှာနိုင်အောင် ဖုန်း Settings မှာ Contacts permission ခွင့်ပြုပေးထားပါ။"
            } else {
                ""
            }
            val quickContactsList = prefs.all.entries
                .filter { it.value is String && it.key.length >= 2 }
                .map { it.key }
                .sorted()
                .joinToString(", ")
            val listInfo = if (quickContactsList.isNotBlank())
                " \u101c\u1000\u103a\u101b\u103e\u102d Quick Contacts: $quickContactsList"
            else
                " Quick Contacts \u1019\u101b\u103e\u102d\u101e\u1031\u1038\u1015\u102b\u104b '\u1014\u102c\u1019\u100a\u103a \u1016\u102f\u1014\u103a\u1038\u1014\u1036\u1015\u102b\u1010\u103a' \u1015\u103f\u1036\u1005\u1036 \u1015\u103c\u1031\u102c\u1015\u103c\u102e\u1038 contact \u1011\u100a\u1037\u103a\u1014\u102d\u102f\u1004\u103a\u1015\u102b\u1010\u101a\u103a\u104b"
            BehaviorMemory.recordCallAttempt(context, input)
            return "'$input' \u1006\u102d\u1032\u1010\u1032\u1037 contact \u1000\u102d\u102f Quick Contacts \u1014\u1032\u1037 Phone Contacts \u1011\u1032\u1019\u103e\u102c \u101b\u103e\u102c\u1019\u1010\u103d\u1031\u1037\u1015\u102b\u104b$permissionTip$listInfo"
        }

        if (matches.size == 1) {
            val match = matches[0]
            val finalPhone = normalizePhoneNumber(match.number)
            if (match.score >= 1980 || (match.type == "Device" && match.score >= 980)) {
                Log.d(TAG, "Call to single exact match: '${match.name}' (${match.type}) -> $finalPhone")
                BehaviorMemory.recordCallAttempt(context, searchInput)
                return proceedWithCall(finalPhone, match.name)
            } else {
                Log.d(TAG, "Call to fuzzy match requires confirmation: '${match.name}' (score=${match.score})")
                return "'$searchInput' \u1006\u102d\u1032\u1010\u102c '${match.name}' \u1000\u102d\u102f \u1001\u1031\u102b\u103a\u1001\u103b\u1004\u103a\u1010\u102c\u101c\u102d\u102f\u1037 \u101a\u1030\u1006\u1015\u102b\u1010\u101a\u103a\u104b \u1019\u103e\u1014\u103a\u1015\u102b\u101e\u101c\u102c\u1038? \u101f\u102f\u1010\u103a\u101b\u1004\u103a '\u101f\u102f\u1010\u103a\u1010\u101a\u103a' \u101c\u102d\u102f\u1037 \u1015\u103c\u1014\u103a\u1015\u103c\u1031\u102c\u1015\u102b\u104b"
            }
        }

        val options = matches.map { 
            if (matches.filter { m -> m.name == it.name }.size > 1) {
                "${it.name} (${it.number})"
            } else {
                "${it.name} (${it.type})"
            }
        }.joinToString(", ")
        return "'$searchInput' \u1021\u1010\u103d\u1000\u103a contacts \u1010\u103d\u1031\u1037: $options - \u1018\u101a\u103a\u101e\u1030\u1037\u1000\u102d\u102f \u1001\u1031\u102b\u103a\u1001\u103b\u1004\u103a\u101c\u1032 \u1015\u103c\u1031\u102c\u1015\u1031\u1038\u1015\u102b\u104b"
    }
private fun shouldPreferDeviceContacts(input: String): Boolean {
        val lower = input.lowercase()
        return lower.contains("phone contact") || lower.contains("device contact") || lower.contains("ဖုန်း contact")
    }

    private fun removeContactSourceModifier(input: String): String {
        return input.replace(Regex("\\b(phone contact|device contact|ဖုန်း contact)\\b", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun buildMissingCallTargetMessage(): String {
        val names = prefs.all.entries
            .filter { it.value is String && it.key.length >= 2 }
            .map { it.key }
            .sorted()
            .take(8)
            .joinToString(", ")
        val hint = if (names.isNotBlank()) " Quick Contacts ထဲမှာ $names ရှိပါတယ်။" else ""
        return "ဘယ်သူ့ကိုခေါ်ရမလဲ နာမည်အတိအကျပြောပေးပါ။$hint"
    }

    private fun looksLikeNonCallRequest(input: String): Boolean {
        val lower = input.lowercase()
        if (lower.isBlank()) return false
        val hasMusicHint = listOf(
            "music", "song", "spotify", "youtube", "playlist", "track", "album",
            "သီချင်း", "ဖွင့်", "နားထောင်"
        ).any { lower.contains(it) }
        val hasCallHint = listOf("call", "phone", "dial", "ဖုန်း", "ခေါ်", "ဆက်").any { lower.contains(it) }
        return hasMusicHint && !hasCallHint
    }

    private fun getQuickContactMatch(input: String): ContactMatch? {
        val searchLower = input.lowercase().trim()
        if (searchLower.isBlank()) return null
        val searchNoSpace = searchLower.replace(" ", "")
        val normQuery = normalizeBurmese(searchLower)
        val looseQuery = normalizeContactForMatch(searchLower)
        
        var bestMatch: ContactMatch? = null
        var bestScore = 0
        
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val keyLower = key.lowercase().trim()
            val keyNoSpace = keyLower.replace(" ", "")
            val normKey = normalizeBurmese(keyLower)
            val looseKey = normalizeContactForMatch(keyLower)
            
            val score = when {
                keyLower == searchLower -> 2000                    // Exact match
                keyNoSpace == searchNoSpace -> 1980                // Exact (no space)
                normKey == normQuery -> 1960                       // Normalized exact
                looseKey == looseQuery && looseQuery.length >= 2 -> 1970
                keyLower.startsWith(searchLower) -> 1800           // Prefix
                normKey.startsWith(normQuery) -> 1780              // Normalized prefix
                looseKey.startsWith(looseQuery) && looseQuery.length >= 2 -> 1770
                keyLower.contains(searchLower) -> 1600             // Substring
                searchLower.contains(keyLower) && keyLower.length >= 2 -> 1650 // Reverse Substring
                looseKey.contains(looseQuery) && looseQuery.length >= 2 -> 1560
                looseQuery.contains(looseKey) && looseKey.length >= 2 -> 1610 // Reverse loose
                normKey.contains(normQuery) && normQuery.length >= 2 -> 1550 // Normalized substring
                normQuery.contains(normKey) && normKey.length >= 2 -> 1600 // Reverse normalized
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = ContactMatch(key, value, "Quick", score)
            }
        }
        
        return if (bestScore >= 1550) bestMatch else null
    }

    private fun proceedWithCall(number: String, displayName: String): String {
        if (com.example.voicebrainlive.SettingsActivity.isTestCallMode(context)) {
            com.example.voicebrainlive.ActionRecordManager.recordAction(
                context, 
                "TestCall", 
                "Simulated call to $displayName ($number) via PhoneHandler."
            )
            return "📞 [စမ်းသပ်ဖုန်းခေါ်ဆိုမှု]\n" +
                   "• အမည်: $displayName ($number)\n" +
                   "• စမ်းသပ်မုဒ်ဖြစ်၍ ဖုန်းတကယ်ခေါ်မည်မဟုတ်ပါ။"
        }
        val callUri = Uri.parse("tel:${number.replace("#", "%23")}")
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = callUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                BehaviorMemory.recordCall(context, displayName)
                "Calling $number ($displayName)"
            } else {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = callUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                BehaviorMemory.recordCall(context, displayName)
                "Opening dialer with $number"
            }
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = callUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            BehaviorMemory.recordCall(context, displayName)
            "Opening dialer with $number"
        }
    }

    private fun normalizeBurmese(input: String): String {
        var s = input.lowercase().trim().replace(" ", "")
        
        // Map common Burmese consonants to their likely English phonetic starting letters
        val burmeseMappings = mapOf(
            "ချ" to "ch",
            "ဈ" to "j",
            "ဋ" to "t",
            "ဌ" to "t",
            "ဍ" to "d",
            "ဎ" to "d",
            "ဏ" to "n",
            "ဧ" to "e",
            "ဝ" to "w",
            "ဖ" to "p",
            "က" to "k",
            "ခ" to "k",
            "မ" to "m",
            "င" to "ng",
            "စ" to "s",
            "ဆ" to "s",
            "ဇ" to "z",
            "ည" to "ny",
            "တ" to "t",
            "ထ" to "t",
            "ဒ" to "d",
            "န" to "n",
            "ပ" to "p",
            "ဘ" to "b",
            "ယ" to "y",
            "ရ" to "r",
            "လ" to "l",
            "သ" to "th",
            "ဟ" to "h",
            "အ" to "a",
            "ဂ" to "g",
            "ဃ" to "g",
            "ဗ" to "b",
            "ဓ" to "d",
            "ဠ" to "l"
        )
        burmeseMappings.forEach { (burmese, roman) -> if (s.contains(burmese)) s = s.replace(burmese, roman) }

        return s
            .replace("\u1031", "")  // e vowel
            .replace("\u103A", "")  // asat
            .replace("\u103B", "y") // ya-pin -> y
            .replace("\u103C", "r") // ya-yit -> r
            .replace("\u103D", "w") // wa-sw -> w
            .replace("\u103E", "h") // ha-hto -> h
            .replace("\u1037", "")  // dot below
            .replace("\u1094", "")  // dot above
            .replace("\u102C", "a") // aa vowel -> a
            .replace("\u1038", "")  // visarga
            .replace("\u102D", "i") // i vowel -> i
            .replace("\u102E", "i") // ii vowel -> i
            .replace("\u102F", "u") // u vowel -> u
            .replace("\u1030", "u") // uu vowel -> u
            .replace(Regex("[^a-z0-9]"), "") // clear remaining Burmese chars
    }

    data class ContactMatch(val name: String, val number: String, val type: String, val score: Int = 0)

    private fun normalizeContactForMatch(input: String): String {
        val withoutHonorifics = input
            .lowercase()
            .replace(Regex("\\b(ko|ma|u|oo|daw|sayama|sayadaw|bro|sis)\\b"), " ")
            .replace(Regex("(^|\\s)(ကို|မ|ဦး|ဦး|ဒေါ်|ဆရာမ|ဆရာ)(\\s|$)"), " ")
        return normalizeBurmese(withoutHonorifics)
    }

    private fun sameContactQuery(a: String, b: String): Boolean {
        return normalizeContactForMatch(a) == normalizeContactForMatch(b)
    }

    /**
     * Unified Scoring Search: ALWAYS prioritizes Quick Contacts over Device Contacts.
     * Quick Contacts get score 2000+ range, Device Contacts get 1000 range.
     * This ensures Quick Contact always wins when there's a match.
     */
    private fun findContactsScored(query: String, quickOnly: Boolean = false): List<ContactMatch> {
        val searchLower = query.lowercase().trim()
        if (searchLower.isBlank()) return emptyList()
        val searchNoSpace = searchLower.replace(" ", "")
        val normQuery = normalizeBurmese(searchLower)
        val looseQuery = normalizeContactForMatch(searchLower)
        
        val allMatches = mutableListOf<ContactMatch>()
        
        // === 1. Quick Contacts (Score 2000+ range) - ALWAYS HIGHEST PRIORITY ===
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val keyLower = key.lowercase().trim()
            val keyNoSpace = keyLower.replace(" ", "")
            val normKey = normalizeBurmese(keyLower)
            val looseKey = normalizeContactForMatch(keyLower)
            val score = when {
                keyLower == searchLower -> 2000                    // Exact match
                keyNoSpace == searchNoSpace -> 1980                // Exact (no space)
                normKey == normQuery -> 1960                       // Normalized exact (Myanmar diacritics stripped)
                looseKey == looseQuery && looseQuery.length >= 2 -> 1970
                keyLower.startsWith(searchLower) -> 1800           // Prefix
                normKey.startsWith(normQuery) -> 1780              // Normalized prefix
                looseKey.startsWith(looseQuery) && looseQuery.length >= 2 -> 1770
                keyLower.contains(searchLower) -> 1600             // Substring
                searchLower.contains(keyLower) && keyLower.length >= 2 -> 1650 // Reverse Substring
                looseKey.contains(looseQuery) && looseQuery.length >= 2 -> 1560
                looseQuery.contains(looseKey) && looseKey.length >= 2 -> 1610 // Reverse loose
                normKey.contains(normQuery) && normQuery.length >= 2 -> 1550 // Normalized substring
                normQuery.contains(normKey) && normKey.length >= 2 -> 1600 // Reverse normalized
                else -> 0
            }
            if (score > 0) allMatches.add(ContactMatch(key, value, "Quick", score))
        }
        
        // 2. Device Contacts (Score 1000 range) - Only searched if Quick Contact doesn't have exact match
        // and not quickOnly mode
        if (!quickOnly) {
            val hasQuickExactMatch = allMatches.any { it.score >= 1950 }
            if (!hasQuickExactMatch) {
                allMatches.addAll(findDeviceContactsScored(searchLower, searchNoSpace, normQuery))
            }
        }
        
        if (allMatches.isEmpty()) return emptyList()
        
        // Sort by score
        val sorted = allMatches.sortedByDescending { it.score }
        val topScore = sorted.first().score
        
        // If top match is clearly superior (score >= 1950 = near perfect Quick Contact match)
        if (topScore >= 1950) {
            return sorted.filter { it.score >= 1950 }
        }
        
        // For lower scores, return close matches for user resolution
        return sorted.filter { it.score == topScore || it.score >= (topScore - 50) }.take(5)
    }
    
    /**
     * Search Device Contacts only (Score 1000 range)
     */
    private fun findDeviceContactsScored(searchLower: String, searchNoSpace: String, normQuery: String): List<ContactMatch> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val displayNameCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            } else {
                android.provider.ContactsContract.Contacts.DISPLAY_NAME
            }
            val projection = arrayOf(displayNameCol, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE)
            
            val deviceMatches = mutableListOf<ContactMatch>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(displayNameCol)
                val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE)
                
                while (cursor.moveToNext()) {
                    val cName = cursor.getString(nameIdx) ?: continue
                    val cNum = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: continue
                    if (cNum.length < 8) continue
                    
                    val cNameLower = cName.lowercase().trim()
                    val cNameNorm = normalizeBurmese(cNameLower)
                    val cNameNoSpace = cNameLower.replace(" ", "")
                    val score = when {
                        cNameLower == searchLower -> 1000          // Exact
                        cNameNoSpace == searchNoSpace -> 980       // Exact (no space)
                        cNameNorm == normQuery -> 960               // Normalized exact
                        cNameLower.startsWith(searchLower) -> 800
                        cNameNorm.startsWith(normQuery) -> 780
                        cNameLower.contains(searchLower) -> 500
                        searchLower.contains(cNameLower) && cNameLower.length >= 2 -> 600 // Reverse Substring
                        cNameNorm.contains(normQuery) && normQuery.length >= 2 -> 400
                        normQuery.contains(cNameNorm) && cNameNorm.length >= 2 -> 450 // Reverse normalized
                        else -> 0
                    }
                    if (score > 0) {
                        deviceMatches.add(ContactMatch(cName, cNum, "Device", score))
                    }
                }
            }
            return deviceMatches
        } catch (e: Exception) { 
            Log.e(TAG, "Device search error: ${e.message}")
            return emptyList()
        }
    }

    private fun normalizePhoneNumber(raw: String): String {
        val s = raw.replace(Regex("[^0-9+*#]"), "")
        if (s.startsWith("+") || s.startsWith("0")) return s
        if (s.startsWith("82") && s.length in 10..12) return "+$s"
        if (s.startsWith("959") && s.length >= 11) return "+$s"
        return s
    }

    private fun extractDialableNumber(input: String): String? {
        val cleaned = myanmarToArabic(input).replace(Regex("[^0-9+*#]"), "")
        if (cleaned.count { it == '+' } > 1) return null
        if (cleaned.contains("+") && !cleaned.startsWith("+")) return null
        val digitCount = cleaned.count { it.isDigit() }
        return if (digitCount >= 8 || cleaned.contains("*") || cleaned.contains("#")) cleaned else null
    }

    /** Learning from Corrections */
    private fun recordCorrection(type: String, value: String): String {
        val v = value.trim()
        if (v.isBlank()) return "ပြင်ပြောတဲ့အရာကို ပြောပေးပါ။ ဥပမာ: မဟုတ်ဘူး အောင်ကို ခေါ်ခဲ့"
        val correctionType = type.lowercase()
        FeedbackLearning(context).recordFeedback(v, mapOf("correction_type" to correctionType), correctionType)

        val userPhrase = UserUtteranceContext.peek()
        when (correctionType) {
            "call", "ခေါ်", "phone" -> {
                BehaviorMemory.recordCallCorrection(context, v)
                userPhrase?.let { IntentPatternLearner.removePattern(context, it) }
                IntentPatternLearner.recordPattern(
                    context = context,
                    phrase = userPhrase ?: "ခေါ်ပေး",
                    commandType = "make_call",
                    target = v,
                    confirmedByUser = true
                )
                return "မှတ်ပြီးပါပြီ။ နောက်တစ်ခါ ခေါ်ပေးလို့ ပြောရင် $v ကို ခေါ်ပေးမယ်။"
            }
            "music", "သီချင်း", "song" -> {
                BehaviorMemory.recordMusicCorrection(context, v)
                userPhrase?.let { IntentPatternLearner.removePattern(context, it) }
                IntentPatternLearner.recordPattern(
                    context = context,
                    phrase = userPhrase ?: "သီချင်းဖွင့်",
                    commandType = "play_music",
                    target = v,
                    confirmedByUser = true
                )
                return "မှတ်ပြီးပါပြီ။ နောက်တစ်ခါ ပြန်ဖွင့်ပေးလို့ ပြောရင် $v ကို ဖွင့်ပေးမယ်။"
            }
            else -> {
                BehaviorMemory.recordCallCorrection(context, v)
                return "မှတ်ပြီးပါပြီ။ နောက်တစ်ခါ $v ကို သုံးပေးမယ်။"
            }
        }
    }

    /** Phase 3: "ဒီလူ" / "that person" / "သူ့ကို" = last called contact */
    private fun isThatPersonReference(s: String): Boolean {
        val t = s.trim().lowercase()
        if (t.length > 20) return false
        return t in listOf(
            "ဒီလူ", "ဒီလူကို", "သူ", "သူ့ကို", "သူမကို", "ပြန်ခေါ်", "ပြန်ခေါ်ပေး", "ပြန်ဆက်ပေး", "ပြန်ဆက်",
            "that person", "that guy", "him", "her", "call again",
            "သူ့", "အဲ့ဒီလူ", "အဲ့ဒီလူကို", "ဒီအစ်ကို", "ဒီအစ်မ", "ဒီဆရာ"
        )
    }

    /** Parse natural date+time */
    private fun parseTimeInput(ctx: Context, input: String): java.util.Calendar? {
        return try {
            val tz = com.example.voicebrainlive.SettingsActivity.getTimezone(ctx)
            val cal = java.util.Calendar.getInstance(tz)
            val normalized = myanmarToArabic(input).trim()
            
            var dayOffset = 0
            when {
                normalized.contains("မနက်ဖြန်") || normalized.contains("tomorrow") -> dayOffset = 1
                normalized.contains("ညနောက်နေ့") || normalized.contains("day after") -> dayOffset = 2
                normalized.contains("ယနေ့") || normalized.contains("today") -> dayOffset = 0
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            
            val timeMatch = Regex("(\\d{1,2}):(\\d{1,2})").find(normalized)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = timeMatch.groupValues[2].toInt()
                val isPM = normalized.contains("pm", ignoreCase = true)
                val isAM = normalized.contains("am", ignoreCase = true)
                when {
                    isPM && hour < 12 -> hour += 12
                    isAM && hour == 12 -> hour = 0
                }
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(java.util.Calendar.SECOND, 0)
                return cal
            }
            
            val hourMatch = Regex("(\\d{1,2})\\s*နာရီ|(\\d{1,2})\\s*o'?clock|(\\d{1,2})\\s*시").find(normalized)
            val minMatch = Regex("(\\d{1,2})\\s*မိနစ်|(\\d{1,2})\\s*minute").find(normalized)
            val hour = hourMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            val minute = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (hour != null) {
                var h = hour.coerceIn(0, 23)
                when {
                    (normalized.contains("မနက်") || normalized.contains("နံနက်")) && h < 12 -> { }
                    (normalized.contains("ညနေ") || normalized.contains("မိုးချုပ်") || normalized.contains("evening")) && h in 1..7 -> h += 12
                    normalized.contains("ည ") && h in 1..11 -> h += 12
                    normalized.contains("နေ့လယ်") || normalized.contains("မုန်း") -> h = 12
                }
                cal.set(java.util.Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(java.util.Calendar.SECOND, 0)
                return cal
            }
            
            val hourOnly = when {
                normalized.contains("နံနက်") || normalized.contains("မနက်") && !Regex("\\d").containsMatchIn(normalized) -> 9
                normalized.contains("နေ့လယ်") || normalized.contains("မုန်း") -> 12
                normalized.contains("ညနေ") -> 17
                normalized.contains("ည") -> 20
                else -> return null
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, hourOnly)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal
        } catch (e: Exception) {
            null
        }
    }

    /** Myanmar numerals ၀၁၂၃၄၅၆၇၈၉ -> 0-9 */
    private fun myanmarToArabic(s: String): String = s.map { c ->
        when (c) {
            '၀' -> '0'; '၁' -> '1'; '၂' -> '2'; '၃' -> '3'; '၄' -> '4'
            '၅' -> '5'; '၆' -> '6'; '၇' -> '7'; '၈' -> '8'; '၉' -> '9'
            else -> c
        }
    }.joinToString("")
}
