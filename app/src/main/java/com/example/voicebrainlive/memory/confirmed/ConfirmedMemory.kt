package com.example.voicebrainlive.memory.confirmed

import java.util.Locale

/** Separate from legacy inferred facts, chat history, summaries and task state. */
enum class ConfirmedMemoryCategory { FACT, PREFERENCE, GOAL, ROUTINE, USER_NOTE }
enum class ConfirmedMemorySource { USER_ENTERED, USER_CONFIRMED_SUGGESTION }
enum class MemoryPrivacy { ORDINARY, PERSONAL, SECRET }

data class MemoryDraft(
    val title: String,
    val content: String,
    val category: ConfirmedMemoryCategory,
    val source: ConfirmedMemorySource,
    val privacy: MemoryPrivacy = MemoryPrivacy.ORDINARY,
    val includeInContext: Boolean = false,
    val expiresAt: Long? = null
)

data class ConfirmedMemory(
    val id: String,
    val revision: Long,
    val draft: MemoryDraft,
    val createdAt: Long,
    val updatedAt: Long,
    val confirmedAt: Long
) {
    fun isActive(now: Long): Boolean = now >= confirmedAt &&
        (draft.expiresAt == null || now < draft.expiresAt)
}

data class ConfirmedMemorySnapshot(val schemaVersion: Int = 1, val records: List<ConfirmedMemory> = emptyList())

/** Failures must throw; a corrupt/unreadable store must never appear as an empty writable store. */
interface ConfirmedMemoryPersistence {
    fun read(): ConfirmedMemorySnapshot
    fun writeAtomically(snapshot: ConfirmedMemorySnapshot)
}

/**
 * UI-only local save API. This is consent to save the exact preview, never a tool/effect grant.
 * One application-owned instance must serialize all access to its persistence implementation.
 */
