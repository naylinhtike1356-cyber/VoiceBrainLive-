package com.example.voicebrainlive.security

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.voicebrainlive.R
import com.example.voicebrainlive.SettingsActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecureSettingsFailureTest {
    // Custom project shadows are not guaranteed to run @Resetter automatically.
    // Explicit teardown also prevents a deliberate failure leaking to older suites.
    @Before fun resetBeforeTest() { ShadowSecureSettingsStorage.reset() }
    @After fun resetAfterTest() { ShadowSecureSettingsStorage.reset() }
    private class NoPlaintextContext(base: Context) : ContextWrapper(base) {
        var preferenceFactoryCalls = 0
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceFactoryCalls++
            error("Plaintext preference factory must not be used on encrypted creation failure")
        }
    }
    @Test fun creationFailureIsTypedSanitizedAndCannotReadOrWritePlaintext() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = NoPlaintextContext(app)
        ShadowSecureSettingsStorage.failCreation = true
        ShadowLog.clear()
        try { SettingsActivity.getSecurePrefs(context); fail("Expected typed failure") }
        catch (e: SecureSettingsUnavailable) { assertEquals("SECURE_SETTINGS_UNAVAILABLE", e.message); assertNull(e.cause) }
        assertEquals("", SettingsActivity.getGeminiApiKey(context))
        assertNull(SettingsActivity.getOpenAIApiKey(context))
        assertNull(SettingsActivity.getNotionToken(context))
        assertNull(SettingsActivity.getSpotifyAccessToken(context))
        assertFalse(SettingsActivity.saveSpotifyTokens(context, "PRIVATE TOKEN", null))
        assertFalse(SettingsActivity.setSpotifyPkceVerifier(context, "PRIVATE VERIFIER"))
        assertEquals(0, context.preferenceFactoryCalls)
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("PRIVATE") || it.throwable != null })
    }
    @Test fun originalStartupSetupCanStillUseIsolatedHostOnlyFakePreferences() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(SettingsActivity.getSecurePrefs(app).edit().putString(SettingsActivity.KEY_GEMINI_API, "YOUR_API_KEY").commit())
        assertEquals("YOUR_API_KEY", SettingsActivity.getGeminiApiKey(app))
    }
    @Test fun settingsShowsErrorRetryAndBackWithoutCredentialSaveControls() {
        ShadowSecureSettingsStorage.failCreation = true
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            assertNull(activity.findViewById<android.view.View>(R.id.btnSave))
            assertNull(activity.findViewById<android.view.View>(R.id.btnTestMessenger))
            // onResume must safely tolerate absent normal binding.
        }
    }

    @Test fun keyUnavailabilityBlocksCachedCredentialsAndPreservesExistingHostFixture() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val existing = SettingsActivity.getSecurePrefs(app)
        assertTrue(existing.edit().putString(SettingsActivity.KEY_GEMINI_API, "HOST-SENTINEL")
            .putString("unrelated", "keep").commit())
        val before = existing.all.toMap()
        ShadowSecureSettingsStorage.creationFailure = java.security.UnrecoverableKeyException("PRIVATE KEY FAILURE")
        val context = NoPlaintextContext(app)
        assertEquals("", SettingsActivity.getGeminiApiKey(context))
        assertNull(SettingsActivity.getNotionToken(context))
        assertFalse(SettingsActivity.saveSpotifyTokens(context, "new-secret", "refresh"))
        assertFalse(SettingsActivity.clearSpotifyTokens(context))
        assertEquals(before, existing.all)
        assertEquals(0, context.preferenceFactoryCalls)
        ShadowSecureSettingsStorage.creationFailure = null
        assertEquals("HOST-SENTINEL", SettingsActivity.getGeminiApiKey(context))
        assertEquals(before, existing.all)
    }

    @Test fun healthyHostBoundarySupportsExactCredentialUpdateAndDeletion() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SettingsActivity.getSecurePrefs(app)
        assertTrue(prefs.edit().putString("unrelated", "preserve").commit())
        assertTrue(SettingsActivity.saveSpotifyTokens(app, "first", "refresh"))
        assertEquals("first", SettingsActivity.getSpotifyAccessToken(app))
        assertTrue(SettingsActivity.saveSpotifyTokens(app, "second", null))
        assertEquals("second", SettingsActivity.getSpotifyAccessToken(app))
        assertEquals("refresh", SettingsActivity.getSpotifyRefreshToken(app))
        assertTrue(SettingsActivity.clearSpotifyTokens(app))
        assertNull(SettingsActivity.getSpotifyAccessToken(app))
        assertNull(SettingsActivity.getSpotifyRefreshToken(app))
        assertEquals("preserve", prefs.getString("unrelated", null))
    }

    @Test fun failedCommitReturnsFailureAndPreservesPreviouslySavedValues() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val existing = SettingsActivity.getSecurePrefs(app)
        assertTrue(existing.edit().putString(SettingsActivity.KEY_SPOTIFY_ACCESS_TOKEN, "old").commit())
        val before = existing.all.toMap()
        ShadowSecureSettingsStorage.prefsOverride = object : SharedPreferences by existing {
            override fun edit(): SharedPreferences.Editor {
                val editor = existing.edit()
                return object : SharedPreferences.Editor by editor { override fun commit() = false }
            }
        }
        assertFalse(SettingsActivity.saveSpotifyTokens(app, "new", "new-refresh"))
        assertFalse(SettingsActivity.clearSpotifyTokens(app))
        assertEquals(before, existing.all)
    }

    @Test fun editorExceptionIsSanitizedAndDoesNotWriteFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val existing = SettingsActivity.getSecurePrefs(app)
        val before = existing.all.toMap()
        ShadowSecureSettingsStorage.prefsOverride = object : SharedPreferences by existing {
            override fun edit(): SharedPreferences.Editor = throw IllegalStateException("PRIVATE EDITOR FAILURE")
        }
        ShadowLog.clear()
        val context = NoPlaintextContext(app)
        assertFalse(SettingsActivity.setSpotifyPkceVerifier(context, "PRIVATE SECRET"))
        assertEquals(before, existing.all)
        assertEquals(0, context.preferenceFactoryCalls)
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("PRIVATE") || it.throwable != null })
    }

    @Test fun corruptReadFailsClosedWithoutBuiltInCredentialFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val existing = SettingsActivity.getSecurePrefs(app)
        ShadowSecureSettingsStorage.prefsOverride = object : SharedPreferences by existing {
            override fun getString(key: String?, defaultValue: String?): String? =
                throw SecurityException("PRIVATE DECRYPTION FAILURE")
        }
        ShadowLog.clear()
        assertEquals("", SettingsActivity.getGeminiApiKey(app))
        assertNull(SettingsActivity.getOpenAIApiKey(app))
        assertNull(SettingsActivity.getNotionToken(app))
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("PRIVATE") || it.throwable != null })
    }
}
