package com.example.voicebrainlive.agent.notion

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface NotionProfilesPersistence {
    /** Return caller-owned secret storage; repository clears it after copying. Never return a cache. */
    fun readOwned(): ProfileVaultState
    fun compareAndReplace(expectedRevision: Long, next: ProfileVaultState): Boolean
}

/** One app-owned IO repository/vault. No legacy settings, provider, arbitrary URL or writer hook. */
class NotionProfilesRepository(private val persistence: NotionProfilesPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val cached = AtomicReference<ProfileVaultView?>()
    /** Process-local validated metadata only; never substitutes for authenticated read/lease guards. */
    fun isCurrentCached(pin: ConnectionReadPin): Boolean = try {
        val frozen = freezeReadPin(pin); val state = cached.get()
        val profile = state?.profiles?.singleOrNull { it.profileId == frozen.profileId }
        val credential = state?.credentials?.singleOrNull { it.credentialId == frozen.credential.credentialId }
        profile != null && credential != null && !credential.revoked && credential.version == frozen.credential.version &&
            profile.status == DirectProfileStatus.SYNTHETIC_VERIFIED && profilePin(profile) == frozen
    } catch (_: Exception) { false }
    suspend fun views(): ProfileVaultView = access { state -> publicView(state) }
    suspend fun addCredential(label: String, input: SecretInput): CredentialView = access { state ->
        profileCheck(opaqueText(label, 80)); profileCheck(state.credentials.size < 16, ProfileFailure.CAPACITY_REACHED)
        val id = ids(); val namespaceId = ids()
        profileCheck(localId(id) && localId(namespaceId) && state.credentials.none { it.view.credentialId == id })
        val secret = input.copySecret()
        var added: StoredCredential? = null
        try {
            val view = CredentialView(id, 1, label, LocalNamespace(namespaceId), false)
            added = StoredCredential(view, secret)
            commit(state, credentials = state.credentials + requireNotNull(added)) { next -> next.credentials.last().view }
        } finally { secret.fill('\u0000'); added?.clear() }
    }
    suspend fun rotateCredential(id: String, expectedVersion: Long, input: SecretInput): CredentialView = access { state ->
        val old = credential(state, CredentialRef(id, expectedVersion)); profileCheck(!old.view.revoked, ProfileFailure.CREDENTIAL_UNAVAILABLE)
        val changed = old.view.copy(version = increment(old.view.version)); val secret = input.copySecret()
        var replacement: StoredCredential? = null
        try {
            val profiles = state.profiles.map { profile -> if (profile.credential.credentialId == id)
                profile.copy(revision = increment(profile.revision), credential = CredentialRef(id, changed.version),
                    status = if (profile.status == DirectProfileStatus.REVOKED) DirectProfileStatus.REVOKED else DirectProfileStatus.DRAFT, evidence = null)
                else profile }
            replacement = StoredCredential(changed, secret)
            commit(state, profiles, state.credentials.map { if (it.view.credentialId == id) requireNotNull(replacement) else it }) { changed }
        } finally { secret.fill('\u0000'); replacement?.clear() }
    }
    suspend fun revokeCredential(id: String, expectedVersion: Long): CredentialView = access { state ->
        val old = credential(state, CredentialRef(id, expectedVersion)); profileCheck(!old.view.revoked, ProfileFailure.CREDENTIAL_UNAVAILABLE)
        val changed = old.view.copy(version = increment(old.view.version), revoked = true)
        val profiles = state.profiles.map { if (it.credential.credentialId == id) it.copy(revision = increment(it.revision),
            credential = CredentialRef(id, changed.version), status = DirectProfileStatus.REVOKED, evidence = null) else it }
        val selected = state.selectedProfileId?.takeIf { value -> profiles.any { it.profileId == value && it.status != DirectProfileStatus.REVOKED } }
        commit(state, profiles, state.credentials.map { if (it.view.credentialId == id) StoredCredential(changed, null) else it }, selected) { changed }
    }
    suspend fun createDraft(draft: ProfileDraft): ConnectionProfileView = access { state ->
        profileCheck(state.profiles.size < 32, ProfileFailure.CAPACITY_REACHED)
        val id = ids(); profileCheck(localId(id) && state.profiles.none { it.profileId == id })
        val profile = draftProfile(state, id, 1, draft)
        commit(state, profiles = state.profiles + profile) { it.profiles.last() }
    }
    suspend fun update(id: String, expectedRevision: Long, draft: ProfileDraft): ConnectionProfileView = access { state ->
        val old = exact(state, id, expectedRevision); profileCheck(old.status != DirectProfileStatus.REVOKED)
        replace(state, draftProfile(state, id, increment(old.revision), draft))
    }
    suspend fun disable(id: String, expectedRevision: Long): ConnectionProfileView = access { state ->
        val old = exact(state, id, expectedRevision); profileCheck(old.status != DirectProfileStatus.REVOKED)
        replace(state, old.copy(revision = increment(old.revision), status = DirectProfileStatus.DISABLED, evidence = null))
    }
    suspend fun revoke(id: String, expectedRevision: Long): ConnectionProfileView = access { state ->
        val old = exact(state, id, expectedRevision); profileCheck(old.status != DirectProfileStatus.REVOKED)
        val changed = old.copy(revision = increment(old.revision), status = DirectProfileStatus.REVOKED, evidence = null)
        val selected = state.selectedProfileId?.takeIf { it != id }
        commit(state, profiles = state.profiles.map { if (it.profileId == id) changed else it }, selected = selected) { changed }
    }
    suspend fun select(id: String, expectedRevision: Long): ConnectionProfileView = access { state ->
        val profile = exact(state, id, expectedRevision); profileCheck(profile.status != DirectProfileStatus.REVOKED)
        commit(state, selected = id) { profile } // Future selection only; never retarget captured requests.
    }
    suspend fun verifySynthetic(id: String, expectedRevision: Long, fixture: SyntheticVerificationEvidence): ConnectionProfileView = access { state ->
        val old = exact(state, id, expectedRevision)
        profileCheck(old.status == DirectProfileStatus.DRAFT)
        profileCheck(old.schema.meaning == StatusMeaning.EXPLICIT_BUSINESS_TASK, ProfileFailure.BUSINESS_SCHEMA_UNAVAILABLE)
        profileCheck(fixture.source == EvidenceSource.SYNTHETIC_FIXTURE && fixture.canRead && fixture.completeSchema &&
            fixture.resource == old.resource && fixture.schemaFingerprint == old.schema.fingerprint && fixture.apiVersion == old.apiVersion,
            ProfileFailure.SCHEMA_MISMATCH)
        replace(state, old.copy(revision = increment(old.revision), status = DirectProfileStatus.SYNTHETIC_VERIFIED,
            evidence = EvidenceSource.SYNTHETIC_FIXTURE))
    }
    suspend fun pin(id: String, expectedRevision: Long): ConnectionReadPin = access { state ->
        val profile = exact(state, id, expectedRevision)
        profileCheck(profile.status == DirectProfileStatus.SYNTHETIC_VERIFIED, ProfileFailure.NOT_CONNECTED)
        val pin = profilePin(profile); requireCurrent(state, pin); pin
    }
    suspend fun isCurrent(pin: ConnectionReadPin): Boolean = access { state -> current(state, freezeReadPin(pin)) }
    suspend fun assertReadable(pin: ConnectionReadPin) = access { state ->
        requireCurrent(state, freezeReadPin(pin)); readable(state, pin); Unit
    }
    suspend fun <T> withCredential(pin: ConnectionReadPin, block: suspend (CharArray) -> T): T {
        val captured = freezeReadPin(pin)
        val lease = access { state ->
            requireCurrent(state, captured); readable(state, captured)
            requireNotNull(credential(state, captured.credential).copySecret())
        }
        // Never hold repository Mutex over a suspension; rotation/revoke must proceed while awaiting.
        try {
            val result = block(lease)
            profileCheck(isCurrent(captured), ProfileFailure.STALE_BINDING)
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: ProfileStoreException) { throw safe }
        catch (_: Exception) { throw ProfileStoreException(ProfileFailure.NETWORK_FAILURE) }
        finally { lease.fill('\u0000') }
    }
    internal suspend fun cooldown(pin: ConnectionReadPin, retryAfterSeconds: Long?) = access { state ->
        requireCurrent(state, pin); val now = time(state)
        profileCheck(retryAfterSeconds == null || retryAfterSeconds >= 0, ProfileFailure.RESPONSE_INVALID)
        // Unknown/unreasonably large hints require review, rather than inventing a fixed wait.
        val until = if (retryAfterSeconds == null || retryAfterSeconds > 86_400 || retryAfterSeconds > (Long.MAX_VALUE - now) / 1000)
            Long.MAX_VALUE else now + retryAfterSeconds * 1000
        val old = state.cooldowns.singleOrNull { it.credentialId == pin.credential.credentialId }
        val next = CredentialCooldown(pin.credential.credentialId, maxOf(until, old?.until ?: 0), retryAfterSeconds)
        commit(state, cooldowns = state.cooldowns.filterNot { it.credentialId == next.credentialId } + next) { Unit }
    }
    private fun readable(state: ProfileVaultState, pin: ConnectionReadPin) {
        // Provider workspace scope is unknown in this synthetic slice. Conservatively apply
        // every retained cooldown to the whole vault, including newly created credentials.
        // Profile edits, rotation, selection and recreation therefore cannot bypass it.
        val now = time(state)
        profileCheck(state.cooldowns.none { now < it.until }, ProfileFailure.RATE_LIMITED)
    }
    private fun current(state: ProfileVaultState, pin: ConnectionReadPin): Boolean {
        val profile = state.profiles.singleOrNull { it.profileId == pin.profileId } ?: return false
        val credential = state.credentials.singleOrNull { it.view.credentialId == pin.credential.credentialId } ?: return false
        return profile.status == DirectProfileStatus.SYNTHETIC_VERIFIED && !credential.view.revoked &&
            credential.view.version == pin.credential.version && profilePin(profile) == pin
    }
    private fun requireCurrent(state: ProfileVaultState, pin: ConnectionReadPin) = profileCheck(current(state, pin), ProfileFailure.STALE_BINDING)
    private fun credential(state: ProfileVaultState, ref: CredentialRef): StoredCredential = state.credentials.singleOrNull {
        it.view.credentialId == ref.credentialId && it.view.version == ref.version
    } ?: throw ProfileStoreException(ProfileFailure.CREDENTIAL_UNAVAILABLE)
    private fun draftProfile(state: ProfileVaultState, id: String, revision: Long, draft: ProfileDraft): ConnectionProfileView {
        val credential = credential(state, draft.credential)
        profileCheck(!credential.view.revoked, ProfileFailure.CREDENTIAL_UNAVAILABLE)
        profileCheck(opaqueText(draft.displayName, 80) && draft.apiVersion == "2026-03-11")
        return ConnectionProfileView(id, revision, draft.displayName, DirectProfileStatus.DRAFT, draft.credential,
            credential.view.namespace, draft.apiVersion, draft.resource, freezeSchema(draft.schema))
    }
    private fun exact(state: ProfileVaultState, id: String, revision: Long) = state.profiles.singleOrNull {
        it.profileId == id && it.revision == revision
    } ?: throw ProfileStoreException(ProfileFailure.STALE_BINDING)
    private fun replace(state: ProfileVaultState, changed: ConnectionProfileView) = commit(state,
        profiles = state.profiles.map { if (it.profileId == changed.profileId) changed else it }) { changed }
    private fun publicView(state: ProfileVaultState) = ProfileVaultView(state.revision, profileList(state.profiles),
        profileList(state.credentials.map { it.view }), state.selectedProfileId,
        profileList(state.cooldowns.map { ProfileCooldownView(it.credentialId, it.until, it.retryAfterSeconds, it.until == Long.MAX_VALUE) }))
    private fun time(state: ProfileVaultState) = clock().also { profileCheck(it >= state.updatedAt && it >= 0, ProfileFailure.CLOCK_ANOMALY) }
    private fun increment(value: Long): Long { profileCheck(value < Long.MAX_VALUE, ProfileFailure.CAPACITY_REACHED); return value + 1 }
    private fun <T> commit(state: ProfileVaultState, profiles: List<ConnectionProfileView> = state.profiles,
        credentials: List<StoredCredential> = state.credentials, selected: String? = state.selectedProfileId,
        cooldowns: List<CredentialCooldown> = state.cooldowns, result: (ProfileVaultState) -> T): T {
        val next = freezeVault(ProfileVaultState(increment(state.revision), time(state), profiles, credentials, selected, cooldowns))
        try {
            profileCheck(persistence.compareAndReplace(state.revision, next), ProfileFailure.STALE_BINDING)
            cached.set(publicView(next)); return result(next)
        }
        finally { next.clearSecrets() }
    }
    private suspend fun <T> access(action: (ProfileVaultState) -> T): T = withContext(io) { mutex.withLock {
        try {
            val raw = persistence.readOwned()
            val state = try { freezeVault(raw) } finally { raw.clearSecrets() }
            try { time(state); cached.set(publicView(state)); action(state) } finally { state.clearSecrets() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: ProfileStoreException) {
            if (safe.failure in setOf(ProfileFailure.KEY_UNAVAILABLE, ProfileFailure.STORAGE_UNAVAILABLE,
                    ProfileFailure.DATA_UNREADABLE, ProfileFailure.UNSUPPORTED_SCHEMA, ProfileFailure.RECOVERY_REQUIRED,
                    ProfileFailure.CLOCK_ANOMALY)) cached.set(null)
            throw safe
        }
        catch (_: Exception) { cached.set(null); throw ProfileStoreException(ProfileFailure.STORAGE_UNAVAILABLE) }
    } }
}
