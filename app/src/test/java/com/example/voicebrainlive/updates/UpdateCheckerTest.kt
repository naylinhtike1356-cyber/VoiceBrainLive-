package com.example.voicebrainlive.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private val pkg = "com.example.voicebrainlive"
    private fun release(code: Long = 4) = ReleaseManifest(code, "1.2.0", pkg, "a".repeat(64), 100)
    private fun provider(value: ReleaseManifest?) = object : ReleaseProvider {
        override val configured = true
        override suspend fun latest() = value
    }

    @Test fun defaultCheckerIsUnconfiguredOffline() = runBlocking {
        assertEquals(UpdateStatus.NotConfigured, UpdateChecker(pkg, 3).check())
    }

    @Test fun unconfiguredProviderIsNeverCalled() = runBlocking {
        val unavailable = object : ReleaseProvider {
            override val configured = false
            override suspend fun latest(): ReleaseManifest? = error("Must not be called")
        }
        assertEquals(UpdateStatus.NotConfigured, UpdateChecker(pkg, 3, unavailable).check())
    }

    @Test fun newerFakeReleaseIsAvailable() = runBlocking {
        val expected = release()
        assertEquals(UpdateStatus.Available(expected), UpdateChecker(pkg, 3, provider(expected)).check())
    }

    @Test fun equalAndOlderVersionsAreNeverOffered() = runBlocking {
        for (code in listOf(1L, 3L)) {
            assertEquals(UpdateStatus.Current, UpdateChecker(pkg, 3, provider(release(code))).check())
        }
    }

    @Test fun noPublishedReleaseIsCurrent() = runBlocking {
        assertEquals(UpdateStatus.Current, UpdateChecker(pkg, 3, provider(null)).check())
    }

    @Test fun invalidFakeReleaseFailsClosed() = runBlocking {
        for (value in listOf(release().copy(packageName = "another.app"),
            release().copy(versionCode = -1), release().copy(sha256 = "broken"), release().copy(size = 0))) {
            assertEquals(UpdateStatus.Failed, UpdateChecker(pkg, 3, provider(value)).check())
        }
    }

    @Test fun providerErrorsDoNotExposeSecrets() = runBlocking {
        val failing = object : ReleaseProvider {
            override val configured = true
            override suspend fun latest(): ReleaseManifest? = error("Bearer private-secret")
        }
        assertEquals(UpdateStatus.Failed, UpdateChecker(pkg, 3, failing).check())
    }

    @Test fun cancellationPropagates() = runBlocking {
        val cancelled = object : ReleaseProvider {
            override val configured = true
            override suspend fun latest(): ReleaseManifest? = throw CancellationException("cancel")
        }
        try {
            UpdateChecker(pkg, 3, cancelled).check()
            fail("Cancellation must not become an update error")
        } catch (_: CancellationException) { }
    }
}
