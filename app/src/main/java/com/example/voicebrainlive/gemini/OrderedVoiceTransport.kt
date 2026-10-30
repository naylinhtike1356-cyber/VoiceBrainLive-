package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** One ordered frame consumer and a separate serial tool consumer per ConnectionEpoch.
 * Stores a bounded call-ID ledger only in RAM. Results/arguments are never audit records.
 * No retries; cancellation is cooperative and cannot undo an already committed device action.
 */
internal class OrderedVoiceTransport(
    private val epochs: ConnectionEpoch,
    val epoch: ConnectionEpoch.Token,
    scope: CoroutineScope,
    private val processFrame: suspend (String) -> Unit,
    private val executeTool: suspend (Call) -> String,
    private val deliverTool: (Call, String) -> Unit,
    private val onOverload: () -> Unit,
    private val onDrop: () -> Unit = {},
    private val onCancel: () -> Unit = {},
    frameCapacity: Int = 32,
    toolCapacity: Int = 8,
    private val maxCalls: Int = 128,
    private val maxFrameChars: Int = 1_048_576,
    private val toolTimeoutMillis: Long = 30_000
) {
    data class Call @JvmOverloads constructor(val id: String, val name: String, val arguments: String,
        val musicSleepRecipe: Triple<String,String?,String?>? = null)
    private data class Entry(val call: Call, var cancelled: Boolean = false, var complete: Boolean = false, var job: Job? = null)
    private val lock = Any()
    private val frames = Channel<String>(frameCapacity)
    private val tools = Channel<Entry>(toolCapacity)
    private val calls = LinkedHashMap<String, Entry>()
    private var closed = false
    private val workers = CoroutineScope(scope.coroutineContext + epoch.job)

    init {
        require(frameCapacity > 0 && toolCapacity > 0 && maxCalls > 0 && maxFrameChars > 0 && toolTimeoutMillis > 0)
        workers.launch {
            for (frame in frames) {
                ensureActive()
                if (epochs.current(epoch)) {
                    try { processFrame(frame) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { overload() }
                }
            }
        }
        workers.launch {
            for (entry in tools) {
                ensureActive()
                if (!epochs.current(epoch)) break
                supervisorScope {
                    val work = async(start = CoroutineStart.LAZY) {
                        withTimeout(toolTimeoutMillis) { executeTool(entry.call) }
                    }
                    val admitted = synchronized(lock) {
                        if (closed || entry.cancelled) false else { entry.job = work; true }
                    }
                    if (!admitted) { work.cancel(); return@supervisorScope }
                    work.start()
                    val result = try { work.await() }
                    catch (_: TimeoutCancellationException) { "OUTCOME_UNKNOWN: Tool timed out; check the outcome before retrying an action." }
                    catch (e: CancellationException) {
                        coroutineContext.ensureActive()
                        return@supervisorScope
                    }
                    catch (_: Exception) { "OUTCOME_UNKNOWN: Tool ended without a confirmed result; check the outcome before retrying an action." }
                    // Epoch admission and cancellation/result delivery share a linearization point.
                    try { epochs.withCurrent(epoch) {
                        synchronized(lock) {
                            entry.job = null
                            if (!closed && !entry.cancelled && !entry.complete) {
                                entry.complete = true
                                deliverTool(entry.call, if (result.length <= 65_536) result else "Tool result exceeded the voice transport limit; check the outcome before retrying an action.")
                            }
                        }
                    } } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { overload() }
                }
            }
        }
        epoch.job.invokeOnCompletion { close() }
    }

    fun offerFrame(text: String): Boolean = epochs.withCurrent(epoch) {
        if (text.length > maxFrameChars) { overload(); false }
        else {
            val accepted = synchronized(lock) { !closed && frames.trySend(text).isSuccess }
            if (!accepted) overload()
            accepted
        }
    } ?: false

    fun rejectOversizedFrame() = epochs.withCurrent(epoch) { overload() }

    fun offerTool(call: Call): Boolean = epochs.withCurrent(epoch) {
        if (call.id.isBlank() || call.id.length > 256 || call.name.isBlank() || call.name.length > 128 || call.arguments.length > 65_536) {
            onDrop(); false
        } else {
            val status = synchronized(lock) {
                when {
                    closed -> 0
                    calls.containsKey(call.id) -> 1 // Never evict IDs and permit replay during this epoch.
                    calls.size >= maxCalls -> 2
                    else -> {
                        val entry = Entry(call)
                        calls[call.id] = entry
                        if (tools.trySend(entry).isSuccess) 3 else 2
                    }
                }
            }
            when (status) { 3 -> true; 2 -> { overload(); false }; else -> { onDrop(); false } }
        }
    } ?: false

    /** Provider cancellation IDs are exact. Unknown IDs become bounded tombstones. */
    fun cancel(ids: List<String>) = epochs.withCurrent(epoch) {
        ids.take(maxCalls + 1).forEach { id ->
            if (id.isBlank() || id.length > 256) { onDrop(); return@forEach }
            val overflow = synchronized(lock) {
                val entry = calls[id]
                if (entry == null) {
                    if (calls.size >= maxCalls) true
                    else { calls[id] = Entry(Call(id, "", ""), cancelled = true); false }
                } else {
                    if (!entry.complete && !entry.cancelled) { entry.cancelled = true; entry.job?.cancel(); onCancel() }
                    false
                }
            }
            if (overflow) overload()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            calls.values.forEach { it.job?.cancel() }
            frames.cancel(); tools.cancel()
        }
    }

    private fun overload() {
        val first = synchronized(lock) { !closed }
        close()
        if (first) onOverload()
    }
}
