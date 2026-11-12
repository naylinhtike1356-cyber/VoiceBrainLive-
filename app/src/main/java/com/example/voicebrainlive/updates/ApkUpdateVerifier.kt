package com.example.voicebrainlive.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Verify the actual archive before handing it to Android's installer. */
object ApkUpdateVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: ReleaseManifest) {
        release.validate(context.packageName)
        require(file.exists() && file.isFile) { "Downloaded APK file does not exist" }
        require(file.length() == release.apkBytes) {
            "APK size mismatch: file has ${file.length()} bytes, metadata specifies ${release.apkBytes}"
        }

        // Verify SHA-256 before interacting with PackageManager
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = stream.read(buf)
                if (r == -1) break
                digest.update(buf, 0, r)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualSha256.equals(release.apkSha256, ignoreCase = true)) {
            "APK checksum mismatch: expected ${release.apkSha256}, got $actualSha256"
        }

        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Downloaded file is not a valid Android APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { it.toCharsString() }.toSet()
        }
        require(archive.packageName == context.packageName) { "APK belongs to a different app: ${archive.packageName}" }
        require(version(archive) == release.androidVersionCode && archive.versionName == release.androidVersionName) {
            "APK version (${archive.versionName}/${version(archive)}) does not match release (${release.androidVersionName}/${release.androidVersionCode})"
        }
        require(version(archive) > version(installed)) {
            "APK is not a newer version (archive: ${version(archive)}, installed: ${version(installed)})"
        }

        val trusted = signers(installed)
        val archiveSigners = signers(archive)
        if (trusted.isNotEmpty() && archiveSigners.isNotEmpty()) {
            require(trusted == archiveSigners) { "APK signing certificate does not match the installed app" }
        }
    }
}
