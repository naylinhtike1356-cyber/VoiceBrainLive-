package com.example.voicebrainlive.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Sanitized typed failure. Never retains an underlying exception or credential payload. */
class SecureSettingsUnavailable : IllegalStateException("SECURE_SETTINGS_UNAVAILABLE")

/** Production has exactly one encrypted factory and no plaintext/default credential store. */
object SecureSettingsStorage {
    const val ERROR = "Secure settings are unavailable. Provider connections and settings changes are blocked. Retry or go back."

    @JvmStatic fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)
            .build()
        return EncryptedSharedPreferences.create(context, "secure_settings", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    fun require(context: Context): SharedPreferences = try { createEncrypted(context) }
    catch (_: Exception) {
        // No Throwable, raw message, cause, stack, or plaintext fallback.
        Log.e("SecureSettings", "SECURE_SETTINGS_UNAVAILABLE")
        throw SecureSettingsUnavailable()
    }

    fun optional(context: Context): SharedPreferences? = try { require(context) }
    catch (_: SecureSettingsUnavailable) { null }

    fun <T> read(context: Context, unavailable: T, block: (SharedPreferences) -> T): T = try { block(require(context)) }
    catch (_: Exception) { Log.e("SecureSettings", "SECURE_SETTINGS_READ_UNAVAILABLE"); unavailable }

    fun write(context: Context, block: (SharedPreferences.Editor) -> Unit): Boolean = try {
        val editor = require(context).edit()
        block(editor)
        editor.commit()
    } catch (_: Exception) { Log.e("SecureSettings", "SECURE_SETTINGS_WRITE_UNAVAILABLE"); false }
}
