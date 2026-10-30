package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.AgentCapabilities
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Untrusted model output is data. No owner, permission, grant, pin or retry fields exist on this wire. */
data class BrainModelOutput(val json: String, val reportedTokens: Int? = null, val reportedCostMicros: Long? = null)
fun interface BrainStructuredPort {
    suspend fun propose(goal: String, confirmedContext: String): BrainModelOutput
}
data class BrainProposal(val goal: String, val candidate: BrainInterpretation.Candidate)

object BrainProposalSchema {
    const val MAX_BYTES = 16_384
    /** Streaming reader rejects duplicate keys, coercions, extra fields, trailing content and deep nesting. */
    fun decode(wire: String): BrainProposal {
        brainCheck(wire.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "MODEL_OUTPUT_BYTES")
        try {
            val reader = JsonReader(StringReader(wire)).apply { isLenient = false }
            val root = reader.use {
                val value = value(it, 0)
                brainCheck(it.peek() == JsonToken.END_DOCUMENT, "MODEL_TRAILING_CONTENT")
                obj(value)
            }
            keys(root, setOf("version", "goal", "steps"))
            brainCheck(root["version"] == NumberLiteral("1"), "MODEL_SCHEMA_VERSION")
            val goal = string(root["goal"])
            brainCheck(goal.isNotBlank() && goal.length <= 2_000, "GOAL_TEXT_BUDGET")
            val steps = array(root["steps"])
            brainCheck(steps.size in 1..8, "MODEL_STEP_BUDGET")
            return BrainProposal(goal, BrainInterpretation.Candidate(steps.map { raw ->
                val step = obj(raw)
                keys(step, setOf("id", "tool", "dependencies", "arguments", "expected"), setOf("target", "value"))
                val tool = string(step["tool"])
                brainCheck(AgentCapabilities.get(tool) != null, "UNSUPPORTED_TOOL")
                val deps = array(step["dependencies"]).map(::string)
                brainCheck(deps.size <= 8 && deps.distinct().size == deps.size, "MODEL_DEPENDENCIES")
                val args = obj(step["arguments"])
                brainCheck(args.size <= 4, "MODEL_ARGUMENT_BUDGET")
                val expected = BrainOutput.entries.singleOrNull { it.name == string(step["expected"]) }
                    ?: throw BrainFailure("MODEL_OUTPUT_TYPE")
                BrainProposedStep(string(step["id"]), tool, deps.toSet(), args.mapValues { string(it.value) },
                    step["target"]?.let(::string), step["value"]?.let(::string), expected)
            }))
        } catch (safe: BrainFailure) { throw safe }
        catch (_: Exception) { throw BrainFailure("MODEL_MALFORMED_JSON") }
    }

    private data class NumberLiteral(val text: String)
    private fun value(reader: JsonReader, depth: Int): Any? {
        brainCheck(depth <= 5, "MODEL_NESTING_BUDGET")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject(); val result = linkedMapOf<String, Any?>()
                while (reader.hasNext()) {
                    brainCheck(result.size < 12, "MODEL_COLLECTION_BUDGET")
                    val name = reader.nextName()
                    brainCheck(!result.containsKey(name), "MODEL_DUPLICATE_KEY")
                    result[name] = value(reader, depth + 1)
                }
                reader.endObject(); result
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray(); val result = mutableListOf<Any?>()
                while (reader.hasNext()) { brainCheck(result.size < 8, "MODEL_COLLECTION_BUDGET"); result += value(reader, depth + 1) }
                reader.endArray(); result
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> NumberLiteral(reader.nextString())
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> throw BrainFailure("MODEL_JSON_TYPE")
        }
    }
    @Suppress("UNCHECKED_CAST") private fun obj(value: Any?) = value as? Map<String, Any?> ?: throw BrainFailure("MODEL_OBJECT_REQUIRED")
    private fun array(value: Any?) = value as? List<*> ?: throw BrainFailure("MODEL_ARRAY_REQUIRED")
    private fun string(value: Any?) = value as? String ?: throw BrainFailure("MODEL_STRING_REQUIRED")
    private fun keys(value: Map<String, Any?>, required: Set<String>, optional: Set<String> = emptySet()) {
        brainCheck(value.keys.containsAll(required) && value.keys.all { it in required || it in optional }, "MODEL_FIELDS")
    }
}

/** Planner supplies the timeout/call budget. This adapter makes zero network requests itself.
 * Reported usage is checked when supplied; absent Live usage is UNKNOWN, never claimed to be free.
 */
class StructuredBrainModel(private val port: BrainStructuredPort, private val maxReportedTokens: Int = 4_096,
    private val maxReportedCostMicros: Long = 0) : BrainGoalModel {
    override suspend fun propose(request: BrainGoalRequest, confirmedContext: String): BrainInterpretation {
        val output = try { port.propose(request.text, confirmedContext) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (safe: BrainFailure) { throw BrainFailure(if (safe.code in ProviderPlanReadiness.safeProviderCodes) safe.code else "MODEL_PROVIDER_UNAVAILABLE") }
            catch (_: Exception) { throw BrainFailure("MODEL_PROVIDER_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        brainCheck(output.reportedTokens == null || output.reportedTokens in 0..maxReportedTokens, "MODEL_TOKEN_BUDGET")
        brainCheck(output.reportedCostMicros == null || output.reportedCostMicros in 0..maxReportedCostMicros, "MODEL_COST_BUDGET")
        val proposal = BrainProposalSchema.decode(output.json)
        brainCheck(proposal.goal == request.text, "MODEL_GOAL_MISMATCH")
        // A single documented local preference placeholder; context never becomes a command or confirmed fact.
        return proposal.candidate.copy(steps = proposal.candidate.steps.map { step ->
            if (step.tool == "agent_study_draft" && step.arguments["minutes"] == "confirmed_preference")
                step.copy(arguments = step.arguments + ("minutes" to if (confirmedContext.contains("short sessions", true)) "20" else "30"))
            else step
        })
    }
}
