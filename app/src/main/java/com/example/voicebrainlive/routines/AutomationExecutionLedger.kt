package com.example.voicebrainlive.routines

import android.content.Context
import java.security.MessageDigest

/**
 * Best-effort idempotency guard for duplicate AlarmManager broadcasts.
 * A routine trigger is claimable once per minute bucket. Old entries are pruned.
 */
object AutomationExecutionLedger {
    private const val PREFS = "automation_execution_ledger"
    private const val STATUS_PREFS = "routine_execution_status"
    private const val MAX_ENTRIES = 100
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    @Synchronized
    fun tryClaim(context: Context, routineId: Int, type: String, isPreReminder: Boolean, occurrence: Long = 0L): Boolean {
        if (routineId < 0) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val bucket = now / 60_000L
        val key = digest("$routineId|${type.lowercase()}|$isPreReminder|${if (occurrence > 0) occurrence else bucket}")
        if (prefs.getLong(key, 0L) > 0L) return false

        val editor = prefs.edit().putLong(key, now)
        prefs.all.entries
            .filter { it.value is Long && now - (it.value as Long) > RETENTION_MS }
            .forEach { editor.remove(it.key) }
        val remaining = prefs.all.size - 1
        if (remaining >= MAX_ENTRIES) {
            prefs.all.entries
                .filter { it.value is Long }
                .sortedBy { it.value as Long }
                .take(remaining - MAX_ENTRIES + 1)
                .forEach { editor.remove(it.key) }
        }
        return editor.commit()
    }

    enum class Result { COMPLETED, PARTIAL, NEEDS_REVIEW, BLOCKED, FAILED, INTERRUPTED }

    fun record(context: Context, routineId: Int, result: Result) {
        if (routineId <= 0) return
        context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
            .edit().putString("result_$routineId", result.name).apply()
    }

    fun lastResult(context: Context, routineId: Int): Result? {
        val value = context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
            .getString("result_$routineId", null)
        return Result.entries.firstOrNull { it.name == value }
    }

    fun classify(result: String): Result = when {
        result.lineSequence().any { it.startsWith("NEEDS_APPROVAL:") } -> Result.NEEDS_REVIEW
        result.startsWith("BLOCKED:") -> Result.BLOCKED
        result.startsWith("FAILED") -> Result.FAILED
        result.startsWith("UNVERIFIED:") -> Result.PARTIAL
        result.lineSequence().any { it.startsWith("PARTIAL:") } -> Result.PARTIAL
        result.isBlank() -> Result.NEEDS_REVIEW
        else -> Result.COMPLETED
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return "run_" + bytes.joinToString("") { "%02x".format(it) }
    }
}
