package com.example.voicebrainlive.agent.notion

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Closed read operations. POST query/search are reads; no arbitrary URL or mutation operation exists. */
enum class NotionReadOperation { QUERY_TASKS, SEARCH, RETRIEVE_PAGE }
class NotionReadHttpRequest internal constructor(val operation: NotionReadOperation, val pin: ConnectionReadPin,
    val pageId: String?, val body: String?) {
    val method get() = if (operation == NotionReadOperation.RETRIEVE_PAGE) "GET" else "POST"
    val path get() = when (operation) {
        NotionReadOperation.QUERY_TASKS -> "/v1/data_sources/${pin.resource.dataSourceId}/query"
        NotionReadOperation.SEARCH -> "/v1/search"
        NotionReadOperation.RETRIEVE_PAGE -> "/v1/pages/$pageId"
    }
    override fun toString() = "NotionReadHttpRequest($operation, scope=<redacted>)"
}
class MockNotionHttpReply(val profileId: String, val profileRevision: Long, val status: Int,
    body: ByteArray, val retryAfterSeconds: Long? = null) {
    internal val body = body.copyOf()
    override fun toString() = "MockNotionHttpReply(status=$status, body=<redacted>)"
}
/** Injection seam ONLY. No production implementation, factory, registration or live HTTP client. */
fun interface MockNotionReadHttp { suspend fun exchange(request: NotionReadHttpRequest, lease: CharArray): MockNotionHttpReply }
data class NotionReadProvenance(val pin: ConnectionReadPin, val sourceId: String,
    val coverage: ReadCoverage = ReadCoverage.SYNTHETIC_TASK_PROPERTIES_ONLY) {
    override fun toString() = "NotionReadProvenance(source=<redacted>, evidence=SYNTHETIC_FIXTURE)"
}
data class NotionReadReference(val provenance: NotionReadProvenance, val kind: String, val title: String) {
    override fun toString() = "NotionReadReference(kind=$kind, content=<redacted>)"
}
data class NotionReadTask(val row: SyntheticTaskRow, val provenance: NotionReadProvenance)

/** HTTP-shape readiness adapter. Default is disabled; all admitted pins remain SYNTHETIC_FIXTURE.
 * Existing PinnedFakeNotionReader owns query pagination/leases/cooldown/budgets. Content is data only.
 */
