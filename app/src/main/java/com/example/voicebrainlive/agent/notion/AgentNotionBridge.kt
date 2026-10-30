package com.example.voicebrainlive.agent.notion

import com.example.voicebrainlive.agent.AgentConnectionPin
import com.example.voicebrainlive.agent.AgentData
import com.example.voicebrainlive.agent.AgentObservation
import com.example.voicebrainlive.agent.AgentReadPort
import com.example.voicebrainlive.agent.AgentStatus
import com.example.voicebrainlive.agent.AgentTaskRow
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Complete immutable profile binding; legacy/null bindings fail closed. */
fun agentPin(pin: ConnectionReadPin): AgentConnectionPin {
    val frozen = freezeReadPin(pin)
    return AgentConnectionPin(frozen.profileId, frozen.profileRevision, "", frozen.resource.dataSourceId, "data_source",
        frozen.schema.revision, frozen.schema.businessStatus.propertyId, frozen.schema.unfinishedValues, frozen)
}
fun fullAgentBinding(pin: AgentConnectionPin): ConnectionReadPin {
    val full = pin.notionReadBinding?.let(::freezeReadPin) ?: throw ProfileStoreException(ProfileFailure.NOT_CONNECTED)
    profileCheck(pin.connectionId == full.profileId && pin.revision == full.profileRevision && pin.workspaceId.isEmpty() &&
        pin.resourceKind == "data_source" && pin.resourceId == full.resource.dataSourceId &&
        pin.schemaRevision == full.schema.revision && pin.businessStatusPropertyId == full.schema.businessStatus.propertyId &&
        pin.unfinishedValues == full.schema.unfinishedValues, ProfileFailure.STALE_BINDING)
    return full
}
/** Trusted facade captures actual existing run/lease/deadline before async reads. Not a grant. */
class CapturedStudyReadContext(val taskId: String, val stepId: String, val deadlineMillis: Long,
    val ownerCurrent: () -> Boolean) {
    override fun toString() = "CapturedStudyReadContext(taskId=$taskId, stepId=$stepId)"
}
class SyntheticStudyReadPort(private val local: AgentReadPort, private val profiles: NotionProfilesRepository,
    private val fakePort: SyntheticNotionPort, private val captureContext: () -> CapturedStudyReadContext?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIds: () -> String = { UUID.randomUUID().toString() }) : AgentReadPort {
    override suspend fun clock() = local.clock()
    override suspend fun battery() = local.battery()
    override suspend fun tasks(connection: AgentConnectionPin): AgentObservation = try {
        val context = captureContext() ?: throw ProfileStoreException(ProfileFailure.CANCELLED)
        profileCheck(context.ownerCurrent(), ProfileFailure.CANCELLED)
        val binding = fullAgentBinding(connection)
        val capturedPin = connection.copy(unfinishedValues = Collections.unmodifiableSet(LinkedHashSet(connection.unfinishedValues)),
            notionReadBinding = binding)
        val reader = PinnedFakeNotionReader(profiles, fakePort, context.ownerCurrent, clock)
        val result = reader.read(TaskReadRequest(requestIds(), context.taskId, context.stepId, binding,
            ReadBudget(), context.deadlineMillis))
        profileCheck(context.ownerCurrent() && profiles.isCurrent(binding), ProfileFailure.STALE_BINDING)
        profileCheck(context.ownerCurrent(), ProfileFailure.CANCELLED)
        when (result) {
            is TaskReadResult.Complete -> AgentObservation(AgentStatus.SUCCEEDED, AgentData.Tasks(capturedPin,
                profileList(result.rows.map { AgentTaskRow(it.pageId, it.title, it.businessStatus, it.syncStatus, it.priority) }), true))
            is TaskReadResult.Incomplete -> AgentObservation(AgentStatus.BLOCKED, AgentData.Tasks(capturedPin,
                profileList(result.rows.map { AgentTaskRow(it.pageId, it.title, it.businessStatus, it.syncStatus, it.priority) }), false), "SYNTHETIC_PARTIAL")
            is TaskReadResult.Failed -> AgentObservation(AgentStatus.BLOCKED, errorCode = "SYNTHETIC_${result.failure.name}")
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (safe: ProfileStoreException) { AgentObservation(AgentStatus.BLOCKED, errorCode = "SYNTHETIC_${safe.failure.name}") }
    catch (_: Exception) { AgentObservation(AgentStatus.FAILED, errorCode = "SYNTHETIC_READER_UNAVAILABLE") }
}
