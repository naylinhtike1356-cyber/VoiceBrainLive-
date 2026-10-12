package com.example.voicebrainlive

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "sw800dp-w1280dp-h800dp-land")
class StartupLaunchTest {
    @Before
    fun isolateStartupFromDeveloperCredentialsAndNetwork() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SettingsActivity.getSecurePrefs(app).edit()
            .putString(SettingsActivity.KEY_GEMINI_API, "YOUR_API_KEY").commit()
        shadowOf(app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .setActiveNetworkInfo(null)
    }

    @Test
    fun firstRunMainRedirectCanBeDestroyedBeforeAudioInitialization() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("onboarding", Application.MODE_PRIVATE).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().use { controller ->
            org.junit.Assert.assertTrue(controller.get().isFinishing)
        }
    }

    @Test
    fun uninitializedViewModelCanClearCallbacksAndResourcesRepeatedly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))
            .get(MainActivityViewModel::class.java)
        vm.clearCallbacks()
        vm.clearCallbacks()
        store.clear()
    }

    @Test
    fun firstRunOnboardingRemainsOpen() {
        Robolectric.buildActivity(OnboardingActivity::class.java).setup().use { controller ->
            assertFalse(controller.get().isFinishing)
            assertNotNull(controller.get().findViewById<android.view.View>(R.id.btnSkip))
        }
    }

    @Test
    fun mainScreenRemainsOpenWithoutPermissionsOrApiKey() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        OnboardingActivity.setOnboardingDone(app)
        // Do not drain the home screen's infinite animation frame queue with visible().
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().use { controller ->
            assertFalse(controller.get().isFinishing)
            assertNotNull(controller.get().findViewById<android.view.View>(R.id.mainContentFrame))
        }
    }
}
