package com.example.voicebrainlive.updates

import android.app.Application
import android.widget.TextView
import com.example.voicebrainlive.BuildConfig
import com.example.voicebrainlive.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InAppUpdateContractTest {

    private val pkg = "com.example.voicebrainlive"
    private val origin = "https://voicebrain.example"

    private fun validMetadataJson(
        app: String = "VoiceBrainLive",
        version: String = "v1.6.2",
        androidVersionName: String = "1.6.2",
        androidVersionCode: Long = 15,
        buildType: String = "release",
        apk: String = "VoiceBrainLive-v1.6.2-15.apk",
        apkBytes: Long = 1000,
        apkSha256: String = "a".repeat(64),
        builtAt: String = "2026-11-01T13:30:00Z",
        notes: String = "[\"Feature A\", \"Feature B\"]"
    ): String {
        return """
        {
          "app": "$app",
          "version": "$version",
          "androidVersionName": "$androidVersionName",
          "androidVersionCode": $androidVersionCode,
          "buildType": "$buildType",
          "apk": "$apk",
          "apkBytes": $apkBytes,
          "apkSha256": "$apkSha256",
          "builtAt": "$builtAt",
          "notes": $notes
        }
        """.trimIndent()
    }

    @Test
    fun debugAppUpdateActivityVersionDisplay() {
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).setup()
        try {
            val activity = controller.get()
            val text = activity.findViewById<TextView>(R.id.tvInstalledVersion).text.toString()
            println("=== DEBUG tvInstalledVersion: '$text' ===")
            println("=== DEBUG BuildConfig.VERSION_NAME: '${BuildConfig.VERSION_NAME}' ===")
            println("=== DEBUG BuildConfig.VERSION_CODE: '${BuildConfig.VERSION_CODE}' ===")
            println("=== DEBUG BuildConfig.BUILD_TYPE: '${BuildConfig.BUILD_TYPE}' ===")
            assertTrue("Text must contain version name", text.contains(BuildConfig.VERSION_NAME))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // 1. Version Code Progression & versionName String Decoupling
    @Test
    fun newerVersionCodeIsOfferedAsUpdate() = runBlocking {
        val manifest = ReleaseManifest.parse(validMetadataJson(androidVersionCode = 15), pkg)
        val provider = object : ReleaseProvider {
            override val configured = true
            override suspend fun latest(): ReleaseManifest = manifest
        }
        val checker = UpdateChecker(pkg, installedVersionCode = 14, provider = provider)
        val status = checker.check()
        assertTrue(status is UpdateStatus.Available)
        assertEquals(15L, (status as UpdateStatus.Available).release.androidVersionCode)
    }

    @Test
    fun equalOrOlderVersionCodeIsNeverOfferedAsUpdate() = runBlocking {
        for (installedCode in listOf(15L, 16L, 100L)) {
            val manifest = ReleaseManifest.parse(validMetadataJson(androidVersionCode = 15), pkg)
            val provider = object : ReleaseProvider {
                override val configured = true
                override suspend fun latest(): ReleaseManifest = manifest
            }
            val checker = UpdateChecker(pkg, installedVersionCode = installedCode, provider = provider)
            assertEquals(UpdateStatus.Current, checker.check())
        }
    }

    @Test
    fun versionNameStringDoesNotDictateUpdateAvailability() = runBlocking {
        val manifest = ReleaseManifest.parse(
            validMetadataJson(androidVersionName = "9.9.9", androidVersionCode = 14),
            pkg
        )
        val provider = object : ReleaseProvider {
            override val configured = true
            override suspend fun latest(): ReleaseManifest = manifest
        }
        val checker = UpdateChecker(pkg, installedVersionCode = 14, provider = provider)
        assertEquals(UpdateStatus.Current, checker.check())
    }

    // 2. Invalid or Missing Metadata
    @Test
    fun rejectsMissingAndMalformedRequiredFields() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse("""{"app":"VoiceBrainLive","androidVersionCode":15,"androidVersionName":"1.6.2","apkBytes":100}""", pkg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(validMetadataJson(apkSha256 = "z".repeat(64)), pkg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(validMetadataJson(apkSha256 = "a".repeat(63)), pkg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(validMetadataJson().replace("\"androidVersionCode\": 15", "\"androidVersionCode\": \"15\""), pkg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(validMetadataJson(androidVersionCode = 0), pkg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(validMetadataJson(apkBytes = 0), pkg)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(validMetadataJson(apkBytes = ReleaseManifest.MAX_APK_BYTES + 1), pkg)
        }
    }

    // 3. Invalid Filename & Untrusted Download URLs
    @Test
    fun rejectsPathTraversalAndUnsafeBasename() {
        listOf(
            "../VoiceBrainLive.apk",
            "folder/VoiceBrainLive.apk",
            "C:\\VoiceBrainLive.apk",
            "VoiceBrainLive.exe",
            "VoiceBrainLive.apk.zip",
            " "
        ).forEach { badName ->
            assertFalse("Should reject: $badName", ReleaseManifest.isValidApkBasename(badName))
        }
        assertTrue(ReleaseManifest.isValidApkBasename("VoiceBrainLive-v1.6.2-15.apk"))
        assertTrue(ReleaseManifest.isValidApkBasename("VoiceBrainLive.apk"))
    }

    @Test
    fun rejectsUntrustedDownloadUrls() {
        listOf(
            "http://voicebrain.example/VoiceBrainLive.apk",
            "https://attacker.example.com/VoiceBrainLive.apk",
            "https://user:pass@voicebrain.example/VoiceBrainLive.apk",
            "https://voicebrain.example/../VoiceBrainLive.apk"
        ).forEach { untrustedUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                HttpReleaseProvider.trustedDownloadUrl(untrustedUrl, origin)
            }
        }
        val safeUrl = "$origin/VoiceBrainLive-v1.6.2-15.apk"
        assertEquals(safeUrl, HttpReleaseProvider.trustedDownloadUrl(safeUrl, origin))
    }

    // 4. Truncated and Oversized Streaming Download Handling
    @Test
    fun truncatedDownloadFailsAndCleansUpTempFile() = runBlocking {
        val payload = "complete-apk-content-123456789".toByteArray()
        val truncatedPayload = "complete-apk".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(truncatedPayload.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                .build()
        }.build()

        val provider = HttpReleaseProvider(baseUrl = origin, client = client)
        val manifest = ReleaseManifest.parse(validMetadataJson(apkBytes = payload.size.toLong(), apkSha256 = hash), pkg)
        val target = File.createTempFile("target-test", ".apk")
        target.delete()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        try {
            assertThrows(IOException::class.java) {
                runBlocking { provider.download(manifest, target) }
            }
            assertFalse("Target APK must not be created on truncated download", target.exists())
            assertFalse(".part temp file must be cleaned up on truncation", part.exists())
        } finally {
            target.delete()
            part.delete()
        }
    }

    @Test
    fun oversizedDownloadAbortsImmediatelyAndCleansUp() = runBlocking {
        val expectedSize = 10L
        val oversizedData = ByteArray(100) { 1 }
        val hash = "a".repeat(64)

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(oversizedData.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                .build()
        }.build()

        val provider = HttpReleaseProvider(baseUrl = origin, client = client)
        val manifest = ReleaseManifest.parse(validMetadataJson(apkBytes = expectedSize, apkSha256 = hash), pkg)
        val target = File.createTempFile("target-oversize", ".apk")
        target.delete()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        try {
            assertThrows(IOException::class.java) {
                runBlocking { provider.download(manifest, target) }
            }
            assertFalse("Target APK must not be created on oversized download", target.exists())
            assertFalse(".part temp file must be cleaned up", part.exists())
        } finally {
            target.delete()
            part.delete()
        }
    }

    // 5. SHA-256 Checksum Mismatch
    @Test
    fun checksumMismatchRejectsDownloadAndDeletesTempFile() = runBlocking {
        val payload = "payload-for-hash-test".toByteArray()
        val wrongHash = "b".repeat(64)

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                .build()
        }.build()

        val provider = HttpReleaseProvider(baseUrl = origin, client = client)
        val manifest = ReleaseManifest.parse(validMetadataJson(apkBytes = payload.size.toLong(), apkSha256 = wrongHash), pkg)
        val target = File.createTempFile("target-hash-mismatch", ".apk")
        target.delete()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        try {
            assertThrows(IOException::class.java) {
                runBlocking { provider.download(manifest, target) }
            }
            assertFalse("Target APK must not be created on checksum mismatch", target.exists())
            assertFalse(".part temp file must be cleaned up", part.exists())
        } finally {
            target.delete()
            part.delete()
        }
    }

    // 6. Interrupted Download & Cancellation Handling
    @Test
    fun cancelledDownloadCleansUpTempPartFile() = runBlocking {
        val target = File.createTempFile("target-cancel", ".apk")
        target.delete()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        val client = OkHttpClient.Builder().addInterceptor {
            throw CancellationException("Simulated coroutine cancellation")
        }.build()

        val provider = HttpReleaseProvider(baseUrl = origin, client = client)
        val manifest = ReleaseManifest.parse(validMetadataJson(), pkg)
        try {
            try {
                provider.download(manifest, target)
                fail("Should throw cancellation")
            } catch (_: CancellationException) {
                // Expected
            }
            assertFalse("Temporary file must be deleted on cancellation", part.exists())
            assertFalse("Target APK must not exist", target.exists())
        } finally {
            target.delete()
            part.delete()
        }
    }

    // 7. Stale Ready File Invalidation
    @Test
    fun staleFileWithMismatchedChecksumOrSizeIsRejected() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("stale-apk", ".apk")
        file.writeBytes("old-content-here".toByteArray())

        val release = ReleaseManifest.parse(
            validMetadataJson(apkBytes = file.length() + 50, apkSha256 = "c".repeat(64)),
            pkg
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ApkUpdateVerifier.verify(context, file, release)
            }
        } finally {
            file.delete()
        }
    }

    // 8. End-to-End Successful Download & Verification
    @Test
    fun successfulStreamingDownloadProducesValidFile() = runBlocking {
        val payload = "verified-apk-stream-payload".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                .build()
        }.build()

        val provider = HttpReleaseProvider(baseUrl = origin, client = client)
        val manifest = ReleaseManifest.parse(validMetadataJson(apkBytes = payload.size.toLong(), apkSha256 = hash), pkg)
        val target = File.createTempFile("target-success", ".apk")
        target.delete()
        var progressReported = false

        try {
            val result = provider.download(manifest, target) { downloaded, total ->
                progressReported = true
                assertEquals(payload.size.toLong(), total)
                assertTrue(downloaded in 1..total)
            }
            assertTrue("Progress callback must be invoked", progressReported)
            assertTrue("Target APK must exist", result.exists())
            assertArrayEquals(payload, result.readBytes())
        } finally {
            target.delete()
        }
    }
}
