package com.example.voicebrainlive

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ActionRecordManager: Provides a persistent log of app actions and errors
 * for the user and AI to analyze and troubleshoot.
 * Logs are stored in both internal and external storage for PC access.
 */
object ActionRecordManager {
    private const val INTERNAL_LOG_NAME = "action_records.txt"
    private const val DIAGNOSTIC_LOG_NAME = "diagnostics.txt"
    private const val MAX_LOG_LINES = 1000 // Increased for PC analysis
    private const val REDACTED = "[REDACTED]"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val apiKeyQueryRegex = Regex("""(?i)([?&](?:key|api_key|x-api-key|access_token|refresh_token)=)[^&\s]+""")
    private val googleApiKeyRegex = Regex("""AIza[0-9A-Za-z_\-]{20,}""")
    private val openAiKeyRegex = Regex("""sk-[0-9A-Za-z_\-]{20,}""")
    private val bearerTokenRegex = Regex("""(?i)(bearer\s+)[0-9A-Za-z._\-]{20,}""")
    private val longHexTokenRegex = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val phoneNumberRegex = Regex("""(?<!\d)(?:\+?\d[\d\s().-]{6,}\d)(?!\d)""")

    fun recordAction(context: Context, tag: String, message: String, isError: Boolean = false) {
        val timestamp = dateFormat.format(Date())
        val status = if (isError) "[ERROR]" else "[INFO]"
        val safeMessage = sanitize(message)
        val logLine = "$timestamp $status $tag: $safeMessage\n"
        
        // Always log to Logcat
        if (isError) Log.e(tag, safeMessage) else Log.d(tag, safeMessage)

        try {
            // 1. Internal Log (AI can read this)
            val internalFile = File(context.filesDir, INTERNAL_LOG_NAME)
            internalFile.appendText(logLine)
            rotateLogs(internalFile, 300) // Keep it small for AI performance

            // 2. Diagnostic Log (Visible via PC USB)
            val extDir = context.getExternalFilesDir("Logs")
            if (extDir != null) {
                val diagnosticFile = File(extDir, DIAGNOSTIC_LOG_NAME)
                // If new file, add device info
                if (!diagnosticFile.exists()) {
                    diagnosticFile.writeText(getDeviceInfoString(context))
                }
                diagnosticFile.appendText(logLine)
                rotateLogs(diagnosticFile, MAX_LOG_LINES)
            }
        } catch (e: Exception) {
            Log.e("ActionRecordManager", "Failed to record action", e)
        }
    }

    fun sanitize(message: String): String {
        return message
            .replace(apiKeyQueryRegex) { "${it.groupValues[1]}$REDACTED" }
            .replace(bearerTokenRegex) { "${it.groupValues[1]}$REDACTED" }
            .replace(googleApiKeyRegex, REDACTED)
            .replace(openAiKeyRegex, REDACTED)
            .replace(longHexTokenRegex, REDACTED)
            .replace(phoneNumberRegex, REDACTED)
    }

    private fun getDeviceInfoString(context: Context): String {
        return """
            ==================================================
            VOICE BRAIN LIVE - DIAGNOSTIC LOG
            VERSION: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            DEVICE: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})
            ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            TIMEZONE: ${java.util.TimeZone.getDefault().id}
            DATE: ${dateFormat.format(Date())}
            ==================================================
            
        """.trimIndent()
    }

    private fun rotateLogs(file: File, maxLines: Int) {
        try {
            val lines = file.readLines()
            if (lines.size > maxLines + 50) { // Buffer to avoid frequent rotation
                val keptLines = lines.takeLast(maxLines)
                file.writeText(keptLines.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e("ActionRecordManager", "Log rotation failed", e)
        }
    }

    fun getLogs(context: Context, limit: Int = 100): String {
        return try {
            val file = File(context.filesDir, INTERNAL_LOG_NAME)
            if (!file.exists()) return "No internal logs found."
            
            val lines = file.readLines()
            lines.takeLast(limit).joinToString("\n")
        } catch (e: Exception) {
            "Error reading internal logs: ${e.message}"
        }
    }

    /** Path to the diagnostic file visible via PC connection. */
    fun getDiagnosticFilePath(context: Context): String? {
        val extDir = context.getExternalFilesDir("Logs")
        return if (extDir != null) File(extDir, DIAGNOSTIC_LOG_NAME).absolutePath else null
    }

    fun clearLogs(context: Context) {
        try {
            File(context.filesDir, INTERNAL_LOG_NAME).delete()
            context.getExternalFilesDir("Logs")?.let { File(it, DIAGNOSTIC_LOG_NAME).delete() }
        } catch (_: Exception) {}
    }
}
