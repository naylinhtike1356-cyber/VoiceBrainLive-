package com.example.voicebrainlive.agent.brain

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.persistence.PersistentTaskIdentity
import com.example.voicebrainlive.agent.persistence.PersistentStepIdentity
import com.example.voicebrainlive.agent.persistence.PersistentTaskState
import com.example.voicebrainlive.commands.CommandPlan
import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Foreground attachment only. No approval capability, credential or executable callback is stored. */
data class BrainTask(val revision: Long, val ownerSession: String, val definition: BrainPlan,
    val snapshot: AgentTaskSnapshot, val recoveries: Int = 0, val totalCalls: Int = 0,
    val reason: String? = null) {
    val identity get() = PersistentTaskIdentity(definition.plan.taskId, definition.plan.parentTaskId,
        definition.plan.idempotencyKey, definition.plan.createdAtMillis, definition.plan.deadlineMillis,
        definition.plan.steps.map { PersistentStepIdentity(it.stepId, it.idempotencyKey) })
    val state get() = when (snapshot.status) {
        AgentStatus.DRAFT -> PersistentTaskState.DRAFT
        AgentStatus.RUNNING -> PersistentTaskState.RUNNING
        AgentStatus.WAITING_FOR_APPROVAL -> PersistentTaskState.WAITING_FOR_APPROVAL
        AgentStatus.SUCCEEDED, AgentStatus.REPORTED -> PersistentTaskState.COMPLETED
        AgentStatus.FAILED -> PersistentTaskState.FAILED
        AgentStatus.CANCELLED -> PersistentTaskState.CANCELLED
        else -> PersistentTaskState.BLOCKED
    }
}

/** Explicit user-authored task metadata. It never establishes tool authorization or execution. */
object LocalUserTaskRecord {
    fun matches(t: BrainTask) = t.ownerSession == "beta_user_tasks" && t.definition.plan.taskId.startsWith("todo_") &&
        t.definition.plan.steps.size == 1 && t.definition.plan.steps.all {
            it.command.source == "beta_user_task" && it.command.intent == "get_current_time" &&
                it.command.target == null && it.command.value == null && it.connection == null &&
                it.parameters.isEmpty() && it.dependencies.isEmpty()
        } && t.snapshot.steps.isEmpty() && t.snapshot.calls == 0 && t.totalCalls == 0
}

