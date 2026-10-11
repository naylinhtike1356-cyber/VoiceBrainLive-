package com.example.voicebrainlive.updates

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReleaseManifestTest {
    private fun json(code: Long = 2, pkg: String = "com.example.voicebrainlive",
                     hash: String = "a".repeat(64), size: Long = 100) =
        """{"versionCode":$code,"versionName":"1.1.0","packageName":"$pkg","sha256":"$hash","size":$size}"""

    @Test fun acceptsValidUpdateAndRejectsDowngrade() {
        val update = ReleaseManifest.parse(json(), "com.example.voicebrainlive")
        assertTrue(update.isNewerThan(1))
        assertFalse(update.isNewerThan(2))
        assertFalse(update.isNewerThan(3))
    }

    @Test fun rejectsWrongPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.parse(json(pkg = "another.app"), "com.example.voicebrainlive")
        }
    }

    @Test fun rejectsInvalidHashVersionAndSize() {
        for (input in listOf(json(hash = "invalid"), json(code = 0), json(size = 0),
            json(size = ReleaseManifest.MAX_APK_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                ReleaseManifest.parse(input, "com.example.voicebrainlive")
            }
        }
    }
}
