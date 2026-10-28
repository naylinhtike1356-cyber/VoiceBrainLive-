package com.example.voicebrainlive.privatefixture

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.example.voicebrainlive.BuildConfig
import com.example.voicebrainlive.agent.brain.EncryptedBrainStore
import com.example.voicebrainlive.agent.persistence.*
import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent
import com.example.voicebrainlive.updates.AppUpdateActivity
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Fake key exists ONLY on unit-test classpath; no test key or database is packaged in the private APK. */
class PrivateTestUiApplication : PrivateTestApplication() {
    var bytes: ByteArray? = null
    private val store by lazy { EncryptedBrainStore(object : AtomicMemoryBytes {
        override fun readOrNull() = bytes?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) { this@PrivateTestUiApplication.bytes = bytes.copyOf() }
    }) { SecretKeySpec(ByteArray(32) { 9 }, "AES") } }
    override fun conversationStore() = store
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PrivateTestUiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateTestUiTest {
    private val app get() = RuntimeEnvironment.getApplication() as PrivateTestUiApplication
    private fun <T : View> view(activity: android.app.Activity, tag: String): T = activity.findViewById<View>(android.R.id.content).findViewWithTag(tag)
    private suspend fun waitFor(test: () -> Boolean) { withTimeout(10_000) { while (!test()) { shadowOf(Looper.getMainLooper()).idle(); delay(20) } }; shadowOf(Looper.getMainLooper()).idle() }
    @Test fun packageLabelAndKeysAreDistinctAndEmpty() {
        assertEquals("com.example.voicebrainlive.privatelevel37", app.packageName)
        assertTrue(BuildConfig.PRIVATE_TEST_BUILD); assertEquals("privateTest", BuildConfig.BUILD_TYPE)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-L37-PRIVATE-TEST")); assertEquals("VoiceBrainLive TEST", app.getString(com.example.voicebrainlive.R.string.app_name))
        listOf(BuildConfig.GEMINI_API_KEY, BuildConfig.YOUTUBE_API_KEY, BuildConfig.OPENAI_API_KEY, BuildConfig.SERPER_API_KEY,
            BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.UPDATE_REPOSITORY).forEach { assertEquals("", it) }
    }
    @Test fun dangerousPermissionsAreAbsentRegardlessOfSettings() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.SYSTEM_ALERT_WINDOW, Manifest.permission.REQUEST_INSTALL_PACKAGES, Manifest.permission.RECEIVE_BOOT_COMPLETED)) {
            assertFalse(permission, requested.contains(permission)); assertEquals(permission, PackageManager.PERMISSION_DENIED, app.packageManager.checkPermission(permission, app.packageName))
        }
    }
    @Test fun noServicesReceiversOrProvidersCanStartAndOnlyHomeIsExported() {
        val p = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
        assertTrue(p.services.isNullOrEmpty()); assertTrue(p.receivers.isNullOrEmpty()); assertTrue(p.providers.isNullOrEmpty())
        assertEquals(listOf(PrivateTestHomeActivity::class.java.name), p.activities!!.filter { it.exported }.map { it.name })
        assertFalse(p.activities!!.any { it.name == "com.example.voicebrainlive.MainActivity" || it.name.endsWith("SpotifyAuthCallbackActivity") })
        assertEquals(0, p.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
    @Test fun backgroundFacadeFailsClosedWithoutAnyQueueOrNotification() = runBlocking<Unit> {
        assertTrue(app.backgroundTasks.history().isEmpty()); assertTrue(app.backgroundTasks.reconcile().isEmpty()); assertFalse(app.backgroundTasks.notificationsAvailable())
        for (op in listOf<suspend () -> Unit>({ app.backgroundTasks.previewLocalStatus() }, { app.backgroundTasks.schedule("task", 1) }, { app.backgroundTasks.execute("task", 1) })) {
            try { op(); fail() } catch (e: TaskStoreException) { assertEquals(TaskFailure.SCHEDULER_UNAVAILABLE, e.failure) }
        }
    }
    @Test fun firstRunHomeIsScrollableSecureAndExplicitlySimulated() {
        val c = Robolectric.buildActivity(PrivateTestHomeActivity::class.java).setup(); val a = c.get()
        assertTrue(view<TextView>(a, "private_test_banner").text.contains("SIMULATION"))
        assertTrue(view<TextView>(a, "private_home_scope").text.contains("production Home/voice-service launcher is intentionally unavailable"))
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertNotNull(view<Button>(a, "private_memory")); assertNotNull(view<Button>(a, "private_inbox")); c.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "w900dp-h600dp-land") fun tabletLandscapeHomeKeepsNavigationAvailable() {
        val c = Robolectric.buildActivity(PrivateTestHomeActivity::class.java).setup(); val a = c.get()
        assertTrue(a.resources.configuration.screenWidthDp >= 900)
        for (tag in listOf("private_conversation", "private_agent", "private_memory", "private_inbox", "private_profiles", "private_rules", "private_settings"))
            assertTrue(view<Button>(a, tag).isEnabled)
        c.pause().stop().destroy()
    }
    @Test fun navigationTargetsOnlyExplicitSamePackageComponents() {
        val c = Robolectric.buildActivity(PrivateTestHomeActivity::class.java).setup(); val a = c.get()
        view<Button>(a, "private_agent").performClick(); val intent = shadowOf(a).nextStartedActivity
        assertEquals(app.packageName, intent.component!!.packageName); assertEquals("com.example.voicebrainlive.agent.AgentTasksActivity", intent.component!!.className)
        assertEquals(app.packageName, intent.`package`); c.pause().stop().destroy()
    }
    @Test fun conversationPreviewDoesNotPersistAndReviewedReadsStaySynthetic() = runBlocking<Unit> {
        val c = Robolectric.buildActivity(PrivateTestConversationActivity::class.java).setup(); val a = c.get()
        view<EditText>(a, "private_goal").setText("မြန်မာ 한국어 English synthetic")
        view<Button>(a, "private_preview_status").performClick()
        waitFor { view<TextView>(a, "private_conversation_status").text.contains("AGENT:DRAFT") }
        assertNull(app.bytes); view<Button>(a, "private_run").performClick()
        waitFor { view<TextView>(a, "private_conversation_status").text.contains("SUCCEEDED") }
        assertNotNull(app.bytes); assertTrue(view<TextView>(a, "private_conversation_result").text.contains("SYNTHETIC 19:00"))
        assertFalse(String(app.bytes!!, Charsets.UTF_8).contains("မြန်မာ 한국어 English synthetic")); c.pause().stop().destroy()
    }
    @Test fun studyProposalWaitsForApprovalAndNeverSchedulesReminder() = runBlocking<Unit> {
        val c = Robolectric.buildActivity(PrivateTestConversationActivity::class.java).setup(); val a = c.get()
        view<Button>(a, "private_preview_study").performClick(); waitFor { view<TextView>(a, "private_conversation_status").text.contains("AGENT:DRAFT") }
        view<Button>(a, "private_run").performClick(); waitFor { view<TextView>(a, "private_conversation_status").text.contains("WAITING_FOR_APPROVAL") }
        assertTrue(view<TextView>(a, "private_conversation_status").text.contains("actions=NOT_CONNECTED"))
        assertTrue(app.backgroundTasks.history().isEmpty()); c.pause().stop().destroy()
    }
    @Test fun cancelAndPauseClearReviewAndPrivateViewState() = runBlocking<Unit> {
        val c = Robolectric.buildActivity(PrivateTestConversationActivity::class.java).setup(); val a = c.get()
        val goal = view<EditText>(a, "private_goal"); goal.setText("PRIVATE SYNTHETIC test")
        view<Button>(a, "private_preview_status").performClick(); waitFor { view<TextView>(a, "private_conversation_status").text.contains("AGENT:DRAFT") }
        view<Button>(a, "private_cancel").performClick(); assertFalse(view<Button>(a, "private_run").isEnabled); assertNull(app.bytes)
        c.pause(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", goal.text.toString()); assertEquals("", view<TextView>(a, "private_conversation_result").text.toString()); assertFalse(goal.isSaveEnabled)
        c.resume(); assertFalse(view<Button>(a, "private_run").isEnabled); c.pause().stop().destroy()
    }
    @Test fun staleConsentDialogCannotEnableAfterPauseAndResume() {
        ConfirmedMemoryContextConsent.setAfterReview(app, false)
        val c = Robolectric.buildActivity(PrivateTestSettingsActivity::class.java).setup(); val a = c.get()
        view<Button>(a, "private_context_review").performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); val old = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        c.pause(); c.resume(); old.performClick(); assertFalse(ConfirmedMemoryContextConsent.isEnabled(app)); c.pause().stop().destroy()
    }
    @Test fun existingAboutScreenDisplaysPrivateVersionAndNoUpdateSource() {
        val c = Robolectric.buildActivity(AppUpdateActivity::class.java).setup(); val a = c.get()
        assertTrue(a.findViewById<TextView>(com.example.voicebrainlive.R.id.tvInstalledVersion).text.contains("PRIVATE-TEST"))
        assertTrue(a.findViewById<TextView>(com.example.voicebrainlive.R.id.tvUpdateStatus).text.contains("NOT CONFIGURED")); c.pause().stop().destroy()
    }
}
