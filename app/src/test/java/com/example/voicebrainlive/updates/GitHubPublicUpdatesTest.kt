package com.example.voicebrainlive.updates

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GitHubPublicUpdatesTest {
    @Test fun checksAndDownloadsPublicReleaseWithoutAuthorizationHeaders() = runBlocking {
        val bytes = "signed-apk-fixture".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val requests = mutableListOf<Request>()
        val publicBase = "https://github.com/owner/repo/releases/download/v2"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val content = when (request.url.toString()) {
                "https://api.github.com/repos/owner/repo/releases/latest" -> """{"draft":false,"prerelease":false,"assets":[{"id":1,"name":"update.json","state":"uploaded","browser_download_url":"$publicBase/update.json"},{"id":2,"name":"VoiceBrainLive.apk","state":"uploaded","size":${bytes.size},"browser_download_url":"$publicBase/VoiceBrainLive.apk"}]}""".toByteArray()
                "$publicBase/update.json" -> """{"versionCode":13,"versionName":"1.6.0","packageName":"com.example.voicebrainlive","sha256":"$hash","size":${bytes.size}}""".toByteArray()
                "$publicBase/VoiceBrainLive.apk" -> bytes
                else -> error("Unexpected URL")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        val updates = GitHubUpdates(repository = "owner/repo", client = client)
        val update = updates.latest("com.example.voicebrainlive")
        val target = File.createTempFile("public-update", ".apk")
        try {
            updates.download(update, target)
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.header("Authorization") == null })
            assertEquals("$publicBase/VoiceBrainLive.apk", requests.last().url.toString())
        } finally { target.delete() }
    }
    @Test fun publicRepositoryIsConfiguredWithoutAToken() {
        assertTrue(GitHubReleaseProvider(repository = "owner/repo").configured)
    }
    @Test fun acceptsOnlyHttpsAssetsFromTheConfiguredRepository() {
        val valid = "https://github.com/owner/repo/releases/download/v1.6.0/VoiceBrainLive.apk"
        assertEquals(valid, GitHubUpdates.trustedDownloadUrl(valid, "owner/repo"))
        listOf(
            "http://github.com/owner/repo/releases/download/v1/app.apk",
            "https://github.com.attacker.test/owner/repo/releases/download/v1/app.apk",
            "https://github.com/other/repo/releases/download/v1/app.apk",
            "https://user@github.com/owner/repo/releases/download/v1/app.apk",
            "https://github.com/owner/repo/releases/download/../app.apk",
            "$valid?token=secret"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.trustedDownloadUrl(url, "owner/repo") }
        }
    }
}
