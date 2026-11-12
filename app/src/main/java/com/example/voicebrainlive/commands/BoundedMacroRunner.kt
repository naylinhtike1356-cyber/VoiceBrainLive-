package com.example.voicebrainlive.commands

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Bounded macro contract. No Android adapter, capability token, storage write or provider access. */
enum class MacroLeafRisk { SAFE, SENSITIVE, UNKNOWN }
enum class MacroOutcomeStatus { VERIFIED, COMPLETED, WAITING_FOR_APPROVAL, BLOCKED, FAILED, OUTCOME_UNKNOWN, UNVERIFIED }
data class MacroLeaf(val command: String, val target: String?, val value: String?, val risk: MacroLeafRisk)
data class MacroLeafResult(val status: MacroOutcomeStatus, val message: String = "")
data class MacroStepObservation(val command: String, val result: MacroLeafResult)
data class MacroRunResult(val status: MacroOutcomeStatus, val reason: String,
                          val calls: Int, val observations: List<MacroStepObservation>)

/** Preserve exact common-guard output and its outer prefix; never manufacture an approval ID. */
fun MacroRunResult.renderLegacy(): String {
    val captured = observations.lastOrNull()?.result?.message.orEmpty()
    val prefix = when (status) {
        MacroOutcomeStatus.WAITING_FOR_APPROVAL -> "NEEDS_APPROVAL:"
        MacroOutcomeStatus.BLOCKED -> "BLOCKED:"
        MacroOutcomeStatus.FAILED -> "FAILED:"
        MacroOutcomeStatus.OUTCOME_UNKNOWN -> "FAILED/OUTCOME_UNKNOWN:"
        MacroOutcomeStatus.UNVERIFIED, MacroOutcomeStatus.VERIFIED -> "UNVERIFIED:"
        MacroOutcomeStatus.COMPLETED -> "COMPLETED:"
    }
    val summary = "$calls macro step(s) dispatched. $reason. No automatic retry or resume."
    // WAITING has no meaningful grant unless the actual guard produced it.
    if (status == MacroOutcomeStatus.WAITING_FOR_APPROVAL && !captured.startsWith(prefix))
        return "BLOCKED: Approval preparation lacked a captured common-guard result. $summary $captured"
    val legacy = if (captured.startsWith(prefix)) "$captured\nMacro status: $summary"
        else "$prefix $summary $captured"
    if (observations.isEmpty()) return legacy
    val steps = observations.mapIndexed { index, observation ->
        val label = MacroDefinitions.preview(observation.command).removePrefix("1. ")
        val status = when (observation.result.status) {
            MacroOutcomeStatus.VERIFIED -> "စက်အခြေအနေ ပြန်စစ်ပြီး"
            MacroOutcomeStatus.WAITING_FOR_APPROVAL -> "အတည်ပြုချက် စောင့်နေသည်"
            MacroOutcomeStatus.BLOCKED -> "ရပ်ထားသည်"
            MacroOutcomeStatus.FAILED -> "မအောင်မြင်ပါ"
            else -> "ရလဒ် အတည်မပြုနိုင်သေးပါ"
        }
        "${index + 1}. $label · $status"
    }.joinToString("\n")
    return "$legacy\nလုပ်ဆောင်ခဲ့သော အဆင့်များ\n$steps"
}

/** String presence/handled/completed text cannot prove that an effect happened. */
object OpaqueMacroResults {
    fun classify(message: String): MacroLeafResult {
        val status = when {
            message.startsWith("NEEDS_APPROVAL:") -> MacroOutcomeStatus.WAITING_FOR_APPROVAL
            message.startsWith("BLOCKED:") -> MacroOutcomeStatus.BLOCKED
            message.startsWith("FAILED/OUTCOME_UNKNOWN:") -> MacroOutcomeStatus.OUTCOME_UNKNOWN
            message.startsWith("FAILED:") -> MacroOutcomeStatus.FAILED
            else -> MacroOutcomeStatus.UNVERIFIED
        }
        return MacroLeafResult(status, message)
    }
}

/**
 * Every adopted dispatch MUST call the existing PhoneCommandExecutor.executeAsync common guard.
 * Only a trusted typed adapter with actual completion evidence may return VERIFIED. The default
 * legacy string adapter returns UNVERIFIED for opaque results and therefore stops after that leaf.
 * Timeouts are cooperative; they cannot undo effects or interrupt an arbitrary blocking adapter.
 */
