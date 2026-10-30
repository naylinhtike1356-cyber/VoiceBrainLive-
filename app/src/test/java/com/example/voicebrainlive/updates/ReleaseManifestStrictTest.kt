package com.example.voicebrainlive.updates

import org.junit.Assert.*
import org.junit.Test

class ReleaseManifestStrictTest {
    private val pkg = "com.example.voicebrainlive"
    private fun json(code: String = "4", size: String = "100", extras: String = "") =
        """{"versionCode":$code,"versionName":"1.2.0","packageName":"$pkg","sha256":"${"a".repeat(64)}","size":$size$extras}"""
    private fun reject(value: String) {
        assertThrows(IllegalArgumentException::class.java) { ReleaseManifest.parse(value, pkg) }
    }

    @Test fun parsesLegacyMetadataWithoutNotes() {
        assertEquals("", ReleaseManifest.parse(json(), pkg).releaseNotes)
    }

    @Test fun preservesMultilingualNotesAndNewlinesExactly() {
        val value = ReleaseManifest.parse(json(extras = ",\"releaseNotes\":\"မြန်မာ 한글 English\\r\\n김민수\\nလေ့လာမှု\""), pkg)
        assertEquals("မြန်မာ 한글 English\r\n김민수\nလေ့လာမှု", value.releaseNotes)
    }

    @Test fun rejectsIntegerCoercionAndOverflow() {
        for (code in listOf("\"4\"", "4.0", "4e0", "-1", "0", "9223372036854775808", "null", "true")) reject(json(code))
        for (size in listOf("\"100\"", "100.0", "1e2", "-1", "0", "9223372036854775808")) reject(json(size = size))
    }

    @Test fun rejectsDuplicateAndUnknownFields() {
        reject(json(extras = ",\"versionCode\":5"))
        reject(json(extras = ",\"apkUrl\":\"https://untrusted.example/app.apk\""))
    }

    @Test fun rejectsTrailingDataAndMalformedJson() {
        reject(json() + " {}")
        reject(json().dropLast(1))
        reject(json().replace("\"versionCode\"", "versionCode"))
        reject(json().replace("{", "{/* comment */"))
    }

    @Test fun rejectsStringCoercionAndNullNotes() {
        reject(json().replace("\"1.2.0\"", "42"))
        reject(json(extras = ",\"releaseNotes\":null"))
    }

    @Test fun rejectsOversizedMetadataAndReleaseNotes() {
        reject(" ".repeat(ReleaseManifest.MAX_METADATA_BYTES + 1))
        reject(json(extras = ",\"releaseNotes\":\"${"a".repeat(ReleaseManifest.MAX_RELEASE_NOTES_CHARS + 1)}\""))
        reject(json(extras = ",\"releaseNotes\":\"${"မြန်မာ".repeat(3000)}\""))
    }

    @Test fun comparesLongVersionCodesNumerically() {
        val value = ReleaseManifest.parse(json(code = "9223372036854775807"), pkg)
        assertTrue(value.isNewerThan(2147483647L))
        assertFalse(value.isNewerThan(Long.MAX_VALUE))
    }
}
