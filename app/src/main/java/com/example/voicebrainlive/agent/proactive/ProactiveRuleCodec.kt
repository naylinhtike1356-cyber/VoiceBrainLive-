package com.example.voicebrainlive.agent.proactive

import android.content.Context
import com.example.voicebrainlive.storage.snapshot.AuthenticatedSnapshotBytes
import com.example.voicebrainlive.storage.snapshot.SnapshotStorageException
import com.example.voicebrainlive.storage.snapshot.androidRuleSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Versioned stable wire names. This is a rule codec, never a task codec. */
internal object ProactiveRuleCodec {
    const val LIMIT = 256 * 1024 - 29
    private const val MOCK_EXTENSION_MAGIC = 0x4d4f434b // recognized mock extension family, never an approval marker
    fun encode(input: ProactiveSnapshot): ByteArray {
        val state = freezeRules(input); val bytes = ByteArrayOutputStream()
        val wire = if (state.rules.any { it.config?.monitor != null } || state.occurrences.any { it.execution != null }) 2 else 1
        DataOutputStream(bytes).use { out ->
            fun optional(value: Long?) { out.writeBoolean(value != null); value?.let(out::writeLong) }
            out.writeInt(wire); if (wire == 2) out.writeInt(MOCK_EXTENSION_MAGIC)
            out.writeLong(state.revision); out.writeLong(state.updatedAt); optional(state.lastGlobalResultAt)
            out.writeInt(state.rules.size)
            state.rules.forEach { rule ->
                out.writeUTF(rule.ruleId); out.writeLong(rule.configRevision); out.writeLong(rule.stateRevision); out.writeUTF(rule.lifecycle.name)
                out.writeBoolean(rule.config != null)
                rule.config?.let { config ->
                    out.writeUTF(config.zoneId); out.writeInt(config.triggerMinute); out.writeInt(config.windowEndMinute)
                    out.writeBoolean(config.quietStartMinute != null)
                    config.quietStartMinute?.let { out.writeInt(it); out.writeInt(requireNotNull(config.quietEndMinute)) }
                    out.writeBoolean(config.showGenericResults); out.writeInt(config.maxResultsPerSevenDays)
                    out.writeUTF(config.kind.name); out.writeUTF(config.mode.name)
                    if (wire == 2) {
                        out.writeBoolean(config.monitor != null)
                        config.monitor?.let { monitor ->
                            out.writeUTF(monitor.objective); out.writeUTF(monitor.profileId); out.writeLong(monitor.profileRevision)
                            out.writeLong(monitor.expiresAt); out.writeInt(monitor.maxAttempts); out.writeLong(monitor.backoffMillis)
                        }
                    }
                }
                optional(rule.reviewedRevision); out.writeLong(rule.createdAt); out.writeLong(rule.updatedAt); optional(rule.highestConsumedDay)
                optional(rule.lastCheckAt); optional(rule.lastAttemptAt); optional(rule.lastResultAt); optional(rule.nextAdvisoryAt)
                out.writeUTF(rule.lastDecision.name)
            }
            out.writeInt(state.occurrences.size)
            state.occurrences.forEach { item ->
                out.writeUTF(item.occurrenceId); out.writeUTF(item.ruleId); out.writeLong(item.configRevision)
                out.writeLong(item.localEpochDay); out.writeLong(item.attemptedAt); out.writeUTF(item.decision.name)
                out.writeBoolean(item.result != null); item.result?.let { out.writeUTF(it.name) }; out.writeBoolean(item.revoked)
                if (wire == 2) {
                    out.writeBoolean(item.execution != null)
                    item.execution?.let { run ->
                        out.writeUTF(run.owner.processId); out.writeUTF(run.owner.attemptId); out.writeInt(run.attempts)
                        out.writeUTF(run.state.name); out.writeLong(run.updatedAt); out.writeUTF(run.reason.name); optional(run.retryAt)
                        out.writeBoolean(run.sourceId != null); run.sourceId?.let(out::writeUTF)
                    }
                }
            }
        }
        return bytes.toByteArray().also { ruleCheck(it.size <= LIMIT) }
    }
    fun decode(bytes: ByteArray): ProactiveSnapshot = try {
        ruleCheck(bytes.size <= LIMIT)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun optional() = if (input.readBoolean()) input.readLong() else null
            val wire = input.readInt(); ruleCheck(wire in 1..2, RuleFailure.UNSUPPORTED_SCHEMA)
            if (wire == 2) ruleCheck(input.available() >= 4 && input.readInt() == MOCK_EXTENSION_MAGIC, RuleFailure.UNSUPPORTED_SCHEMA)
            val revision = input.readLong(); val updatedAt = input.readLong(); val global = optional()
            val ruleCount = input.readInt(); ruleCheck(ruleCount in 0..20)
            val rules = List(ruleCount) {
                val id = input.readUTF(); val configRev = input.readLong(); val stateRev = input.readLong()
                val lifecycle = RuleLifecycle.valueOf(input.readUTF())
                val config = if (input.readBoolean()) {
                    val zone = input.readUTF(); val trigger = input.readInt(); val end = input.readInt()
                    val quiet = input.readBoolean(); val quietStart = if (quiet) input.readInt() else null
                    val quietEnd = if (quiet) input.readInt() else null
                    val show = input.readBoolean(); val quota = input.readInt()
                    val kind = RuleKind.valueOf(input.readUTF()); val mode = RuleMode.valueOf(input.readUTF())
                    val monitor = if (wire == 2 && input.readBoolean()) MockTaskReviewConfig(input.readUTF(), input.readUTF(),
                        input.readLong(), input.readLong(), input.readInt(), input.readLong()) else null
                    DailySimulationConfig(zone, trigger, end, quietStart, quietEnd, show, quota, kind, mode, monitor)
                } else null
                val reviewed = optional(); val created = input.readLong(); val changed = input.readLong(); val high = optional()
                val check = optional(); val attempt = optional(); val result = optional(); val next = optional()
                ProactiveRule(id, configRev, stateRev, lifecycle, config, reviewed, created, changed, high,
                    check, attempt, result, next, RuleDecision.valueOf(input.readUTF()))
            }
            val occurrenceCount = input.readInt(); ruleCheck(occurrenceCount in 0..100)
            val occurrences = List(occurrenceCount) {
                val id = input.readUTF(); val owner = input.readUTF(); val configRev = input.readLong()
                val day = input.readLong(); val time = input.readLong(); val decision = RuleDecision.valueOf(input.readUTF())
                val result = if (input.readBoolean()) GenericRuleResult.valueOf(input.readUTF()) else null
                val revoked = input.readBoolean()
                val execution = if (wire == 2 && input.readBoolean()) MockReviewExecution(
                    com.example.voicebrainlive.agent.persistence.PersistentRunOwner(input.readUTF(), input.readUTF()),
                    input.readInt(), com.example.voicebrainlive.agent.persistence.PersistentTaskState.valueOf(input.readUTF()),
                    input.readLong(), MockReviewReason.valueOf(input.readUTF()), optional(), if (input.readBoolean()) input.readUTF() else null) else null
                RuleOccurrence(id, owner, configRev, day, time, decision, result, revoked, execution)
            }
            ruleCheck(input.available() == 0)
            freezeRules(ProactiveSnapshot(1, revision, updatedAt, global, rules, occurrences))
        }
    } catch (safe: RuleStoreException) {
        if (safe.failure == RuleFailure.UNSUPPORTED_SCHEMA) throw safe
        throw RuleStoreException(RuleFailure.DATA_UNREADABLE)
    } catch (_: Exception) { throw RuleStoreException(RuleFailure.DATA_UNREADABLE) }
}
internal class EncryptedProactiveRules(private val storage: AuthenticatedSnapshotBytes) : ProactiveRulePersistence {
    @Synchronized override fun read(): ProactiveSnapshot = safe {
        storage.read()?.let(ProactiveRuleCodec::decode) ?: freezeRules(ProactiveSnapshot())
    }
    @Synchronized override fun compareAndReplace(expectedRevision: Long, next: ProactiveSnapshot): Boolean = safe {
        val admitted = freezeRules(next); val current = read()
        if (current.revision != expectedRevision) false
        else {
            ruleCheck(expectedRevision in 0 until Long.MAX_VALUE && admitted.revision == expectedRevision + 1)
            storage.replace(ProactiveRuleCodec.encode(admitted)); true
        }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
    catch (safe: SnapshotStorageException) { throw RuleStoreException(RuleFailure.valueOf(safe.failure.name)) }
}
object ProactiveRuleStoreFactory {
    /** Returned adapter initializes its file/key only on repository IO's first call. Own once per app. */
    fun create(context: Context): ProactiveRulePersistence {
        val app = context.applicationContext
        return object : ProactiveRulePersistence {
            private val delegate by lazy { EncryptedProactiveRules(androidRuleSnapshot(app)) }
            override fun read() = delegate.read()
            override fun compareAndReplace(expectedRevision: Long, next: ProactiveSnapshot) = delegate.compareAndReplace(expectedRevision, next)
        }
    }
}
