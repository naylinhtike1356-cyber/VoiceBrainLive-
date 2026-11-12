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
    val downloadUrl: String = "",
    val app: String = "",
    val version: String = "",
    val buildType: String = "",
    val apk: String = "",
    val builtAt: String = "",
    val notes: List<String> = emptyList()
) {
    val androidVersionCode: Long get() = versionCode
    val androidVersionName: String get() = versionName
    val apkBytes: Long get() = size
    val apkSha256: String get() = sha256

    // Backward compatibility for legacy 5-argument callers
    constructor(
        versionCode: Long,
        versionName: String,
        packageName: String,
        sha256: String,
        size: Long
    ) : this(
        versionCode = versionCode,
        versionName = versionName,
        packageName = packageName,
        sha256 = sha256,
        size = size,
        releaseNotes = "",
        downloadUrl = ""
    )

    // Backward compatibility for legacy 6-argument callers
    constructor(
        versionCode: Long,
        versionName: String,
        packageName: String,
        sha256: String,
        size: Long,
        releaseNotes: String
    ) : this(
        versionCode = versionCode,
        versionName = versionName,
        packageName = packageName,
        sha256 = sha256,
        size = size,
        releaseNotes = releaseNotes,
        downloadUrl = ""
    )

    fun isNewerThan(installedVersion: Long): Boolean = androidVersionCode > installedVersion

    val apkName: String
        get() = if (apk.isNotBlank()) apk else APK_NAME

    fun validate(expectedPackage: String) {
        require(androidVersionCode > 0 && androidVersionName.isNotBlank() && androidVersionName.length <= 128) { "Invalid update version" }
        require(packageName == expectedPackage) { "Update belongs to a different app" }
        require(apkSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Invalid update checksum" }
        require(apkBytes in 1..MAX_APK_BYTES) { "Invalid update size" }
        require(releaseNotes.length <= MAX_RELEASE_NOTES_CHARS) { "Release notes are too large" }
        if (apk.isNotBlank()) {
            require(isValidApkBasename(apk)) { "Invalid APK filename: must be a safe basename" }
        }
        if (app.isNotBlank()) {
            require(app.length <= 128) { "Invalid app name" }
        }
    }

    fun toContractJson(): String {
        val notesArray = notes.joinToString(",", prefix = "[", postfix = "]") {
            "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
        }
        val appName = if (app.isNotBlank()) app else "VoiceBrainLive"
        val vName = androidVersionName
        val verStr = if (version.isNotBlank()) version else "v$vName"
        val bType = if (buildType.isNotBlank()) buildType else "release"
        return """
{
  "app": "$appName",
  "version": "$verStr",
  "androidVersionName": "$vName",
  "androidVersionCode": $androidVersionCode,
  "buildType": "$bType",
  "apk": "$apkName",
  "apkBytes": $apkBytes,
  "apkSha256": "$apkSha256",
  "builtAt": "$builtAt",
  "notes": $notesArray
}
""".trimIndent()
    }

    companion object {
        const val APK_NAME = "VoiceBrainLive.apk"
        const val MAX_APK_BYTES = 250L * 1024 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_RELEASE_NOTES_CHARS = 8192

        fun isValidApkBasename(name: String): Boolean {
            if (name.contains("/") || name.contains("\\") || name.contains("..")) return false
            return name.matches(Regex("^[A-Za-z0-9_.-]+\\.apk$"))
        }

        /** Structural and cryptographic contract validation. */
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
                            "versionCode", "androidVersionCode", "size", "apkBytes" -> {
                                require(reader.peek() == JsonToken.NUMBER) { "Invalid integer field" }
                                val number = reader.nextString()
                                require(number.matches(Regex("[1-9][0-9]*"))) { "Invalid integer field" }
                                number.toLongOrNull() ?: throw IllegalArgumentException("Integer field overflow")
                            }
                            "notes" -> {
                                require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Invalid notes field" }
                                reader.beginArray()
                                val noteList = mutableListOf<String>()
                                while (reader.hasNext()) {
                                    require(reader.peek() == JsonToken.STRING) { "Invalid note item" }
                                    noteList.add(reader.nextString())
                                }
                                reader.endArray()
                                noteList
                            }
                            "app", "version", "versionName", "androidVersionName", "packageName",
                            "sha256", "apkSha256", "buildType", "apk", "builtAt", "releaseNotes", "downloadUrl" -> {
                                require(reader.peek() == JsonToken.STRING) { "Invalid text field" }
                                reader.nextString()
                            }
                            else -> throw IllegalArgumentException("Unsupported update field: $name")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing update data" }

                    val rawCode = (fields["androidVersionCode"] ?: fields["versionCode"]) as? Long
                        ?: throw IllegalArgumentException("Missing version code")
                    val rawName = (fields["androidVersionName"] ?: fields["versionName"]) as? String
                        ?: (fields["version"] as? String)?.removePrefix("v")
                        ?: throw IllegalArgumentException("Missing version name")
                    val rawPkg = (fields["packageName"] as? String) ?: expectedPackage
                    val rawSha = (fields["apkSha256"] ?: fields["sha256"]) as? String
                        ?: throw IllegalArgumentException("Missing checksum")
                    val rawBytes = (fields["apkBytes"] ?: fields["size"]) as? Long
                        ?: throw IllegalArgumentException("Missing size")

                    @Suppress("UNCHECKED_CAST")
                    val rawNotesList = (fields["notes"] as? List<String>) ?: emptyList()
                    val rawReleaseNotes = (fields["releaseNotes"] as? String)?.takeIf { it.isNotBlank() }
                        ?: if (rawNotesList.isNotEmpty()) rawNotesList.joinToString("\n• ", prefix = "• ") else ""

                    val rawApp = (fields["app"] as? String) ?: ""
                    val rawVersion = (fields["version"] as? String) ?: "v$rawName"
                    val rawBuildType = (fields["buildType"] as? String) ?: ""
                    val rawApk = (fields["apk"] as? String) ?: ""
                    val rawBuiltAt = (fields["builtAt"] as? String) ?: ""
                    val rawDownloadUrl = (fields["downloadUrl"] as? String) ?: ""

                    val result = ReleaseManifest(
                        versionCode = rawCode,
                        versionName = rawName,
                        packageName = rawPkg,
                        sha256 = rawSha,
                        size = rawBytes,
                        releaseNotes = rawReleaseNotes,
                        downloadUrl = rawDownloadUrl,
                        app = rawApp,
                        version = rawVersion,
                        buildType = rawBuildType,
                        apk = rawApk,
                        builtAt = rawBuiltAt,
                        notes = rawNotesList
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
