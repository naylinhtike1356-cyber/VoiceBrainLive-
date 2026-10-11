package com.example.voicebrainlive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Email Integration - Gmail API helper
 * v2.0 Feature
 */
class EmailHelper(private val context: Context) {
    
    companion object {
        const val TAG = "EmailHelper"
    }
    
    /**
     * Open Gmail app
     */
    fun openGmail() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.gm")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                // Gmail not installed, open Play Store
                openPlayStore("com.google.android.gm")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Gmail: ${e.message}")
        }
    }
    
    /**
     * Open Gmail compose
     */
    fun composeEmail(
        to: String,
        subject: String = "",
        body: String = ""
    ) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // Try Gmail first
            val gmailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                setPackage("com.google.android.gm")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            try {
                context.startActivity(gmailIntent)
            } catch (e: Exception) {
                // Fall back to any email app
                context.startActivity(Intent.createChooser(intent, "Send email"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error composing email: ${e.message}")
        }
    }
    
    /**
     * Search emails by query
     */
    fun searchEmails(query: String) {
        try {
            // Open Gmail with search query
            val searchUri = Uri.parse("https://mail.google.com/mail/u/0/#search/${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching emails: ${e.message}")
        }
    }
    
    /**
     * Get recent emails (opens Gmail inbox)
     */
    fun openInbox() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/u/0/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening inbox: ${e.message}")
        }
    }
    
    /**
     * Open unread emails
     */
    fun openUnread() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/u/0/#inbox")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening unread: ${e.message}")
        }
    }
    
    /**
     * Send email via voice command
     */
    suspend fun sendEmailVoice(
        recipient: String,
        subject: String,
        body: String
    ): String = withContext(Dispatchers.Main) {
        try {
            // Validate email
            if (!isValidEmail(recipient)) {
                return@withContext "Invalid email address. Please check the recipient."
            }
            
            // Open compose
            composeEmail(recipient, subject, body)
            
            "Opening email. Please send from Gmail app."
        } catch (e: Exception) {
            Log.e(TAG, "Error sending email: ${e.message}")
            "Could not open email: ${e.message}"
        }
    }
    
    /**
     * Check if email is valid
     */
    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
    
    /**
     * Get email address from contacts
     */
    fun getEmailFromContact(contactName: String): String? {
        try {
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS,
                android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
            )
            
            val selection = "${android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")
            
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting email from contact: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Open Play Store for app
     */
    private fun openPlayStore(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fall back to web Play Store
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
    
    /**
     * Voice command handlers
     */
    fun handleEmailCommand(command: String, target: String?, value: String?): String {
        return when (command.lowercase()) {
            "open_gmail", "open_email", "gmail" -> {
                openGmail()
                "Opening Gmail"
            }
            "compose_email", "new_email", "send_email" -> {
                composeEmail(target ?: "", "", value ?: "")
                "Opening email compose"
            }
            "search_email", "find_email" -> {
                if (!target.isNullOrBlank()) {
                    searchEmails(target)
                    "Searching for: $target"
                } else {
                    "Please specify what to search for"
                }
            }
            "inbox", "check_email" -> {
                openInbox()
                "Opening inbox"
            }
            "unread", "unread_email" -> {
                openUnread()
                "Opening unread emails"
            }
            else -> "Unknown email command"
        }
    }
}
