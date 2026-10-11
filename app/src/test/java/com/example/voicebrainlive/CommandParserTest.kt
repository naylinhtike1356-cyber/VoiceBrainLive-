package com.example.voicebrainlive

import com.example.voicebrainlive.commands.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for CommandParser logic.
 */
class CommandParserTest {

    private val mockMap = mapOf(
        "ဖုန်းခေါ်" to Triple("make_call", "", null),
        "မီးအိမ်ဖွင့်" to Triple("turn_on_flashlight", null, null),
        "spotify" to Triple("open_app", "spotify", null)
    )

    @Test
    fun testTimeAndDateQueries() {
        val timeQuery = CommandParser.parse("ဘယ်နှစ်နာရီရှိပြီလဲ", mockMap)
        assertEquals("get_current_time", timeQuery?.first)
        assertEquals("time", timeQuery?.third)

        val dateQuery = CommandParser.parse("ဒီနေ့ ဘယ်နှရက်နေ့လဲ", mockMap)
        assertEquals("get_current_time", dateQuery?.first)
        assertEquals("date", dateQuery?.third)
    }

    @Test
    fun testVolumePercentParsing() {
        val vol50 = CommandParser.parse("အသံ ၅၀ ထားပေး", mockMap)
        assertEquals("set_volume", vol50?.first)
        assertEquals("50", vol50?.third)

        val vol75 = CommandParser.parse("volume 75 percent", mockMap)
        assertEquals("set_volume", vol75?.first)
        assertEquals("75", vol75?.third)
        
        val vol90 = CommandParser.parse("၉၀ ရာခိုင်နှုန်း", mockMap)
        assertEquals("set_volume", vol90?.first)
        assertEquals("90", vol90?.third)
    }

    @Test
    fun testOpenAppPatterns() {
        val openMaps = CommandParser.parse("open maps", mockMap)
        assertEquals("open_app", openMaps?.first)
        assertEquals("maps", openMaps?.second)

        val openSettings = CommandParser.parse("ဖွင့် settings", mockMap)
        assertEquals("open_app", openSettings?.first)
        assertEquals("settings", openSettings?.second)
    }

    @Test
    fun testExactAndPartialMatches() {
        // Exact
        val facebook = CommandParser.parse("ဖုန်းခေါ်", mockMap)
        assertEquals("make_call", facebook?.first)

        // Partial
        val flashlight = CommandParser.parse("မီးအိမ်ဖွင့်ပေးပါဦး", mockMap)
        assertEquals("turn_on_flashlight", flashlight?.first)
    }

    @Test
    fun testInvalidInputsReturnsNull() {
        assertNull(CommandParser.parse("", mockMap))
        assertNull(CommandParser.parse("အရမ်းလှတဲ့ ညနေခင်းလေးပါ", mockMap))
    }
}
