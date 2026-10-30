package com.example.voicebrainlive.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.voicebrainlive.VoiceBrainApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.coroutines.CoroutineContext

class ForegroundAgentUiApplication : VoiceBrainApplication() {
    override val foregroundAgentTasks get() = fake
    override fun onCreate() { /* Local UI fixtures do not start production services. */ }
    companion object { lateinit var fake: ForegroundAgentTasks }
}
private class FakeForegroundReads : AgentReadPort {
    var clocks = 0; var batteries = 0; var taskReads = 0
    var release: CompletableDeferred<Unit>? = null
    var nonCooperative = false
    var failBattery = false
    override suspend fun clock(): AgentObservation {
        clocks++
        if (nonCooperative) withContext(NonCancellable) { release?.await() } else release?.await()
        return AgentObservation(AgentStatus.SUCCEEDED, AgentData.Clock("Fake local clock"))
    }
    override suspend fun battery(): AgentObservation {
        batteries++
        if (failBattery) error("PRIVATE ADAPTER PAYLOAD")
        return AgentObservation(AgentStatus.SUCCEEDED, AgentData.Battery(73))
    }
    override suspend fun tasks(connection: AgentConnectionPin): AgentObservation { taskReads++; error("No task provider is connected") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ForegroundAgentUiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ForegroundAgentTasksUiTest {
    private lateinit var reads: FakeForegroundReads
    private lateinit var facade: ForegroundAgentTasks
    @Before fun prepare() {
        reads = FakeForegroundReads()
        facade = ForegroundAgentTasks(reads, CoroutineScope(Dispatchers.Unconfined + SupervisorJob()), now = { 1_000L })
        ForegroundAgentUiApplication.fake = facade
    }
    @Test fun explicitPreviewMakesNoCallsAndRunShowsActualLocalStepResults() {
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            idle()
            assertTrue(controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertFalse(button(root, "agent_run").isEnabled)
            button(root, "agent_choose").performClick(); idle()
            assertEquals(0, reads.clocks); assertEquals(0, reads.batteries)
            assertTrue(label(root, "agent_preview").text.contains("Read device time"))
            assertTrue(label(root, "agent_preview").text.contains("Read battery level"))
            assertTrue(button(root, "agent_run").minimumHeight >= (48 * controller.get().resources.displayMetrics.density).toInt())
            button(root, "agent_run").performClick(); idle()
            assertEquals(1, reads.clocks); assertEquals(1, reads.batteries)
            assertEquals(AgentStatus.SUCCEEDED, facade.state.value.snapshot?.status)
            assertEquals(3, facade.state.value.snapshot?.calls)
            assertTrue(label(root, "agent_result").text.contains("Battery: 73%"))
            assertFalse(button(root, "agent_run").isEnabled)
            button(root, "agent_run").performClick(); assertEquals(1, reads.clocks)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun leavingForegroundCancelsPendingTaskAndRejectsItsLateResult() {
        reads.release = CompletableDeferred(); reads.nonCooperative = true
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        val root = controller.get().window.decorView
        button(root, "agent_choose").performClick(); idle(); button(root, "agent_run").performClick(); idle()
        assertTrue(facade.state.value.running)
        controller.pause().stop()
        assertFalse(facade.state.value.running)
        assertTrue(facade.state.value.message.contains("Cancelled"))
        reads.release!!.complete(Unit)
        assertTrue(facade.state.value.snapshot?.steps?.none { it.result != null } != false)
        assertEquals(0, reads.batteries)
        controller.destroy()
    }
    @Test fun completionObserverStopPreservesSuccessAndDropsFacadeAndRunnerResultReferences() {
        val owner = facade.openView()
        assertTrue(facade.previewLocalStatus(owner))
        val taskId = facade.state.value.preview!!.taskId
        var observedCompletion = false
        var completionCancelAccepted = true
        var observedBeforeOwnedFinally = false
        val synchronousObserver = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { block.run() }
        }
        val collector = CoroutineScope(synchronousObserver + SupervisorJob()).launch {
            facade.state.collect { state ->
                if (state.snapshot?.status == AgentStatus.SUCCEEDED && !observedCompletion) {
                    // Dispatch inline at StateFlow publication, rather than an Unconfined event-loop turn.
                    observedCompletion = true
                    observedBeforeOwnedFinally = ForegroundAgentTasks::class.java.getDeclaredField("active")
                        .apply { isAccessible = true }.get(facade) != null
                    completionCancelAccepted = facade.cancel(owner, taskId)
                    facade.stop(owner)
                }
            }
        }
        try {
            assertTrue(facade.runPreview(owner, taskId))
            assertTrue(observedCompletion)
            assertTrue("Observer must run while terminal state is published but owned-job finally has not cleared active", observedBeforeOwnedFinally)
            assertFalse(completionCancelAccepted)
            assertEquals(AgentStatus.SUCCEEDED, facade.state.value.snapshot?.status)
            assertTrue(facade.state.value.snapshot!!.steps.all { it.result == null })
            val runner = ForegroundAgentTasks::class.java.getDeclaredField("runner").apply { isAccessible = true }.get(facade) as AgentRunner
            assertEquals(AgentStatus.SUCCEEDED, runner.snapshot()?.status)
            assertTrue(runner.snapshot()!!.steps.all { it.result == null })
            assertEquals(1, reads.clocks); assertEquals(1, reads.batteries)
        } finally { collector.cancel(); facade.stop(owner) }
    }
    @Test fun obsoleteViewCannotCancelReplacementAndNewRunWaitsForPriorCancellation() {
        reads.release = CompletableDeferred(); reads.nonCooperative = true
        val old = facade.openView(); facade.previewLocalStatus(old)
        val firstId = facade.state.value.preview!!.taskId; assertTrue(facade.runPreview(old, firstId))
        val replacement = facade.openView(); assertTrue(facade.previewLocalStatus(replacement))
        val nextId = facade.state.value.preview!!.taskId
        assertFalse(facade.runPreview(replacement, nextId))
        facade.stop(old); assertTrue(facade.isCurrent(replacement))
        assertFalse(facade.cancel(old, firstId)); assertFalse(facade.cancel(replacement, firstId))
        reads.release!!.complete(Unit); reads.release = null; reads.nonCooperative = false
        assertTrue(facade.runPreview(replacement, nextId))
        assertEquals(nextId, facade.state.value.snapshot?.taskId)
        assertEquals(AgentStatus.SUCCEEDED, facade.state.value.snapshot?.status)
        facade.stop(replacement)
    }
    @Test fun recreationRetainsOnlyRamMetadataAndDoesNotResumeOrReplayResults() {
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        val root = controller.get().window.decorView
        button(root, "agent_choose").performClick(); idle(); button(root, "agent_run").performClick(); idle()
        controller.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); assertEquals(1, reads.clocks)
            assertTrue(facade.state.value.snapshot!!.steps.all { it.result == null })
            assertFalse(label(replacement.get().window.decorView, "agent_result").text.contains("Fake local clock"))
            assertFalse(button(replacement.get().window.decorView, "agent_run").isEnabled)
        } finally { replacement.pause().stop().destroy() }
    }
    @Test fun failedReadNeverShowsCompletedReportOrPrivateExceptionPayload() {
        reads.failBattery = true
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            button(root, "agent_choose").performClick(); idle(); button(root, "agent_run").performClick(); idle()
            assertEquals(AgentStatus.FAILED, facade.state.value.snapshot?.status)
            assertEquals(AgentStatus.SKIPPED, facade.state.value.snapshot?.steps?.last()?.status)
            assertFalse(label(root, "agent_status").text.contains("completed"))
            assertFalse(label(root, "agent_result").text.contains("PRIVATE"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun notionStudyIsTruthfullyUnavailableAndMakesNoTaskProviderCall() {
        val controller = Robolectric.buildActivity(AgentTasksActivity::class.java).setup()
        try {
            idle(); assertTrue(label(controller.get().window.decorView, "agent_notion_unavailable").text.contains("NOT CONNECTED"))
            assertEquals(0, reads.taskReads); assertEquals(0, reads.clocks)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun realLocalReadPortValidatesBatteryAndHasNoTasksAdapter() = runBlocking {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            var level = 25; var scale = 50
            override fun getApplicationContext(): Context = this
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent =
                Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale)
        }
        val port = AndroidAgentReadPort(context)
        assertEquals(AgentData.Battery(50), port.battery().data)
        context.scale = 0; assertEquals(AgentStatus.FAILED, port.battery().status)
        context.scale = 101; context.level = 102; assertEquals(AgentStatus.FAILED, port.battery().status)
        assertEquals(AgentStatus.SUCCEEDED, port.clock().status)
        val pin = AgentConnectionPin("notconnected", 1, "workspace", "database", "database", 1, "status", setOf("Todo"))
        assertEquals(AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_CONNECTED"), port.tasks(pin))
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun button(root: View, tag: String) = views(root).single { it.tag == tag } as Button
    private fun label(root: View, tag: String) = views(root).single { it.tag == tag } as TextView
}