class BoundedMacroRunner(
    private val lookupDefinition: (String) -> String?,
    private val classifyRisk: (String) -> MacroLeafRisk,
    private val dispatchThroughCommonGuard: suspend (MacroLeaf) -> MacroLeafResult,
    private val monotonicMillis: () -> Long,
    private val durationMillis: Long = 30_000,
    private val perLeafMillis: Long = 5_000,
    private val fallbackValueToTarget: Boolean = true,
    private val allowNumericShorthand: Boolean = false
) {
    private data class FrozenProgram(val definitions: Map<String, String>, val leaves: List<MacroLeaf>)
    private class Rejected(val reason: String) : IllegalArgumentException(reason)

    init { require(durationMillis in 1..60_000 && perLeafMillis in 1..30_000) }

    suspend fun run(name: String): MacroRunResult {
        coroutineContext.ensureActive()
        val started = monotonicMillis()
        require(started >= 0 && started <= Long.MAX_VALUE - durationMillis)
        val deadline = started + durationMillis
        val program = try { preflight(name) }
        catch (rejected: Rejected) { return MacroRunResult(MacroOutcomeStatus.BLOCKED, rejected.reason, 0, emptyList()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return MacroRunResult(MacroOutcomeStatus.BLOCKED, "DEFINITION_UNAVAILABLE", 0, emptyList()) }
        var lastObserved = monotonicMillis()
        if (lastObserved < started || lastObserved >= deadline) {
            return MacroRunResult(MacroOutcomeStatus.BLOCKED, "PREFLIGHT_BUDGET_OR_CLOCK_INVALID", 0, emptyList())
        }
        val observations = mutableListOf<MacroStepObservation>()
        var calls = 0
        for (leaf in program.leaves) {
            coroutineContext.ensureActive()
            val before = monotonicMillis()
            if (before < lastObserved || before >= deadline) {
                return MacroRunResult(MacroOutcomeStatus.BLOCKED, "DEADLINE_OR_CLOCK_INVALID", calls, observations.toList())
            }
            // Re-check captured definitions before any subsequent leaf; no silent edited sequence.
            val changed = try { program.definitions.any { (id, captured) -> lookupDefinition(id) != captured } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return MacroRunResult(MacroOutcomeStatus.BLOCKED, "DEFINITION_UNAVAILABLE", calls, observations.toList()) }
            if (changed) {
                return MacroRunResult(MacroOutcomeStatus.BLOCKED, "MACRO_CHANGED_REVIEW_AGAIN", calls, observations.toList())
            }
            coroutineContext.ensureActive()
            calls++ // Count a dispatch even when it fails or has an uncertain result.
            val raw = try {
                withTimeoutOrNull(minOf(perLeafMillis, deadline - before)) { dispatchThroughCommonGuard(leaf) }
                    ?: MacroLeafResult(MacroOutcomeStatus.OUTCOME_UNKNOWN, "COOPERATIVE_TIMEOUT_NO_RETRY")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MacroLeafResult(MacroOutcomeStatus.OUTCOME_UNKNOWN, "DISPATCH_FAILED_NO_RETRY") }
            val after = monotonicMillis()
            lastObserved = after
            val result = when {
                after < before || after >= deadline -> MacroLeafResult(MacroOutcomeStatus.OUTCOME_UNKNOWN, "LATE_RESULT_NO_RETRY")
                leaf.risk == MacroLeafRisk.SENSITIVE && (raw.status == MacroOutcomeStatus.VERIFIED || raw.status == MacroOutcomeStatus.COMPLETED) ->
                    MacroLeafResult(MacroOutcomeStatus.BLOCKED, "SENSITIVE_LEAF_MUST_STOP_AT_GUARD")
                else -> raw
            }
            observations += MacroStepObservation(leaf.command, result)
            val isSuccess = result.status == MacroOutcomeStatus.VERIFIED || result.status == MacroOutcomeStatus.COMPLETED
            if (!isSuccess || leaf.risk != MacroLeafRisk.SAFE) {
                return MacroRunResult(result.status, "STOPPED_NO_AUTOMATIC_RESUME_OR_RETRY", calls, observations.toList())
            }
        }
        return MacroRunResult(MacroOutcomeStatus.COMPLETED, "ALL_TYPED_LEAVES_VERIFIED", calls, observations.toList())
    }

    /** Complete nested expansion and all validation occur before the first leaf dispatch. */
    private fun preflight(root: String): FrozenProgram {
        val captured = linkedMapOf<String, String>()
        val leaves = mutableListOf<MacroLeaf>()
        fun expand(name: String, path: List<String>) {
            val id = name.trim().lowercase(Locale.ROOT)
            if (id.isBlank() || id.length > MAX_NAME_CHARS) throw Rejected("INVALID_MACRO_NAME")
            if (id in path) throw Rejected("MACRO_CYCLE")
            if (path.size >= MAX_DEPTH) throw Rejected("MACRO_DEPTH_LIMIT")
            val sequence = captured[id] ?: lookupDefinition(id)?.also { captured[id] = it }
                ?: throw Rejected("MACRO_NOT_FOUND")
            if (sequence.isBlank() || sequence.length > MAX_SEQUENCE_CHARS) throw Rejected("MACRO_LENGTH_LIMIT")
            val parsed = parse(sequence, fallbackValueToTarget || path.isNotEmpty())
            if (parsed.isEmpty()) throw Rejected("EMPTY_MACRO")
            parsed.forEach { leaf ->
                if (leaf.command == "run_macro") {
                    expand(leaf.target ?: leaf.value ?: throw Rejected("MISSING_NESTED_MACRO"), path + id)
                } else {
                    leaves += leaf
                    if (leaves.size > MAX_LEAVES) throw Rejected("MACRO_LEAF_LIMIT")
                }
            }
        }
        expand(root, emptyList())
        if (leaves.isEmpty()) throw Rejected("EMPTY_EXPANSION")
        return FrozenProgram(captured.toMap(), leaves.toList())
    }

    private fun parse(sequence: String, useTargetFallback: Boolean): List<MacroLeaf> {
        var remaining = sequence
        val steps = mutableListOf<MacroLeaf>()
        while (remaining.isNotBlank()) {
            val command = remaining.substringBefore(':').substringBefore(';').trim().lowercase(Locale.ROOT)
            if (!command.matches(Regex("[a-z0-9_]{1,64}"))) throw Rejected("INVALID_COMMAND")
            val risk = classifyRisk(command)
            if (risk == MacroLeafRisk.UNKNOWN) throw Rejected("UNKNOWN_COMMAND")
            val separator = remaining.indexOf(';')
            // Preserve the existing sensitive-consumes-remainder rule: ';' may be exact body text.
            val step = if (risk == MacroLeafRisk.SENSITIVE || separator < 0) remaining
                else remaining.substring(0, separator)
            val parts = step.trimStart().split(':', limit = 3)
            val explicitNumber = command in NUMBER_VALUE_COMMANDS && parts.getOrNull(1).isNullOrEmpty() &&
                parts.getOrNull(2)?.matches(NUMERIC_VALUE) == true
            if (risk == MacroLeafRisk.SAFE && separator >= 0 &&
                (command in FREE_TEXT_COMMANDS || (parts.size == 3 && !explicitNumber))) {
                throw Rejected("AMBIGUOUS_PAYLOAD_SEPARATOR")
            }
            val target = parts.getOrNull(1)
            val shorthandNumber = allowNumericShorthand && !useTargetFallback && command in NUMBER_VALUE_COMMANDS &&
                parts.size == 2 && target?.matches(NUMERIC_VALUE) == true
            val value = (parts.getOrNull(2) ?: target.takeIf { useTargetFallback || shorthandNumber })
                ?.let { raw ->
                    if (command == "set_music_sleep_timer") {
                        raw.map { if (it in '၀'..'၉') '0' + (it - '၀') else it }.joinToString("")
                    } else raw
                }
            if ((target?.length ?: 0) > MAX_ARGUMENT_CHARS || (value?.length ?: 0) > MAX_ARGUMENT_CHARS)
                throw Rejected("ARGUMENT_LENGTH_LIMIT")
            val commandTarget = if (explicitNumber || shorthandNumber ||
                (command == "set_music_sleep_timer" && parts.size == 2 && value?.toIntOrNull() != null)) null else target
            steps += MacroLeaf(command, commandTarget, value, risk)
            if (steps.size > MAX_LEAVES) throw Rejected("MACRO_STEP_LIMIT")
            if (risk == MacroLeafRisk.SENSITIVE || separator < 0) break
            remaining = remaining.substring(separator + 1).trimStart(';')
        }
        return steps.toList()
    }

    companion object {
        const val MAX_LEAVES = 12
        const val MAX_DEPTH = 4
        const val MAX_SEQUENCE_CHARS = 1_000
        const val MAX_ARGUMENT_CHARS = 200
        const val MAX_NAME_CHARS = 160
        private val NUMBER_VALUE_COMMANDS = setOf("set_volume", "set_music_sleep_timer")
        private val NUMERIC_VALUE = Regex("[0-9၀-၉]{1,3}%?")
        private val FREE_TEXT_COMMANDS = setOf("save_note", "add_note", "remember", "remember_fact", "add_memory",
            "save_preference", "record_correction", "save_correction", "create_macro", "set_reminder", "add_routine",
            "add_scheduled_routine", "add_meeting_note", "take_note")
    }
}
