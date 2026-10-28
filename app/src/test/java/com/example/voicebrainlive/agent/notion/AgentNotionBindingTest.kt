package com.example.voicebrainlive.agent.notion

import com.example.voicebrainlive.agent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Requires the unapplied additive planner binding patch, not old snapshot test evidence. */
class AgentNotionBindingTest {
    private fun plan(pin: AgentConnectionPin) = AgentGoalTemplates.interpret(AgentGoal.StudyDraft(pin, 30), "study", "study_key", 1_000)
    @Test fun legacyFixtureRemainsAdmissibleButCannotEnterNewReaderWithoutFullBinding() = runBlocking {
        val legacy = AgentConnectionPin("legacy", 1, "workspace", "resource", "data_source", 1, "business", setOf("Todo"))
        assertEquals(legacy, AgentPlanAdmission.admit(plan(legacy), 1_000).plan.steps.first().connection)
        profileRejects(ProfileFailure.NOT_CONNECTED) { fullAgentBinding(legacy) }
    }
    @Test fun fullLocalNamespaceAllowsExplicitEmptyWorkspaceAndRejectsEveryMirrorMismatch(): Unit = runBlocking {
        val f = NotionFixture(); val full = f.pin(f.profile("A")); val pin = agentPin(full)
        assertEquals(pin, AgentPlanAdmission.admit(plan(pin), 1_000).plan.steps.first().connection)
        for (mismatch in listOf(pin.copy(connectionId = "other"), pin.copy(revision = 9), pin.copy(workspaceId = "guessed_provider_workspace"),
            pin.copy(resourceId = "other"), pin.copy(resourceKind = "database"), pin.copy(schemaRevision = 9),
            pin.copy(businessStatusPropertyId = "sync"), pin.copy(unfinishedValues = setOf("Synced")))) {
            assertThrows(Exception::class.java) { AgentPlanAdmission.admit(plan(mismatch), 1_000) }
        }
        val legacyEmpty = pin.copy(notionReadBinding = null)
        assertThrows(IllegalArgumentException::class.java) { AgentPlanAdmission.admit(plan(legacyEmpty), 1_000) }
    }
    @Test fun admittedFullBindingOwnsMutableStatusSetsAndCannotBeRetargeted() = runBlocking {
        val f = NotionFixture(); val full = f.pin(f.profile("A")); val statuses = linkedSetOf("Todo")
        val raw = full.copy(schema = full.schema.copy(unfinishedValues = statuses)); val rawAgent = agentPin(raw).copy(unfinishedValues = statuses,
            notionReadBinding = raw)
        val frozen = AgentPlanAdmission.admit(plan(rawAgent), 1_000).plan.steps.first().connection!!
        statuses.clear(); statuses += "Synced"
        assertEquals(setOf("Todo"), frozen.notionReadBinding!!.schema.unfinishedValues)
        assertEquals(setOf("Todo"), frozen.unfinishedValues)
    }
    @Test fun fakeStudyProjectionUsesExistingRunnerAndSyncPendingIsNotBusinessCompletion() = runBlocking {
        val f = NotionFixture(); val full = f.pin(f.profile("A")); val pin = agentPin(full)
        val local = object : AgentReadPort {
            override suspend fun clock() = AgentObservation(AgentStatus.BLOCKED)
            override suspend fun battery() = AgentObservation(AgentStatus.BLOCKED)
            override suspend fun tasks(connection: AgentConnectionPin) = AgentObservation(AgentStatus.BLOCKED)
        }
        val port = SyntheticStudyReadPort(local, f.repo, SyntheticNotionPort { _, _ -> f.page(full, listOf(
            f.row(full, "unfinished").copy(businessStatus = "Todo", syncStatus = "Synced"),
            f.row(full, "finished").copy(businessStatus = "Done", syncStatus = "Pending"))) },
            { CapturedStudyReadContext("study", "tasks", 61_000) { f.owner } }, { f.now }, { "request" })
        val runner = AgentRunner(port, { f.owner }, { candidate -> try { f.repo.isCurrentCached(fullAgentBinding(candidate)) } catch (_: Exception) { false } }, wallClock = { f.now })
        val result = runner.run(plan(pin)); assertEquals(AgentStatus.SUCCEEDED, result.status)
        val draft = result.steps.last().result as AgentData.Draft
        assertEquals(listOf("unfinished"), draft.sourceRowIds); assertEquals(EvidenceSource.SYNTHETIC_FIXTURE, draft.connection!!.notionReadBinding!!.evidence)
        runner.clearResultPayloads("study")
    }
}
