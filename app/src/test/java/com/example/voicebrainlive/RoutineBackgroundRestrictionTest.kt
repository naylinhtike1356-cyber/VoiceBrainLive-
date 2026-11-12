package com.example.voicebrainlive

import android.app.Application
import android.app.NotificationManager
import android.content.ContextWrapper
import android.content.Intent
import com.example.voicebrainlive.routines.RoutineReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RoutineBackgroundRestrictionTest {
    @Test fun deniedStartDoesNotCrashOrRetryAndOffersReview() {
        val app = RuntimeEnvironment.getApplication()
        listOf(IllegalStateException("blocked"), SecurityException("denied")).forEach { failure ->
            var attempts = 0
            val context = object : ContextWrapper(app) {
                override fun startForegroundService(service: Intent): android.content.ComponentName? {
                    attempts++
                    throw failure
                }
            }
            RoutineReceiver().onReceive(context, Intent(RoutineReceiver.ACTION_ROUTINE)
                .putExtra(MainActivity.EXTRA_AUTOMATION_ID, -1)
                .putExtra(MainActivity.EXTRA_AUTOMATION_MESSAGE, "private routine"))
            assertEquals(1, attempts)
            val notice = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(94640)
            assertNotNull(notice)
            assertFalse(notice.extras.toString().contains("private routine"))
            assertNotNull(notice.contentIntent)
        }
    }
}
