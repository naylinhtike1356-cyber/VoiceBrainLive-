package com.example.voicebrainlive.agent.notion

import android.content.Context
import com.example.voicebrainlive.storage.snapshot.AuthenticatedSnapshotBytes
import com.example.voicebrainlive.storage.snapshot.AndroidNoBackupSnapshot
import com.example.voicebrainlive.storage.snapshot.SnapshotDomain
import com.example.voicebrainlive.storage.snapshot.SnapshotStorageException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal object NotionProfilesCodec {
    const val LIMIT = 256 * 1024 - 29
    fun encode(input: ProfileVaultState): ByteArray {
        val state = freezeVault(input); val bytes = ByteArrayOutputStream()
        try {
            DataOutputStream(bytes).use { out ->
                fun namespace(value: LocalNamespace) { out.writeUTF(value.namespaceId); out.writeUTF(value.kind.name); out.writeBoolean(value.providerWorkspaceId != null); value.providerWorkspaceId?.let(out::writeUTF) }
                fun property(value: PropertyPin) { out.writeUTF(value.propertyId); out.writeUTF(value.kind.name) }
                fun optionalProperty(value: PropertyPin?) { out.writeBoolean(value != null); value?.let(::property) }
                out.writeInt(1); out.writeLong(state.revision); out.writeLong(state.updatedAt)
                out.writeBoolean(state.selectedProfileId != null); state.selectedProfileId?.let(out::writeUTF)
                out.writeInt(state.credentials.size)
                state.credentials.forEach { credential ->
                    val view = credential.view; out.writeUTF(view.credentialId); out.writeLong(view.version); out.writeUTF(view.label)
                    namespace(view.namespace); out.writeBoolean(view.revoked)
                    val secret = credential.copySecret()
                    try { out.writeInt(secret?.size ?: -1); secret?.forEach { out.writeChar(it.code) } }
                    finally { secret?.fill('\u0000') }
                }
                out.writeInt(state.profiles.size)
                state.profiles.forEach { profile ->
                    out.writeUTF(profile.profileId); out.writeLong(profile.revision); out.writeUTF(profile.displayName); out.writeUTF(profile.status.name)
                    out.writeUTF(profile.credential.credentialId); out.writeLong(profile.credential.version); namespace(profile.namespace)
                    out.writeUTF(profile.apiVersion); out.writeUTF(profile.resource.databaseId); out.writeUTF(profile.resource.dataSourceId)
                    val schema = profile.schema; out.writeLong(schema.revision); out.writeUTF(schema.fingerprint)
                    property(schema.title); property(schema.businessStatus); out.writeInt(schema.unfinishedValues.size)
                    schema.unfinishedValues.sorted().forEach(out::writeUTF); optionalProperty(schema.priority); optionalProperty(schema.syncStatus); out.writeUTF(schema.meaning.name)
                    out.writeBoolean(profile.evidence != null); profile.evidence?.let { out.writeUTF(it.name) }
                }
                out.writeInt(state.cooldowns.size)
                state.cooldowns.forEach { out.writeUTF(it.credentialId); out.writeLong(it.until); out.writeBoolean(it.retryAfterSeconds != null); it.retryAfterSeconds?.let(out::writeLong) }
            }
            return bytes.toByteArray().also { profileCheck(it.size <= LIMIT) }
        } finally { state.clearSecrets() }
    }
    fun decode(bytes: ByteArray): ProfileVaultState {
        val credentials = mutableListOf<StoredCredential>()
        try {
            profileCheck(bytes.size <= LIMIT)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                fun namespace(): LocalNamespace {
                    val id = input.readUTF(); val kind = NamespaceKind.valueOf(input.readUTF())
                    return LocalNamespace(id, kind, if (input.readBoolean()) input.readUTF() else null)
                }
                fun property() = PropertyPin(input.readUTF(), PropertyKind.valueOf(input.readUTF()))
                fun optionalProperty() = if (input.readBoolean()) property() else null
                profileCheck(input.readInt() == 1, ProfileFailure.UNSUPPORTED_SCHEMA)
                val revision = input.readLong(); val updated = input.readLong(); val selected = if (input.readBoolean()) input.readUTF() else null
                val credentialCount = input.readInt(); profileCheck(credentialCount in 0..16)
                repeat(credentialCount) {
                    val id = input.readUTF(); val version = input.readLong(); val label = input.readUTF(); val ns = namespace(); val revoked = input.readBoolean()
                    val size = input.readInt(); profileCheck(size in -1..512 && size != 0)
                    val secret = if (size < 0) null else CharArray(size)
                    try {
                        if (secret != null) for (index in secret.indices) secret[index] = input.readChar()
                        credentials += StoredCredential(CredentialView(id, version, label, ns, revoked), secret)
                    }
                    finally { secret?.fill('\u0000') }
                }
                val count = input.readInt(); profileCheck(count in 0..32)
                val profiles = List(count) {
                    val id = input.readUTF(); val rev = input.readLong(); val name = input.readUTF(); val status = DirectProfileStatus.valueOf(input.readUTF())
                    val ref = CredentialRef(input.readUTF(), input.readLong()); val ns = namespace(); val api = input.readUTF()
                    val resource = DataSourceBinding(input.readUTF(), input.readUTF()); val schemaRevision = input.readLong(); val fingerprint = input.readUTF()
                    val title = property(); val business = property(); val valuesCount = input.readInt(); profileCheck(valuesCount in 1..10)
                    val values = List(valuesCount) { input.readUTF() }; profileCheck(values.toSet().size == values.size)
                    val schema = TaskSchemaPin(schemaRevision, fingerprint, title, business, values.toSet(), optionalProperty(), optionalProperty(), StatusMeaning.valueOf(input.readUTF()))
                    val evidence = if (input.readBoolean()) EvidenceSource.valueOf(input.readUTF()) else null
                    ConnectionProfileView(id, rev, name, status, ref, ns, api, resource, schema, evidence)
                }
                val cooldownCount = input.readInt(); profileCheck(cooldownCount in 0..16)
                val cooldowns = List(cooldownCount) { CredentialCooldown(input.readUTF(), input.readLong(), if (input.readBoolean()) input.readLong() else null) }
                profileCheck(input.available() == 0)
                freezeVault(ProfileVaultState(revision, updated, profiles, credentials, selected, cooldowns))
            }
        } catch (safe: ProfileStoreException) {
            if (safe.failure == ProfileFailure.UNSUPPORTED_SCHEMA) throw safe
            throw ProfileStoreException(ProfileFailure.DATA_UNREADABLE)
        } catch (_: Exception) { throw ProfileStoreException(ProfileFailure.DATA_UNREADABLE) }
        finally { credentials.forEach { it.clear() } }
    }
}
internal class EncryptedNotionProfiles(private val storage: AuthenticatedSnapshotBytes) : NotionProfilesPersistence {
    @Synchronized override fun readOwned(): ProfileVaultState = safe {
        val payload = storage.read()
        if (payload == null) freezeVault(ProfileVaultState())
        else try { NotionProfilesCodec.decode(payload) } finally { payload.fill(0) }
    }
    @Synchronized override fun compareAndReplace(expectedRevision: Long, next: ProfileVaultState): Boolean = safe {
        val admitted = freezeVault(next); var current: ProfileVaultState? = null
        try {
            current = readOwned()
            if (current.revision != expectedRevision) false
            else {
                profileCheck(expectedRevision in 0 until Long.MAX_VALUE && admitted.revision == expectedRevision + 1)
                val payload = NotionProfilesCodec.encode(admitted)
                try { storage.replace(payload) } finally { payload.fill(0) }; true
            }
        } finally { admitted.clearSecrets(); current?.clearSecrets() }
    }
    private fun <T> safe(block: () -> T): T = try { block() }
    catch (safe: SnapshotStorageException) { throw ProfileStoreException(ProfileFailure.valueOf(safe.failure.name)) }
}
object NotionProfilesStoreFactory {
    /** New domain only; uses reviewed snapshot primitive without changing task/memory/rule formats. */
    fun create(context: Context): NotionProfilesPersistence {
        val app = context.applicationContext
        return object : NotionProfilesPersistence {
            private val delegate by lazy {
                val domain = SnapshotDomain("agent-notion-profiles-v1.enc", "voicebrainlive.agent-notion-profiles.aes-gcm.v1",
                    "VoiceBrainLive/notion-profiles/schema-1")
                EncryptedNotionProfiles(AndroidNoBackupSnapshot(File(app.noBackupFilesDir, domain.fileName), domain).authenticated)
            }
            override fun readOwned() = delegate.readOwned()
            override fun compareAndReplace(expectedRevision: Long, next: ProfileVaultState) = delegate.compareAndReplace(expectedRevision, next)
        }
    }
}
