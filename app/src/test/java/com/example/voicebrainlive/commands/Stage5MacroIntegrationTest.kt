package com.example.voicebrainlive.commands

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.commands.handlers.ToolRegistryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class Stage5ProposalApplication : VoiceBrainApplication() {
    override fun onCreate() { /* Fake host app: no production services, credentials or startup. */ }
}

/** Actual common-guard and truthful-result host regressions; no production effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Stage5ProposalApplication::class)
class Stage5MacroIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val macros get() = app.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)
    private lateinit var executor: PhoneCommandExecutor

    @Before fun prepareFakeApp() {
        macros.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        executor = PhoneCommandExecutor(app)
        while (shadowOf(app).nextStartedActivity != null) { /* Drain shadow intents only. */ }
    }
    @After fun revokeFakeSession() { executor.invalidateApprovals() }
    private fun assertNoCallIntent() {
        val intents = mutableListOf<Intent>()
        while (true) intents += shadowOf(app).nextStartedActivity ?: break
        assertTrue(intents.none { it.action == Intent.ACTION_CALL })
    }

    @Test fun cycleAndMissingNestedMacroAreRejectedBeforeLeafEffects() = runBlocking {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        macros.edit().putString("root", "set_volume:30;run_macro:root").commit()
        assertTrue(executor.executeAsync("run_macro", "root", null).contains("MACRO_CYCLE"))
        assertEquals(volumeBefore, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        macros.edit().putString("root", "set_volume:30;run_macro:missing").commit()
        assertTrue(executor.executeAsync("run_macro", "root", null).contains("MACRO_NOT_FOUND"))
        assertEquals(volumeBefore, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertNoCallIntent()
    }

    @Test fun totalLeafOverflowIsRejectedBeforeAnyDispatch() = runBlocking {
        macros.edit().putString("root", "run_macro:child;run_macro:child")
            .putString("child", List(7) { "get_time" }.joinToString(";")).commit()
        val result = executor.executeAsync("run_macro", "root", null)
        assertTrue(result.contains("MACRO_LEAF_LIMIT"))
        assertTrue(result.contains("0 macro step(s) dispatched"))
        assertNoCallIntent()
    }

    @Test fun creationBoundsRejectBeforeWriteAndSafeExistingOverwriteRemainsAllowed() = runBlocking {
        macros.edit().putString("existing", "get_time").commit()
        assertEquals(CommandSafetyPolicy.Classification.SAFE, CommandSafetyPolicy.classify("create_macro"))
        val valid = executor.executeAsync("create_macro", "existing", "get_date")
        assertFalse(valid.startsWith("BLOCKED:"))
        assertEquals("get_date", macros.getString("existing", null))
        val before = macros.all.toMap()
        assertTrue(executor.executeAsync("create_macro", "x".repeat(161), "get_time").startsWith("BLOCKED:"))
        assertTrue(executor.executeAsync("create_macro", "existing", "x".repeat(1_001)).startsWith("BLOCKED:"))
        assertEquals(before, macros.all)
    }

    @Test fun actualCommonGuardPreparesSensitiveCallWithoutPlacingIt() = runBlocking {
        val telecom = shadowOf(app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
        telecom.setCallPhonePermission(true)
        val calls = telecom.allOutgoingCalls.size
        macros.edit().putString("root", "make_call:+821012345678").commit()
        val result = executor.executeAsync("run_macro", "root", null)
        assertTrue(result, result.startsWith("NEEDS_APPROVAL:") || result.startsWith("BLOCKED:"))
        assertEquals(calls, telecom.allOutgoingCalls.size)
        assertNoCallIntent()
    }

    @Test fun opaqueReadCompletionCannotProduceMacroCompletedOrRunSuffix() = runBlocking {
        macros.edit().putString("root", "get_time;create_macro:suffix:get_date").commit()
        // A third argument followed by no separator is unambiguous; the read still has an opaque result.
        val result = executor.executeAsync("run_macro", "root", null)
        assertTrue(result, result.startsWith("UNVERIFIED:"))
        assertFalse(macros.contains("suffix"))
    }

    @Test fun unconfiguredSmartHomeCommandsAreHandledWithoutFalseSuccessOrNativeEffects() = runBlocking {
        val registry = ToolRegistryImpl(app, app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE))
        val aliases = listOf("turn_on_light", "light_on", "turn_off_light", "light_off", "set_temperature", "thermostat",
            "smart_home_scene", "scene", "toggle_light", "dim_light", "set_ac", "turn_off_all_lights", "smart_home")
        for (alias in aliases) {
            val result = registry.executeCommandAsync(alias, "PRIVATE_TARGET", "PRIVATE_ACTION")
            assertNotNull(result)
            assertTrue(result!!, result.startsWith("NOT_CONFIGURED:"))
            assertFalse(result.contains("PRIVATE_TARGET")); assertFalse(result.contains("PRIVATE_ACTION"))
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun learnedMacroUnverifiedResultDoesNotReinforcePatternOrSpeakSuccessHint() = runBlocking {
        macros.edit().putString("root", "get_time").commit()
        val phrase = "review bounded macro"
        val learner = com.example.voicebrainlive.learning.IntentPatternLearner
        learner.clearPatterns(app)
        learner.recordPattern(app, phrase, "run_macro", "root", confirmedByUser = true)
        learner.confirmPattern(app, phrase)
        val before = learner.getIntentForPhrase(app, phrase)
        val result = com.example.voicebrainlive.learning.LearnedIntentExecutor.tryExecute(app, phrase, executor)
        assertNotNull(result)
        assertTrue(result!!.resultMessage, result.resultMessage.startsWith("UNVERIFIED:"))
        assertEquals(result.resultMessage, result.spokenHint)
        assertEquals(before, learner.getIntentForPhrase(app, phrase))
    }

    @Test fun learnedUnconfiguredDeviceResultDoesNotReinforcePatternOrSpeakSuccessHint() = runBlocking {
        val phrase = "review home light"
        val learner = com.example.voicebrainlive.learning.IntentPatternLearner
        learner.clearPatterns(app)
        learner.recordPattern(app, phrase, "turn_on_light", "synthetic-room", confirmedByUser = true)
        learner.confirmPattern(app, phrase)
        val before = learner.getIntentForPhrase(app, phrase)
        val result = com.example.voicebrainlive.learning.LearnedIntentExecutor.tryExecute(app, phrase, executor)
        assertNotNull(result)
        assertTrue(result!!.resultMessage, result.resultMessage.startsWith("NOT_CONFIGURED:"))
        assertEquals(result.resultMessage, result.spokenHint)
        assertEquals(before, learner.getIntentForPhrase(app, phrase))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun scheduledSequenceStopsAfterOpaqueReadBeforeLocalWriteSuffix() = runBlocking {
        val result = com.example.voicebrainlive.usecases.AutomationCommandRunner(app).run("command",
            "get_time;create_macro:suffix:get_date")
        assertTrue(result, result.startsWith("UNVERIFIED:"))
        assertFalse(macros.contains("suffix"))
        assertTrue(result.contains("1 macro step(s) dispatched"))
    }

    @Test fun scheduledSensitiveCallKeepsGuardPrefixAndNeverCallsOrRunsSuffix() = runBlocking {
        val telecom = shadowOf(app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
        telecom.setCallPhonePermission(true)
        val before = telecom.allOutgoingCalls.size
        val result = com.example.voicebrainlive.usecases.AutomationCommandRunner(app).run("command",
            "make_call:+821012345678")
        assertTrue(result, result.startsWith("NEEDS_APPROVAL:") || result.startsWith("BLOCKED:"))
        assertEquals(before, telecom.allOutgoingCalls.size)
        assertNoCallIntent()
    }
}
