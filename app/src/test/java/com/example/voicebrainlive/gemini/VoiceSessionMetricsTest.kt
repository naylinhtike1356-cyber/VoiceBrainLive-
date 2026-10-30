package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionMetricsTest {
    @Test fun firstEventIsMonotonicAndDuplicateCallbacksDoNotChangeLatency() {
        val epochs = ConnectionEpoch()
        val token = epochs.open(null)
        var now = 100L
        val metrics = VoiceSessionMetrics(epochs) { now }
        assertTrue(metrics.begin(token))
        now = 150L
        metrics.first(token, VoiceSessionMetrics.FirstEvent.INPUT_TRANSCRIPT)
        now = 999L
        metrics.first(token, VoiceSessionMetrics.FirstEvent.INPUT_TRANSCRIPT)
        assertEquals(50L, metrics.snapshot().firstEventNanos[VoiceSessionMetrics.FirstEvent.INPUT_TRANSCRIPT])
    }

    @Test fun oldEpochCannotWriteMetricsIntoReplacementSession() {
        val epochs = ConnectionEpoch()
        val old = epochs.open(null)
        val metrics = VoiceSessionMetrics(epochs) { 0L }
        metrics.begin(old)
        metrics.dropped(old)
        val fresh = epochs.open(null)
        assertFalse(metrics.first(old, VoiceSessionMetrics.FirstEvent.RESPONSE_AUDIO))
        assertFalse(metrics.begin(old))
        metrics.begin(fresh)
        assertFalse(metrics.cancelled(old))
        assertTrue(metrics.cancelled(fresh))
        assertEquals(0L, metrics.snapshot().dropped)
        assertEquals(1L, metrics.snapshot().cancelled)
        assertTrue(metrics.snapshot().firstEventNanos.isEmpty())
    }

    @Test fun closedEpochDoesNotAcceptDurationOrCounterUpdates() {
        val epochs = ConnectionEpoch()
        val token = epochs.open(null)
        val metrics = VoiceSessionMetrics(epochs) { 0L }
        metrics.begin(token)
        epochs.close(token)
        assertFalse(metrics.duration(token, VoiceSessionMetrics.Work.TOOL_EXECUTION, 12))
        assertFalse(metrics.dropped(token))
        assertTrue(metrics.snapshot().timing.isEmpty())
    }

    @Test fun independentWorkDurationsAreAggregatedWithoutPayloads() {
        val epochs = ConnectionEpoch()
        val token = epochs.open(null)
        val metrics = VoiceSessionMetrics(epochs) { 0L }
        metrics.begin(token)
        metrics.duration(token, VoiceSessionMetrics.Work.APPROVAL_WAIT, 30)
        metrics.duration(token, VoiceSessionMetrics.Work.APPROVAL_WAIT, 10)
        metrics.duration(token, VoiceSessionMetrics.Work.TOOL_EXECUTION, 4)
        assertEquals(VoiceSessionMetrics.Timing(2, 40, 30), metrics.snapshot().timing[VoiceSessionMetrics.Work.APPROVAL_WAIT])
        assertEquals(VoiceSessionMetrics.Timing(1, 4, 4), metrics.snapshot().timing[VoiceSessionMetrics.Work.TOOL_EXECUTION])
    }

    @Test fun snapshotsAreCopiesAndExtremeDurationsDoNotOverflow() {
        val epochs = ConnectionEpoch()
        val token = epochs.open(null)
        val metrics = VoiceSessionMetrics(epochs) { 0L }
        metrics.begin(token)
        val before = metrics.snapshot()
        metrics.duration(token, VoiceSessionMetrics.Work.RECONNECT, Long.MAX_VALUE)
        metrics.duration(token, VoiceSessionMetrics.Work.RECONNECT, 1)
        assertTrue(before.timing.isEmpty())
        assertEquals(Long.MAX_VALUE, metrics.snapshot().timing[VoiceSessionMetrics.Work.RECONNECT]!!.totalNanos)
    }
}
