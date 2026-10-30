package com.example.voicebrainlive.updates

import com.example.voicebrainlive.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AvailableUpdate(val manifest: ReleaseManifest, val assetId: Long, val downloadUrl: String = "")

class GitHubReleaseProvider(
    private val token: String = "",
    private val repository: String = ""
) : ReleaseProvider {
    val gitHubUpdates = GitHubUpdates(token, repository)
    var lastAvailableUpdate: AvailableUpdate? = null
        private set

    override val configured: Boolean
        get() = repository.isNotBlank() || BuildConfig.UPDATE_REPOSITORY.isNotBlank()

    override suspend fun latest(): ReleaseManifest? {
        if (!configured) return null
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            gitHubUpdates.latest("com.example.voicebrainlive")
        }
        lastAvailableUpdate = result
        return result.manifest
    }
}

class GitHubUpdates(
    private val token: String = "",
    private val repository: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()
) {
    private val targetRepo = repository.ifBlank { BuildConfig.UPDATE_REPOSITORY.ifBlank { "naylinhtike1356-cyber/VoiceBrainLive-" } }
    private val baseUrl = "https://api.github.com/repos/$targetRepo"
    init { require(targetRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) }

    private fun request(url: String, binary: Boolean = false): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
            .header("User-Agent", "VoiceBrainLive/${BuildConfig.VERSION_NAME}")
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private fun failure(code: Int): IOException = IOException(when (code) {
        401, 403, 429 -> "GitHub update access is temporarily unavailable or rate limited. Please try again later."
        404 -> "No public stable release is available yet."
        else -> "GitHub update request failed ($code). Please try again later."
    })

    fun latest(packageName: String): AvailableUpdate {
        val release = client.newCall(request("$baseUrl/releases/latest")).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code)
            val body = response.body ?: throw IOException("Empty release response")
            JSONObject(body.string())
        }
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Release is not stable" }
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name && it.optString("state") == "uploaded" }
            ?: throw IOException("Release is missing $name")
        fun assetUrl(asset: JSONObject): String = if (token.isNotBlank()) {
            "$baseUrl/releases/assets/${asset.getLong("id")}"
        } else trustedDownloadUrl(asset.getString("browser_download_url"), targetRepo)
        val manifest = client.newCall(request(assetUrl(asset("update.json")), true)).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code)
            val body = response.body ?: throw IOException("Empty update manifest")
            body.byteStream().use { input ->
                val bytes = ByteArray(32 * 1024 + 1)
                var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count == -1) break
                    total += count
                }
                require(total <= 32 * 1024) { "Update manifest is too large" }
                ReleaseManifest.parse(String(bytes, 0, total, Charsets.UTF_8), packageName)
            }
        }
        val apk = asset(ReleaseManifest.APK_NAME)
        require(apk.getLong("size") == manifest.size) { "Release asset size does not match metadata" }
        return AvailableUpdate(manifest, apk.getLong("id"), assetUrl(apk))
    }

    suspend fun download(
        update: AvailableUpdate,
        destination: File,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        val digest = MessageDigest.getInstance("SHA-256")
        var complete = false
        try {
            val url = update.downloadUrl.ifBlank { "$baseUrl/releases/assets/${update.assetId}" }
            if (token.isBlank()) trustedDownloadUrl(url, targetRepo)
            client.newCall(request(url, true)).execute().use { response ->
                if (!response.isSuccessful) throw failure(response.code)
                val body = response.body ?: throw IOException("Empty APK response")
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= update.manifest.size) { "APK exceeds the expected size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress?.invoke(total, update.manifest.size)
                        }
                        require(total == update.manifest.size) { "APK download is incomplete" }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(update.manifest.sha256, ignoreCase = true)) { "APK checksum does not match" }
            complete = true
            return destination
        } finally {
            if (!complete) destination.delete()
        }
    }

    companion object {
        internal fun trustedDownloadUrl(url: String, repository: String): String {
            val parsed = java.net.URI(url)
            require(parsed.scheme == "https" && parsed.host == "github.com" && parsed.userInfo == null &&
                parsed.port == -1 && parsed.rawQuery == null && parsed.rawFragment == null &&
                parsed.rawPath.startsWith("/$repository/releases/download/") &&
                !parsed.rawPath.contains("..")) { "Invalid release asset URL" }
            return url
        }
    }
}
