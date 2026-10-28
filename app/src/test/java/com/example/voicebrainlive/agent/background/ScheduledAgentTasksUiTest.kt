package com.example.voicebrainlive.agent.background

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import kotlinx.coroutines.runBlocking
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

/** Fake app only; no Keystore, native queue, notification or private result is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = BackgroundTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduledAgentTasksUiTest {
    private lateinit var fake: FakeBackgroundTasks
    @Before fun prepare() { fake = FakeBackgroundTasks(); BackgroundTestApplication.fake = fake }

    @Test fun previewRequiresExactExplicitConfirmationAndDoesNotRunWork() {
        val controller = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            assertTrue(controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue("Initial history must load before a user can preview", button(root, "scheduled_preview").isEnabled)
            button(root, "scheduled_preview").performClick(); idle()
            val dialog = latest()
            assertEquals(0, fake.scheduleCalls); assertEquals(0, fake.executeCalls)
            assertEquals(PersistentTaskState.DRAFT, fake.records.single().state)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val exact = fake.records.single()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertEquals(exact.identity.taskId to exact.revision, fake.lastScheduled)
            assertEquals(1, fake.scheduleCalls); assertEquals(0, fake.executeCalls)
            assertTrue(text(root).contains("SCHEDULED"))
            assertFalse(text(root).contains("COMPLETED"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun deniedNotificationsDisableConfirmationAndRemainVisible() {
        fake.notices = false
        val controller = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            assertTrue(text(root).contains("Notifications unavailable"))
            assertTrue("Notification denial permits drafts, not scheduling", button(root, "scheduled_preview").isEnabled)
            button(root, "scheduled_preview").performClick(); idle()
            assertFalse(latest().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertEquals(0, fake.scheduleCalls); assertEquals(0, fake.executeCalls)
            assertEquals(View.VISIBLE, button(root, "scheduled_permission").visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun committedScheduleErrorReloadsActualStateAndNotificationFailure() {
        fake.failAfterScheduleCommit = true
        val controller = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            assertTrue(button(root, "scheduled_preview").isEnabled)
            button(root, "scheduled_preview").performClick(); idle()
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertEquals(PersistentTaskState.SCHEDULED, fake.records.single().state)
            assertTrue(text(root).contains("NOTIFICATION_UNAVAILABLE"))
            assertTrue(text(root).contains("Prior notification not accepted"))
            assertTrue(text(root).contains("SCHEDULED")); assertEquals(0, fake.executeCalls)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun stoppedPreviewCannotScheduleAfterReplacementActivity() {
        val old = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        idle(); assertTrue(button(old.get().window.decorView, "scheduled_preview").isEnabled)
        button(old.get().window.decorView, "scheduled_preview").performClick(); idle()
        val prior = latest().getButton(AlertDialog.BUTTON_POSITIVE)
        old.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        try {
            idle(); prior.performClick(); idle()
            assertEquals(0, fake.scheduleCalls); assertEquals(PersistentTaskState.DRAFT, fake.records.single().state)
            assertTrue(text(replacement.get().window.decorView).contains("DRAFT"))
        } finally { replacement.pause().stop().destroy() }
    }

    @Test fun unreadableHistoryFailsClosedWithoutPretendingEmptyWritableStore() {
        fake.unreadable = true
        val controller = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            assertFalse(button(root, "scheduled_preview").isEnabled)
            assertTrue(text(root).contains("History unavailable"))
            assertFalse(text(root).contains("No scheduled local task metadata yet"))
            assertEquals(0, fake.scheduleCalls); assertEquals(0, fake.executeCalls)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pauseAndCancelUseExactRenderedRevisionAndNeverExecute() = runBlocking {
        val draft = fake.previewLocalStatus()
        fake.schedule(draft.identity.taskId, draft.revision)
        val controller = Robolectric.buildActivity(ScheduledAgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "scheduled_pause_${draft.identity.taskId}").performClick(); idle()
            assertEquals(1, fake.pauseCalls); assertEquals(PersistentTaskState.PAUSED, fake.records.single().state)
            button(root, "scheduled_cancel_${draft.identity.taskId}").performClick(); idle()
            assertEquals(1, fake.cancelCalls); assertEquals(PersistentTaskState.CANCELLED, fake.records.single().state)
            assertEquals(0, fake.executeCalls)
        } finally { controller.pause().stop().destroy() }
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun latest(): AlertDialog { idle(); return (ShadowDialog.getLatestDialog() as AlertDialog).also { idle() } }
    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun button(root: View, tag: String) = views(root).single { it.tag == tag } as Button
    private fun text(root: View) = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text }
}