/** Explicit versioned, bounded wire format; decoding never instantiates arbitrary model classes. */
internal object BrainCodec {
    const val MAX_BYTES = 512 * 1024
    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray().also { brainCheck(it.size <= MAX_BYTES, "CHECKPOINT_SIZE") }
    private fun <T> read(bytes: ByteArray, block: DataInputStream.() -> T): T {
        brainCheck(bytes.size <= MAX_BYTES, "CHECKPOINT_SIZE")
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.block().also { brainCheck(input.available() == 0, "TRAILING_CHECKPOINT_DATA") }
        }
    }
    private fun DataOutputStream.s(value: String) { brainCheck(value.length <= 8_000, "STRING_BUDGET"); writeUTF(value) }
    private fun DataInputStream.s(max: Int = 8_000): String = readUTF().also { brainCheck(it.length <= max, "STRING_BUDGET") }
    private fun DataOutputStream.n(value: String?) { writeBoolean(value != null); if (value != null) s(value) }
    private fun DataInputStream.n(max: Int = 8_000): String? = if (readBoolean()) s(max) else null
    private fun DataInputStream.count(max: Int) = readInt().also { brainCheck(it in 0..max, "COLLECTION_BUDGET") }
    private fun DataOutputStream.strings(values: Collection<String>) { writeInt(values.size); values.forEach { s(it) } }
    private fun DataInputStream.strings(max: Int, chars: Int = 100) = List(count(max)) { s(chars) }
    private fun DataOutputStream.pin(value: AgentConnectionPin?) {
        writeBoolean(value != null); if (value == null) return
        brainCheck(value.notionReadBinding == null, "ALPHA_FULL_BINDING_NOT_SUPPORTED")
        s(value.connectionId); writeLong(value.revision); s(value.workspaceId); s(value.resourceId)
        s(value.resourceKind); writeLong(value.schemaRevision); s(value.businessStatusPropertyId)
        strings(value.unfinishedValues.sorted())
    }
    private fun DataInputStream.pin(): AgentConnectionPin? = if (!readBoolean()) null else
        AgentConnectionPin(s(80), readLong(), s(80), s(80), s(80), readLong(), s(80), strings(10, 80).toSet())
    private fun DataOutputStream.plan(value: AgentPlan) {
        s(value.taskId); n(value.parentTaskId); s(value.objective); writeLong(value.createdAtMillis)
        writeLong(value.deadlineMillis); s(value.idempotencyKey); writeInt(value.steps.size)
        value.steps.forEach { step ->
            s(step.stepId); s(step.objective); s(step.command.intent); n(step.command.target); n(step.command.value)
            writeInt(step.command.confidence); s(step.command.source); writeBoolean(step.command.requiresConfirmation)
            strings(step.dependencies.sorted()); writeInt(step.parameters.size)
            step.parameters.toSortedMap().forEach { (key, item) -> s(key); s(item) }
            pin(step.connection); writeLong(step.timeoutMillis); writeLong(step.deadlineMillis)
            s(step.retry.name); s(step.idempotencyKey)
        }
    }
    private fun DataInputStream.plan(): AgentPlan {
        val id = s(80); val parent = n(80); val goal = s(2_000); val created = readLong()
        val deadline = readLong(); val key = s(80)
        val steps = List(count(8)) {
            val sid = s(80); val objective = s(500)
            val command = CommandPlan(s(80), n(2_000), n(), readInt(), s(80), readBoolean())
            val dependencies = strings(8, 80)
            brainCheck(dependencies.distinct().size == dependencies.size, "DUPLICATE_DEPENDENCY")
            val pairs = List(count(4)) { s(100) to s(100) }
            brainCheck(pairs.map { it.first }.distinct().size == pairs.size, "DUPLICATE_PARAMETER")
            AgentStep(sid, objective, command, dependencies.toSet(), pairs.toMap(), pin(), readLong(), readLong(),
                AgentRetry.valueOf(s(30)), s(80))
        }
        // Historical validation only. The executor must separately enforce the CURRENT deadline/session.
        return AgentPlanAdmission.admit(AgentPlan(id, parent, goal, created, deadline, key, steps), created).plan
    }
    fun planBytes(plan: AgentPlan): ByteArray = bytes { plan(plan) }
    private fun DataOutputStream.rows(rows: List<AgentTaskRow>) {
        brainCheck(rows.size <= 100, "ROW_BUDGET"); writeInt(rows.size)
        rows.forEach { s(it.id); s(it.title); s(it.businessStatus); s(it.syncStatus); writeInt(it.priority) }
    }
    private fun DataInputStream.rows(): List<AgentTaskRow> = List(count(100)) {
        AgentTaskRow(s(80), s(2_000), s(80), s(80), readInt()).also {
            brainCheck(it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.title.isNotBlank() && it.priority in 0..100, "INVALID_ROW")
        }
    }.also { brainCheck(it.map { row -> row.id }.distinct().size == it.size, "DUPLICATE_ROW") }
    private fun DataOutputStream.data(value: AgentData?) {
        when (value) {
            null -> s("none")
            is AgentData.Clock -> { s("clock"); s(value.exactDisplay) }
            is AgentData.Battery -> { s("battery"); writeInt(value.percent) }
            is AgentData.Tasks -> { s("tasks"); pin(value.connection); rows(value.rows); writeBoolean(value.complete) }
            is AgentData.Priorities -> { s("priorities"); pin(value.connection); rows(value.rows) }
            is AgentData.Draft -> { s("draft"); s(value.exactText); pin(value.connection); strings(value.sourceRowIds) }
            is AgentData.Report -> { s("report"); s(value.exactText) }
        }
    }
    private fun DataInputStream.data(): AgentData? = when (s(30)) {
        "none" -> null
        "clock" -> AgentData.Clock(s(200))
        "battery" -> AgentData.Battery(readInt().also { brainCheck(it in 0..100, "INVALID_BATTERY") })
        "tasks" -> AgentData.Tasks(requireNotNull(pin()), rows(), readBoolean())
        "priorities" -> AgentData.Priorities(requireNotNull(pin()), rows())
        "draft" -> AgentData.Draft(s(), pin(), strings(3, 80))
        "report" -> AgentData.Report(s())
        else -> throw BrainFailure("UNKNOWN_RESULT_TYPE")
    }
    private fun DataOutputStream.task(task: BrainTask) {
        writeLong(task.revision); s(task.ownerSession); plan(task.definition.plan)
        s(task.definition.memoryQuery); s(task.definition.memoryFingerprint)
        task.definition.budget.let { writeInt(it.steps); writeInt(it.depth); writeInt(it.calls); writeInt(it.costUnits); writeLong(it.durationMillis); writeLong(it.modelMillis) }
        s(task.definition.digest); writeInt(task.recoveries); writeInt(task.totalCalls); n(task.reason)
        val snapshot = task.snapshot
        s(snapshot.status.name); writeLong(snapshot.updatedAtMillis); writeInt(snapshot.calls); writeInt(snapshot.steps.size)
        snapshot.steps.forEach { item ->
            s(item.stepId); s(item.status.name); writeBoolean(item.startedAtMillis != null)
            item.startedAtMillis?.let { writeLong(it) }; writeLong(item.updatedAtMillis); data(item.result); n(item.errorCode)
        }
    }
    private fun DataInputStream.task(): BrainTask {
        val revision = readLong(); val owner = s(80); val plan = plan()
        val query = s(80); val fingerprint = s(64)
        val budget = BrainBudget(readInt(), readInt(), readInt(), readInt(), readLong(), readLong())
        val definition = BrainPlan(plan, query, fingerprint, budget)
        brainCheck(s(64) == definition.digest, "PLAN_DIGEST_MISMATCH")
        val recoveries = readInt(); val total = readInt(); val reason = n(100)
        val status = AgentStatus.valueOf(s(40)); val updated = readLong(); val calls = readInt()
        val records = List(count(8)) {
            val id = s(80); val state = AgentStatus.valueOf(s(40)); val started = if (readBoolean()) readLong() else null
            AgentStepRecord(id, state, started, readLong(), data(), n(100))
        }
        brainCheck(revision > 0 && owner.matches(Regex("[A-Za-z0-9_-]{1,80}")) && recoveries in 0..1 &&
            total in 0..budget.calls && calls in 0..budget.calls && fingerprint.matches(Regex("[a-f0-9]{64}")), "INVALID_CHECKPOINT")
        brainCheck(records.map { it.stepId }.distinct().size == records.size && records.all { record ->
            record.stepId in plan.steps.map { it.stepId } && record.updatedAtMillis >= plan.createdAtMillis &&
                (record.result == null || record.status == AgentStatus.SUCCEEDED)
        }, "INVALID_STEP_CHECKPOINT")
        val snapshot = AgentTaskSnapshot(plan.taskId, plan.parentTaskId, plan.objective, status, plan.createdAtMillis,
            updated, plan.deadlineMillis, plan.idempotencyKey, calls, records)
        return BrainTask(revision, owner, definition, snapshot, recoveries, total, reason)
    }
    fun encode(tasks: List<BrainTask>): ByteArray = bytes {
        brainCheck(tasks.size <= 8 && tasks.map { it.definition.plan.taskId }.distinct().size == tasks.size, "TASK_CAPACITY")
        writeInt(1); writeInt(tasks.size); tasks.sortedBy { it.definition.plan.taskId }.forEach { task(it) }
    }
    fun decode(bytes: ByteArray): List<BrainTask> = read(bytes) {
        brainCheck(readInt() == 1, "UNSUPPORTED_CHECKPOINT_SCHEMA")
        List(count(8)) { task() }.also { brainCheck(it.map { task -> task.definition.plan.taskId }.distinct().size == it.size, "DUPLICATE_TASK") }
    }
}

