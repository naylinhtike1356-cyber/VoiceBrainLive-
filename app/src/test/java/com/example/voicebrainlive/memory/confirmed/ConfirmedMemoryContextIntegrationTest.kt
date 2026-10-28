package com.example.voicebrainlive.memory.confirmed

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.voicebrainlive.memory.PersonalSystemInstructionBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Application-owned fake memory only; no provider session, real Keystore or production data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ConfirmedMemoryUiTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConfirmedMemoryContextIntegrationTest {
    private lateinit var fake: FakeConfirmedMemoryUiStore
    private val app get() = RuntimeEnvironment.getApplication() as ConfirmedMemoryUiTestApplication
    @Before fun prepare() {
        fake = FakeConfirmedMemoryUiStore()
        ConfirmedMemoryUiTestApplication.fake = fake
        assertTrue(ConfirmedMemoryContextConsent.setAfterReview(app, false))
    }
    private fun memory(content: String = "CONTEXT_EXACT မြန်မာ 한국어🙂\r\n ", include: Boolean = true): ConfirmedMemory {
        val now = System.currentTimeMillis()
        return ConfirmedMemory("context-record", 1, MemoryDraft("Language", content, ConfirmedMemoryCategory.PREFERENCE,
            ConfirmedMemorySource.USER_ENTERED, MemoryPrivacy.PERSONAL, include), now, now, now)
    }

    @Test fun liveSharedBuilderRequiresBothConsentsAndReadsCorrectionsAndDeletionsFresh() {
        fake.records = listOf(memory())
        assertFalse(ConfirmedMemoryContextConsent.isEnabled(app))
        assertFalse(PersonalSystemInstructionBuilder.build(app).contains("CONTEXT_EXACT"))
        assertTrue(ConfirmedMemoryContextConsent.setAfterReview(app, true))
        val enabled = PersonalSystemInstructionBuilder.build(app)
        assertTrue(enabled.contains("CONTEXT_EXACT"))
        assertTrue(enabled.contains("\"source\":\"USER_ENTERED\""))
        assertTrue(enabled.contains(ConfirmedMemoryPromptComposer.POLICY))
        fake.records = listOf(memory("CORRECTED_RECORD").copy(revision = 2))
        val corrected = PersonalSystemInstructionBuilder.build(app)
        assertFalse(corrected.contains("CONTEXT_EXACT")); assertTrue(corrected.contains("CORRECTED_RECORD"))
        fake.records = listOf(memory("LOCAL_ONLY", include = false))
        assertFalse(PersonalSystemInstructionBuilder.build(app).contains("LOCAL_ONLY"))
        fake.records = emptyList()
        assertFalse(PersonalSystemInstructionBuilder.build(app).contains("CORRECTED_RECORD"))
    }

    @Test fun unavailableStoreNeverReplaysEarlierPayloadAndGlobalOffLeavesItUnopened() {
        fake.records = listOf(memory())
        assertTrue(ConfirmedMemoryContextConsent.setAfterReview(app, true))
        assertTrue(ConfirmedMemoryContextConsent.freshContext(app).contains("CONTEXT_EXACT"))
        fake.unreadable = true
        assertEquals("", ConfirmedMemoryContextConsent.freshContext(app))
        assertFalse(PersonalSystemInstructionBuilder.build(app).contains("CONTEXT_EXACT"))
        assertTrue(ConfirmedMemoryContextConsent.setAfterReview(app, false))
        assertEquals("", ConfirmedMemoryContextConsent.freshContext(app))
    }

    @Test fun globalConsentDialogDisclosesTransmissionAndCannotReplayAfterBackgrounding() {
        val controller = Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitReady(activity)
            button(activity.window.decorView, "Enable confirmed memory in AI sessions").performClick()
            val abandoned = latest()
            assertTrue(abandoned.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(views(abandoned.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains("leave this device") && it.text.contains("selected AI provider") })
            val abandonedConfirm = abandoned.getButton(AlertDialog.BUTTON_POSITIVE)
            controller.pause().stop()
            abandonedConfirm.performClick()
            assertFalse(ConfirmedMemoryContextConsent.isEnabled(app))
            controller.start().resume().visible()
            button(activity.window.decorView, "Enable confirmed memory in AI sessions").performClick()
            val confirm = latest().getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ConfirmedMemoryContextConsent.isEnabled(app))
            confirm.performClick()
            assertEquals(0, fake.saveCalls)
            assertEquals(0, fake.correctCalls)
            assertTrue(fake.records.isEmpty())
            button(activity.window.decorView, "Disable confirmed memory in AI sessions").performClick()
            assertFalse(ConfirmedMemoryContextConsent.isEnabled(app))
            confirm.performClick()
            assertFalse(ConfirmedMemoryContextConsent.isEnabled(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun builderBoundsLegacyTextAndKeepsMandatoryAuthorityPolicyWithGlobalConsentOff() {
        val result = PersonalSystemInstructionBuilder.build(app, "LEGACY_UNVERIFIED " + "🙂".repeat(20_000))
        assertTrue(result.length <= ConfirmedMemoryPromptComposer.MAX_CHARS)
        assertTrue(result.startsWith(ConfirmedMemoryPromptComposer.POLICY))
        assertTrue(result.endsWith(ConfirmedMemoryPromptComposer.POLICY))
        assertTrue(result.contains("Unverified legacy"))
        for (index in result.indices) {
            if (result[index].isHighSurrogate()) assertTrue(index + 1 < result.length && result[index + 1].isLowSurrogate())
            if (result[index].isLowSurrogate()) assertTrue(index > 0 && result[index - 1].isHighSurrogate())
        }
    }

    private fun latest(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun button(root: View, text: String) = views(root).filterIsInstance<Button>().single { it.text.toString() == text }
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun awaitReady(activity: ConfirmedMemoryActivity) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (button(activity.window.decorView, "Add confirmed memory").isEnabled) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        fail("Fake memory UI did not settle within bounded wait")
    }
}
