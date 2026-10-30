package com.example.voicebrainlive.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.memory.VoiceBrainDatabase
import com.example.voicebrainlive.notion.NotionClient
import com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Resolve before approval, then execute stable IDs only. No fuzzy/ambient recipient fallback. */
object ActionTargetResolver {
    fun phone(raw: String): String? {
        val digits = raw.map { ch -> if (ch in '၀'..'၉') ('0'.code + ch.code - '၀'.code).toChar() else ch }.joinToString("")
        val number = digits.replace(Regex("[ ()-]"), "")
        return number.takeIf { it.matches(Regex("\\+?[0-9]{8,15}")) }
    }

    private data class PhoneTarget(val number: String, val id: String, val label: String, val revision: Long = 0)
    private fun resolvePhone(context: Context, raw: String, allowAlias: Boolean = true): PhoneTarget? {
        phone(raw)?.let { return PhoneTarget(it, "tel:$it", raw) }
        val prefs = context.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        val captured = QuickContactStore.snapshot(prefs, raw)
        val quick = captured.keys.entries.toList()
        if (quick.size == 1) {
            val number = phone(quick.single().value as? String ?: return null) ?: return null
            return PhoneTarget(number, "quick:${quick.single().key}:$number", raw, captured.revision)
        }
        if (quick.isNotEmpty()) return null
        val legacy = QuickContactStore.snapshot(context.getSharedPreferences("voice_contacts", Context.MODE_PRIVATE), raw)
        if (legacy.keys.isNotEmpty()) {
            val entry = legacy.keys.entries.singleOrNull() ?: return null
            val number = phone(entry.value as? String ?: return null) ?: return null
            return PhoneTarget(number, "legacy:${entry.key}:$number", raw, legacy.revision)
        }
        val matches = mutableListOf<PhoneTarget>()
        if (permission(context, Manifest.permission.READ_CONTACTS)) context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(raw), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = phone(cursor.getString(1)) ?: continue
                matches += PhoneTarget(number, "contact:${cursor.getLong(0)}:$number", raw)
            }
        }
        if (matches.isNotEmpty()) return matches.distinct().singleOrNull()
        // A learned alias is a resolution hint, never an execution approval. It must still
        // resolve uniquely, appear in the preview and resolve identically at execution.
        if (allowAlias) {
            val alias = com.example.voicebrainlive.memory.BehaviorMemory.getRememberedCallAlias(context, raw)
            if (alias != null && alias != raw) {
                return resolvePhone(context, alias, false)?.let { it.copy(label = "$raw → ${it.label}") }
            }
        }
        return null
    }

    private fun permission(context: Context, name: String) =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    suspend fun prepare(context: Context, command: String, target: String?, value: String?): SensitiveActionGuard.Operation? {
        val canonical = CommandSafetyPolicy.canonical(command)
        val recipient = target?.takeIf { it.isNotBlank() }
        val content = value // exact UTF-16 string: do not trim, case-fold or normalize
        when (canonical) {
            "make_call", "send_sms" -> {
                val raw = recipient ?: return null
                val resolved = resolvePhone(context, raw) ?: return null
                if (canonical == "send_sms" && content.isNullOrBlank()) return null
                val required = if (canonical == "send_sms") Manifest.permission.SEND_SMS else Manifest.permission.CALL_PHONE
                val simulation = canonical == "make_call" && SettingsActivity.isTestCallMode(context)
                val snapshot = ActionSnapshot(canonical, resolved.label, if (canonical == "send_sms") "Android SMS" else "Android Phone",
                    resolved.id, content, if (canonical == "send_sms") "Send this exact message to ${resolved.number}" else if(simulation) "SIMULATION only; do not place a call to ${resolved.number}" else "Call ${resolved.number}")
                return SensitiveActionGuard.Operation(snapshot,
                    { permission(context, required) && resolvePhone(context, raw) == resolved &&
                        (canonical != "make_call" || SettingsActivity.isTestCallMode(context) == simulation) }, {
                        coroutineContext.ensureActive()
                        if(canonical == "make_call" && SettingsActivity.isTestCallMode(context) != simulation)
                            return@Operation "BLOCKED: Call simulation mode changed; new approval required."
                        if (!permission(context, required)) "BLOCKED: Android permission revoked."
                        else if(simulation) "SIMULATED_CALL: စမ်းသပ်မှုသာဖြစ်၍ ဖုန်းတကယ်မခေါ်ပါ။"
                        else try {
                            if (canonical == "send_sms") {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                                    return@Operation "BLOCKED: SMS permission revoked."
                                val sms = SmsManager.getDefault()
                                val parts = sms.divideMessage(content!!)
                                if (parts.size == 1) sms.sendTextMessage(resolved.number, null, content, null, null)
                                else sms.sendMultipartTextMessage(resolved.number, null, parts, null, null)
                                "SMS request submitted; delivery unverified."
                            } else {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                                    return@Operation "BLOCKED: Phone permission revoked."
                                if(SettingsActivity.isTestCallMode(context) != simulation)
                                    return@Operation "BLOCKED: Call simulation mode changed; new approval required."
                                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${resolved.number}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                "Call requested."
                            }
                        } catch (_: SecurityException) { "BLOCKED: Android permission denied." }
                        catch (_: Exception) { "FAILED/OUTCOME_UNKNOWN: No automatic retry." }
                    })
            }
            "send_messenger", "send_whatsapp" -> {
                val raw = recipient ?: return null
                if (content.isNullOrBlank()) return null
                val app = if (canonical == "send_whatsapp") "com.whatsapp" else "com.facebook.orca"
                val reply = UnifiedNotificationListenerService.resolveExactReply(raw, app) ?: return null
                if (!UnifiedNotificationListenerService.isExactReplyCurrent(reply)) return null
                val snapshot = ActionSnapshot(canonical, reply.sender, app, reply.key, content,
                    "Reply only to this notification/conversation", reply.postTime.toString())
                return SensitiveActionGuard.Operation(snapshot,
                    { UnifiedNotificationListenerService.isExactReplyCurrent(reply) }, {
                        if (UnifiedNotificationListenerService.sendExactReply(reply, content)) "Reply request submitted; delivery unverified."
                        else "FAILED/OUTCOME_UNKNOWN: Notification changed or reply failed; no fallback/retry."
                    })
            }
            "send_email" -> {
                val email = recipient?.takeIf { android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches() } ?: return null
                if (content.isNullOrBlank()) return null
                return SensitiveActionGuard.Operation(ActionSnapshot(canonical, email, "Gmail draft", "mailto:$email", content,
                    "Open a draft with exact body; Gmail Send still requires user interaction"), { true }, {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))
                        .setPackage("com.google.android.gm").putExtra(Intent.EXTRA_TEXT, content)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Email draft opened; not sent."
                })
            }
            "delete_note", "clear_notes" -> {
                val dao = VoiceBrainDatabase.getInstance(context).noteDao()
                val all = dao.getAllNotes() // no legacy-import/memory migration as an authorization side effect
                val selected = if (canonical == "clear_notes") all else {
                    val raw = recipient ?: return null
                    val id = raw.removePrefix("id:").toLongOrNull()
                    all.filter { if (id != null) it.id == id else it.title == raw }.takeIf { it.size == 1 } ?: return null
                }
                if (selected.isEmpty()) return null
                val snapshot = ActionSnapshot(canonical, "Local notes", "VoiceBrainLive Room notes", selected.joinToString(",") { it.id.toString() },
                    selected.joinToString("\n\n") { "ID ${it.id}: ${it.title}\n${it.content}" },
                    "Delete ONLY these ${selected.size} note IDs; no conversations/reminders", selected.toString())
                return SensitiveActionGuard.Operation(snapshot,
                    { val now = dao.getAllNotes(); selected.all { it in now } }, {
                        var deleted = 0
                        selected.forEach { note ->
                            coroutineContext.ensureActive()
                            deleted += dao.deleteApprovedSnapshot(note.id, note.title, note.content, note.timestamp)
                        }
                        if (deleted == selected.size) "Deleted $deleted approved notes."
                        else "PARTIAL/BLOCKED: $deleted approved notes deleted; changed items were retained."
                    })
            }
            "delete_contact", "edit_contact", "update_contact", "add_contact" -> {
                val raw = recipient ?: return null
                val prefs = context.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
                val captured = QuickContactStore.snapshot(prefs, raw)
                val original = captured.keys
                val keys = original.keys.toList()
                val key = if (canonical == "add_contact") raw.lowercase(java.util.Locale.ROOT) else keys.singleOrNull() ?: return null
                val old = original[key] as? String
                if (canonical != "add_contact" && old == null) return null
                if (canonical == "add_contact" && (old != null || keys.isNotEmpty())) return null
                val number = if (canonical == "delete_contact") null else phone(content ?: return null) ?: return null
                val snapshot = ActionSnapshot(canonical, raw, "VoiceBrainLive quick contacts", "quick:$key", content,
                    "${if (canonical == "delete_contact") "Delete" else "Write number '$number' to"} only quick-contact key '$key'; old number: ${old ?: "none"}", old.orEmpty())
                return SensitiveActionGuard.Operation(snapshot, { QuickContactStore.current(prefs, captured) }, {
                    coroutineContext.ensureActive()
                    if (QuickContactStore.compareAndWrite(prefs, captured) {
                        if (canonical == "delete_contact") remove(key) else putString(key, number)
                    }) "Approved quick contact updated."
                    else "BLOCKED: Contact changed or write failed; new approval required."
                })
            }
            "send_to_notion" -> {
                val db = SettingsActivity.getNotionDatabaseId(context)?.takeIf { it.isNotBlank() } ?: return null
                val title = recipient ?: return null
                val body = content?.takeIf { it.isNotBlank() } ?: return null
                val client = NotionClient(context)
                val prepared = client.prepareExactCreate(title, body) ?: return null
                return SensitiveActionGuard.Operation(ActionSnapshot(canonical, title, "Notion database", prepared.databaseId, body,
                    "Create one page: exact title '$title', Category=Quick Note, Status=မလုပ်ရသေး; exact body in Content and one paragraph"),
                    { SettingsActivity.getNotionDatabaseId(context) == db && prepared.isCurrent() }, {
                        if (prepared.submit()) "Notion page creation reported success."
                        else "FAILED/OUTCOME_UNKNOWN: One Notion POST failed; no schema fallback or automatic retry."
                    })
            }
            // Generic screen coordinates/text, calendar defaults, broad memory resets, last-note
            // sharing, title-based remote mutations cannot yet supply a stable immutable target.
            // They remain blocked, never unlocked by yes/biometrics/automation configuration alone.
            else -> return null
        }
    }
}
