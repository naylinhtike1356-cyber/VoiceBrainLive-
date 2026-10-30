package com.example.voicebrainlive.health

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowLog

/** Host-only injection; fake preferences do not prove Android Keystore encryption. */
@Implements(HealthSecureStorage::class, isInAndroidSdk = false)
class ShadowHealthSecureStorage {
    companion object {
        var failure: Exception? = null
        var preferences: SharedPreferences? = null
        @Implementation @JvmStatic fun createEncrypted(context: Context): SharedPreferences {
            failure?.let { throw it }
            return checkNotNull(preferences)
        }
        fun reset() { failure = null; preferences = null }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ShadowHealthSecureStorage::class])
class HealthStoragePrivacyTest {
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var context: NoPlaintextContext
    private class NoPlaintextContext(base: Context) : ContextWrapper(base) {
        var plaintextCalls = 0
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            plaintextCalls++
            error("Plaintext fallback forbidden")
        }
    }
    @Before fun before() {
        ShadowHealthSecureStorage.reset()
        app = ApplicationProvider.getApplicationContext()
        prefs = app.getSharedPreferences("__health_host_fixture__", Context.MODE_PRIVATE)
        prefs.edit().clear().commit() // Synthetic test application only.
        ShadowHealthSecureStorage.preferences = prefs
        context = NoPlaintextContext(app)
        ShadowLog.clear()
    }
    @After fun after() { ShadowHealthSecureStorage.reset() }

    @Test fun healthyStoreRetainsDefaultAndPersistsExplicitOptOut() {
        assertTrue(HealthManager.isHealthAssistantEnabled(context))
        assertTrue(HealthManager.setHealthAssistantEnabled(context, false))
        assertFalse(HealthManager.isHealthAssistantEnabled(context))
        assertTrue(HealthManager.setHealthAssistantEnabled(context, true))
        assertTrue(HealthManager.isHealthAssistantEnabled(context))
        assertEquals(0, context.plaintextCalls)
    }
    @Test fun sameLegacyKeysReadEveryHealthFieldWithoutMigration() {
        assertTrue(prefs.edit().putFloat("cached_sleep_hours", 7.5f).putInt("cached_steps", 8100)
            .putInt("cached_calories", 320).putInt("cached_workout_min", 25)
            .putString("cached_workout_type", "walk").putInt("cached_heart_rate", 70)
            .putLong("last_health_sync_time", 123L).putString("cached_sleep_quality", "good")
            .putString("cached_bedtime", "23:00").putString("cached_waketime", "06:30")
            .putString("health_data_source", "legacy encrypted source").putString("unrelated", "keep").commit())
        val before = prefs.all.toMap()
        assertEquals(HealthManager.HealthData(7.5, 8100, 320, 25, "walk", 70, 123L,
            "good", "23:00", "06:30", "legacy encrypted source"), HealthManager.fetchHealthData(context))
        assertEquals(before, prefs.all)
    }
    @Test fun partialUpdatePreservesOtherRecordsAndUsesExistingSchema() {
        prefs.edit().putInt("cached_steps", 100).putString("unrelated", "keep").commit()
        assertTrue(HealthManager.updateCachedHealthData(context, heartRate = 72))
        assertEquals(100, HealthManager.fetchHealthData(context).steps)
        assertEquals(72, HealthManager.fetchHealthData(context).avgHeartRate)
        assertEquals("keep", prefs.getString("unrelated", null))
        assertEquals("Samsung Health notification", HealthManager.fetchHealthData(context).dataSource)
    }
    @Test fun creationFailureDisablesReadsWritesAndNotificationPersistence() {
        ShadowHealthSecureStorage.failure = IllegalStateException("PRIVATE HEALTH ERROR")
        assertFalse(HealthManager.isHealthAssistantEnabled(context))
        assertEquals(HealthManager.HealthData(), HealthManager.fetchHealthData(context))
        assertFalse(HealthManager.setHealthAssistantEnabled(context, true))
        assertFalse(HealthManager.updateCachedHealthData(context, steps = 500))
        assertFalse(HealthManager.parseAndSaveFromNotification(context, "Steps: 500", ""))
        assertEquals(0, context.plaintextCalls)
        assertTrue(prefs.all.isEmpty())
    }
    @Test fun unavailableKeyPreservesExistingRecordsAndRecoveryReadsThem() {
        HealthManager.updateCachedHealthData(context, steps = 1234)
        val before = prefs.all.toMap()
        ShadowHealthSecureStorage.failure = java.security.UnrecoverableKeyException("PRIVATE KEY")
        assertEquals(HealthManager.HealthData(), HealthManager.fetchHealthData(context))
        assertFalse(HealthManager.updateCachedHealthData(context, steps = 9999))
        assertEquals(before, prefs.all)
        ShadowHealthSecureStorage.failure = null
        assertEquals(1234, HealthManager.fetchHealthData(context).steps)
        assertEquals(before, prefs.all)
    }
    @Test fun legacyPlaintextIsNeitherReadImportedDeletedNorOverwrittenOnFailure() {
        val legacy = app.getSharedPreferences("secure_health_prefs", Context.MODE_PRIVATE)
        legacy.edit().putInt("cached_steps", 9876).putString("old_record", "keep").commit()
        val before = legacy.all.toMap()
        ShadowHealthSecureStorage.failure = SecurityException("PRIVATE LEGACY KEYSET FAILURE")
        assertEquals(HealthManager.HealthData(), HealthManager.fetchHealthData(context))
        assertFalse(HealthManager.updateCachedHealthData(context, steps = 1))
        assertEquals(before, legacy.all)
        assertEquals(0, context.plaintextCalls)
    }
    @Test fun corruptReadDoesNotExposePartialDataOrRewriteRecords() {
        prefs.edit().putString("cached_steps", "wrong old type").putString("old_record", "keep").commit()
        val before = prefs.all.toMap()
        assertEquals(HealthManager.HealthData(), HealthManager.fetchHealthData(context))
        assertEquals(before, prefs.all)
        assertEquals(0, context.plaintextCalls)
    }
    @Test fun preferenceReadFailureFailsClosedWithoutPlaintext() {
        ShadowHealthSecureStorage.preferences = object : SharedPreferences by prefs {
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw SecurityException("PRIVATE READ")
        }
        assertFalse(HealthManager.isHealthAssistantEnabled(context))
        assertFalse(HealthManager.parseAndSaveFromNotification(context, "Steps: 100", ""))
        assertEquals(0, context.plaintextCalls)
    }
    private fun failCommit(throws: Boolean) {
        ShadowHealthSecureStorage.preferences = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        if (throws) throw SecurityException("PRIVATE COMMIT")
                        return false
                    }
                }
            }
        }
    }
    @Test fun falseCommitIsNotReportedAsNotificationSuccess() {
        failCommit(false)
        assertFalse(HealthManager.parseAndSaveFromNotification(context, "Steps: 100", ""))
        assertFalse(HealthManager.setHealthAssistantEnabled(context, false))
        assertTrue(prefs.all.isEmpty())
    }
    @Test fun thrownCommitDoesNotFallbackOrReportSuccess() {
        failCommit(true)
        assertFalse(HealthManager.updateCachedHealthData(context, heartRate = 90))
        assertFalse(HealthManager.parseAndSaveFromNotification(context, "Heart rate 90", ""))
        assertTrue(prefs.all.isEmpty())
        assertEquals(0, context.plaintextCalls)
    }
    @Test fun healthyNotificationPersistsAndDisabledNotificationDoesNot() {
        assertTrue(HealthManager.parseAndSaveFromNotification(context, "Sleep 7h 30m", "Steps: 100"))
        assertEquals(7.5, HealthManager.fetchHealthData(context).sleepHours, 0.001)
        assertEquals(100, HealthManager.fetchHealthData(context).steps)
        assertTrue(HealthManager.setHealthAssistantEnabled(context, false))
        val before = prefs.all.toMap()
        assertFalse(HealthManager.parseAndSaveFromNotification(context, "Steps: 200", ""))
        assertEquals(before, prefs.all)
    }
    @Test fun unavailableVaultStopsLivePathBeforeHealthConnectAccess() = runBlocking {
        ShadowHealthSecureStorage.failure = SecurityException("PRIVATE KEY")
        assertEquals(HealthManager.HealthData(), HealthManager.fetchHealthDataLive(context))
        assertEquals(0, context.plaintextCalls)
    }
    @Test fun logsContainNeitherHealthMetricsNorRawStorageFailure() {
        HealthManager.updateCachedHealthData(context, steps = 87654321, heartRate = 77)
        ShadowHealthSecureStorage.failure = SecurityException("PRIVATE FAILURE PAYLOAD")
        HealthManager.fetchHealthData(context)
        HealthManager.updateCachedHealthData(context, steps = 87654321)
        assertTrue(ShadowLog.getLogs().none {
            it.msg.contains("87654321") || it.msg.contains("PRIVATE") || it.throwable != null
        })
    }
}
