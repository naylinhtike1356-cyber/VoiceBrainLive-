package com.example.voicebrainlive.memory.confirmed

/** UI-local boundary; no external tool grants, credentials or legacy-memory APIs. */
interface ConfirmedMemoryStore {
    fun list(includeExpired: Boolean = false): List<ConfirmedMemory>
    fun save(draft: MemoryDraft, confirmedExactPreview: Boolean): ConfirmedMemory
    fun correct(id: String, revision: Long, draft: MemoryDraft, confirmedExactPreview: Boolean): ConfirmedMemory
    fun forget(id: String, revision: Long, confirmedExactPreview: Boolean): Boolean
}
