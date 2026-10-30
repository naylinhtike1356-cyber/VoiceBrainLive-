package com.example.voicebrainlive.agent.persistence

import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TaskMetadataPersistence {
    fun read(): PersistentTaskSnapshot
    fun compareAndReplace(expectedRevision: Long, next: PersistentTaskSnapshot): Boolean
}

/** Metadata only. Explicit bounded stable wire names, not enum ordinals or serialized commands. */
object TaskMetadataCodec {
    const val MAX_BYTES = 256 * 1024
    fun encode(input: PersistentTaskSnapshot): ByteArray {
        val snapshot = freezeTasks(input)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(snapshot.schemaVersion); out.writeLong(snapshot.revision); out.writeInt(snapshot.tasks.size)
            snapshot.tasks.forEach { task ->
                val id = task.identity
                out.writeUTF(id.taskId); out.writeBoolean(id.parentTaskId != null); id.parentTaskId?.let(out::writeUTF)
                out.writeUTF(id.idempotencyKey); out.writeLong(id.sourceCreatedAt); out.writeLong(id.sourceDeadlineAt)
                out.writeInt(id.steps.size); id.steps.forEach { out.writeUTF(it.stepId); out.writeUTF(it.idempotencyKey) }
                out.writeUTF(task.recipe.name); out.writeUTF(task.state.name); out.writeLong(task.revision)
                out.writeLong(task.updatedAt); out.writeLong(task.deadlineAt); out.writeBoolean(task.dueAt != null)
                task.dueAt?.let(out::writeLong); out.writeInt(task.attempts); out.writeBoolean(task.runOwner != null)
                task.runOwner?.let { out.writeUTF(it.processId); out.writeUTF(it.attemptId) }; out.writeUTF(task.reason.name)
                out.writeBoolean(task.notificationFailure != null)
                task.notificationFailure?.let { out.writeUTF(it.name); out.writeLong(requireNotNull(task.notificationFailureAt)) }
                out.writeBoolean(task.queueCancellationPending)
            }
        }
        return bytes.toByteArray().also { taskCheck(it.size <= MAX_BYTES - 29) }
    }
    fun decode(bytes: ByteArray): PersistentTaskSnapshot = try {
        taskCheck(bytes.size <= MAX_BYTES - 29, TaskFailure.DATA_UNREADABLE)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            taskCheck(input.readInt() == 1, TaskFailure.UNSUPPORTED_SCHEMA)
            val revision = input.readLong(); val count = input.readInt(); taskCheck(count in 0..100)
            val tasks = List(count) {
                val taskId = input.readUTF(); val parent = if (input.readBoolean()) input.readUTF() else null
                val key = input.readUTF(); val created = input.readLong(); val sourceDeadline = input.readLong()
                val size = input.readInt(); taskCheck(size in 1..8)
                val steps = List(size) { PersistentStepIdentity(input.readUTF(), input.readUTF()) }
                val recipe = PersistentTaskRecipe.valueOf(input.readUTF()); val state = PersistentTaskState.valueOf(input.readUTF())
                val taskRevision = input.readLong(); val updated = input.readLong(); val deadline = input.readLong()
                val due = if (input.readBoolean()) input.readLong() else null
                val attempts = input.readInt(); val owner = if (input.readBoolean()) PersistentRunOwner(input.readUTF(), input.readUTF()) else null
                val reason = PersistentTaskReason.valueOf(input.readUTF())
                val failure = if (input.readBoolean()) TaskFailure.valueOf(input.readUTF()) else null
                val failureAt = if (failure != null) input.readLong() else null
                val cancellationPending = input.readBoolean()
                PersistentTaskRecord(PersistentTaskIdentity(taskId, parent, key, created, sourceDeadline, steps),
                    recipe, state, taskRevision, updated, deadline, due, attempts, owner, reason, failure, failureAt, cancellationPending)
            }
            taskCheck(input.available() == 0)
            freezeTasks(PersistentTaskSnapshot(1, revision, tasks))
        }
    } catch (safe: TaskStoreException) {
        if (safe.failure == TaskFailure.UNSUPPORTED_SCHEMA) throw safe
        throw TaskStoreException(TaskFailure.DATA_UNREADABLE)
    } catch (_: Exception) { throw TaskStoreException(TaskFailure.DATA_UNREADABLE) }
}

/** One application-owned instance. CAS is not cross-instance/process locking. */
class EncryptedTaskMetadata(private val bytes: AtomicMemoryBytes, private val key: () -> SecretKey) : TaskMetadataPersistence {
    @Synchronized override fun read(): PersistentTaskSnapshot {
        val envelope = try { bytes.readOrNull() } catch (safe: TaskStoreException) { throw safe }
        catch (_: IOException) { throw TaskStoreException(TaskFailure.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { throw TaskStoreException(TaskFailure.STORAGE_UNAVAILABLE) }
        if (envelope == null) return freezeTasks(PersistentTaskSnapshot())
        taskCheck(envelope.size in 30..TaskMetadataCodec.MAX_BYTES, TaskFailure.DATA_UNREADABLE)
        taskCheck(envelope[0].toInt() == 1, TaskFailure.UNSUPPORTED_SCHEMA)
        val secret = existingKey()
        val payload = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
                updateAAD(AAD); doFinal(envelope.copyOfRange(13, envelope.size))
            }
        } catch (_: Exception) { throw TaskStoreException(TaskFailure.DATA_UNREADABLE) }
        return TaskMetadataCodec.decode(payload)
    }
    @Synchronized override fun compareAndReplace(expectedRevision: Long, next: PersistentTaskSnapshot): Boolean {
        // Freeze before validation/encryption; never write live caller collections.
        val frozen = freezeTasks(next)
        val current = read() // Authentication must succeed before accepting any replacement.
        if (current.revision != expectedRevision) return false
        taskCheck(expectedRevision in 0 until Long.MAX_VALUE && frozen.revision == expectedRevision + 1)
        val payload = TaskMetadataCodec.encode(frozen)
        val secret = existingKey()
        val envelope = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, secret); taskCheck(iv.size == 12); updateAAD(AAD)
                byteArrayOf(1) + iv + doFinal(payload)
            }
        } catch (_: Exception) { throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
        taskCheck(envelope.size <= TaskMetadataCodec.MAX_BYTES)
        try { bytes.replaceAtomically(envelope) } catch (safe: TaskStoreException) { throw safe }
        catch (_: Exception) { throw TaskStoreException(TaskFailure.STORAGE_UNAVAILABLE) }
        return true
    }
    private fun existingKey(): SecretKey = try { key() } catch (safe: TaskStoreException) { throw safe }
    catch (_: Exception) { throw TaskStoreException(TaskFailure.KEY_UNAVAILABLE) }
    companion object { private val AAD = "VoiceBrainLive/task-metadata/schema-1".toByteArray(Charsets.UTF_8) }
}
