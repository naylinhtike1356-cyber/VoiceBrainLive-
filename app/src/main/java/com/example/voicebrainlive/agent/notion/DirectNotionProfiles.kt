package com.example.voicebrainlive.agent.notion

import java.util.UUID

/** Local synthetic profile management only. No real credential input or provider verification. */
interface DirectNotionProfiles {
    suspend fun views(): ProfileVaultView
    suspend fun captureSelectedPin(): ConnectionReadPin = throw ProfileStoreException(ProfileFailure.NOT_CONNECTED)
    suspend fun createSyntheticDraft(name: String): ConnectionProfileView
    suspend fun verifySynthetic(id: String, revision: Long): ConnectionProfileView
    suspend fun select(id: String, revision: Long): ConnectionProfileView
    suspend fun disable(id: String, revision: Long): ConnectionProfileView
    suspend fun revoke(id: String, revision: Long): ConnectionProfileView
}

class RepositoryDirectNotionProfiles(private val repository: NotionProfilesRepository) : DirectNotionProfiles {
    override suspend fun views() = repository.views()
    override suspend fun captureSelectedPin(): ConnectionReadPin {
        val captured = repository.views()
        val selectedId = captured.selectedProfileId ?: throw ProfileStoreException(ProfileFailure.NOT_CONNECTED)
        val selected = captured.profiles.singleOrNull { it.profileId == selectedId }
            ?: throw ProfileStoreException(ProfileFailure.STALE_BINDING)
        return repository.pin(selected.profileId, selected.revision)
    }
    override suspend fun createSyntheticDraft(name: String): ConnectionProfileView {
        profileCheck(opaqueText(name, 80))
        // Reuse only an explicitly labelled fixture credential; never import legacy credentials.
        val label = "Local synthetic fixture; not a provider credential"
        val existing = repository.views().credentials.firstOrNull { it.label == label && !it.revoked }
        val credential = existing ?: SecretInput("synthetic-only-no-account".toCharArray()).use {
            repository.addCredential(label, it)
        }
        val schema = taskSchema(1, PropertyPin("title", PropertyKind.TITLE),
            PropertyPin("business_status", PropertyKind.STATUS), setOf("Todo", "Doing"),
            PropertyPin("priority", PropertyKind.NUMBER), PropertyPin("sync_status", PropertyKind.SELECT))
        return repository.createDraft(ProfileDraft(name, CredentialRef(credential.credentialId, credential.version),
            DataSourceBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString()), schema))
    }
    override suspend fun verifySynthetic(id: String, revision: Long): ConnectionProfileView {
        val profile = repository.views().profiles.singleOrNull { it.profileId == id && it.revision == revision }
            ?: throw ProfileStoreException(ProfileFailure.STALE_BINDING)
        return repository.verifySynthetic(id, revision, SyntheticVerificationEvidence(profile.resource,
            profile.schema.fingerprint, completeSchema = true, canRead = true))
    }
    override suspend fun select(id: String, revision: Long) = repository.select(id, revision)
    override suspend fun disable(id: String, revision: Long) = repository.disable(id, revision)
    override suspend fun revoke(id: String, revision: Long) = repository.revoke(id, revision)
}
