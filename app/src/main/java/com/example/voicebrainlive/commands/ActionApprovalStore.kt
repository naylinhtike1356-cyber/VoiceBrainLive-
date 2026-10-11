package com.example.voicebrainlive.commands

import java.util.UUID

/** Immutable, resolved action. Content is deliberately never normalized. */
data class ActionSnapshot(
    val command: String,
    val recipient: String,
    val destination: String,
    val targetId: String,
    val payload: String?,
    val scope: String,
    val revision: String = ""
) {
    fun preview(): String = buildString {
        append("လုပ်ဆောင်ချက် / Action: $command\n")
        append("လက်ခံသူ / Recipient: $recipient\n")
        append("Destination / app: $destination\n")
        append("Target ID: $targetId\nScope: $scope\n")
        append("စာသားအပြည့်အစုံ / Exact content:\n")
        append(payload ?: "(no outgoing text)")
    }
}

/** Process-local capability store: persisted queue data cannot create an execution grant. */
class ActionApprovalStore(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val ttlMs: Long = 15 * 60 * 1000L
) {
    data class Pending(val id: String, val session: String, val snapshot: ActionSnapshot, val deadline: Long, val epoch: Long = 0)
    private data class Entry(val pending: Pending, var approved: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()
    private val epochs = mutableMapOf<String, Long>()
    private val closed = mutableSetOf<String>()

    @Synchronized fun epoch(session: String): Long? = if (session in closed) null else epochs[session] ?: 0L
    @Synchronized fun isCurrent(pending: Pending): Boolean = epoch(pending.session) == pending.epoch
    @Synchronized fun requestIfCurrent(session: String, snapshot: ActionSnapshot, expected: Long): Pending? {
        if (epoch(session) != expected) return null
        return request(session, snapshot)
    }

    @Synchronized
    fun request(session: String, snapshot: ActionSnapshot): Pending {
        check(session !in closed) { "Session closed; create a fresh executor." }
        entries.entries.removeAll { it.value.pending.deadline <= clock() }
        while (entries.size >= 20) entries.remove(entries.keys.first())
        val pending = Pending(UUID.randomUUID().toString(), session, snapshot, clock() + ttlMs, epoch(session)!!)
        entries[pending.id] = Entry(pending)
        return pending
    }

    @Synchronized
    fun pending(id: String): Pending? = entries[id]?.pending?.takeIf { it.deadline > clock() && isCurrent(it) }

    fun isExpired(pending: Pending): Boolean = pending.deadline <= clock()

    /** Called only by the explicit preview UI after successful Android authentication. */
    @Synchronized
    fun approve(id: String, shown: ActionSnapshot): Boolean {
        val entry = entries[id] ?: return false
        if (!isCurrent(entry.pending) || entry.pending.deadline <= clock() || entry.pending.snapshot != shown || entry.approved) return false
        entry.approved = true
        return true
    }

    /** Claim before doing work, not after. Failed/uncertain execution also spends the capability. */
    @Synchronized
    fun consume(id: String, session: String, action: ActionSnapshot): Boolean {
        val entry = entries[id] ?: return false
        if (!isCurrent(entry.pending) || !entry.approved || entry.pending.session != session || entry.pending.snapshot != action ||
            entry.pending.deadline <= clock()) return false
        entries.remove(id)
        return true
    }

    @Synchronized
    fun cancel(id: String) { entries.remove(id) }

    @Synchronized
    fun invalidate(session: String) {
        closed += session
        epochs[session] = (epochs[session] ?: 0L) + 1L
        entries.entries.removeAll { it.value.pending.session == session }
    }
}
