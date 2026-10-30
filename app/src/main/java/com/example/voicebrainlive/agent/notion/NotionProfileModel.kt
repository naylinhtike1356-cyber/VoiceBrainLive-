package com.example.voicebrainlive.agent.notion

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

enum class ProfileFailure { INVALID_REQUEST, STALE_BINDING, CAPACITY_REACHED, CLOCK_ANOMALY,
    KEY_UNAVAILABLE, STORAGE_UNAVAILABLE, DATA_UNREADABLE, UNSUPPORTED_SCHEMA, RECOVERY_REQUIRED,
    NOT_CONNECTED, CREDENTIAL_UNAVAILABLE, BUSINESS_SCHEMA_UNAVAILABLE, AUTHENTICATION,
    ACCESS_UNAVAILABLE, RESOURCE_UNAVAILABLE, SCHEMA_MISMATCH, RATE_LIMITED, TIMEOUT,
    CANCELLED, RESPONSE_INVALID, PAGINATION_INVALID, BUDGET_EXCEEDED, NETWORK_FAILURE, PARTIAL }
class ProfileStoreException(val failure: ProfileFailure) : Exception("Notion profile: ${failure.name}")
enum class DirectProfileStatus { DRAFT, SYNTHETIC_VERIFIED, DISABLED, REVOKED }
enum class EvidenceSource { SYNTHETIC_FIXTURE }
enum class NamespaceKind { LOCAL_PROFILE_NAMESPACE }
enum class PropertyKind { TITLE, STATUS, SELECT, NUMBER, RICH_TEXT }
enum class StatusMeaning { EXPLICIT_BUSINESS_TASK, SYNC_ONLY }
data class CredentialRef(val credentialId: String, val version: Long)
data class LocalNamespace(val namespaceId: String, val kind: NamespaceKind = NamespaceKind.LOCAL_PROFILE_NAMESPACE,
    val providerWorkspaceId: String? = null)
data class PropertyPin(val propertyId: String, val kind: PropertyKind)
data class DataSourceBinding(val databaseId: String, val dataSourceId: String)
data class TaskSchemaPin(val revision: Long, val fingerprint: String, val title: PropertyPin,
    val businessStatus: PropertyPin, val unfinishedValues: Set<String>, val priority: PropertyPin? = null,
    val syncStatus: PropertyPin? = null, val meaning: StatusMeaning = StatusMeaning.EXPLICIT_BUSINESS_TASK)
data class ProfileDraft(val displayName: String, val credential: CredentialRef,
    val resource: DataSourceBinding, val schema: TaskSchemaPin, val apiVersion: String = "2026-03-11")
data class ConnectionProfileView(val profileId: String, val revision: Long, val displayName: String,
    val status: DirectProfileStatus, val credential: CredentialRef, val namespace: LocalNamespace,
    val apiVersion: String, val resource: DataSourceBinding, val schema: TaskSchemaPin,
    val evidence: EvidenceSource? = null)
data class CredentialView(val credentialId: String, val version: Long, val label: String,
    val namespace: LocalNamespace, val revoked: Boolean)
data class ProfileCooldownView(val credentialId: String, val until: Long, val retryAfterSeconds: Long?, val requiresReview: Boolean)
data class ProfileVaultView(val revision: Long, val profiles: List<ConnectionProfileView>,
    val credentials: List<CredentialView>, val selectedProfileId: String?, val cooldowns: List<ProfileCooldownView> = emptyList())
data class ConnectionReadPin(val profileId: String, val profileRevision: Long, val credential: CredentialRef,
    val namespace: LocalNamespace, val apiVersion: String, val resource: DataSourceBinding,
    val schema: TaskSchemaPin, val evidence: EvidenceSource)
data class SyntheticVerificationEvidence(val resource: DataSourceBinding, val schemaFingerprint: String,
    val apiVersion: String = "2026-03-11", val completeSchema: Boolean, val canRead: Boolean,
    val source: EvidenceSource = EvidenceSource.SYNTHETIC_FIXTURE)

