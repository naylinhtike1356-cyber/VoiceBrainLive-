package com.example.voicebrainlive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.commands.handlers.ToolRegistryImpl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ToolRegistryTest {

    private lateinit var context: Context
    private lateinit var registry: ToolRegistryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("tool_registry_test", Context.MODE_PRIVATE)
        registry = ToolRegistryImpl(context, prefs)
    }

    @Test
    fun switch_gem_isHandled() {
        val result = registry.executeCommand("switch_gem", "assistant", null)
        assertNotNull(result)
        assertTrue(result!!.contains("Switched") || result.contains("Gem"))
    }

    @Test
    fun get_relevant_memory_withoutApiKey_returnsMessage() {
        val result = registry.executeCommand("get_relevant_memory", "test query", null)
        assertNotNull(result)
        assertTrue(
            result!!.contains("API") ||
                result.contains("Query") ||
                result.contains("memory") ||
                result.contains("မရ")
        )
    }

    @Test
    fun take_photo_returnsGuidanceWhenNoUiBridge() {
        val result = registry.executeCommand("take_photo", null, null)
        assertNotNull(result)
        assertTrue(result!!.isNotBlank())
    }

    @Test
    fun smart_home_isHandled() {
        val result = registry.executeCommand("smart_home", "light", "on")
        assertNotNull(result)
        assertFalse(result!!.startsWith("Unknown command"))
    }

    @Test
    fun set_home_location_withoutGps_returnsPermissionOrLocationMessage() {
        val result = registry.executeCommand("set_home_location", null, null)
        assertNotNull(result)
        assertFalse(result!!.startsWith("Unknown command"))
    }

    @Test
    fun get_calendar_events_today_isHandled() {
        val result = registry.executeCommand("get_calendar_events", null, "today")
        assertNotNull(result)
        assertFalse(result!!.startsWith("Unknown command"))
    }

    @Test
    fun typedRegistryResult_reportsUnsupportedCommand() {
        val unknown = registry.execute("unknown_command_xyz", null, null)
        assertFalse(unknown.handled)
        assertNotNull(unknown.userFacingError)
    }
}
