package com.example.voicebrainlive.audio

/** Sink operations belong exclusively to one captured worker, including creation and release. */
internal interface PcmPlaybackSink {
    fun play()
    /** Must be nonblocking. Returns accepted bytes, zero for backpressure, negative for error. */
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
    fun pause()
    fun flush()
    fun stop()
    fun release()
}

/** Pure JVM ownership/buffering policy. Native audio behavior is outside this contract. */
internal class BoundedPlaybackController(
    private val sinkFactory: () -> PcmPlaybackSink,
    private val maxQueuedBytes: Int = 96_000,
    maxQueuedChunks: Int = 128,
    private val writeSliceBytes: Int = 4_096,
    private val maxZeroWrites: Int = 40,
    private val zeroWriteDelayMillis: Long = 5,
    private val stopJoinMillis: Long = 100,
    private val onWorkerStart: () -> Unit = {},
    private val onError: (Error) -> Unit = {}
) {
    init {
        require(maxQueuedBytes >= 2 && writeSliceBytes > 0 && writeSliceBytes % 2 == 0)
        require(maxZeroWrites > 0 && zeroWriteDelayMillis in 1..100 && stopJoinMillis in 0..1_000)
    }
    enum class Error { INITIALIZATION, WRITE_FAILED, WRITE_STALLED, SINK_EXCEPTION, CLEANUP_FAILED, RETIRING_WRITER }
    enum class Start { STARTED, ALREADY_RUNNING, RETIRING }
    data class Snapshot(
        val queue: ByteBoundedPcmQueue.Snapshot, val writtenBytes: Long,
        val writeCalls: Long, val zeroWrites: Long, val partialWrites: Long,
        val cancelledInFlightBytes: Long, val droppedInFlightBytes: Long, val rejectedEncodedInputs: Long,
        val failures: Long, val running: Boolean, val retiring: Boolean
    )
    class Producer internal constructor(internal val owner: Any, internal val token: ByteBoundedPcmQueue.Token)
    private class Run(@Volatile var token: ByteBoundedPcmQueue.Token) {
        @Volatile var cancelled = false
        @Volatile var inFlight = false
        lateinit var worker: Thread
    }

    private val lock = Any()
    private val queue = ByteBoundedPcmQueue(maxQueuedBytes, maxQueuedChunks)
    private var active: Run? = null
    private var retiring: Run? = null
    private var writtenBytes = 0L
    private var writeCalls = 0L
    private var zeroWrites = 0L
    private var partialWrites = 0L
    private var cancelledInFlightBytes = 0L
    private var droppedInFlightBytes = 0L
    private var rejectedEncodedInputs = 0L
    private var failures = 0L

    fun start(): Start = synchronized(lock) {
        if (active != null) return@synchronized Start.ALREADY_RUNNING
        if (retiring?.worker?.isAlive == true) return@synchronized Start.RETIRING
        retiring = null
        val run = Run(queue.open())
        run.worker = Thread({ runWorker(run) }, "AudioPlaybackPriority").apply { isDaemon = true }
        active = run
        run.worker.start()
        Start.STARTED
    }

    /** Capture BEFORE decode, launch, or any asynchronous producer work. */
    fun captureProducer(): Producer? = synchronized(lock) {
        active?.takeUnless { it.cancelled }?.let { Producer(it, it.token) }
    }

    fun current(producer: Producer): Boolean = synchronized(lock) {
        active === producer.owner && queue.current(producer.token)
    }

    fun offer(producer: Producer, bytes: ByteArray): ByteBoundedPcmQueue.Admission = synchronized(lock) {
        // A token cannot be borrowed from a replacement playback owner.
        if (active !== producer.owner) {
            return@synchronized queue.offer(producer.token, bytes)
        }
        queue.offer(producer.token, bytes)
    }

    fun rejectEncodedInput() = synchronized(lock) { rejectedEncodedInputs++ }
    fun maxFrameBytes(): Int = maxQueuedBytes

    /** Immediate Java generation revocation; native pause/flush is acknowledged by worker later. */
    fun clear(): Producer? = synchronized(lock) {
        val run = active ?: return@synchronized null
        run.token = queue.open()
        run.worker.interrupt()
        Producer(run, run.token)
    }

    /** Bounded caller wait. No caller can stop/release a sink beneath its writer. */
    fun stop(): Boolean {
        val run = synchronized(lock) {
            val existing = active ?: retiring ?: return true
            if (active === existing) {
                existing.cancelled = true
                queue.close(existing.token)
                active = null
                retiring = existing
            }
            existing
        }
        run.worker.interrupt()
        if (Thread.currentThread() !== run.worker && stopJoinMillis > 0) {
            try { run.worker.join(stopJoinMillis) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return !run.worker.isAlive
    }

    fun isRunning(): Boolean = synchronized(lock) { active != null }
    /** Covers Java queued/in-flight PCM, not AudioTrack's audible hardware drain. */
    fun isSpeaking(): Boolean = synchronized(lock) {
        active?.inFlight == true || (active != null && queue.snapshot().queuedChunks > 0)
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(queue.snapshot(), writtenBytes, writeCalls, zeroWrites, partialWrites,
            cancelledInFlightBytes, droppedInFlightBytes, rejectedEncodedInputs, failures, active != null,
            retiring?.worker?.isAlive == true)
    }

    private fun runWorker(run: Run) {
        var sink: PcmPlaybackSink? = null
        var failure: Error? = null
        try {
            onWorkerStart()
            if (run.cancelled) return
            sink = sinkFactory()
            var observedToken = run.token
            var started = false
            while (!run.cancelled) {
                // Cancellation wake-ups are control signals, not new playback lifetimes.
                Thread.interrupted()
                val token = run.token
                if (observedToken !== token) {
                    sink.pause()
                    sink.flush()
                    observedToken = token
                    started = false
                }
                val chunk = try { queue.await(token, 150) }
                catch (_: InterruptedException) { continue }
                if (chunk == null) continue
                run.inFlight = true
                var offset = 0
                try {
                    if (!run.cancelled && queue.current(chunk.token)) {
                        if (!started) { sink.play(); started = true }
                        var zeros = 0
                        while (offset < chunk.bytes.size && !run.cancelled && queue.current(chunk.token)) {
                            val count = minOf(writeSliceBytes, chunk.bytes.size - offset)
                            val written = sink.write(chunk.bytes, offset, count)
                            synchronized(lock) { writeCalls++ }
                            if (written < 0 || written > count || written % 2 != 0) {
                                failure = Error.WRITE_FAILED
                                return
                            }
                            if (written == 0) {
                                synchronized(lock) { zeroWrites++ }
                                if (++zeros >= maxZeroWrites) { failure = Error.WRITE_STALLED; return }
                                try { Thread.sleep(zeroWriteDelayMillis) }
                                catch (_: InterruptedException) { /* Loop rechecks revocation. */ }
                            } else {
                                synchronized(lock) {
                                    writtenBytes += written
                                    if (written < count) partialWrites++
                                }
                                offset += written
                                zeros = 0
                            }
                        }
                    }
                } finally {
                    if (offset < chunk.bytes.size) synchronized(lock) {
                        if (run.cancelled || !queue.current(chunk.token)) cancelledInFlightBytes += chunk.bytes.size - offset
                        else droppedInFlightBytes += chunk.bytes.size - offset
                    }
                    run.inFlight = false
                }
            }
        } catch (_: Exception) {
            failure = if (sink == null) Error.INITIALIZATION else Error.SINK_EXCEPTION
        } finally {
            // Only this worker ever touches this sink. Attempt every cleanup even after failures.
            var cleanupFailed = false
            sink?.let { owned ->
                for (effect in listOf<() -> Unit>(owned::pause, owned::flush, owned::stop, owned::release)) {
                    try { effect() } catch (_: Exception) { cleanupFailed = true }
                }
            }
            synchronized(lock) {
                if (active === run) {
                    queue.close(run.token)
                    active = null
                    // Keep this worker registered until it actually exits, including error callback.
                    retiring = run
                }
                if (failure != null || cleanupFailed) failures++
            }
            val reported = failure ?: if (cleanupFailed) Error.CLEANUP_FAILED else null
            if (reported != null) try { onError(reported) } catch (_: Exception) { /* Diagnostics cannot own lifecycle. */ }
        }
    }
}