class NotionReadOnlyAdapter(private val profiles: NotionProfilesRepository,
    private val http: MockNotionReadHttp? = null) : SyntheticNotionPort {
    private suspend fun guard(pin: ConnectionReadPin) {
        currentCoroutineContext().ensureActive()
        profileCheck(http != null, ProfileFailure.NOT_CONNECTED)
        freezeReadPin(pin)
        profileCheck(profiles.views().selectedProfileId == pin.profileId, ProfileFailure.STALE_BINDING)
        profiles.assertReadable(pin)
    }
    private suspend fun call(request: NotionReadHttpRequest, lease: CharArray): MockNotionHttpReply {
        guard(request.pin)
        val result = try { withTimeoutOrNull(2_000) { requireNotNull(http).exchange(request, lease) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: ProfileStoreException) { throw safe }
        catch (_: Exception) { throw ProfileStoreException(ProfileFailure.NETWORK_FAILURE) }
        guard(request.pin)
        profileCheck(result != null, ProfileFailure.TIMEOUT)
        val reply = requireNotNull(result)
        profileCheck(reply.profileId == request.pin.profileId && reply.profileRevision == request.pin.profileRevision,
            ProfileFailure.STALE_BINDING)
        profileCheck(reply.body.size <= 1_000_000 && (reply.retryAfterSeconds == null || reply.retryAfterSeconds in 0..86_400),
            ProfileFailure.BUDGET_EXCEEDED)
        return reply
    }
    private fun error(reply: MockNotionHttpReply): ProfileFailure? = when (reply.status) {
        200 -> null
        401 -> ProfileFailure.AUTHENTICATION
        403 -> ProfileFailure.ACCESS_UNAVAILABLE
        404 -> ProfileFailure.RESOURCE_UNAVAILABLE
        429, 529 -> ProfileFailure.RATE_LIMITED
        in 500..599 -> ProfileFailure.NETWORK_FAILURE
        else -> ProfileFailure.RESPONSE_INVALID
    }
    private suspend fun checked(request: NotionReadHttpRequest): Map<String, Any?> {
        guard(request.pin)
        return profiles.withCredential(request.pin) { lease ->
            val reply = call(request, lease)
            error(reply)?.let {
                if (it == ProfileFailure.RATE_LIMITED) profiles.cooldown(request.pin, reply.retryAfterSeconds)
                throw ProfileStoreException(it)
            }
            val decoded = ReadJson.objectValue(ReadJson.decode(reply.body))
            guard(request.pin); decoded
        }.also { guard(request.pin) }
    }
    override suspend fun query(request: SyntheticPageRequest, secretLease: CharArray): SyntheticPageReply {
        profileCheck(request.pageSize in 1..50 && (request.cursor == null || request.cursor.length in 1..1024))
        val body = JSONObject().put("page_size", request.pageSize).apply { request.cursor?.let { put("start_cursor", it) } }.toString()
        val reply = call(NotionReadHttpRequest(NotionReadOperation.QUERY_TASKS, request.pin, null, body), secretLease)
        error(reply)?.let { return SyntheticPageReply.Error(it, reply.retryAfterSeconds, reply.status) }
        val page = ReadJson.objectValue(ReadJson.decode(reply.body)); val (items, more, cursor) = envelope(page)
        profileCheck(items.size <= request.pageSize, ProfileFailure.BUDGET_EXCEEDED)
        val rows = items.map { task(ReadJson.objectValue(it), request.pin) }
        guard(request.pin)
        return SyntheticPageReply.Page(request.pin.resource, request.pin.schema.fingerprint, profileList(rows), more, cursor,
            QueryCompletion.COMPLETE, reply.body.size)
    }
    /** Search is narrowed to the selected, pinned source; no workspace-wide indexing or block content. */
    suspend fun search(pin: ConnectionReadPin, query: String): List<NotionReadReference> {
        val captured = freezeReadPin(pin)
        profileCheck(query.length <= 200 && query.none { it.isISOControl() })
        val seen = mutableSetOf<String>(); val ids = mutableSetOf<String>(); val results = mutableListOf<NotionReadReference>()
        var cursor: String? = null; var totalBytes = 0L
        repeat(5) {
            val body = JSONObject().put("query", query).put("page_size", 50).apply { cursor?.let { put("start_cursor", it) } }.toString()
            val page = checked(NotionReadHttpRequest(NotionReadOperation.SEARCH, captured, null, body))
            // The decoder's per-page bound plus five-call bound limits total allocation; returned content is bounded too.
            val (items, more, next) = envelope(page)
            for (raw in items) {
                val item = ReadJson.objectValue(raw); val kind = ReadJson.string(item["object"])
                val id = ReadJson.string(item["id"]); profileCheck(localId(id), ProfileFailure.RESPONSE_INVALID)
                val parent = ReadJson.objectValue(item["parent"])
                val belongs = when (kind) {
                    "page" -> parent["data_source_id"] == captured.resource.dataSourceId
                    "data_source" -> id == captured.resource.dataSourceId && parent["database_id"] == captured.resource.databaseId
                    else -> throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID)
                }
                if (!belongs) continue // Never surface another resource's content, even under the same account.
                profileCheck(ids.add(id), ProfileFailure.RESPONSE_INVALID)
                val title = if (kind == "page") title(property(item, captured.schema.title)["title"])
                    else title(item["title"])
                totalBytes += title.length
                profileCheck(results.size < 100 && totalBytes <= 80_000, ProfileFailure.BUDGET_EXCEEDED)
                results += NotionReadReference(NotionReadProvenance(captured, id), kind, title)
            }
            guard(captured)
            if (!more) return profileList(results)
            profileCheck(next != null && seen.add(next), ProfileFailure.PAGINATION_INVALID); cursor = next
        }
        throw ProfileStoreException(ProfileFailure.BUDGET_EXCEEDED) // No partial list presented as exhaustive.
    }
    suspend fun retrieve(pin: ConnectionReadPin, pageId: String): NotionReadTask {
        val captured = freezeReadPin(pin); profileCheck(localId(pageId))
        val page = checked(NotionReadHttpRequest(NotionReadOperation.RETRIEVE_PAGE, captured, pageId, null))
        profileCheck(page["id"] == pageId, ProfileFailure.RESPONSE_INVALID)
        val row = task(page, captured); guard(captured)
        return NotionReadTask(row, NotionReadProvenance(captured, row.pageId))
    }
    private fun envelope(page: Map<String, Any?>): Triple<List<Any?>, Boolean, String?> {
        profileCheck(page["object"] == "list", ProfileFailure.RESPONSE_INVALID)
        val rows = ReadJson.array(page["results"]); val more = page["has_more"] as? Boolean
            ?: throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID)
        profileCheck(page.containsKey("next_cursor"), ProfileFailure.PAGINATION_INVALID)
        val cursor = page["next_cursor"]?.let(ReadJson::string)
        profileCheck(if (more) cursor != null && cursor.length in 1..1024 else cursor == null, ProfileFailure.PAGINATION_INVALID)
        return Triple(rows, more, cursor)
    }
    private fun task(page: Map<String, Any?>, pin: ConnectionReadPin): SyntheticTaskRow {
        profileCheck(page["object"] == "page" && page["archived"] != true && page["in_trash"] != true, ProfileFailure.RESOURCE_UNAVAILABLE)
        val parent = ReadJson.objectValue(page["parent"])
        profileCheck(parent["type"] == "data_source_id" && parent["data_source_id"] == pin.resource.dataSourceId &&
            (parent["database_id"] == null || parent["database_id"] == pin.resource.databaseId), ProfileFailure.STALE_BINDING)
        val id = ReadJson.string(page["id"]); profileCheck(localId(id), ProfileFailure.RESPONSE_INVALID)
        val schema = pin.schema
        profileCheck(schema.meaning == StatusMeaning.EXPLICIT_BUSINESS_TASK, ProfileFailure.BUSINESS_SCHEMA_UNAVAILABLE)
        fun status(field: PropertyPin): String {
            val p = property(page, field); return if (field.kind == PropertyKind.RICH_TEXT) title(p["rich_text"])
                else ReadJson.string(ReadJson.objectValue(p[field.kind.name.lowercase(java.util.Locale.ROOT)])["name"])
        }
        val priority = schema.priority?.let { property(page, it)["number"] as? BigDecimal
            ?: throw ProfileStoreException(ProfileFailure.SCHEMA_MISMATCH) }?.let {
            try { it.intValueExact().also { n -> profileCheck(n in 0..100, ProfileFailure.RESPONSE_INVALID) } }
            catch (_: ArithmeticException) { throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID) }
        } ?: 0
        return SyntheticTaskRow(id, pin.resource.dataSourceId, title(property(page, schema.title)["title"]),
            status(schema.businessStatus), schema.syncStatus?.let(::status).orEmpty(), priority)
    }
    private fun property(page: Map<String, Any?>, pin: PropertyPin): Map<String, Any?> {
        val fields = ReadJson.objectValue(page["properties"]).values.map(ReadJson::objectValue)
        val p = fields.singleOrNull { it["id"] == pin.propertyId } ?: throw ProfileStoreException(ProfileFailure.SCHEMA_MISMATCH)
        profileCheck(p["type"] == pin.kind.name.lowercase(java.util.Locale.ROOT) && p["has_more"] != true, ProfileFailure.SCHEMA_MISMATCH)
        return p
    }
    private fun title(value: Any?): String {
        val parts = ReadJson.array(value); profileCheck(parts.size <= 25, ProfileFailure.BUDGET_EXCEEDED)
        val text = parts.joinToString("") { ReadJson.string(ReadJson.objectValue(it)["plain_text"]) }
        profileCheck(text.isNotBlank() && text.length <= 2_000 && !text.contains('\u0000'), ProfileFailure.RESPONSE_INVALID)
        return text
    }
}

