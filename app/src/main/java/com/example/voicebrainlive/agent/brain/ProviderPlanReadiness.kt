package com.example.voicebrainlive.agent.brain

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Local recorded-frame protocol, NOT a claim about Gemini's wire format. No network client exists here. */
internal enum class ProviderReadinessMode { DISABLED, RECORDED_HOST_ONLY }
internal enum class ProviderPlanError { DISCONNECTED, AUTHENTICATION, RATE_LIMITED, QUOTA, UNAVAILABLE }
internal data class ProviderPlanFrame(val session: String, val call: Int, val sequence: Int,
    val fragment: String = "", val complete: Boolean = false, val tokens: Int? = null,
    val costMicros: Long? = null, val error: ProviderPlanError? = null) {
    override fun toString() = "ProviderPlanFrame(sequence=$sequence, complete=$complete, content=<redacted>)"
}
internal fun interface RecordedPlanFrames { fun frames(goal: String, session: String, call: Int): Flow<ProviderPlanFrame> }
internal data class ProviderTextFallback(val reason: String, val mode: String = "TEXT", val executionConfirmed: Boolean = false)

/** Completes and bounds a stream before the existing strict parser/planner sees it.
 * Confirmed memory is used locally by StructuredBrainModel; it is never forwarded to this port.
 * Defaults fail closed. Only explicit host fixtures can inject a recorded port. No live factory or paid request.
 */
internal class ProviderPlanReadiness(private val session: String, private val current: () -> Boolean,
    private val mode: ProviderReadinessMode = ProviderReadinessMode.DISABLED,
    private val recorded: RecordedPlanFrames? = null, private val timeoutMillis: Long = 1_500,
    private val maxTokens: Int = 4_096, private val maxCostMicros: Long = 0) : BrainStructuredPort {
    private val calls = AtomicInteger()
    init { require(session.matches(Regex("[A-Za-z0-9_-]{1,80}")) && timeoutMillis in 1..1_500 && maxTokens in 1..4_096 && maxCostMicros == 0L) }
    override suspend fun propose(goal: String, confirmedContext: String): BrainModelOutput {
        brainCheck(mode == ProviderReadinessMode.RECORDED_HOST_ONLY && recorded != null, "PROVIDER_DISABLED")
        brainCheck(current(), "STALE_PROVIDER_SESSION")
        brainCheck(goal.isNotBlank() && goal.length <= 2_000, "GOAL_TEXT_BUDGET")
        val call = calls.incrementAndGet(); brainCheck(call <= 16, "PROVIDER_CALL_BUDGET")
        val result = try { withTimeoutOrNull(timeoutMillis) {
            val text = StringBuilder(); var sequence = 0; var finished = false; var tokens: Int? = null; var cost: Long? = null
            requireNotNull(recorded).frames(goal, session, call).collect { frame ->
                currentCoroutineContext().ensureActive(); brainCheck(current(), "STALE_PROVIDER_SESSION")
                brainCheck(!finished && frame.session == session && frame.call == call && frame.sequence == sequence++, "PROVIDER_FRAME_OWNER_OR_ORDER")
                brainCheck(sequence <= 64 && frame.fragment.length <= BrainProposalSchema.MAX_BYTES, "PROVIDER_FRAME_BUDGET")
                frame.error?.let { throw BrainFailure("PROVIDER_${it.name}") }
                brainCheck(frame.complete || (frame.tokens == null && frame.costMicros == null), "PROVIDER_PARTIAL_USAGE")
                text.append(frame.fragment); brainCheck(text.toString().toByteArray(Charsets.UTF_8).size <= BrainProposalSchema.MAX_BYTES, "MODEL_OUTPUT_BYTES")
                if (frame.complete) {
                    brainCheck(frame.tokens != null && frame.tokens in 0..maxTokens && frame.costMicros != null && frame.costMicros in 0..maxCostMicros, "PROVIDER_USAGE_UNVERIFIED_OR_EXCEEDED")
                    tokens = frame.tokens; cost = frame.costMicros; finished = true
                }
            }
            brainCheck(finished && current(), "PROVIDER_INCOMPLETE_OR_STALE")
            val wire = text.toString(); brainCheck(BrainProposalSchema.decode(wire).goal == goal, "MODEL_GOAL_MISMATCH")
            BrainModelOutput(wire, tokens, cost)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: BrainFailure) { throw safe }
        catch (_: Exception) { throw BrainFailure("PROVIDER_UNAVAILABLE") }
        return result ?: throw BrainFailure("PROVIDER_TIMEOUT_NO_RETRY")
    }
    suspend fun prepareConversation(agent: ConversationAgentSession, callId: String, goal: String): String =
        agent.propose(callId, propose(goal, "").json, goal)
    companion object {
        val safeProviderCodes = ProviderPlanError.entries.map { "PROVIDER_${it.name}" }.toSet() + setOf(
            "PROVIDER_DISABLED", "PROVIDER_TIMEOUT_NO_RETRY", "STALE_PROVIDER_SESSION", "PROVIDER_CALL_BUDGET",
            "PROVIDER_FRAME_OWNER_OR_ORDER", "PROVIDER_FRAME_BUDGET", "PROVIDER_PARTIAL_USAGE",
            "PROVIDER_USAGE_UNVERIFIED_OR_EXCEEDED", "PROVIDER_INCOMPLETE_OR_STALE")
        fun textFallback(error: Throwable) = ProviderTextFallback(
            (error as? BrainFailure)?.code?.takeIf { it in safeProviderCodes } ?: "PROVIDER_UNAVAILABLE")
    }
}
