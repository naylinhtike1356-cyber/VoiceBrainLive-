package com.example.voicebrainlive.agent.notion

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** HTTP-shaped fake exchange only. No OkHttpClient, redirect handler, socket or live registration. */
internal class RecordedNotionRequest(val request: Request) {
    override fun toString() = "RecordedNotionRequest(scope=<redacted>, credential=<redacted>)"
}
internal fun interface RecordedNotionExchange { suspend fun exchange(request: RecordedNotionRequest): RecordedNotionResponse }
internal class RecordedNotionResponse(val status: Int, bytes: ByteArray, val contentType: String = "application/json",
    val retryAfterSeconds: Long? = null) {
    init { require(bytes.size <= 1_000_000) }
    val body = bytes.copyOf()
    override fun toString() = "RecordedNotionResponse(status=$status, body=<redacted>)"
}

/** Secure credential acquisition, pagination, mappings and error/cooldown policy remain in the existing vault/reader.
 * New GETs require a page reference returned by this instance's validated scoped search first.
 * Synthetic evidence stays synthetic; no real profile migration or production HTTP enable switch exists.
 */
internal class NotionTransportReadiness(private val profiles: NotionProfilesRepository,
    private val hostOnly: Boolean = false, private val exchange: RecordedNotionExchange? = null) {
    private data class Known(val pin: ConnectionReadPin, val page: String)
    private val known = linkedSetOf<Known>()
    val adapter = NotionReadOnlyAdapter(profiles, MockNotionReadHttp { input, lease ->
        profileCheck(hostOnly && exchange != null, ProfileFailure.NOT_CONNECTED)
        val pin = freezeReadPin(input.pin)
        profileCheck(profiles.views().selectedProfileId == pin.profileId, ProfileFailure.STALE_BINDING)
        profiles.assertReadable(pin)
        if (input.operation == NotionReadOperation.RETRIEVE_PAGE)
            profileCheck(synchronized(known) { Known(pin, requireNotNull(input.pageId)) in known }, ProfileFailure.ACCESS_UNAVAILABLE)
        profileCheck(lease.size in 1..512 && lease.none { it.isISOControl() }, ProfileFailure.CREDENTIAL_UNAVAILABLE)
        val builder = Request.Builder().url("https://api.notion.com" + input.path)
            .header("Authorization", "Bearer " + String(lease)).header("Notion-Version", pin.apiVersion)
            .header("Accept", "application/json")
        if (input.method == "POST") builder.post(requireNotNull(input.body).toRequestBody("application/json".toMediaType()))
        else builder.get()
        val request = builder.build()
        profileCheck(request.url.isHttps && request.url.host == "api.notion.com" && request.url.port == 443 &&
            request.url.encodedPath == input.path && request.url.query == null && request.url.username.isEmpty() && request.url.password.isEmpty())
        val reply = requireNotNull(exchange).exchange(RecordedNotionRequest(request))
        try {
        currentCoroutineContext().ensureActive()
        profiles.assertReadable(pin)
        profileCheck(profiles.views().selectedProfileId == pin.profileId, ProfileFailure.STALE_BINDING)
        profileCheck(reply.status !in 300..399 && reply.status in 200..599, ProfileFailure.RESPONSE_INVALID)
        if (reply.status == 200) profileCheck(reply.contentType.substringBefore(';').trim().equals("application/json", true), ProfileFailure.RESPONSE_INVALID)
        // Error bodies are discarded before the adapter; never log response/headers/credential or retry uncertain writes.
        MockNotionHttpReply(pin.profileId, pin.profileRevision, reply.status,
            if (reply.status == 200) reply.body else ByteArray(0), reply.retryAfterSeconds)
        } finally { reply.body.fill(0) }
    })
    suspend fun search(pin: ConnectionReadPin, query: String): List<NotionReadReference> {
        val captured = freezeReadPin(pin); val result = adapter.search(captured, query)
        synchronized(known) {
            known.clear()
            result.filter { it.kind == "page" }.forEach { known += Known(captured, it.provenance.sourceId) }
        }
        return result
    }
    suspend fun retrieveKnown(pin: ConnectionReadPin, pageId: String) = adapter.retrieve(pin, pageId)
    fun forgetReferences() { synchronized(known) { known.clear() } }
}

/** Conversation -> recorded structured proposal -> SAME planner/admission/runner -> pinned fake HTTP -> draft.
 * Full binding is RAM-only until a separate durable Brain codec compatibility gate is implemented.
 */
internal class ProviderNotionReadiness(private val provider: BrainStructuredPort, private val profiles: NotionProfilesRepository,
    private val transport: NotionTransportReadiness, private val memory: BrainMemoryAccess,
    private val current: () -> Boolean, private val now: () -> Long) {
    suspend fun review(goal: String, pin: ConnectionReadPin, session: String): ReadOnlyReviewResult = withContext(Dispatchers.IO) {
        val frozen = freezeReadPin(pin); val memoryView = memory.capture("study"); val binding = agentPin(frozen)
        fun owner() = current() && memory.capture("study").fingerprint == memoryView.fingerprint
        profileCheck(owner() && profiles.views().selectedProfileId == frozen.profileId, ProfileFailure.STALE_BINDING)
        val request = BrainGoalRequest(UUID.randomUUID().toString(), goal, session, binding, "study")
        val plan = BrainPlanner(StructuredBrainModel(provider), now).planReadOnlyTransient(request, memoryView)
        val local = object : AgentReadPort {
            override suspend fun clock() = AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_SELECTED")
            override suspend fun battery() = AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_SELECTED")
            override suspend fun tasks(connection: AgentConnectionPin) = AgentObservation(AgentStatus.BLOCKED, errorCode = "NOT_CONNECTED")
        }
        val reads = SyntheticStudyReadPort(local, profiles, transport.adapter,
            { CapturedStudyReadContext(plan.taskId, "tasks", plan.deadlineMillis, ::owner) }, now)
        val result = AgentRunner(reads, ::owner, { it == binding && profiles.isCurrentCached(frozen) }, wallClock = now).run(plan)
        profileCheck(owner() && profiles.isCurrent(frozen) && profiles.views().selectedProfileId == frozen.profileId, ProfileFailure.STALE_BINDING)
        ReadOnlyReviewResult(result, NotionReadProvenance(frozen, frozen.resource.dataSourceId))
    }
}