/** Bounded strict JSON; no coercion, duplicate keys, trailing input or executable content. */
internal object ReadJson {
    fun decode(bytes: ByteArray): Any? = try {
        profileCheck(bytes.size in 1..1_000_000, ProfileFailure.BUDGET_EXCEEDED)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val reader = JsonReader(StringReader(text)).apply { isLenient = false }
        var nodes = 0
        fun read(depth: Int): Any? {
            profileCheck(depth <= 12 && ++nodes <= 10_000, ProfileFailure.BUDGET_EXCEEDED)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().also { map ->
                    reader.beginObject(); while (reader.hasNext()) {
                        profileCheck(map.size < 128, ProfileFailure.BUDGET_EXCEEDED)
                        val key = reader.nextName(); profileCheck(!map.containsKey(key), ProfileFailure.RESPONSE_INVALID)
                        map[key] = read(depth + 1)
                    }; reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> mutableListOf<Any?>().also { list ->
                    reader.beginArray(); while (reader.hasNext()) { profileCheck(list.size < 100, ProfileFailure.BUDGET_EXCEEDED); list += read(depth + 1) }; reader.endArray()
                }
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextString().let { raw ->
                    profileCheck(raw.length <= 64, ProfileFailure.BUDGET_EXCEEDED)
                    raw.toBigDecimal().also { profileCheck(it.scale() in -100..100, ProfileFailure.BUDGET_EXCEEDED) }
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID)
            }
        }
        reader.use { read(0).also { profileCheck(reader.peek() == JsonToken.END_DOCUMENT, ProfileFailure.RESPONSE_INVALID) } }
    } catch (safe: ProfileStoreException) { throw safe }
    catch (_: Exception) { throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID) }
    @Suppress("UNCHECKED_CAST") fun objectValue(value: Any?) = value as? Map<String, Any?> ?: throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID)
    @Suppress("UNCHECKED_CAST") fun array(value: Any?) = value as? List<Any?> ?: throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID)
    fun string(value: Any?) = value as? String ?: throw ProfileStoreException(ProfileFailure.RESPONSE_INVALID)
}