/** Owns a copy; never put this input in saved state, profile/plan metadata, logs or exceptions. */
class SecretInput(value: CharArray) : AutoCloseable {
    private val owned = value.copyOf()
    private var closed = false
    internal fun copySecret(): CharArray {
        profileCheck(!closed && owned.size in 1..512 && owned.none { it == '\u0000' || it.isISOControl() })
        return owned.copyOf()
    }
    override fun close() { owned.fill('\u0000'); closed = true }
    override fun toString() = "SecretInput(<redacted>)"
}
internal class StoredCredential(val view: CredentialView, secret: CharArray?) {
    private val owned = secret?.copyOf()
    fun copySecret(): CharArray? = owned?.copyOf()
    fun clear() { owned?.fill('\u0000') }
    override fun toString() = "StoredCredential(<redacted>)"
}
internal data class CredentialCooldown(val credentialId: String, val until: Long, val retryAfterSeconds: Long?)
class ProfileVaultState internal constructor(internal val revision: Long = 0, internal val updatedAt: Long = 0,
    internal val profiles: List<ConnectionProfileView> = emptyList(), internal val credentials: List<StoredCredential> = emptyList(),
    internal val selectedProfileId: String? = null, internal val cooldowns: List<CredentialCooldown> = emptyList()) {
    fun clearSecrets() { credentials.forEach { it.clear() } }
    override fun toString() = "ProfileVaultState(revision=$revision, credentials=<redacted>)"
}
internal fun profileCheck(ok: Boolean, failure: ProfileFailure = ProfileFailure.INVALID_REQUEST) {
    if (!ok) throw ProfileStoreException(failure)
}
internal fun localId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,80}"))
internal fun opaqueText(value: String, limit: Int) = value.isNotBlank() && value.length <= limit &&
    value.none { it.isISOControl() } && value.indices.all { index -> when {
        Character.isHighSurrogate(value[index]) -> index + 1 < value.length && Character.isLowSurrogate(value[index + 1])
        Character.isLowSurrogate(value[index]) -> index > 0 && Character.isHighSurrogate(value[index - 1])
        else -> true
    } }
internal fun <T> profileList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun schemaFingerprint(schema: TaskSchemaPin): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeLong(schema.revision); out.writeUTF(schema.meaning.name)
        listOf(schema.title, schema.businessStatus, schema.priority, schema.syncStatus).forEach { field ->
            out.writeBoolean(field != null); field?.let { out.writeUTF(it.propertyId); out.writeUTF(it.kind.name) }
        }
        out.writeInt(schema.unfinishedValues.size); schema.unfinishedValues.sorted().forEach(out::writeUTF)
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
fun taskSchema(revision: Long, title: PropertyPin, businessStatus: PropertyPin, unfinishedValues: Set<String>,
    priority: PropertyPin? = null, syncStatus: PropertyPin? = null,
    meaning: StatusMeaning = StatusMeaning.EXPLICIT_BUSINESS_TASK): TaskSchemaPin {
    profileCheck(unfinishedValues.size in 1..10 && unfinishedValues.all { opaqueText(it, 80) } &&
        listOfNotNull(title, businessStatus, priority, syncStatus).all { opaqueText(it.propertyId, 80) })
    val base = TaskSchemaPin(revision, "", title, businessStatus,
        Collections.unmodifiableSet(LinkedHashSet(unfinishedValues)), priority, syncStatus, meaning)
    return freezeSchema(base.copy(fingerprint = schemaFingerprint(base)))
}
internal fun freezeSchema(schema: TaskSchemaPin): TaskSchemaPin {
    profileCheck(schema.unfinishedValues.size in 1..10)
    val copied = schema.copy(unfinishedValues = Collections.unmodifiableSet(LinkedHashSet(schema.unfinishedValues)))
    profileCheck(copied.revision > 0 && copied.fingerprint.matches(Regex("[0-9a-f]{64}")))
    profileCheck(copied.title.kind == PropertyKind.TITLE && copied.businessStatus.kind in setOf(PropertyKind.STATUS, PropertyKind.SELECT))
    val properties = listOfNotNull(copied.title, copied.businessStatus, copied.priority, copied.syncStatus)
    // Provider property IDs are opaque, including short and URL-encoded strings; do not re-encode.
    profileCheck(properties.all { opaqueText(it.propertyId, 80) } && properties.map { it.propertyId }.toSet().size == properties.size)
    profileCheck(copied.unfinishedValues.all { opaqueText(it, 80) })
    profileCheck(copied.priority == null || copied.priority.kind == PropertyKind.NUMBER)
    profileCheck(copied.syncStatus == null || copied.syncStatus.kind in setOf(PropertyKind.STATUS, PropertyKind.SELECT, PropertyKind.RICH_TEXT))
    profileCheck(copied.fingerprint == schemaFingerprint(copied))
    return copied
}
fun freezeReadPin(pin: ConnectionReadPin): ConnectionReadPin {
    profileCheck(localId(pin.profileId) && pin.profileRevision > 0 && localId(pin.credential.credentialId) && pin.credential.version > 0)
    profileCheck(localId(pin.namespace.namespaceId) && pin.namespace.providerWorkspaceId == null &&
        pin.namespace.kind == NamespaceKind.LOCAL_PROFILE_NAMESPACE && pin.apiVersion == "2026-03-11")
    profileCheck(localId(pin.resource.databaseId) && localId(pin.resource.dataSourceId))
    profileCheck(pin.evidence == EvidenceSource.SYNTHETIC_FIXTURE)
    return pin.copy(schema = freezeSchema(pin.schema))
}
internal fun profilePin(profile: ConnectionProfileView) = freezeReadPin(ConnectionReadPin(profile.profileId, profile.revision,
    profile.credential, profile.namespace, profile.apiVersion, profile.resource, profile.schema,
    requireNotNull(profile.evidence)))