/** Authenticate-before-write; any key/read/atomic-write failure leaves old bytes in place. No plaintext path. */
class EncryptedBrainStore(private val bytes: AtomicMemoryBytes, private val key: () -> SecretKey) {
    companion object { private val lock = Any(); private val aad = "VoiceBrainLive/foreground-brain/schema-1".toByteArray(Charsets.UTF_8) }
    private fun load(): List<BrainTask> {
        val encoded = bytes.readOrNull() ?: return emptyList()
        brainCheck(encoded.size in 30..(BrainCodec.MAX_BYTES + 29) && encoded[0] == 1.toByte(), "INVALID_ENCRYPTED_CHECKPOINT")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(1, 13))); cipher.updateAAD(aad)
        return BrainCodec.decode(cipher.doFinal(encoded.copyOfRange(13, encoded.size)))
    }
    private fun save(tasks: List<BrainTask>) {
        val plain = BrainCodec.encode(tasks)
        BrainCodec.decode(plain) // refuse invalid in-memory state before touching storage
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        bytes.replaceAtomically(byteArrayOf(1) + iv + cipher.doFinal(plain))
    }
    fun list(): List<BrainTask> = synchronized(lock) { load() }
    fun create(task: BrainTask): BrainTask = synchronized(lock) {
        val all = load()
        brainCheck(all.size < 8 && all.none { it.definition.plan.taskId == task.definition.plan.taskId ||
            it.definition.plan.idempotencyKey == task.definition.plan.idempotencyKey }, "DUPLICATE_TASK_OR_CAPACITY")
        brainCheck(task.revision == 1L, "INITIAL_REVISION")
        save(all + task); load().single { it.definition.plan.taskId == task.definition.plan.taskId }
    }
    /** User-authored BETA todo revision only: one atomic encrypted write, no execution/approval grant.
     * Original immutable definition remains as cancelled history. Schema and capacity are unchanged.
     */
    fun reviseLocalTask(expected: BrainTask, next: BrainTask, now: Long): BrainTask = synchronized(lock) {
        fun local(t: BrainTask) = LocalUserTaskRecord.matches(t)
        val all = load()
        val old = all.singleOrNull { it.definition.plan.taskId == expected.definition.plan.taskId }
            ?: throw BrainFailure("TASK_NOT_FOUND")
        brainCheck(old == expected && local(old) && old.snapshot.status == AgentStatus.DRAFT, "STALE_LOCAL_TASK")
        brainCheck(local(next) && next.revision == 1L && next.snapshot.status == AgentStatus.DRAFT &&
            next.definition.plan.parentTaskId == old.definition.plan.taskId && now >= old.snapshot.updatedAtMillis &&
            next.definition.plan.createdAtMillis == now, "INVALID_LOCAL_REVISION")
        brainCheck(all.size < 8 && all.none { it.definition.plan.taskId == next.definition.plan.taskId ||
            it.definition.plan.idempotencyKey == next.definition.plan.idempotencyKey }, "DUPLICATE_TASK_OR_CAPACITY")
        val archived = old.copy(revision=old.revision+1, reason="USER_EDITED_VERSION",
            snapshot=old.snapshot.copy(status=AgentStatus.CANCELLED,updatedAtMillis=now))
        save(all.map { if(it === old) archived else it } + next)
        load().single { it.definition.plan.taskId == next.definition.plan.taskId }
    }
    fun update(id: String, revision: Long, owner: String, transform: (BrainTask) -> BrainTask): BrainTask = synchronized(lock) {
        val all = load(); val old = all.singleOrNull { it.definition.plan.taskId == id } ?: throw BrainFailure("TASK_NOT_FOUND")
        brainCheck(old.revision == revision && old.ownerSession == owner, "STALE_TASK_OWNER")
        val next = transform(old)
        brainCheck(next.revision == old.revision + 1 && next.definition == old.definition, "CHECKPOINT_PLAN_CHANGED")
        save(all.map { if (it === old) next else it }); load().single { it.definition.plan.taskId == id }
    }
}
