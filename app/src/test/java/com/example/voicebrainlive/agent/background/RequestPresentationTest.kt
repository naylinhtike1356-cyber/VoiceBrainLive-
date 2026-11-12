package com.example.voicebrainlive.agent.background

import org.junit.Assert.*
import org.junit.Test

class RequestPresentationTest {
    private fun row(id:String,state:RequestState,due:Long=100_000,updated:Long=1_000)=ScheduledRequest(
        id,1,RequestKind.AI_GOAL,"existing work","","",due,due+86_400_000,state,updated)

    @Test fun activeAndAttentionWorkPrecedeUpcomingAndFinishedHistory() {
        val rows=listOf(row("done",RequestState.SUBMITTED),row("later",RequestState.SCHEDULED,200_000),
            row("ready",RequestState.READY),row("earlier",RequestState.SCHEDULED),
            row("paused",RequestState.PAUSED),row("active",RequestState.SUBMITTING))
        assertEquals(listOf("active","ready","earlier","later","paused","done"),RequestPresentation.ordered(rows).map{it.id})
        assertEquals(RequestState.SUBMITTING,rows.last().state) // Presentation does not change repository facts.
    }

    @Test fun newestFinishedHistoryComesFirstAndDraftsStayActionable() {
        val rows=listOf(row("old",RequestState.CANCELLED,updated=1_000),row("new",RequestState.SUMMARY_READY,updated=2_000),row("draft",RequestState.DRAFT_READY))
        assertEquals(listOf("draft","new","old"),RequestPresentation.ordered(rows).map{it.id})
        assertEquals(RequestSection.ATTENTION,RequestPresentation.section(RequestState.DRAFT_READY))
    }

    @Test fun everyPersistedStateHasAnExplicitNextStepAndASection() {
        RequestState.entries.forEach { state ->
            assertNotNull(RequestPresentation.section(state))
            assertTrue(RequestPresentation.next(row("id",state)).isNotBlank())
        }
        assertTrue(RequestPresentation.next(row("id",RequestState.SUBMITTED)).contains("ပြီး/မပြီး"))
        assertTrue(RequestPresentation.next(row("id",RequestState.DRAFT_READY)).contains("မပို့ရသေး"))
    }
}
