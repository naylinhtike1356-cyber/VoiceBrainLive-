package com.example.voicebrainlive.privatefixture

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.memory.confirmed.ConfirmedMemoryContextConsent
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Existing session/parser/planner/runner, fixed offline proposals and synthetic source. No speech/network engine. */
class PrivateTestConversationActivity : PrivateTestScreen() {
    private var session: ConversationAgentSession? = null
    private var job: Job? = null
    private var reviewId: String? = null
    private lateinit var goal: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var run: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button("Back", "private_conversation_back") { finish() }
        label("SIMULATION: choose a fixed proposal recipe. This is not model interpretation or real voice. Preview runs nothing; Run saves an encrypted TEST-only checkpoint and executes fake read steps. Reminder/message steps remain blocked proposals.", "private_conversation_scope")
        goal = EditText(this).apply {
            tag = "private_goal"; contentDescription = "Synthetic conversation goal"; hint = "Synthetic Burmese / 한국어 / English goal"
            filters = arrayOf(android.text.InputFilter.LengthFilter(2_000)); isSaveEnabled = false; isSaveFromParentEnabled = false
        }; page.addView(goal)
        button("Preview fixed local-status simulation", "private_preview_status") { preview(false) }
        button("Preview fixed study/reminder simulation", "private_preview_study") { preview(true) }
        run = button("Run reviewed FAKE read steps", "private_run") {
            val id = reviewId ?: return@button
            reviewId = null; run.isEnabled = false
            operate { it.runFromUser(id) }
        }; run.isEnabled = false
        button("Cancel simulation", "private_cancel") { cancel("Cancelled. Fresh preview required; no real action executed.") }
        status = label("Choose a synthetic recipe.", "private_conversation_status")
        result = label("", "private_conversation_result")
    }
    override fun onResume() {
        super.onResume()
        val owner = epoch; val app = application as PrivateTestApplication
        lateinit var owned: ConversationAgentSession
        owned = ConversationAgentSession("private_" + UUID.randomUUID().toString(), { foreground && epoch == owner },
            { app.conversationStore() }, BrainMemoryAccess({ ConfirmedMemoryContextConsent.isEnabled(this) }, { app.confirmedMemoryStore.list() }, System::currentTimeMillis),
            BrainSamples.reads(), BrainSamples.connection, { it == BrainSamples.connection }, display = { text ->
                val publication = owned.publicationGuard()
                runOnUiThread { if (foreground && epoch == owner && publication()) result.text = "SIMULATION ONLY\n$text" }
            })
        session = owned
    }
    override fun onPause() {
        // Revoke lifecycle ownership before asynchronous callbacks can publish; do not retain private view state.
        foreground = false; epoch++; cancel("Paused; fresh preview required."); session = null
        goal.text.clear(); result.text = ""; super.onPause()
    }
    private fun cancel(message: String) {
        session?.interrupt(); job?.cancel(); job = null; reviewId = null
        if (::run.isInitialized) run.isEnabled = false
        if (::status.isInitialized) status.text = message
        if (::result.isInitialized) result.text = ""
    }
    private fun preview(study: Boolean) {
        if (job?.isActive == true) return
        val text = goal.text.toString().ifBlank { if (study) BrainSamples.BURMESE_GOAL else "စက်အခြေအနေစစ်ပေး" }
        val wire = proposal(text, study)
        operate { it.propose(UUID.randomUUID().toString(), wire, text) }
    }
    private fun operate(action: suspend (ConversationAgentSession) -> String) {
        val owned = session ?: return; val owner = epoch
        if (job?.isActive == true) return
        reviewId = null; run.isEnabled = false
        job = lifecycleScope.launch {
            try {
                val reply = action(owned)
                if (foreground && epoch == owner) {
                    status.text = "SIMULATION · $reply"
                    reviewId = if (reply.startsWith("AGENT:DRAFT;")) reply.substringAfter("task=").substringBefore(';') else null
                    run.isEnabled = reviewId != null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (foreground && epoch == owner) status.text = "SIMULATION unavailable; no real action executed." }
        }
    }
    private fun proposal(text: String, study: Boolean): String {
        fun step(id: String, tool: String, output: String, deps: List<String> = emptyList(), args: Map<String, String> = emptyMap()) = JSONObject()
            .put("id", id).put("tool", tool).put("expected", output).put("dependencies", JSONArray(deps)).put("arguments", JSONObject(args))
        val steps = if (!study) listOf(step("clock", "get_current_time", "CLOCK"), step("battery", "get_battery_status", "BATTERY"),
            step("report", "agent_local_report", "REPORT", listOf("clock", "battery"), mapOf("clockStep" to "clock", "batteryStep" to "battery")))
        else listOf(step("tasks", "agent_read_tasks", "TASKS"),
            step("priorities", "agent_prioritize_tasks", "PRIORITIES", listOf("tasks"), mapOf("sourceStep" to "tasks")),
            step("draft", "agent_study_draft", "DRAFT", listOf("priorities"), mapOf("sourceStep" to "priorities", "minutes" to "confirmed_preference")),
            step("reminder", "set_reminder", "APPROVAL_PROPOSAL", listOf("draft")).put("target", "SYNTHETIC study reminder").put("value", "Proposal only; never scheduled"))
        return JSONObject().put("version", 1).put("goal", text).put("steps", JSONArray(steps)).toString()
    }
}
