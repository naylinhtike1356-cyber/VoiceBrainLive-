package com.example.voicebrainlive.updates

import android.app.Application
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.example.voicebrainlive.BuildConfig
import com.example.voicebrainlive.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppUpdateActivityTest {
    @Test fun showsVersionBuildAndUnconfiguredSourceOffline() {
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).setup()
        try {
            val activity = controller.get()
            val version = activity.findViewById<TextView>(R.id.tvInstalledVersion).text.toString()
            assertTrue(version.contains(BuildConfig.VERSION_NAME))
            assertTrue(version.contains(BuildConfig.VERSION_CODE.toString()))
            assertTrue(version.contains(BuildConfig.BUILD_TYPE))
            assertTrue(activity.findViewById<TextView>(R.id.tvUpdateStatus).text.contains("NOT CONFIGURED"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun checkDoesNotLaunchAnInstallerOrExternalPage() {
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.btnCheckUpdate).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.findViewById<TextView>(R.id.tvUpdateStatus).text.contains("NOT CONFIGURED"))
            assertTrue(activity.findViewById<Button>(R.id.btnCheckUpdate).isEnabled)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
}
