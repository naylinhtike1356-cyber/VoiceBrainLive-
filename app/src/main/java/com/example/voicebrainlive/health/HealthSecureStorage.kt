package com.example.voicebrainlive.health

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keeps the existing health file, default master-key alias and encryption schemes. */
internal object HealthSecureStorage {
    @JvmStatic fun createEncrypted(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, "secure_health_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    fun <T> read(context: Context, unavailable: T, block: (SharedPreferences) -> T): T = try {
        block(createEncrypted(context))
    } catch (_: Exception) {
        Log.w("HealthStorage", "HEALTH_STORAGE_READ_UNAVAILABLE")
        unavailable
    }

    fun write(context: Context, block: (SharedPreferences.Editor) -> Unit): Boolean = try {
        val editor = createEncrypted(context).edit()
        block(editor)
        editor.commit()
    } catch (_: Exception) {
        Log.w("HealthStorage", "HEALTH_STORAGE_WRITE_UNAVAILABLE")
        false
    }
}
