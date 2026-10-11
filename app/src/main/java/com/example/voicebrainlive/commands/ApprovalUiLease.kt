package com.example.voicebrainlive.commands

/** Resumed owner + tap generation. A paused/recreated owner cannot authorize a late callback. */
object ApprovalUiLease {
    class Lease internal constructor(val owner: Any, val generation: Long, val explicitTap: Boolean = false)
    private val owners = java.util.IdentityHashMap<Any, Long>()
    private var generation = 0L
    @Synchronized fun resume(owner: Any) { owners[owner] = ++generation }
    @Synchronized fun pause(owner: Any) { owners.remove(owner) }
    @Synchronized fun hasResumedOwner(): Boolean = owners.isNotEmpty()
    @Synchronized fun resumedLease(): Lease? = owners.entries.firstOrNull()?.let { Lease(it.key, it.value) }
    @Synchronized fun tap(owner: Any): Lease? {
        if (!owners.containsKey(owner)) return null
        owners[owner] = ++generation
        return Lease(owner, generation, true)
    }
    @Synchronized fun current(lease: Lease?): Boolean = lease != null && owners[lease.owner] == lease.generation
    @Synchronized fun currentTap(lease: Lease?): Boolean = lease?.explicitTap == true && current(lease)
    @Synchronized fun <T> withCurrentTap(lease: Lease?, effect: () -> T): T? = if (currentTap(lease)) effect() else null
}
