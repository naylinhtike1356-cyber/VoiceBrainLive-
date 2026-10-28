package com.example.voicebrainlive.memory.confirmed

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.voicebrainlive.VoiceBrainApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

class ConfirmedMemoryUiTestApplication : VoiceBrainApplication() {
    override val confirmedMemoryStore: ConfirmedMemoryStore get() = fake
    override fun onCreate() { /* No production startup, credentials or services in UI fixtures. */ }
    companion object { lateinit var fake: FakeConfirmedMemoryUiStore }
}

class FakeConfirmedMemoryUiStore : ConfirmedMemoryStore {
    @Volatile var unreadable = false
    @Volatile var stale = false
    @Volatile var saveCalls = 0
    @Volatile var correctCalls = 0
    @Volatile var lastDraft: MemoryDraft? = null
    @Volatile var lastId: String? = null
    @Volatile var lastRevision: Long? = null
    var records: List<ConfirmedMemory> = emptyList()
    @Synchronized override fun list(includeExpired: Boolean): List<ConfirmedMemory> {
        if (unreadable) throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.DATA_UNREADABLE)
        return records.toList()
    }
    @Synchronized override fun save(draft: MemoryDraft, confirmedExactPreview: Boolean): ConfirmedMemory {
        check(confirmedExactPreview)
        saveCalls++; lastDraft = draft
        return ConfirmedMemory("fake-id", 1, draft, 100, 100, 100).also { records = records + it }
    }
    @Synchronized override fun correct(id: String, revision: Long, draft: MemoryDraft, confirmedExactPreview: Boolean): ConfirmedMemory {
        check(confirmedExactPreview)
        correctCalls++; lastDraft = draft; lastId = id; lastRevision = revision
        if (stale) throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.INVALID_OR_STALE_REQUEST)
        val updated = records.single { it.id == id }.copy(revision = revision + 1, draft = draft)
        records = records.map { if (it.id == id) updated else it }
        return updated
    }
    @Synchronized override fun forget(id: String, revision: Long, confirmedExactPreview: Boolean): Boolean {
        check(confirmedExactPreview)
        lastId = id; lastRevision = revision
        if (stale) throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.INVALID_OR_STALE_REQUEST)
        records = records.filterNot { it.id == id }
        return true
    }
}

/** Fake store only: no AtomicFile, real Keystore, external provider or production app data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ConfirmedMemoryUiTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConfirmedMemoryActivityTest {
    private lateinit var fake: FakeConfirmedMemoryUiStore
    @Before fun setUp() {
        fake = FakeConfirmedMemoryUiStore()
        ConfirmedMemoryUiTestApplication.fake = fake
    }

    @Test fun activityAndExactDialogProtectSensitivePreviews() {
        val controller = Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitUi { button(activity.window.decorView, "Add confirmed memory").isEnabled }
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            button(activity.window.decorView, "Add confirmed memory").performClick()
            val editor = latest()
            assertTrue(editor.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(0, fake.saveCalls)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unreadableStoreNeverBecomesWritableEmptyStore() {
        fake.unreadable = true
        val controller = Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitUi { views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("could not be opened") } }
            val add = button(activity.window.decorView, "Add confirmed memory")
            assertFalse(add.isEnabled)
            add.performClick()
            assertEquals(0, fake.saveCalls)
            assertTrue(fake.records.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exactImmutablePreviewDefaultsContextOffAndRejectsHiddenDuplicateCallback() {
        val controller = Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitUi { button(activity.window.decorView, "Add confirmed memory").isEnabled }
            button(activity.window.decorView, "Add confirmed memory").performClick()
            val abandoned = latest()
            fields(abandoned)[0].setText("Old preview")
            fields(abandoned)[1].setText("Must never save after Home")
            abandoned.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val oldConfirm = latest().getButton(AlertDialog.BUTTON_POSITIVE)
            controller.pause().stop()
            oldConfirm.performClick()
            assertEquals(0, fake.saveCalls)
            controller.start().resume().visible()

            button(activity.window.decorView, "Add confirmed memory").performClick()
            val editor = latest()
            val title = "  မြန်မာ / 한국어 김민수  "
            val content = "\r\nမြန်မာ၊ 한글 / English 🙂;  \n"
            fields(editor)[0].setText(title)
            fields(editor)[1].setText(content)
            val context = views(editor.window!!.decorView).filterIsInstance<CheckBox>()
                .single { it.text.contains("future assistant context") }
            assertFalse(context.isChecked)
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val preview = latest()
            assertTrue(views(preview.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == title })
            assertTrue(views(preview.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == content })
            fields(editor)[1].setText("Changed behind immutable preview")
            val confirm = preview.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.performClick()
            awaitUi { fake.saveCalls == 1 && button(activity.window.decorView, "Add confirmed memory").isEnabled }
            confirm.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, fake.saveCalls)
            assertEquals(title, fake.lastDraft!!.title)
            assertEquals(content, fake.lastDraft!!.content)
            assertFalse(fake.lastDraft!!.includeInContext)
            assertNull(fake.lastDraft!!.expiresAt)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun staleRevisionKeepsExactIdentityAndRequiresRefreshWithoutReplayingConfirmation() {
        val initial = ConfirmedMemory("specific-id", 3,
            MemoryDraft("Saved title", "Exact body", ConfirmedMemoryCategory.FACT, ConfirmedMemorySource.USER_ENTERED),
            100, 100, 100)
        fake.records = listOf(initial)
        val controller = Robolectric.buildActivity(ConfirmedMemoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitUi { button(activity.window.decorView, "Add confirmed memory").isEnabled }
            button(activity.window.decorView, "View / edit").performClick()
            val editor = latest()
            fields(editor)[1].setText("Corrected 한국어\r\nမြန်မာ  ")
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val confirm = latest().getButton(AlertDialog.BUTTON_POSITIVE)
            fake.stale = true
            confirm.performClick()
            awaitUi { views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("could not be verified") } }
            confirm.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, fake.correctCalls)
            assertEquals("specific-id", fake.lastId)
            assertEquals(3L, fake.lastRevision)
            assertEquals(initial, fake.records.single())
            assertFalse(button(activity.window.decorView, "Add confirmed memory").isEnabled)
            button(activity.window.decorView, "Refresh").performClick()
            awaitUi { button(activity.window.decorView, "Add confirmed memory").isEnabled }
            confirm.performClick()
            assertEquals(1, fake.correctCalls)
        } finally { controller.pause().stop().destroy() }
    }

    private fun latest(): AlertDialog {
        // PAUSED looper: deliver Dialog's queued onShow before reading flags or clicking.
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun fields(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<EditText>()
    private fun button(root: View, label: String) = views(root).filterIsInstance<Button>().single { it.text.toString() == label }
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        fail("Fake-store UI did not settle within the bounded host wait")
    }
}
