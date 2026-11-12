package com.example.voicebrainlive.updates

import com.example.voicebrainlive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class HttpReleaseProvider(
    private val baseUrl: String = BuildConfig.UPDATE_BASE_URL,
    private val client: OkHttpClient = defaultClient()
) : ReleaseProvider {

    var lastAvailableRelease: ReleaseManifest? = null
        private set

    override val configured: Boolean
        get() = ReleaseSource.isAllowedOrigin(baseUrl)

    override suspend fun latest(): ReleaseManifest? {
        if (!configured) return null
        return withContext(Dispatchers.IO) {
            val manifestUrl = "$baseUrl/release.json"
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store")
                .header("User-Agent", "VoiceBrainLive/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Release metadata request failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty release metadata response")
                val stream = body.byteStream()
                val bytes = ByteArray(ReleaseManifest.MAX_METADATA_BYTES + 1)
                var total = 0
                while (total < bytes.size) {
                    val count = stream.read(bytes, total, bytes.size - total)
                    if (count == -1) break
                    total += count
                }
                if (total > ReleaseManifest.MAX_METADATA_BYTES) {
                    throw IllegalArgumentException("Update manifest is too large")
                }
                val json = String(bytes, 0, total, Charsets.UTF_8)
                val manifest = ReleaseManifest.parse(json, "com.example.voicebrainlive")
                val finalManifest = if (manifest.downloadUrl.isBlank()) {
                    manifest.copy(downloadUrl = "$baseUrl/${manifest.apkName}")
                } else {
                    manifest
                }
                lastAvailableRelease = finalManifest
                finalManifest
            }
        }
    }

    suspend fun download(
        manifest: ReleaseManifest,
        destination: File,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        return withContext(Dispatchers.IO) {
            require(ReleaseManifest.isValidApkBasename(manifest.apkName)) {
                "Invalid APK filename: must be a safe basename"
            }
            val rawUrl = manifest.downloadUrl.ifBlank { "$baseUrl/${manifest.apkName}" }
            val downloadUrl = trustedDownloadUrl(rawUrl, baseUrl)

            destination.parentFile?.mkdirs()
            val tempFile = File(destination.parentFile, "${destination.name}.part")
            if (tempFile.exists()) tempFile.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            var complete = false

            val request = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .header("User-Agent", "VoiceBrainLive/${BuildConfig.VERSION_NAME}")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("APK download failed with HTTP ${response.code}")
                    }
                    val finalUrl = response.request.url.toString()
                    trustedDownloadUrl(finalUrl, baseUrl)

                    val body = response.body ?: throw IOException("Empty APK download response")
                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                total += count
                                if (total > manifest.apkBytes) {
                                    throw IOException("APK download exceeded the expected size of ${manifest.apkBytes} bytes")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress?.invoke(total, manifest.apkBytes)
                            }
                            if (total != manifest.apkBytes) {
                                throw IOException("APK download is incomplete: got $total bytes, expected ${manifest.apkBytes}")
                            }
                        }
                    }
                }

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(manifest.apkSha256, ignoreCase = true)) {
                    throw IOException("APK checksum mismatch: expected ${manifest.apkSha256}, got $actualHash")
                }

                if (destination.exists()) destination.delete()
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                complete = true
                destination
            } finally {
                if (!complete && tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)
                // Do not contact an unreviewed origin (including Salary) through a redirect.
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        fun trustedDownloadUrl(url: String, expectedOrigin: String): String {
            require(ReleaseSource.isAllowedOrigin(expectedOrigin)) { "Release origin is not configured or is reserved" }
            val parsed = URI(url)
            val baseParsed = URI(expectedOrigin)
            require(parsed.scheme.equals("https", ignoreCase = true)) {
                "Only HTTPS URLs are permitted for APK downloads"
            }
            require(parsed.userInfo == null) {
                "URLs with user credentials are not permitted"
            }
            require(parsed.host.equals(baseParsed.host, ignoreCase = true)) {
                "APK download URL host (${parsed.host}) does not match expected release origin (${baseParsed.host})"
            }
            require((if (parsed.port == -1) 443 else parsed.port) ==
                (if (baseParsed.port == -1) 443 else baseParsed.port)) { "APK origin port differs" }
            require(parsed.query == null && parsed.fragment == null) { "Unexpected APK URL suffix" }
            require(!parsed.path.contains("..")) {
                "Path traversal sequences are strictly forbidden in download URLs"
            }
            return url
        }
    }
}