internal fun freezeVault(input: ProfileVaultState): ProfileVaultState {
    profileCheck(input.revision >= 0 && input.updatedAt >= 0 && input.profiles.size <= 32 && input.credentials.size <= 16 && input.cooldowns.size <= 16)
    profileCheck(input.profiles.map { it.profileId }.toSet().size == input.profiles.size &&
        input.credentials.map { it.view.credentialId }.toSet().size == input.credentials.size &&
        input.cooldowns.map { it.credentialId }.toSet().size == input.cooldowns.size)
    profileCheck(input.revision > 0 || (input.profiles.isEmpty() && input.credentials.isEmpty()))
    val credentials = mutableListOf<StoredCredential>()
    try {
    input.credentials.forEach { item ->
        val view = item.view; val secret = item.copySecret()
        try {
            profileCheck(localId(view.credentialId) && view.version > 0 && opaqueText(view.label, 80) &&
                localId(view.namespace.namespaceId) && view.namespace.providerWorkspaceId == null)
            profileCheck(if (view.revoked) secret == null else secret != null && secret.size in 1..512 && secret.none { it == '\u0000' || it.isISOControl() })
            credentials += StoredCredential(view.copy(), secret)
        } finally { secret?.fill('\u0000') }
    }
    val profiles = input.profiles.map { profile ->
        profileCheck(localId(profile.profileId) && profile.revision > 0 && opaqueText(profile.displayName, 80))
        val credential = credentials.singleOrNull { it.view.credentialId == profile.credential.credentialId }
            ?: throw ProfileStoreException(ProfileFailure.INVALID_REQUEST)
        profileCheck(profile.credential.version == credential.view.version && profile.namespace == credential.view.namespace &&
            profile.apiVersion == "2026-03-11" && localId(profile.resource.databaseId) && localId(profile.resource.dataSourceId))
        profileCheck((profile.status == DirectProfileStatus.SYNTHETIC_VERIFIED) == (profile.evidence == EvidenceSource.SYNTHETIC_FIXTURE))
        profileCheck(!credential.view.revoked || profile.status == DirectProfileStatus.REVOKED)
        profile.copy(schema = freezeSchema(profile.schema))
    }
    profileCheck(input.selectedProfileId == null || profiles.any { it.profileId == input.selectedProfileId && it.status != DirectProfileStatus.REVOKED })
    input.cooldowns.forEach { profileCheck(credentials.any { credential -> credential.view.credentialId == it.credentialId } && it.until >= 0 &&
        (it.retryAfterSeconds == null || it.retryAfterSeconds >= 0)) }
    return ProfileVaultState(input.revision, input.updatedAt, profileList(profiles), profileList(credentials),
        input.selectedProfileId, profileList(input.cooldowns.map { it.copy() }))
    } catch (failure: Throwable) {
        credentials.forEach { it.clear() }
        throw failure
    }
}
