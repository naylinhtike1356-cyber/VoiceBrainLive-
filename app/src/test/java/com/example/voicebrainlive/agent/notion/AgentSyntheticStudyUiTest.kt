package com.example.voicebrainlive.agent.notion

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.AgentData
import com.example.voicebrainlive.agent.AgentObservation
import com.example.voicebrainlive.agent.AgentReadPort
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.AgentTasksActivity
import com.example.voicebrainlive.agent.ForegroundAgentTasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

class AgentStudyUiApplication : VoiceBrainApplication() {
    override val foregroundAgentTasks get() = tasks
    override val directNotionProfiles get() = profiles
    override fun onCreate() { /* Synthetic fixtures do not start production services. */ }
    companion object { lateinit var tasks: ForegroundAgentTasks; lateinit var profiles: DirectNotionProfiles }
}

private class CapturingProfiles(private val delegate: DirectNotionProfiles) : DirectNotionProfiles by delegate {
    var gate: CompletableDeferred<Unit>? = null
    var nonCooperative = false
    var failure: RuntimeException? = null
    var captures = 0
    override suspend fun captureSelectedPin(): ConnectionReadPin {
        captures++; failure?.let { throw it }
        val pin = delegate.captureSelectedPin() // Capture exact selection before synthetic suspension.
        if (nonCooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
        return pin
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = AgentStudyUiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentSyntheticStudyUiTest {
    private lateinit var f: NotionFixture
    private lateinit var selected: ConnectionProfileView
    private lateinit var profiles: CapturingProfiles
    private lateinit var facade: ForegroundAgentTasks
    private lateinit var scope: CoroutineScope
    private var query: SyntheticNotionPort = SyntheticNotionDemoPort
    private var queries = 0
    private var localClocks = 0
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    @Before fun prepare() = runBlocking {
        f = NotionFixture(); selected = f.profile("sample_A"); f.repo.select(selected.profileId, selected.revision)
        profiles = CapturingProfiles(RepositoryDirectNotionProfiles(f.repo))
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val local = object : AgentReadPort {
            override suspend fun clock(): AgentObservation { localClocks++; return AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("Synthetic local clock")) }
            override suspend fun battery() = AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery(61))
            override suspend fun tasks(connection: com.example.voicebrainlive.agent.AgentConnectionPin) = AgentObservation(AgentStatus.BLOCKED)
        }
        facade = ForegroundAgentTasks(local, scope, { f.now }, f.repo, SyntheticNotionPort { request, secret ->
            queries++; query.query(request, secret)
        })
        AgentStudyUiApplication.tasks = facade; AgentStudyUiApplication.profiles = profiles
    }
    @After fun release() {
        releases.forEach { it.complete(Unit) }; scope.cancel(); idle()
    }
    private fun gate() = CompletableDeferred<Unit>().also { releases += it }

    @Test fun reviewedSampleUsesActualFacadeAndRendersSyntheticDraftWithExactSource() {
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            assertTrue(controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(label(root, "agent_notion_unavailable").text.contains("NOT CONNECTED"))
            assertFalse(button(root, "agent_run").isEnabled)
            button(root, "agent_study").performClick(); idle()
            assertEquals(1, profiles.captures); assertEquals(0, queries)
            assertTrue(label(root, "agent_preview").text.contains(selected.profileId))
            assertTrue(label(root, "agent_preview").text.contains("revision ${selected.revision}"))
            assertTrue(label(root, "agent_preview").text.contains("SYNTHETIC"))
            assertTrue(button(root, "agent_run").isEnabled)
            button(root, "agent_run").performClick(); idle()
            assertEquals(1, queries); assertEquals(0, localClocks)
            assertEquals(AgentStatus.SUCCEEDED, facade.state.value.snapshot!!.status)
            val shown = label(root, "agent_result").text
            assertTrue(shown.contains("Study draft — 30 minutes")); assertTrue(shown.contains("no connected account"))
            assertTrue(shown.contains(selected.resource.dataSourceId)); assertTrue(shown.contains("SYNTHETIC study sample"))
            assertFalse(shown.contains("SYNTHETIC finished sample"))
            assertFalse(button(root, "agent_run").isEnabled)
            assertTrue(button(root, "agent_study").minimumHeight >= (48 * controller.get().resources.displayMetrics.density).toInt())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun pendingLookupCanBeSupersededByLocalPreviewWithoutLateOverwrite() {
        val gate = gate(); profiles.gate = gate; profiles.nonCooperative = true
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle()
            assertEquals(1, profiles.captures); assertFalse(button(root, "agent_run").isEnabled)
            assertFalse(button(root, "agent_study").isEnabled); assertTrue(button(root, "agent_choose").isEnabled)
            button(root, "agent_choose").performClick(); idle()
            val localTask = facade.state.value.preview!!.taskId
            assertTrue(label(root, "agent_preview").text.contains("Read device time"))
            gate.complete(Unit); idle()
            assertEquals(localTask, facade.state.value.preview!!.taskId)
            assertTrue(label(root, "agent_preview").text.contains("Read device time"))
            button(root, "agent_run").performClick(); idle()
            assertEquals(1, localClocks); assertEquals(0, queries)
            assertTrue(label(root, "agent_result").text.contains("Battery: 61%"))
        } finally { gate.complete(Unit); controller.pause().stop().destroy() }
    }
    @Test fun stoppedNonCooperativeLookupCannotChangeOldViewOrReplacementLease() {
        val gate = gate(); profiles.gate = gate; profiles.nonCooperative = true
        val old = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        idle(); val oldRoot = old.get().window.decorView
        button(oldRoot, "agent_study").performClick(); idle(); old.pause().stop()
        val closedText = label(oldRoot, "agent_status").text.toString()
        profiles.gate = null; profiles.nonCooperative = false
        val replacement = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = replacement.get().window.decorView
            button(root, "agent_choose").performClick(); idle()
            val task = facade.state.value.preview!!.taskId
            gate.complete(Unit); idle()
            assertEquals(closedText, label(oldRoot, "agent_status").text.toString())
            assertEquals("No execution results.", label(oldRoot, "agent_result").text.toString())
            assertEquals(task, facade.state.value.preview!!.taskId)
            assertTrue(label(root, "agent_preview").text.contains("Read device time")); assertEquals(0, queries)
        } finally { gate.complete(Unit); old.destroy(); replacement.pause().stop().destroy() }
    }
    @Test fun selectionSwitchDuringLookupKeepsReviewedAndExecutedSourceA() = runBlocking {
        val b = f.profile("sample_B"); val aPin = f.pin(selected)
        val gate = gate(); profiles.gate = gate
        var queried: ConnectionReadPin? = null
        query = SyntheticNotionPort { request, _ -> queried = request.pin; f.page(request.pin, listOf(f.row(request.pin))) }
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle(); assertEquals(1, profiles.captures)
            f.repo.select(b.profileId, b.revision); gate.complete(Unit); idle()
            assertTrue(label(root, "agent_preview").text.contains(aPin.resource.dataSourceId))
            assertFalse(label(root, "agent_preview").text.contains(b.resource.dataSourceId))
            button(root, "agent_run").performClick(); idle()
            assertEquals(aPin, queried); assertEquals(1, profiles.captures)
            assertEquals(aPin, fullAgentBinding((facade.state.value.snapshot!!.steps.last().result as AgentData.Draft).connection!!))
            assertTrue(label(root, "agent_result").text.contains(aPin.resource.dataSourceId))
        } finally { gate.complete(Unit); controller.pause().stop().destroy() }
    }
    @Test fun cancelButtonRevokesTheExactRunningSampleAndRejectsItsLateDraft() {
        val gate = gate()
        query = SyntheticNotionPort { request, _ -> withContext(NonCancellable) {
            gate.await(); f.page(request.pin, listOf(f.row(request.pin, title = "LATE_PRIVATE_SAMPLE")))
        } }
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle()
            val taskId = facade.state.value.preview!!.taskId
            button(root, "agent_run").performClick(); idle(); assertTrue(facade.state.value.running)
            assertTrue(button(root, "agent_cancel").isEnabled)
            button(root, "agent_cancel").performClick(); idle()
            assertFalse(facade.state.value.running); assertFalse(button(root, "agent_cancel").isEnabled)
            gate.complete(Unit); idle()
            assertEquals(taskId, facade.state.value.preview!!.taskId)
            assertTrue(facade.state.value.snapshot?.steps?.none { it.result != null } != false)
            assertFalse(label(root, "agent_result").text.contains("LATE_PRIVATE_SAMPLE"))
            assertFalse(label(root, "agent_status").text.contains("prepared locally")); assertEquals(1, queries)
        } finally { gate.complete(Unit); controller.pause().stop().destroy() }
    }
    @Test fun incompleteEmptySampleIsBlockedAndNeverRenderedAsSuccessfulStudy() {
        query = SyntheticNotionPort { request, _ -> f.page(request.pin, status = QueryCompletion.INCOMPLETE) }
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle(); button(root, "agent_run").performClick(); idle()
            assertEquals(AgentStatus.BLOCKED, facade.state.value.snapshot!!.status)
            assertTrue(facade.state.value.snapshot!!.steps.none { it.result is AgentData.Draft })
            assertFalse(label(root, "agent_result").text.contains("Study draft —"))
            assertFalse(label(root, "agent_status").text.contains("prepared locally"))
            assertFalse(button(root, "agent_run").isEnabled)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun pausedStaleButtonsCannotStartReadsOrChangeTheReviewedPreview() {
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_choose").performClick(); idle()
            val task = facade.state.value.preview!!.taskId
            assertTrue(button(root, "agent_run").isEnabled)
            controller.pause(); idle()
            listOf("agent_choose", "agent_study", "agent_run", "agent_cancel").forEach {
                assertFalse(button(root, it).isEnabled)
                button(root, it).performClick() // Direct stale callback bypasses normal touch disabling.
            }
            idle()
            assertEquals(task, facade.state.value.preview!!.taskId)
            assertFalse(facade.state.value.running); assertEquals(0, localClocks); assertEquals(0, queries)
            assertEquals(0, profiles.captures)
            controller.resume(); idle()
            assertTrue(button(root, "agent_run").isEnabled)
            button(root, "agent_run").performClick(); idle()
            assertEquals(1, localClocks); assertEquals(0, queries)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun profileDisabledWhileLookupAwaitsCannotBecomeAReviewableStudy() = runBlocking {
        val gate = gate(); profiles.gate = gate
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle()
            f.repo.disable(selected.profileId, selected.revision); gate.complete(Unit); idle()
            assertNull(facade.state.value.preview); assertFalse(button(root, "agent_run").isEnabled)
            assertTrue(label(root, "agent_status").text.contains("unavailable")); assertEquals(0, queries)
        } finally { gate.complete(Unit); controller.pause().stop().destroy() }
    }
    @Test fun failedLookupShowsOnlyCategoryFreeSafeErrorAndRestoresControls() {
        profiles.failure = IllegalStateException("PRIVATE_PROFILE_LOOKUP_PAYLOAD")
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle()
            assertTrue(label(root, "agent_status").text.contains("Synthetic preview unavailable"))
            assertFalse(label(root, "agent_status").text.contains("PRIVATE"))
            assertTrue(button(root, "agent_study").isEnabled); assertTrue(button(root, "agent_choose").isEnabled)
            assertFalse(button(root, "agent_run").isEnabled); assertEquals(0, queries)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun multilingualDraftIsPlainReadableTextAndResultViewsDoNotSaveTheirPayload() {
        val title = "မြန်မာစာလေ့လာ 한국어 공부 English study ".repeat(35)
        query = SyntheticNotionPort { request, _ -> f.page(request.pin, listOf(f.row(request.pin, title = title))) }
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); val root = controller.get().window.decorView
            button(root, "agent_study").performClick(); idle(); button(root, "agent_run").performClick(); idle()
            assertEquals(AgentStatus.SUCCEEDED, facade.state.value.snapshot!!.status)
            val text = label(root, "agent_result")
            assertTrue(text.text.contains(title)); assertTrue(text.isTextSelectable)
            assertFalse(text.isSaveEnabled); assertFalse(text.isSaveFromParentEnabled)
            assertFalse(label(root, "agent_preview").isSaveEnabled)
            assertTrue(text.text.contains("SYNTHETIC sample; no connected account"))
            controller.pause().stop()
            assertEquals("No execution results.", text.text.toString())
            assertTrue(facade.state.value.snapshot!!.steps.all { it.result == null })
        } finally { controller.destroy() }
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun button(root: View, tag: String) = views(root).single { it.tag == tag } as Button
    private fun label(root: View, tag: String) = views(root).single { it.tag == tag } as TextView
}
