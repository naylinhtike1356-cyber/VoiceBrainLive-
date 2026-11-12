package com.example.voicebrainlive.commands

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompoundCommandExecutorTest {
    @Test
    fun durationConnectorStaysInsideTheTimerStep() {
        val steps = CompoundCommandExecutor.parseSteps("သီချင်းဖွင့်ပြီး ၃၀ မိနစ်ပြီးရင် ပိတ်ပေးပါ")
        assertEquals(listOf("media_play", "set_music_sleep_timer"), steps?.map { it.first })
        assertEquals("30", steps?.last()?.third)
    }

    @Test
    fun parseStepsReturnsNullForSingleCommand() {
        assertNull(CompoundCommandExecutor.parseSteps("ဓာတ်မီးဖွင့်ပေးပါ"))
        assertNull(CompoundCommandExecutor.parseSteps("အသံတိုးပေး"))
        assertNull(CompoundCommandExecutor.parseSteps("မနက် ၇ နာရီမှာ နှိုးစက်ထားပေးပါ"))
    }

    @Test
    fun parseStepsRecognizesCompoundCommandsWithVariousConjunctions() {
        val steps1 = CompoundCommandExecutor.parseSteps("ဓာတ်မီးဖွင့်ပြီး အသံတိုးပေးပါ")
        assertNotNull(steps1)
        assertEquals(2, steps1!!.size)
        assertEquals("turn_on_flashlight", steps1[0].first)
        assertEquals("volume_up", steps1[1].first)

        val steps2 = CompoundCommandExecutor.parseSteps("မနက် ၇ နာရီ နှိုးစက်ထားပြီးတော့ ဓာတ်မီးဖွင့်ပါ")
        assertNotNull(steps2)
        assertEquals(2, steps2!!.size)
        assertEquals("set_alarm", steps2[0].first)
        assertEquals("07:00", steps2[0].second)
        assertEquals("turn_on_flashlight", steps2[1].first)

        val steps3 = CompoundCommandExecutor.parseSteps("သီချင်းဖွင့်ပြီးရင် ဓာတ်မီးပိတ်ပေး")
        assertNotNull(steps3)
        assertEquals(2, steps3!!.size)
        assertEquals("media_play", steps3[0].first)
        assertEquals("turn_off_flashlight", steps3[1].first)

        val steps4 = CompoundCommandExecutor.parseSteps("turn on flashlight and volume up")
        assertNotNull(steps4)
        assertEquals(2, steps4!!.size)
        assertEquals("turn_on_flashlight", steps4[0].first)
        assertEquals("volume_up", steps4[1].first)

        // Compound delayed command: Play music and pause after 5 minutes
        val steps5 = CompoundCommandExecutor.parseSteps("သီချင်းဖွင့်ပြီး ၅ မိနစ်ကြာရင် ပိတ်ပေးပါ")
        assertNotNull(steps5)
        assertEquals(2, steps5!!.size)
        assertEquals("media_play", steps5[0].first)
        assertEquals("set_music_sleep_timer", steps5[1].first)
        assertEquals("5", steps5[1].third)

        // Informal Burmese variant: သီးချင်းကိုဖွင့်ပီး ၅မီနစ်ကြာရင် ပိတ်ပေးပါ
        val steps6 = CompoundCommandExecutor.parseSteps("သီးချင်းကိုဖွင့်ပီး ၅မီနစ်ကြာရင် ပိတ်ပေးပါ")
        assertNotNull(steps6)
        assertEquals(2, steps6!!.size)
        assertEquals("media_play", steps6[0].first)
        assertEquals("set_music_sleep_timer", steps6[1].first)
        assertEquals("5", steps6[1].third)

        // Alarm dismissal + other safe action
        val steps7 = CompoundCommandExecutor.parseSteps("နှိုးစက်ပိတ်ပြီး ဓာတ်မီးဖွင့်ပါ")
        assertNotNull(steps7)
        assertEquals(2, steps7!!.size)
        assertEquals("stop_wake_alarm", steps7[0].first)
        assertEquals("turn_on_flashlight", steps7[1].first)
    }

    @Test
    fun executeRunsCommandsSequentiallyAndCombinesOutputs() = runBlocking {
        val executed = mutableListOf<Triple<String, String?, String?>>()
        val result = CompoundCommandExecutor.execute("ဓာတ်မီးဖွင့်ပြီး အသံတိုးပေးပါ") { cmd, target, value ->
            executed.add(Triple(cmd, target, value))
            when (cmd) {
                "turn_on_flashlight" -> "Flashlight on"
                "volume_up" -> "အသံ တိုးမြှင့်ပြီးပါပြီ"
                else -> "OK"
            }
        }

        assertEquals(2, executed.size)
        assertEquals("turn_on_flashlight", executed[0].first)
        assertEquals("volume_up", executed[1].first)
        assertNotNull(result)
        assertTrue(result!!.contains("ဓာတ်မီး"))
        assertTrue(result.contains("အသံ"))
    }

    @Test
    fun parseStepsRejectsUnsafeOrUnrecognizedSegments() {
        // Contains unknown bank app
        assertNull(CompoundCommandExecutor.parseSteps("ဓာတ်မီးဖွင့်ပြီး unknown bank app ဖွင့်ပေး"))
        // Contains arbitrary macro or sensitive action without reviewed format
        assertNull(CompoundCommandExecutor.parseSteps("ဓာတ်မီးဖွင့်ပြီး /agent-run task1"))
    }
}
