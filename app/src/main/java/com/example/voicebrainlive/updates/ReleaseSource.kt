package com.example.voicebrainlive.updates

import com.example.voicebrainlive.BuildConfig
import java.net.URI

/** Shared by startup and Settings. Missing hosting must never select an implicit publisher. */
object ReleaseSource {
    fun provider(packageName: String, baseUrl: String = BuildConfig.UPDATE_BASE_URL): ReleaseProvider =
        if (packageName == "com.example.voicebrainlive" && isAllowedOrigin(baseUrl)) {
            HttpReleaseProvider(baseUrl.trimEnd('/'))
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
