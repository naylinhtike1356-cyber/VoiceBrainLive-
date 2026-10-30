package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch

/** Content-free, in-memory metrics; no transcript, arguments, target, API key, or call ID. */
internal class VoiceSessionMetrics(
    private val epochs: ConnectionEpoch,
    private val nanoTime: () -> Long = System::nanoTime
) {
    enum class FirstEvent { SOCKET_OPEN, SETUP_READY, INPUT_TRANSCRIPT, RESPONSE_AUDIO }
    enum class Work { TOOL_PREPARATION, APPROVAL_WAIT, TOOL_EXECUTION, RECONNECT }
    data class Timing(val count: Long = 0, val totalNanos: Long = 0, val maxNanos: Long = 0)
    data class Snapshot(
        val firstEventNanos: Map<FirstEvent, Long>, val timing: Map<Work, Timing>,
        val dropped: Long, val cancelled: Long
    )

    private val lock = Any()
    private var token: ConnectionEpoch.Token? = null
    private var origin = 0L
    private val first = mutableMapOf<FirstEvent, Long>()
    private val timing = mutableMapOf<Work, Timing>()
    private var dropped = 0L
    private var cancelled = 0L

    fun begin(epoch: ConnectionEpoch.Token): Boolean = epochs.withCurrent(epoch) {
        synchronized(lock) {
            token = epoch; origin = nanoTime(); first.clear(); timing.clear(); dropped = 0; cancelled = 0
        }
        true
    } ?: false

    fun first(epoch: ConnectionEpoch.Token, event: FirstEvent): Boolean = update(epoch) {
        if (event !in first) first[event] = (nanoTime() - origin).coerceAtLeast(0)
    }

    /** Caller supplies a monotonic duration; never accepts wall-clock timestamps or payloads. */
    fun duration(epoch: ConnectionEpoch.Token, work: Work, nanos: Long): Boolean {
        require(nanos >= 0)
        return update(epoch) {
            val old = timing[work] ?: Timing()
            timing[work] = Timing(old.count + 1, saturatedAdd(old.totalNanos, nanos), maxOf(old.maxNanos, nanos))
        }
    }

    fun dropped(epoch: ConnectionEpoch.Token): Boolean = update(epoch) { dropped++ }
    fun cancelled(epoch: ConnectionEpoch.Token): Boolean = update(epoch) { cancelled++ }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(first.toMap(), timing.toMap(), dropped, cancelled)
    }

    private fun update(epoch: ConnectionEpoch.Token, effect: () -> Unit): Boolean = epochs.withCurrent(epoch) {
        synchronized(lock) {
            if (token !== epoch) false else { effect(); true }
        }
    } ?: false

    private fun saturatedAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
}
