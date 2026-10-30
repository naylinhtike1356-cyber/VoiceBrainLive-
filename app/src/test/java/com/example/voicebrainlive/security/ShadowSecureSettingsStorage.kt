package com.example.voicebrainlive.security

import android.content.Context
import android.content.SharedPreferences
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** HOST TEST ONLY. This shadows the entire encrypted creation boundary, including MasterKey.
 * Robolectric's isolated preferences are a fake fixture; this is NOT encryption evidence.
 */
@Implements(SecureSettingsStorage::class, isInAndroidSdk = false)
class ShadowSecureSettingsStorage {
    companion object {
        @JvmField var failCreation = false
        @JvmField var creationCalls = 0
        @JvmField var creationFailure: Exception? = null
        @JvmField var prefsOverride: SharedPreferences? = null
        @Implementation @JvmStatic fun createEncrypted(context: Context): SharedPreferences {
            creationCalls++
            creationFailure?.let { throw it }
            if (failCreation) throw IllegalStateException("PRIVATE KEYSTORE ERROR PAYLOAD")
            prefsOverride?.let { return it }
            return context.applicationContext.getSharedPreferences("__host_only_ephemeral_secure_fixture__", Context.MODE_PRIVATE)
        }
        @Resetter @JvmStatic fun reset() {
            failCreation = false; creationCalls = 0; creationFailure = null; prefsOverride = null
        }
    }
}
