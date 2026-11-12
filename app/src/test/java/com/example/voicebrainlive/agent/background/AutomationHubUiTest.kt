package com.example.voicebrainlive.agent.background

import android.app.AlertDialog
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.AutomationActivity
import com.example.voicebrainlive.R
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.databinding.ActivitySettingsBinding
import com.example.voicebrainlive.routines.RoutineStorage
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ScheduledRequestTestApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AutomationHubUiTest {
    private val app get() = RuntimeEnvironment.getApplication() as ScheduledRequestTestApp
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private suspend fun until(test: () -> Boolean) {
        withTimeout(15_000) { while (!test()) { idle(); delay(20) } }
        idle()
    }
    private fun tab(a: AutomationActivity, index: Int) {
        a.findViewById<TabLayout>(R.id.hubTabs).getTabAt(index)!!.select()
        idle()
    }
    private fun <T : View> tagged(root: View, key: String): T {
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(root.findViewWithTag<View>(key)) { key } as T
    }
    private fun root(a: AutomationActivity) = a.findViewById<View>(android.R.id.content)
    private fun rows(a: AutomationActivity, id: Int): RecyclerView {
        val root = root(a)
        root.forceLayout()
        root.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 420, 900)
        idle()
        return a.findViewById(id)
    }
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()

    @Test fun hubStartsWithoutSchedulingOrSeedingMacrosAndRestoresSelectedTab() = runBlocking<Unit> {
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a = c.get()
        until { tagged<android.widget.TextView>(root(a), "requests_status").text.isNotBlank() }
        assertTrue(app.enqueued.isEmpty())
        assertNull(app.bytes.value)
        assertTrue(app.getSharedPreferences("custom_macros", 0).all.isEmpty())
        tab(a, 1)
        c.recreate()
        assertEquals(1, c.get().findViewById<TabLayout>(R.id.hubTabs).selectedTabPosition)
        assertEquals(View.VISIBLE, c.get().findViewById<View>(R.id.routinesPanel).visibility)
        c.pause().stop().destroy()
    }

    @Test fun scheduledPauseAndResumeUseTheExistingRepositoryAndQueue() = runBlocking<Unit> {
        val r = app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL, "existing task", "", "", app.now + 300_000))
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a = c.get()
        until { root(a).findViewWithTag<View>("requests_pause_${r.id}") != null }
        tagged<View>(root(a), "requests_pause_${r.id}").performClick()
        until { root(a).findViewWithTag<View>("requests_resume_${r.id}") != null }
        assertEquals(RequestState.PAUSED, app.scheduledRequests.history().single().state)
        tagged<View>(root(a), "requests_resume_${r.id}").performClick()
        until { app.scheduledRequests.history().single().state == RequestState.SCHEDULED }
        assertEquals(r.id, app.scheduledRequests.history().single().id)
        assertEquals(1, app.enqueued.size)
        c.pause().stop().destroy()
    }

    @Test fun switchingTabsRevokesAnOpenScheduleReview() = runBlocking<Unit> {
        val r = app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL, "do not submit", "", "", app.now + 60_000))
        app.now = r.dueAt
        app.scheduledRequests.due(r.id, r.revision)
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a = c.get()
        until { root(a).findViewWithTag<View>("requests_review_${r.id}") != null }
        tagged<View>(root(a), "requests_review_${r.id}").performClick()
        val preview = dialog()
        tab(a, 1)
        assertFalse(preview.isShowing)
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(RequestState.READY, app.scheduledRequests.history().single().state)
        assertNull(Shadows.shadowOf(a).peekNextStartedActivity())
        c.pause().stop().destroy()
    }

    @Test fun notionPresetInHubStillRequiresExplicitSharingAndFinalReview() {
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        tagged<View>(root(c.get()), "requests_notion").performClick()
        idle()
        val form = dialog()
        assertFalse(tagged<android.widget.CheckBox>(form.window!!.decorView, "request_automatic").isChecked)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(form.isShowing)
        assertTrue(app.enqueued.isEmpty())
        assertNull(app.bytes.value)
        c.pause().stop().destroy()
    }

    @Test fun routineToggleAndEditPreserveIdentityAndExecutionMode() {
        val r = RoutineStorage.add(app, "weather", 7, 30, useLiveAgent = false)
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a = c.get()
        tab(a, 1)
        var list = rows(a, R.id.recyclerAutomation)
        var row = list.findViewHolderForAdapterPosition(0)!!.itemView
        row.findViewById<Switch>(R.id.switchAutomationToggle).isChecked = false
        assertFalse(RoutineStorage.getById(app, r.id)!!.enabled)
        list = rows(a, R.id.recyclerAutomation)
        row = list.findViewHolderForAdapterPosition(0)!!.itemView
        row.findViewById<View>(R.id.btnEditAutomation).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val saved = RoutineStorage.getById(app, r.id)!!
        assertEquals(7, saved.hour)
        assertEquals(30, saved.minute)
        assertFalse(saved.useLiveAgent)
        assertFalse(saved.enabled)
        c.pause().stop().destroy()
    }

    @Test fun macroFlagsAreNotListedAndAddDeleteUseExistingPreferences() {
        val prefs = app.getSharedPreferences("custom_macros", 0)
        prefs.edit().putString("my phrase", "volume_up").putBoolean("show_macro_guide", false).commit()
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a = c.get()
        tab(a, 2)
        assertEquals(1, rows(a, R.id.recyclerMacros).adapter!!.itemCount)
        tagged<View>(root(a), "hub_macro_add").performClick()
        val form = dialog()
        tagged<EditText>(form.window!!.decorView, "macro_name").setText("new phrase")
        tagged<EditText>(form.window!!.decorView, "macro_sequence").setText("volume_down")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("volume_down", prefs.getString("new phrase", null))
        var list = rows(a, R.id.recyclerMacros)
        list.scrollToPosition(1)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        list = rows(a, R.id.recyclerMacros)
        list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.btnDelete).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(prefs.contains("new phrase"))
        assertEquals("volume_up", prefs.getString("my phrase", null))
        assertFalse(prefs.getBoolean("show_macro_guide", true))
        c.pause().stop().destroy()
    }

    @Test fun settingsEntryKeepsUpdatesLastAndLabsCollapsed() {
        val context = android.view.ContextThemeWrapper(app, R.style.Theme_VoiceBrainLive)
        val binding = ActivitySettingsBinding.inflate(LayoutInflater.from(context))
        assertEquals(View.GONE, binding.labsSection.visibility)
        assertNotNull(binding.btnAutomationHub)
        val parent = binding.btnAppUpdates.parent as ViewGroup
        assertSame(binding.btnAppUpdates, parent.getChildAt(parent.childCount - 1))
    }

    @Test fun openingEmptyMacroTabNeverCreatesOrRestoresDefinitions() {
        val prefs=app.getSharedPreferences("custom_macros",0)
        val c=Robolectric.buildActivity(AutomationActivity::class.java).setup()
        tab(c.get(),2)
        assertTrue(prefs.all.isEmpty())
        assertEquals(0,rows(c.get(),R.id.recyclerMacros).adapter!!.itemCount)
        c.pause().stop().destroy()
    }

    @Test fun duplicateMacroNameDoesNotOverwriteAndPreviewShowsSteps() {
        val prefs=app.getSharedPreferences("custom_macros",0)
        prefs.edit().putString("Meeting Mode","mute").commit()
        val c=Robolectric.buildActivity(AutomationActivity::class.java).setup()
        tab(c.get(),2)
        tagged<View>(root(c.get()),"hub_macro_add").performClick()
        val form=dialog()
        tagged<EditText>(form.window!!.decorView,"macro_name").setText(" MEETING MODE ")
        tagged<EditText>(form.window!!.decorView,"macro_sequence").setText("volume_up;volume_down")
        val preview=tagged<android.widget.TextView>(form.window!!.decorView,"macro_step_preview").text.toString()
        assertTrue(preview.contains("1."));assertTrue(preview.contains("2."));assertFalse(preview.contains("volume_up"))
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(form.isShowing)
        assertEquals("mute",prefs.getString("Meeting Mode",null))
        assertFalse(prefs.contains("meeting mode"))
        c.pause().stop().destroy()
    }

    @Test fun pendingWorkIsGroupedAndWorkerChangesRefreshWhileVisible() = runBlocking<Unit> {
        val scheduled=app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL,"upcoming","","",app.now+60_000))
        val c=Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a=c.get()
        until { root(a).findViewWithTag<View>("requests_section_UPCOMING")!=null }
        app.now=scheduled.dueAt
        app.scheduledRequests.due(scheduled.id,scheduled.revision)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        until { root(a).findViewWithTag<View>("requests_section_ATTENTION")!=null }
        assertNotNull(root(a).findViewWithTag<View>("requests_review_${scheduled.id}"))
        assertTrue(tagged<android.widget.TextView>(root(a),"requests_next_${scheduled.id}").text.contains("မလုပ်ဆောင်ရသေး"))
        c.pause().stop().destroy()
    }

    @Test fun preparedDraftCanBeReopenedWithoutResubmittingWork() = runBlocking<Unit> {
        val scheduled=app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL,"my draft","","",app.now+60_000))
        app.now=scheduled.dueAt
        val ready=app.scheduledRequests.due(scheduled.id,scheduled.revision)!!
        app.scheduledRequests.finish(app.scheduledRequests.claimReviewed(ready){true},true)
        val c=Robolectric.buildActivity(AutomationActivity::class.java).setup()
        until { root(c.get()).findViewWithTag<View>("requests_draft_${scheduled.id}")!=null }
        tagged<View>(root(c.get()),"requests_draft_${scheduled.id}").performClick()
        assertEquals(scheduled.id,Shadows.shadowOf(c.get()).nextStartedActivity.getStringExtra("scheduled_draft_id"))
        assertEquals(RequestState.DRAFT_READY,app.scheduledRequests.history().single().state)
        assertTrue(app.enqueued.isEmpty())
        c.pause().stop().destroy()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-notnight-mdpi")
    fun renderHubPhoneWithLargeBurmeseText() = capture(360, 800, "phone", 1.5f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w1000dp-h700dp-land-notnight-mdpi")
    fun renderHubTabletInLightTheme() {
        ThemeUtils.setTheme(app, "light")
        capture(1000, 700, "tablet-light", 1f)
    }

    private fun capture(width: Int, height: Int, name: String, fontScale: Float) = runBlocking<Unit> {
        RuntimeEnvironment.setFontScale(fontScale)
        app.scheduledRequests.queueAccepted(app.scheduledRequests.create(RequestKind.AI_GOAL, "မနက်ခင်း အလုပ်များ စီစဉ်ရန်", "", "", app.now + 300_000))
        RoutineStorage.add(app, "weather", 7, 30)
        app.getSharedPreferences("custom_macros", 0).edit().putString("စာကြည့်ချိန်", "set_focus_mode:30;set_ringer_silent").putBoolean("show_macro_guide", false).commit()
        val c = Robolectric.buildActivity(AutomationActivity::class.java).setup()
        val a = c.get()
        val root = root(a)
        until { root.findViewWithTag<View>("requests_section_UPCOMING")!=null }
        for (index in 0..2) {
            tab(a, index)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            idle()
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val out = java.io.File("build/automation-hub-captures").apply { mkdirs() }
            java.io.File(out, "$name-tab$index.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue(a.findViewById<View>(R.id.hubTabs).height > 0)
        }
        c.pause().stop().destroy()
    }
}
