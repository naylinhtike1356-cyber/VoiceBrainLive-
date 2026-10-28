package com.example.voicebrainlive.updates

import kotlinx.coroutines.CancellationException

/**
 * Providers supply metadata only and must use cancellable, bounded, off-main-thread I/O.
 * This contract grants no download or installation authority.
 * A live adapter may be configured only after trusted hosting/publisher verification.
 */
interface ReleaseProvider {
    val configured: Boolean
    suspend fun latest(): ReleaseManifest?
}

object UnconfiguredReleaseProvider : ReleaseProvider {
    override val configured = false
    override suspend fun latest(): ReleaseManifest? = null
}

sealed interface UpdateStatus {
    data object NotConfigured : UpdateStatus
    data object Current : UpdateStatus
    data class Available(val release: ReleaseManifest) : UpdateStatus
    data object Failed : UpdateStatus
}

class UpdateChecker(
    private val packageName: String,
    private val installedVersionCode: Long,
    private val provider: ReleaseProvider = UnconfiguredReleaseProvider
) {
    init {
        require(packageName.isNotBlank())
        require(installedVersionCode > 0)
    }

    suspend fun check(): UpdateStatus {
        if (!provider.configured) return UpdateStatus.NotConfigured
        return try {
            val release = provider.latest() ?: return UpdateStatus.Current
            release.validate(packageName)
            if (release.isNewerThan(installedVersionCode)) UpdateStatus.Available(release)
            else UpdateStatus.Current
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Provider exception text can include authorization headers or private endpoint details.
            UpdateStatus.Failed
        }
    }
}
