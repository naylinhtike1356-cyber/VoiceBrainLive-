package com.example.voicebrainlive.updates

import org.json.JSONObject

data class ReleaseManifest(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val sha256: String,
    val size: Long
) {
    fun isNewerThan(installedVersion: Long): Boolean = versionCode > installedVersion

    companion object {
        const val APK_NAME = "VoiceBrainLive.apk"
        const val MAX_APK_BYTES = 250L * 1024 * 1024

        fun parse(json: String, expectedPackage: String): ReleaseManifest {
            val value = JSONObject(json)
            val result = ReleaseManifest(
                value.getLong("versionCode"), value.getString("versionName"),
                value.getString("packageName"), value.getString("sha256"), value.getLong("size")
            )
            require(result.versionCode > 0 && result.versionName.isNotBlank()) { "Invalid update version" }
            require(result.packageName == expectedPackage) { "Update belongs to a different app" }
            require(result.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid update checksum" }
            require(result.size in 1..MAX_APK_BYTES) { "Invalid update size" }
            return result
        }
    }
}
