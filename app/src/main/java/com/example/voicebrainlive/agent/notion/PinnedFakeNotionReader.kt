package com.example.voicebrainlive.agent.notion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class QueryCompletion { COMPLETE, INCOMPLETE, UNKNOWN }
enum class ReadCoverage { SYNTHETIC_TASK_PROPERTIES_ONLY }
data class ReadBudget(val maxPages: Int = 5, val pageSize: Int = 50, val maxRows: Int = 100,
    val maxCalls: Int = 5, val maxPageBytes: Int = 1_000_000, val maxTotalBytes: Int = 2_000_000,
    val maxTotalTextChars: Int = 80_000, val maxElapsedMillis: Long = 4_000, val pageTimeoutMillis: Long = 2_000)
data class TaskReadRequest(val requestId: String, val taskId: String, val stepId: String,
    val pin: ConnectionReadPin, val budget: ReadBudget, val deadlineMillis: Long)
data class SyntheticTaskRow(val pageId: String, val parentDataSourceId: String, val title: String,
    val businessStatus: String, val syncStatus: String, val priority: Int) {
    override fun toString() = "SyntheticTaskRow(pageId=$pageId, text=<redacted>)"
}
class SyntheticPageRequest internal constructor(val requestId: String, val pin: ConnectionReadPin,
    val cursor: String?, val pageSize: Int) {
    override fun toString() = "SyntheticPageRequest(requestId=$requestId, cursor=<opaque>)"
}
sealed interface SyntheticPageReply {
    data class Page(val resource: DataSourceBinding, val schemaFingerprint: String,
        val rows: List<SyntheticTaskRow>, val hasMore: Boolean, val nextCursor: String?,
        val requestStatus: QueryCompletion, val responseBytes: Int,
        val evidence: EvidenceSource = EvidenceSource.SYNTHETIC_FIXTURE) : SyntheticPageReply {
        override fun toString() = "SyntheticPage(rows=${rows.size}, cursor=<opaque>, source=SYNTHETIC_FIXTURE)"
    }
    data class Error(val failure: ProfileFailure, val retryAfterSeconds: Long? = null,
        val syntheticStatusCode: Int? = null) : SyntheticPageReply
}
/** Fake typed port only. No general URL, HTTP, mutation, legacy token or provider adapter exists. */
fun interface SyntheticNotionPort {
    suspend fun query(request: SyntheticPageRequest, secretLease: CharArray): SyntheticPageReply
}
sealed interface TaskReadResult {
    data class Complete(val requestId: String, val pin: ConnectionReadPin, val rows: List<SyntheticTaskRow>,
        val pages: Int, val calls: Int, val coverage: ReadCoverage = ReadCoverage.SYNTHETIC_TASK_PROPERTIES_ONLY) : TaskReadResult {
        override fun toString() = "Complete(source=SYNTHETIC_FIXTURE, rows=${rows.size}, text=<redacted>)"
    }
    data class Incomplete(val requestId: String, val pin: ConnectionReadPin, val rows: List<SyntheticTaskRow>,
        val failure: ProfileFailure, val pages: Int, val calls: Int, val retryAfterSeconds: Long? = null) : TaskReadResult {
        override fun toString() = "Incomplete(source=SYNTHETIC_FIXTURE, reason=$failure, rows=${rows.size})"
    }
    data class Failed(val requestId: String, val failure: ProfileFailure, val retryAfterSeconds: Long? = null) : TaskReadResult
}

