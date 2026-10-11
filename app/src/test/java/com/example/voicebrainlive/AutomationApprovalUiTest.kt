package com.example.voicebrainlive

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.commands.SensitiveActionGuard
import com.example.voicebrainlive.routines.AutomationApprovalActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomationApprovalUiTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun fullPreview_hasNoAutomaticExecution_andExplicitCancelRevokes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SensitiveActionGuard.uiVisible = false
        val content = (" မြန်မာ၊ 한국어! MiXeD English.\n".repeat(200)) + "  final line 🙂  "
        val output = SensitiveActionGuard.run(app, "ui-test", "send_sms", "01012345678", content) { error("bypass") }
        val id = output.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        val pending = SensitiveActionGuard.store.pending(id)!!
        val intent = Intent(app, AutomationApprovalActivity::class.java)
            .putExtra(AutomationApprovalActivity.EXTRA_APPROVAL_ID, id)
        val controller = Robolectric.buildActivity(AutomationApprovalActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val views = descendants(activity.findViewById(android.R.id.content))
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == pending.snapshot.preview() })
            assertTrue(pending.snapshot.preview().endsWith(content))
            assertFalse(SensitiveActionGuard.store.consume(id, "ui-test", pending.snapshot))
            assertNull(shadowOf(app).nextStartedActivity)
            views.filterIsInstance<Button>().single { it.text.contains("Cancel") }.performClick()
            assertNull(SensitiveActionGuard.store.pending(id))
            assertTrue(SensitiveActionGuard.executeApproved(app, id, pending.snapshot).startsWith("BLOCKED"))
        } finally {
            controller.pause().stop().destroy()
            SensitiveActionGuard.cancel(app, id)
            SensitiveActionGuard.invalidate("ui-test")
        }
    }
}