class ConfirmedMemoryRepository(
    private val persistence: ConfirmedMemoryPersistence,
    private val now: () -> Long,
    private val newId: () -> String
) {
    @Synchronized
    fun list(includeExpired: Boolean = false): List<ConfirmedMemory> = readValidated().records
        .filter { includeExpired || it.isActive(now()) }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(preview: MemoryDraft, userConfirmedExactPreview: Boolean): ConfirmedMemory {
        require(userConfirmedExactPreview) { "An explicit confirmation of the displayed memory is required" }
        val time = now()
        validateDraft(preview, time)
        val snapshot = readValidated()
        snapshot.records.firstOrNull { it.draft == preview }?.let { return it }
        require(snapshot.records.none {
            it.draft.category == preview.category && it.draft.title == preview.title
        }) { "Use exact-id revision-checked correction for an existing title/category" }
        require(snapshot.records.size < MAX_RECORDS) { "Memory limit reached; choose memories to forget" }
        val id = newId()
        require(id.isNotBlank() && id.length <= 80 && snapshot.records.none { it.id == id })
        val record = ConfirmedMemory(id, 1, preview, time, time, time)
        persistence.writeAtomically(snapshot.copy(records = snapshot.records + record))
        return record
    }

    @Synchronized
    fun importConfirmed(drafts:List<MemoryDraft>,expected:List<ConfirmedMemory>,userConfirmedExactPreview:Boolean):List<ConfirmedMemory> {
        require(userConfirmedExactPreview && drafts.size in 1..MAX_RECORDS)
        val time=now();val snapshot=readValidated()
        require(snapshot.records.sortedBy{it.id}==expected.sortedBy{it.id}) { "Memory changed; review the import again" }
        require(snapshot.records.size+drafts.size<=MAX_RECORDS)
        require(snapshot.records.all{time>=it.updatedAt}) { "Clock moved backwards" }
        val frozen=drafts.toList()
        frozen.forEach{validateDraft(it,time);require(!it.includeInContext && it.source==ConfirmedMemorySource.USER_CONFIRMED_SUGGESTION)}
        val keys=(snapshot.records.map{it.draft}+frozen).map{it.category to it.title}
        require(keys.distinct().size==keys.size) { "Duplicate/conflicting memory; choose a correction explicitly" }
        val records=frozen.map{ConfirmedMemory(newId(),1,it,time,time,time)}
        val ids=(snapshot.records+records).map{it.id}
        require(ids.all{it.isNotBlank()&&it.length<=80}&&ids.distinct().size==ids.size)
        val next=snapshot.copy(records=snapshot.records+records)
        persistence.writeAtomically(next)
        require(readValidated()==next) { "Import outcome unverified; refresh before retrying" }
        return records
    }

    @Synchronized
    fun correct(id: String, expectedRevision: Long, preview: MemoryDraft,
                userConfirmedExactPreview: Boolean): ConfirmedMemory {
        require(userConfirmedExactPreview)
        val time = now()
        validateDraft(preview, time)
        val snapshot = readValidated()
        val old = snapshot.records.single { it.id == id }
        require(old.revision == expectedRevision && old.revision < Long.MAX_VALUE) { "Stale memory preview" }
        require(time >= old.updatedAt) { "Clock moved backwards; retry after the clock is corrected" }
        require(snapshot.records.none {
            it.id != id && it.draft.category == preview.category && it.draft.title == preview.title
        }) { "Conflicting title/category" }
        val updated = old.copy(revision = old.revision + 1, draft = preview,
            updatedAt = time, confirmedAt = time)
        persistence.writeAtomically(snapshot.copy(records = snapshot.records.map { if (it.id == id) updated else it }))
        return updated
    }

    @Synchronized
    fun forget(id: String, expectedRevision: Long, userConfirmed: Boolean): Boolean {
        require(userConfirmed)
        val snapshot = readValidated()
        val record = snapshot.records.singleOrNull { it.id == id } ?: return false
        require(record.revision == expectedRevision) { "Stale delete preview" }
        persistence.writeAtomically(snapshot.copy(records = snapshot.records.filterNot { it.id == id }))
        return true
    }

    /** Exact substring matching requires no paid/network embedding calls; stored payloads are untouched. */
    fun retrieve(query: String, maxChars: Int = 2_000, maxItems: Int = 8): String {
        require(maxChars in 0..8_000 && maxItems in 0..20)
        if (maxChars == 0 || maxItems == 0) return ""
        val needle = query.lowercase(Locale.ROOT)
        val candidates = list().filter { it.draft.includeInContext }
            .filter { needle.isBlank() ||
                it.draft.title.lowercase(Locale.ROOT).contains(needle) ||
                it.draft.content.lowercase(Locale.ROOT).contains(needle) }
            .sortedWith(compareByDescending<ConfirmedMemory> { it.draft.category == ConfirmedMemoryCategory.PREFERENCE }
                .thenByDescending { it.updatedAt }.thenBy { it.id })
        val result = StringBuilder()
        var count = 0
        val seen = mutableSetOf<Triple<ConfirmedMemoryCategory, String, String>>()
        for (record in candidates) {
            val draft = record.draft
            if (!seen.add(Triple(draft.category, draft.title, draft.content))) continue
            // Include whole entries only; never split Unicode or silently truncate user payloads.
            val entry = "[User-confirmed ${draft.category}; source=${draft.source}; context only]\n" +
                "${draft.title}\n${draft.content}\n[End user-confirmed memory]\n"
            if (entry.length > maxChars - result.length) continue
            result.append(entry)
            if (++count == maxItems) break
        }
        return result.toString()
    }

    private fun readValidated(): ConfirmedMemorySnapshot {
        val snapshot = persistence.read()
        require(snapshot.schemaVersion == 1) { "Unsupported memory schema; preserve the file" }
        require(snapshot.records.size <= MAX_RECORDS)
        require(snapshot.records.map { it.id }.toSet().size == snapshot.records.size)
        snapshot.records.forEach {
            require(it.id.isNotBlank() && it.id.length <= 80 && it.revision > 0)
            require(it.createdAt >= 0 && it.updatedAt >= it.createdAt && it.confirmedAt == it.updatedAt)
            validateDraft(it.draft, it.confirmedAt)
        }
        return snapshot.copy(records = snapshot.records.toList())
    }

    private fun validateDraft(draft: MemoryDraft, time: Long) {
        require(time >= 0)
        require(draft.title.isNotBlank() && draft.title.length <= 160)
        require(draft.content.isNotBlank() && draft.content.length <= 4_096)
        require(draft.privacy != MemoryPrivacy.SECRET) { "Credentials and secrets belong in dedicated secure settings" }
        require(draft.expiresAt == null || draft.expiresAt > time)
    }

    companion object { const val MAX_RECORDS = 100 }
}
