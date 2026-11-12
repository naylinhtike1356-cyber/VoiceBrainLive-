package com.example.voicebrainlive.commands

import android.app.Application
import android.content.Context
import android.media.AudioManager
import com.example.voicebrainlive.FocusMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Stage5ProposalApplication::class)
class MacroWorkflowPolishTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)

    @Test fun legacyMixedCaseNamesWorkWithoutChangingStoredDefinitions() = runBlocking {
        prefs.edit().putString("Meeting Mode", "set_ringer_vibrate;mute").commit()
        val before = prefs.all.toMap()
        val output = PhoneCommandExecutor(app).executeAsync("run_macro", " MEETING MODE ", null)
        assertTrue(output, output.startsWith("COMPLETED:"))
        assertEquals(before, prefs.all)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test fun audioMacroRunsEveryVerifiedStepThroughTheActualGuard() = runBlocking {
        prefs.edit().putString("audio", "set_volume:၁၅၀;volume_down;mute;unmute").commit()
        val output = PhoneCommandExecutor(app).executeAsync("run_macro", "audio", null)
        assertTrue(output, output.startsWith("COMPLETED:"))
        assertTrue(output, output.contains("4 macro step(s) dispatched"))
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) - 1, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test fun studyMacroVerifiesFocusBeforeContinuing() = runBlocking {
        prefs.edit().putString("study", "set_focus_mode:30;set_ringer_vibrate").commit()
        val output = PhoneCommandExecutor(app).executeAsync("run_macro", "study", null)
        assertTrue(output, output.startsWith("COMPLETED:"))
        assertTrue(FocusMode.isActive(app))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode)
    }

    @Test fun aSuccessStringWithoutDeviceChangeDoesNotRunTheSuffix() = runBlocking {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val verification = MacroDeviceVerification(app)
        val result = verification.dispatch(MacroLeaf("set_volume", "150", "150", MacroLeafRisk.SAFE)) { "Volume set to 150%" }
        assertEquals(MacroOutcomeStatus.UNVERIFIED, result.status)
    }

    @Test fun guardRejectionCannotBePromotedEvenWhenDeviceAlreadyMatches() = runBlocking {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val verification = MacroDeviceVerification(app)
        for (message in listOf("BLOCKED: policy", "NEEDS_APPROVAL: review", "FAILED: unavailable", "FAILED/OUTCOME_UNKNOWN: timeout")) {
            val result = verification.dispatch(MacroLeaf("set_ringer_vibrate", null, null, MacroLeafRisk.SAFE)) { message }
            assertNotEquals(MacroOutcomeStatus.VERIFIED, result.status)
            assertEquals(message, result.message)
        }
    }

    @Test fun ambiguousLegacyNamesAndReservedFlagsCannotExecuteOrBeOverwritten() = runBlocking {
        prefs.edit().putString("Mode", "mute").putString("mode", "unmute").putBoolean("show_macro_guide", false).commit()
        val executor = PhoneCommandExecutor(app)
        val before = prefs.all.toMap()
        assertTrue(executor.executeAsync("run_macro", "Mode", null).startsWith("BLOCKED:"))
        assertTrue(executor.executeAsync("create_macro", "mode", "get_time").startsWith("BLOCKED:"))
        assertTrue(executor.executeAsync("create_macro", "SHOW_MACRO_GUIDE", "get_time").startsWith("BLOCKED:"))
        assertEquals(before, prefs.all)
    }

    @Test fun previewListsReadableStepsAndPreservesSensitivePayload() {
        val preview = MacroDefinitions.preview("set_focus_mode:30;set_ringer_vibrate")
        assertTrue(preview.contains("1. "))
        assertTrue(preview.contains("2. "))
        assertFalse(preview.contains("set_focus_mode"))
        assertTrue(MacroDefinitions.preview("send_to_notion:destination:exact;body").contains("exact;body"))
    }

    @Test fun displayedMacroResultShowsVerifiedAndUncertainStepsWithoutClaimingCompletion() {
        val run = MacroRunResult(MacroOutcomeStatus.UNVERIFIED,"STOPPED_NO_AUTOMATIC_RESUME_OR_RETRY",2,listOf(
            MacroStepObservation("mute",MacroLeafResult(MacroOutcomeStatus.VERIFIED,"muted")),
            MacroStepObservation("get_weather",MacroLeafResult(MacroOutcomeStatus.UNVERIFIED,"opaque"))))
        val display = CommandResultFormatter.format(run.renderLegacy())
        assertTrue(display.contains("1. မီဒီယာအသံ ပိတ်ရန် · စက်အခြေအနေ ပြန်စစ်ပြီး"))
        assertTrue(display.contains("2. မိုးလေဝသ ကြည့်ရန် · ရလဒ် အတည်မပြုနိုင်သေးပါ"))
        assertTrue(display.contains("ကျန်အဆင့်များ ရပ်ထားသည်"))
        assertFalse(display.contains("UNVERIFIED:"))
        assertFalse(display.contains("အဆင့်အားလုံးကို လုပ်ဆောင်ပြီး"))
    }
}
