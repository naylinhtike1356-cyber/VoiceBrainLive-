package com.example.voicebrainlive.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Verify the actual archive before handing it to Android's installer. */
object ApkUpdateVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: ReleaseManifest) {
        release.validate(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Downloaded file is not an APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { it.toCharsString() }.toSet()
        }
        require(archive.packageName == context.packageName) { "APK belongs to a different app" }
        require(version(archive) == release.versionCode && archive.versionName == release.versionName) { "APK version does not match release" }
        require(version(archive) > version(installed)) { "APK is not a newer version" }
        val trusted = signers(installed)
        require(trusted.isNotEmpty() && trusted == signers(archive)) { "APK signing certificate does not match the installed app" }
    }
}
