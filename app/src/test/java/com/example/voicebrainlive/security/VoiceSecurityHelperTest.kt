package com.example.voicebrainlive.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceSecurityHelperTest {
    private val key = KeyGenerator.getInstance("AES").generateKey()

    @Test
    fun readsLegacyCiphertextWithoutAssociatedData() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val original = "မိသားစု"
        val legacy = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(original.toByteArray()))

        assertEquals(original, VoiceSecurityHelper.decrypt(legacy, key))
    }

    @Test
    fun authenticatesNewCiphertextAndRejectsADowngradedEnvelope() {
        val original = "voice preference"
        val encrypted = VoiceSecurityHelper.encrypt(original, key)

        assertEquals(original, VoiceSecurityHelper.decrypt(encrypted, key))
        assertThrows(java.security.GeneralSecurityException::class.java) {
            VoiceSecurityHelper.decrypt(encrypted.substringAfter(':'), key)
        }
    }
}