class PinnedFakeNotionReader(private val profiles: NotionProfilesRepository, private val port: SyntheticNotionPort,
    private val ownerCurrent: () -> Boolean, private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    suspend fun read(input: TaskReadRequest): TaskReadResult {
        val rows = mutableListOf<SyntheticTaskRow>(); var pages = 0; var calls = 0; var bytes = 0L; var text = 0L
        var captured: TaskReadRequest? = null
        var retryHint: Long? = null
        fun failed(reason: ProfileFailure): TaskReadResult = if (rows.isNotEmpty() && reason !in setOf(ProfileFailure.STALE_BINDING,
                ProfileFailure.CANCELLED, ProfileFailure.CREDENTIAL_UNAVAILABLE, ProfileFailure.KEY_UNAVAILABLE,
                ProfileFailure.STORAGE_UNAVAILABLE, ProfileFailure.DATA_UNREADABLE))
            TaskReadResult.Incomplete(input.requestId, requireNotNull(captured).pin, profileList(rows), reason, pages, calls, retryHint)
            else TaskReadResult.Failed(input.requestId, reason, retryHint)
        try {
            val pin = freezeReadPin(input.pin); val budget = input.budget.copy()
            val request = input.copy(pin = pin, budget = budget); captured = request
            validate(request)
            val start = elapsedClock(); var lastWall = wallClock()
            suspend fun guard(checkCooldown: Boolean = true) {
                currentCoroutineContext().ensureActive()
                profileCheck(ownerCurrent(), ProfileFailure.CANCELLED)
                if (checkCooldown) profiles.assertReadable(pin)
                else profileCheck(profiles.isCurrent(pin), ProfileFailure.STALE_BINDING)
                val now = wallClock(); val elapsed = elapsedClock() - start
                profileCheck(now >= lastWall && elapsed >= 0, ProfileFailure.CLOCK_ANOMALY); lastWall = now
                profileCheck(now < request.deadlineMillis && elapsed < budget.maxElapsedMillis, ProfileFailure.TIMEOUT)
            }
            guard() // Before credential lookup.
            val result = profiles.withCredential(pin) { lease ->
                var cursor: String? = null; val seenCursors = mutableSetOf<String>(); val seenPages = mutableSetOf<String>()
                while (true) {
                    guard() // Before every page and credential use.
                    profileCheck(pages < budget.maxPages && calls < budget.maxCalls, ProfileFailure.BUDGET_EXCEEDED)
                    val timeout = minOf(budget.pageTimeoutMillis, request.deadlineMillis - wallClock(), budget.maxElapsedMillis - (elapsedClock() - start))
                    profileCheck(timeout > 0, ProfileFailure.TIMEOUT)
                    calls++
                    val reply = try {
                        withTimeoutOrNull(timeout) { port.query(SyntheticPageRequest(request.requestId, pin, cursor, budget.pageSize), lease) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { guard(); return@withCredential failed(ProfileFailure.NETWORK_FAILURE) }
                    guard() // After await, before touching potentially stale data.
                    profileCheck(reply != null, ProfileFailure.TIMEOUT)
                    when (reply) {
                        is SyntheticPageReply.Error -> {
                            profileCheck(reply.syntheticStatusCode !in setOf(429, 529) || reply.failure == ProfileFailure.RATE_LIMITED,
                                ProfileFailure.RESPONSE_INVALID)
                            profileCheck(reply.failure in setOf(ProfileFailure.AUTHENTICATION, ProfileFailure.ACCESS_UNAVAILABLE,
                                ProfileFailure.RESOURCE_UNAVAILABLE, ProfileFailure.RATE_LIMITED, ProfileFailure.NETWORK_FAILURE), ProfileFailure.RESPONSE_INVALID)
                            if (reply.failure == ProfileFailure.RATE_LIMITED) {
                                profileCheck(reply.syntheticStatusCode == null || reply.syntheticStatusCode in setOf(429, 529), ProfileFailure.RESPONSE_INVALID)
                                profileCheck(reply.retryAfterSeconds == null || reply.retryAfterSeconds >= 0, ProfileFailure.RESPONSE_INVALID)
                                retryHint = reply.retryAfterSeconds; profiles.cooldown(pin, retryHint)
                            }
                            return@withCredential failed(reply.failure)
                        }
                        is SyntheticPageReply.Page -> {
                            profileCheck(reply.evidence == EvidenceSource.SYNTHETIC_FIXTURE && reply.resource == pin.resource,
                                ProfileFailure.RESOURCE_UNAVAILABLE)
                            profileCheck(reply.schemaFingerprint == pin.schema.fingerprint, ProfileFailure.SCHEMA_MISMATCH)
                            profileCheck(reply.responseBytes in 1..budget.maxPageBytes && reply.rows.size <= budget.pageSize,
                                ProfileFailure.BUDGET_EXCEEDED)
                            bytes += reply.responseBytes; profileCheck(bytes <= budget.maxTotalBytes, ProfileFailure.BUDGET_EXCEEDED)
                            val copied = profileList(reply.rows.map { it.copy() }); pages++
                            for (row in copied) {
                                profileCheck(localId(row.pageId) && row.parentDataSourceId == pin.resource.dataSourceId && seenPages.add(row.pageId),
                                    ProfileFailure.RESPONSE_INVALID)
                                profileCheck(taskText(row.title, 2_000) && opaqueText(row.businessStatus, 80) &&
                                    (row.syncStatus.isEmpty() || opaqueText(row.syncStatus, 80)) && row.priority in 0..100,
                                    ProfileFailure.RESPONSE_INVALID)
                                profileCheck(rows.size < budget.maxRows, ProfileFailure.BUDGET_EXCEEDED)
                                text += row.title.length + row.businessStatus.length + row.syncStatus.length
                                profileCheck(text <= budget.maxTotalTextChars, ProfileFailure.BUDGET_EXCEEDED)
                                rows += row
                            }
                            if (reply.requestStatus != QueryCompletion.COMPLETE) return@withCredential TaskReadResult.Incomplete(
                                request.requestId, pin, profileList(rows), if (reply.requestStatus == QueryCompletion.INCOMPLETE)
                                    ProfileFailure.PARTIAL else ProfileFailure.RESPONSE_INVALID, pages, calls)
                            if (!reply.hasMore) {
                                profileCheck(reply.nextCursor == null, ProfileFailure.PAGINATION_INVALID)
                                guard() // Complete(empty) is possible only after an actual complete successful page.
                                return@withCredential TaskReadResult.Complete(request.requestId, pin, profileList(rows), pages, calls)
                            }
                            val next = reply.nextCursor
                            profileCheck(next != null && next.isNotEmpty() && next.length <= 1024 && seenCursors.add(next), ProfileFailure.PAGINATION_INVALID)
                            cursor = next // Exact opaque string; no UUID parsing or additional encoding.
                        }
                        null -> throw ProfileStoreException(ProfileFailure.TIMEOUT)
                    }
                }
                @Suppress("UNREACHABLE_CODE") TaskReadResult.Failed(request.requestId, ProfileFailure.RESPONSE_INVALID)
            }
            guard(checkCooldown = false) // After vault's final await, before accepting any result.
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: ProfileStoreException) {
            if (rows.isNotEmpty()) {
                try {
                    if (!ownerCurrent()) return TaskReadResult.Failed(input.requestId, ProfileFailure.CANCELLED)
                    if (!profiles.isCurrent(requireNotNull(captured).pin)) return TaskReadResult.Failed(input.requestId, ProfileFailure.STALE_BINDING)
                } catch (unavailable: ProfileStoreException) { return TaskReadResult.Failed(input.requestId, unavailable.failure) }
            }
            return failed(safe.failure)
        }
        catch (_: Exception) { return TaskReadResult.Failed(input.requestId, ProfileFailure.NETWORK_FAILURE) }
    }
    private fun validate(request: TaskReadRequest) {
        val budget = request.budget; val now = wallClock()
        profileCheck(localId(request.requestId) && localId(request.taskId) && localId(request.stepId))
        profileCheck(now >= 0 && request.deadlineMillis > now && request.deadlineMillis - now <= 60_000)
        profileCheck(budget.maxPages in 1..5 && budget.pageSize in 1..50 && budget.maxRows in 1..100 && budget.maxCalls in 1..5 &&
            budget.maxPageBytes in 1..1_000_000 && budget.maxTotalBytes in 1..2_000_000 && budget.maxTotalTextChars in 1..80_000 &&
            budget.maxElapsedMillis in 1..20_000 && budget.pageTimeoutMillis in 1..budget.maxElapsedMillis)
        profileCheck(request.pin.schema.meaning == StatusMeaning.EXPLICIT_BUSINESS_TASK, ProfileFailure.BUSINESS_SCHEMA_UNAVAILABLE)
    }
    private fun taskText(text: String, max: Int) = text.isNotBlank() && text.length <= max && text.none { it == '\u0000' } &&
        text.indices.all { i -> when { Character.isHighSurrogate(text[i]) -> i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            Character.isLowSurrogate(text[i]) -> i > 0 && Character.isHighSurrogate(text[i - 1]); else -> true } }
}
