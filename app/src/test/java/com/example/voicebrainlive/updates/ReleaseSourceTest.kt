package com.example.voicebrainlive.updates

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ReleaseSourceTest {
    @Test fun onlyDedicatedProductionFeedUsesGitHubFallback() {
        assertTrue(ReleaseSource.provider("com.example.voicebrainlive", "https://voicebrainlive.vercel.app/") is FallbackReleaseProvider)
        assertTrue(ReleaseSource.provider("com.example.voicebrainlive", "https://voicebrain.example") is HttpReleaseProvider)
    }

    @Test fun brokenProductionFeedSelectsFallbackProvider() = runBlocking {
        val primary = object : ReleaseProvider {
            override val configured = true
            override suspend fun latest(): ReleaseManifest? = throw java.io.IOException("unavailable")
        }
        val fallback = object : ReleaseProvider {
            override val configured = true
            override suspend fun latest(): ReleaseManifest? = null
        }
        val provider = FallbackReleaseProvider(primary, fallback)
        provider.latest()
        assertSame(fallback, provider.selected)
    }
    @Test fun rejectsMissingMalformedAndReservedOrigins() {
        listOf("", "https://", "http://voicebrain.example", "https://user@voicebrain.example",
            "https://voicebrain.example/feed", "https://voicebrain.example?x=1",
            "https://voicebrain.example#fragment", "https://voicebrain.example:444",
            "https://webdist-puce.vercel.app", "https://WEBDIST-PUCE.VERCEL.APP./",
            "https://webdist-naylin.vercel.app").forEach {
            assertFalse(it, ReleaseSource.isAllowedOrigin(it))
            assertSame(UnconfiguredReleaseProvider, ReleaseSource.provider("com.example.voicebrainlive", it))
        }
    }
    @Test fun isolatedPackagesNeverUseReleaseFeed() {
        assertSame(UnconfiguredReleaseProvider,
            ReleaseSource.provider("com.example.voicebrainlive.interactivebeta", "https://voicebrain.example"))
        assertTrue(ReleaseSource.isAllowedOrigin("https://voicebrain.example/"))
    }
    @Test fun reservedSourceNeverMakesNetworkRequest() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Network must not run") }.build()
        assertNull(HttpReleaseProvider("https://webdist-puce.vercel.app", client).latest())
    }
    @Test fun downloadCannotOverrideReservedOriginOrPort() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpReleaseProvider.trustedDownloadUrl("https://webdist-puce.vercel.app/app.apk", "https://webdist-puce.vercel.app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpReleaseProvider.trustedDownloadUrl("https://voicebrain.example:444/app.apk", "https://voicebrain.example")
        }
        assertFalse(HttpReleaseProvider.defaultClient().followRedirects)
    }
}
