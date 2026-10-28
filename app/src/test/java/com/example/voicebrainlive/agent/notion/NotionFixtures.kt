package com.example.voicebrainlive.agent.notion

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*

/** Synthetic in-memory serialization seam only; production factory always encrypts. */
internal class FakeVaultPersistence : NotionProfilesPersistence {
    var bytes = NotionProfilesCodec.encode(ProfileVaultState())
    var writes = 0; var failRead: ProfileFailure? = null; var failWrite = false
    override fun readOwned(): ProfileVaultState { failRead?.let { throw ProfileStoreException(it) }; return NotionProfilesCodec.decode(bytes) }
    override fun compareAndReplace(expectedRevision: Long, next: ProfileVaultState): Boolean {
        if (failWrite) throw IOException("FAKE_SECRET_ERROR_BODY")
        val current = readOwned()
        try {
            if (current.revision != expectedRevision) return false
            bytes = NotionProfilesCodec.encode(next); writes++; return true
        } finally { current.clearSecrets() }
    }
}
internal class NotionFixture {
    var now = 1_000L; var elapsed = 0L; var owner = true
    val ids = AtomicInteger(); val persistence = FakeVaultPersistence()
    fun repository() = NotionProfilesRepository(persistence, { now }, { "local_${ids.incrementAndGet()}" }, Dispatchers.Unconfined)
    val repo = repository()
    suspend fun credential(name: String) = SecretInput("FAKE_$name".toCharArray()).use { repo.addCredential(name, it) }
    fun schema(meaning: StatusMeaning = StatusMeaning.EXPLICIT_BUSINESS_TASK, property: String = "business") = taskSchema(1,
        PropertyPin("title", PropertyKind.TITLE), PropertyPin(property, PropertyKind.STATUS), setOf("Todo"),
        PropertyPin("priority", PropertyKind.NUMBER), PropertyPin("sync", PropertyKind.SELECT), meaning)
    suspend fun profile(name: String, credential: CredentialView? = null, schema: TaskSchemaPin = schema()): ConnectionProfileView {
        val selected = credential ?: this.credential(name)
        val draft = repo.createDraft(ProfileDraft(name, CredentialRef(selected.credentialId, selected.version), DataSourceBinding("db_$name", "ds_$name"), schema))
        return verify(draft)
    }
    suspend fun verify(profile: ConnectionProfileView): ConnectionProfileView = repo.verifySynthetic(profile.profileId, profile.revision,
        SyntheticVerificationEvidence(profile.resource, profile.schema.fingerprint, completeSchema = true, canRead = true))
    suspend fun pin(profile: ConnectionProfileView) = repo.pin(profile.profileId, profile.revision)
    fun request(pin: ConnectionReadPin, budget: ReadBudget = ReadBudget()) = TaskReadRequest("request", "task", "tasks", pin, budget, now + 10_000)
    fun reader(port: SyntheticNotionPort, repository: NotionProfilesRepository = repo) = PinnedFakeNotionReader(repository, port, { owner }, { now }, { elapsed })
    fun row(pin: ConnectionReadPin, id: String = "page", title: String = "မင်္ဂလာပါ 한국어 English") =
        SyntheticTaskRow(id, pin.resource.dataSourceId, title, "Todo", "Pending", 10)
    fun page(pin: ConnectionReadPin, rows: List<SyntheticTaskRow> = emptyList(), more: Boolean = false, cursor: String? = null,
        status: QueryCompletion = QueryCompletion.COMPLETE) = SyntheticPageReply.Page(pin.resource, pin.schema.fingerprint, rows, more, cursor, status, 100)
}
internal suspend fun profileRejects(expected: ProfileFailure, action: suspend () -> Unit) {
    try { action(); fail("Expected $expected") } catch (safe: ProfileStoreException) {
        assertEquals(expected, safe.failure); assertFalse(safe.message.orEmpty().contains("FAKE_SECRET"))
    }
}
