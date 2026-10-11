package com.example.voicebrainlive

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityLaunchTest {

    @Test
    fun testSettingsActivityLaunchesWithoutCrash() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                org.junit.Assert.assertNotNull(activity)
            }
        }
    }
}
