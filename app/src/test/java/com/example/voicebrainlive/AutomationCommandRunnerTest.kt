package com.example.voicebrainlive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomationCommandRunnerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun run_returnsEmpty_forReminderType() {
        val runner = AutomationCommandRunner(context)

        val result = runBlocking { runner.run(type = "reminder", message = "drink water") }

        assertEquals("", result)
    }

    @Test
    fun run_returnsEmpty_forPreReminderFlag() {
        val runner = AutomationCommandRunner(context)

        val result = runBlocking {
            runner.run(
                type = "music",
                message = "play focus music",
                isPreReminder = true
            )
        }

        assertEquals("", result)
    }

    @Test
    fun run_blocksUnknownAutomationTypes() {
        val runner = AutomationCommandRunner(context)

        val result = runBlocking { runner.run(type = "make_call", message = "Mom") }

        assertEquals(
            "Automation type 'make_call' is not supported for automatic execution.",
            result
        )
    }

    @Test
    fun run_executesOfflineCommand_forReminderWithActionPhrase() {
        val runner = AutomationCommandRunner(context)

        val result = runBlocking {
            runner.run(type = "reminder", message = "မီးအိမ်ဖွင့်")
        }

        assertFalse(result.isBlank())
    }
}
