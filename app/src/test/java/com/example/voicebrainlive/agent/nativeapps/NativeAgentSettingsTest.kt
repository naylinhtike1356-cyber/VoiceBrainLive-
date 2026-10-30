package com.example.voicebrainlive.agent.nativeapps

import android.app.Application
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.widget.CheckBox
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.notifications.AgentNotificationAudio
import com.example.voicebrainlive.security.ShadowSecureSettingsStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class NativeAgentSettingsTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val prefs get()=SettingsActivity.getSecurePrefs(app)
    @Before fun setup(){ShadowSecureSettingsStorage.reset();prefs.edit().clear().putString(SettingsActivity.KEY_GEMINI_API,"synthetic-host-only-key").putString(SettingsActivity.KEY_GEMINI_MODEL,"models/test-reviewed").putStringSet(SettingsActivity.KEY_SELECTED_NOTIFICATION_APPS,setOf("selected.app")).commit()}
    @After fun cleanup(){AgentNotificationAudio.queue.clear();ShadowSecureSettingsStorage.reset()}
    private fun enable()=NativeAgentSettings.setEnabled(app,true,NativeAgentSettings.binding(app))
    @Test fun disabledUntilExactReviewAndNeverImplicitlyEnabledBySelectedApps(){assertFalse(NativeAgentSettings.enabled(app));assertFalse(NativeAgentSettings.setEnabled(app,true));assertTrue(enable());assertTrue(NativeAgentSettings.enabled(app))}
    @Test fun modelProfileAndKeyChangesInvalidateConsent(){assertTrue(enable());prefs.edit().putString(SettingsActivity.KEY_GEMINI_MODEL,"models/changed").commit();assertFalse(NativeAgentSettings.enabled(app));assertTrue(enable());prefs.edit().putString(SettingsActivity.KEY_GEMINI_API,"different-host-key").commit();assertFalse(NativeAgentSettings.enabled(app));assertTrue(enable());prefs.edit().putStringSet(SettingsActivity.KEY_SELECTED_NOTIFICATION_APPS,setOf("other.app")).commit();assertFalse(NativeAgentSettings.enabled(app))}
    @Test fun oldReviewCannotEnableDifferentScope(){val old=NativeAgentSettings.binding(app);prefs.edit().putString(SettingsActivity.KEY_VOICE,"Kore").commit();assertFalse(NativeAgentSettings.setEnabled(app,true,old));assertFalse(NativeAgentSettings.enabled(app))}
    @Test fun finiteBudgetPersistsAndRejectsClockRewind(){assertTrue(enable());val start=100_000L;assertTrue(NativeAgentSettings.reserve(app,start));assertFalse(NativeAgentSettings.reserve(app,start+1));assertFalse(NativeAgentSettings.reserve(app,start-1));for(i in 1..11)assertTrue(NativeAgentSettings.reserve(app,start+i*60_000));assertFalse(NativeAgentSettings.reserve(app,start+12*60_000));assertTrue(NativeAgentSettings.reserve(app,start+86_400_000))}
    @Test fun secureFailureBlocksEnableAndNoPlaintextFallback(){assertTrue(enable());ShadowSecureSettingsStorage.failCreation=true;assertFalse(NativeAgentSettings.enabled(app));assertFalse(NativeAgentSettings.reserve(app));assertFalse(NativeAgentSettings.setEnabled(app,true,"old-review"))}
    @Test fun disableRevokesExistingConsent(){assertTrue(enable());assertTrue(NativeAgentSettings.setEnabled(app,false));assertFalse(NativeAgentSettings.enabled(app));assertFalse(NativeAgentSettings.reserve(app))}
    @Test fun failedDisableWriteStillBlocksThisProcessUntilNewReview(){
        assertTrue(enable());val original=prefs
        ShadowSecureSettingsStorage.prefsOverride=object:SharedPreferences by original {
            override fun edit():SharedPreferences.Editor {
                val real=original.edit()
                return object:SharedPreferences.Editor by real {
                    override fun putString(key:String?,value:String?):SharedPreferences.Editor {real.putString(key,value);return this}
                    override fun commit()=false
                }
            }
        }
        assertFalse(NativeAgentSettings.setEnabled(app,false));assertFalse(NativeAgentSettings.enabled(app))
        ShadowSecureSettingsStorage.prefsOverride=null;assertFalse(NativeAgentSettings.enabled(app));assertTrue(enable());assertTrue(NativeAgentSettings.enabled(app))
    }
    @Test fun settingsUiDoesNotEnableWithoutCheckbox(){val controller=Robolectric.buildActivity(NativeAgentSettingsActivity::class.java).setup();val a=controller.get();val button=a.window.decorView.findViewWithTag<Button>("native_audio_enable");button.performClick();assertFalse(NativeAgentSettings.enabled(app));a.window.decorView.findViewWithTag<CheckBox>("native_audio_review").isChecked=true;button.performClick();assertTrue(NativeAgentSettings.enabled(app));controller.pause().stop().destroy()}
    @Test @Config(sdk=[34]) fun serviceAndSettingsArePrivateAndNoMicrophoneServiceType(){val info=app.packageManager.getServiceInfo(android.content.ComponentName(app,com.example.voicebrainlive.notifications.AgentNotificationAudioService::class.java),0);assertFalse(info.exported);assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,info.foregroundServiceType);val activity=app.packageManager.getActivityInfo(android.content.ComponentName(app,NativeAgentSettingsActivity::class.java),0);assertFalse(activity.exported)}
}
