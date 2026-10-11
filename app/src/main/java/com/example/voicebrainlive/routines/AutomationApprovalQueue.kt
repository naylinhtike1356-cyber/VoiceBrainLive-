package com.example.voicebrainlive.routines

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Safe holding queue for scheduled commands that require user approval. */
object AutomationApprovalQueue {
    private const val PREFS = "automation_approval_queue"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 20
    private const val EXPIRY_MS = 15 * 60 * 1000L

    data class PendingApproval(
        val id: String,
        val command: String,
        val target: String?,
        val value: String?,
        val reason: String,
        val createdAt: Long,
        val expiresAt: Long,
        val exactPreview: String = ""
    )

    @Synchronized
    fun enqueue(context: Context, command: String, target: String?, value: String?, reason: String, id: String = UUID.randomUUID().toString(), exactPreview: String = ""): PendingApproval {
        val now = System.currentTimeMillis()
        val item = PendingApproval(id, command, target, value, reason, now, now + EXPIRY_MS, exactPreview)
        val items = read(context).filter { it.expiresAt > now }.toMutableList()
        items += item
        write(context, items.takeLast(MAX_ITEMS))
        try { AutomationApprovalNotification.show(context, item) } catch (_: SecurityException) { /* pending remains, no execution */ }
        return item
    }

    @Synchronized
    fun list(context: Context): List<PendingApproval> {
        val now = System.currentTimeMillis()
        val valid = read(context).filter { it.expiresAt > now }
        write(context, valid)
        return valid
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val items = read(context)
        val updated = items.filterNot { it.id == id }
        write(context, updated)
        return updated.size != items.size
    }

    @Synchronized
    fun consume(context: Context, id: String): PendingApproval? {
        val now = System.currentTimeMillis()
        val items = read(context)
        val item = items.firstOrNull { it.id == id && it.expiresAt > now } ?: return null
        write(context, items.filterNot { it.id == id })
        return item
    }

    fun preview(item: PendingApproval): String {
        if (item.exactPreview.isNotEmpty()) return item.exactPreview
        val target = item.target?.takeIf { it.isNotBlank() }?.let { " target=$it" }.orEmpty()
        return "Approval pending: ${item.command}$target. ${item.reason}"
    }

    private fun read(context: Context): List<PendingApproval> {
        return try {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index ->
                val obj = arr.optJSONObject(index) ?: return@mapNotNull null
                PendingApproval(
                    id = obj.optString("id"),
                    command = obj.optString("command"),
                    target = obj.optString("target").ifBlank { null },
                    value = obj.optString("value").ifBlank { null },
                    reason = obj.optString("reason"),
                    createdAt = obj.optLong("createdAt"),
                    expiresAt = obj.optLong("expiresAt"),
                    exactPreview = obj.optString("exactPreview")
                )
            }.filter { it.id.isNotBlank() && it.command.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(context: Context, items: List<PendingApproval>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("command", item.command)
                put("target", item.target.orEmpty())
                put("value", item.value.orEmpty())
                put("reason", item.reason)
                put("createdAt", item.createdAt)
                put("expiresAt", item.expiresAt)
                put("exactPreview", item.exactPreview)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).commit()
    }
}
