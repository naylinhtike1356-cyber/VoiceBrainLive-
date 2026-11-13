package com.example.voicebrainlive.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Voice Security Helper - Voice training data encryption
 * 
 * လုံခြုံရေးအတွက် Voice training data များကို AES-256-GCM ဖြင့် encrypt လုပ်သည်။
 * Android Keystore System ကို အသုံးပြုပြီး key များကို secure hardware တွင် သိမ်းသည်။
 * 
 * Usage:
 * ```
 * val security = VoiceSecurityHelper(context)
 * security.encryptVoiceData("user voice sample")
 * security.decryptVoiceData(encryptedData)
 * ```
 */
class VoiceSecurityHelper(private val context: Context) {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "voice_security_prefs", Context.MODE_PRIVATE
    )

    companion object {
        private const val TAG = "VoiceSecurityHelper"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "voicebrain_voice_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val GCM_AAD = "VoiceBrainLive"
        private const val AAD_PREFIX = "v2:"
        private val keyLock = Any()

        internal fun encrypt(plaintext: String, key: SecretKey): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(GCM_AAD.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return AAD_PREFIX + Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
        }

        internal fun decrypt(encryptedData: String, key: SecretKey): String {
            val usesAad = encryptedData.startsWith(AAD_PREFIX)
            val bytes = Base64.getDecoder().decode(encryptedData.removePrefix(AAD_PREFIX))
            require(bytes.size >= GCM_IV_LENGTH + GCM_TAG_LENGTH / Byte.SIZE_BITS)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, bytes, 0, GCM_IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            // The original writer authenticated the ciphertext without associated data.
            if (usesAad) cipher.updateAAD(GCM_AAD.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.size - GCM_IV_LENGTH), Charsets.UTF_8)
        }
    }

    init {
        ensureKeyExists()
    }

    /**
     * Key ရှိမရှိ စစ်ဆေးပြီး မရှိရင် ဖန်တီးသည်
     */
    private fun ensureKeyExists() {
        synchronized(keyLock) {
            if (!keyStore.containsAlias(KEY_ALIAS)) generateKey()
        }
    }

    /**
     * AES-256-GCM key ဖန်တီးခြင်း
     */
    private fun generateKey() {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false) // Voice data doesn't need biometric
                    .build()
            )
            keyGenerator.generateKey()
            Log.d(TAG, "Voice encryption key generated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate voice encryption key", e)
            throw SecurityException("Cannot create encryption key", e)
        }
    }

    /**
     * SecretKey ကို Keystore မှ ရယူခြင်း
     */
    private fun getSecretKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw SecurityException("Voice encryption key not found")
        return entry.secretKey
    }

    /**
     * Voice data ကို encrypt လုပ်ခြင်း
     * 
     * @param plaintext ရိုက်ထည့်ထားသော voice data
     * @return Version prefix followed by Base64 encoded IV, ciphertext and tag
     */
    fun encryptVoiceData(plaintext: String): String {
        return try {
            encrypt(plaintext, getSecretKey())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt voice data", e)
            ""
        }
    }

    /**
     * Encrypted voice data ကို decrypt လုပ်ခြင်း
     * 
     * @param encryptedData Base64 encoded encrypted data
     * @return Decrypted plaintext voice data
     */
    fun decryptVoiceData(encryptedData: String): String {
        return try {
            decrypt(encryptedData, getSecretKey())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt voice data", e)
            ""
        }
    }

    /**
     * Voice training sample ကို SharedPreferences တွင် encrypted အဖြစ် သိမ်းခြင်း
     */
    fun saveEncryptedVoiceSample(key: String, voiceSample: String): Boolean {
        return try {
            val encrypted = encryptVoiceData(voiceSample)
            if (encrypted.isNotEmpty()) {
                prefs.edit().putString("voice_sample_$key", encrypted).apply()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save encrypted voice sample", e)
            false
        }
    }

    /**
     * Encrypted voice sample ကို SharedPreferences မှ ဖတ်ခြင်း
     */
    fun loadEncryptedVoiceSample(key: String): String? {
        return try {
            val encrypted = prefs.getString("voice_sample_$key", null)
            if (encrypted != null && encrypted.isNotEmpty()) {
                decryptVoiceData(encrypted)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load encrypted voice sample", e)
            null
        }
    }

    /**
     * Voice sample ကို ဖျက်ခြင်း
     */
    fun deleteVoiceSample(key: String) {
        prefs.edit().remove("voice_sample_$key").apply()
    }

    /**
     * Voice samples အားလုံးကို ဖျက်ခြင်း
     */
    fun clearAllVoiceSamples() {
        prefs.edit().clear().apply()
    }

    /**
     * Encryption key ကို reset လုပ်ခြင်း (သတိ! - ရှိပြီးသား data များ decrypt မလုပ်နိုင်တော့)
     */
    fun resetEncryptionKey() {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            generateKey()
            clearAllVoiceSamples() // Old data cannot be decrypted with new key
            Log.d(TAG, "Encryption key reset and all voice samples cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset encryption key", e)
        }
    }

    /**
     * Encryption လုပ်နိုင်သလား စစ်ဆေးခြင်း
     */
    fun isEncryptionAvailable(): Boolean {
        return try {
            ensureKeyExists()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encryption not available", e)
            false
        }
    }

    /**
     * Voice profile data ကို encrypt လုပ်ပြီး JSON string အဖြစ် ပြန်ခြင်း
     */
    fun encryptVoiceProfile(profileData: Map<String, String>): String {
        val json = profileData.map { "${it.key}=${it.value}" }.joinToString("|")
        return encryptVoiceData(json)
    }

    /**
     * Encrypted voice profile ကို decrypt လုပ်ပြီး Map အဖြစ် ပြန်ခြင်း
     */
    fun decryptVoiceProfile(encryptedProfile: String): Map<String, String> {
        val json = decryptVoiceData(encryptedProfile)
        return if (json.isNotEmpty()) {
            json.split("|").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
        } else {
            emptyMap()
        }
    }
}
