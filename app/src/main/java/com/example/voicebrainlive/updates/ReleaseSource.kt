package com.example.voicebrainlive.updates

import com.example.voicebrainlive.BuildConfig
import kotlinx.coroutines.CancellationException
import java.net.URI

/** Shared by startup and Settings. Missing hosting must never select an implicit publisher. */
object ReleaseSource {
    private const val PRODUCTION_HOST = "https://voicebrainlive.vercel.app"
    private const val PUBLIC_REPOSITORY = "naylinhtike1356-cyber/VoiceBrainLive-"

    fun provider(packageName: String, baseUrl: String = BuildConfig.UPDATE_BASE_URL): ReleaseProvider =
        if (packageName == "com.example.voicebrainlive" && isAllowedOrigin(baseUrl)) {
            val origin = baseUrl.trimEnd('/')
            val http = HttpReleaseProvider(origin)
            if (origin == PRODUCTION_HOST) {
                FallbackReleaseProvider(http, GitHubReleaseProvider(repository = PUBLIC_REPOSITORY))
            } else http
        } else UnconfiguredReleaseProvider

    fun isAllowedOrigin(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        host.isNotBlank() && uri.scheme.equals("https", true) && uri.userInfo == null &&
            uri.port in listOf(-1, 443) && uri.rawPath.orEmpty() in listOf("", "/") &&
            uri.rawQuery == null && uri.rawFragment == null &&
            host !in setOf("webdist-puce.vercel.app", "webdist-naylin.vercel.app")
    }.getOrDefault(false)
}

internal class FallbackReleaseProvider(
    private val primary: ReleaseProvider,
    private val fallback: ReleaseProvider
) : ReleaseProvider {
    override val configured: Boolean get() = primary.configured
    var selected: ReleaseProvider = primary
        private set

    override suspend fun latest(): ReleaseManifest? {
        selected = primary
        val release = try {
            primary.latest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (release != null) return release
        selected = fallback
        return fallback.latest()
    }
}
