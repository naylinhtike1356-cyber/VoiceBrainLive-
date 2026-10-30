package com.example.voicebrainlive.updates

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

data class ReleaseManifest(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val sha256: String,
    val size: Long,
    val releaseNotes: String = "",
    val downloadUrl: String = ""
) {
    fun isNewerThan(installedVersion: Long): Boolean = versionCode > installedVersion

    fun validate(expectedPackage: String) {
        require(versionCode > 0 && versionName.isNotBlank() && versionName.length <= 128) { "Invalid update version" }
        require(packageName == expectedPackage) { "Update belongs to a different app" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid update checksum" }
        require(size in 1..MAX_APK_BYTES) { "Invalid update size" }
        require(releaseNotes.length <= MAX_RELEASE_NOTES_CHARS) { "Release notes are too large" }
    }

    companion object {
        const val APK_NAME = "VoiceBrainLive.apk"
        const val MAX_APK_BYTES = 250L * 1024 * 1024
        const val MAX_METADATA_BYTES = 32 * 1024
        const val MAX_RELEASE_NOTES_CHARS = 8192

        /** Structural validation only; a parsed manifest is not proof of publisher authenticity. */
        fun parse(json: String, expectedPackage: String): ReleaseManifest {
            require(json.length <= MAX_METADATA_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) {
                "Update manifest is too large"
            }
            try {
                JsonReader(StringReader(json)).use { reader ->
                    reader.isLenient = false
                    val fields = mutableMapOf<String, Any>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(name !in fields) { "Duplicate update field" }
                        fields[name] = when (name) {
                            "versionCode", "size" -> {
                                require(reader.peek() == JsonToken.NUMBER) { "Invalid integer field" }
                                val number = reader.nextString()
                                require(number.matches(Regex("[1-9][0-9]*"))) { "Invalid integer field" }
                                number.toLongOrNull() ?: throw IllegalArgumentException("Integer field overflow")
                            }
                            "versionName", "packageName", "sha256", "releaseNotes", "downloadUrl" -> {
                                require(reader.peek() == JsonToken.STRING) { "Invalid text field" }
                                reader.nextString()
                            }
                            else -> throw IllegalArgumentException("Unsupported update field")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing update data" }
                    val result = ReleaseManifest(
                        fields["versionCode"] as? Long ?: throw IllegalArgumentException("Missing version code"),
                        fields["versionName"] as? String ?: throw IllegalArgumentException("Missing version name"),
                        fields["packageName"] as? String ?: throw IllegalArgumentException("Missing package name"),
                        fields["sha256"] as? String ?: throw IllegalArgumentException("Missing checksum"),
                        fields["size"] as? Long ?: throw IllegalArgumentException("Missing size"),
                        fields["releaseNotes"] as? String ?: "",
                        fields["downloadUrl"] as? String ?: ""
                    )
                    result.validate(expectedPackage)
                    return result
                }
            } catch (invalid: IllegalArgumentException) {
                throw invalid
            } catch (invalid: Exception) {
                throw IllegalArgumentException("Invalid update manifest", invalid)
            }
        }
    }
}
