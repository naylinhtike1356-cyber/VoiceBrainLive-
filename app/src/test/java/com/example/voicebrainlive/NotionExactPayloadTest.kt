package com.example.voicebrainlive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.notion.NotionClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Serializer tests only. No Notion credentials or production/network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotionExactPayloadTest {
    @Test fun frozenRequest_containsExactTitleDestinationAndMultilingualBody() {
        val client = NotionClient(ApplicationProvider.getApplicationContext<Context>())
        val title = "  မြန်မာ / 한국어 / MiXeD!  "
        val body = "a".repeat(1999) + "🙂\r\nမြန်မာ၊ 한국어! MiXeD English;  "
        val id = "0123456789abcdef0123456789abcdef"
        val json = JSONObject(client.exactCreateJson(id, title, body)!!)
        assertEquals(id, json.getJSONObject("parent").getString("database_id"))
        val properties = json.getJSONObject("properties")
        assertEquals(title, properties.getJSONObject("Name").getJSONArray("title").getJSONObject(0).getJSONObject("text").getString("content"))
        val content = properties.getJSONObject("Content").getJSONArray("rich_text")
        val parts = (0 until content.length()).map { content.getJSONObject(it).getJSONObject("text").getString("content") }
        assertEquals(body, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 2000 && !Character.isHighSurrogate(it.last()) })
        assertEquals("Quick Note", properties.getJSONObject("Category").getJSONObject("select").getString("name"))
        assertEquals("မလုပ်ရသေး", properties.getJSONObject("Status").getJSONObject("status").getString("name"))
    }

    @Test fun oversizedAndMalformedContent_isRejectedInsteadOfTruncated() {
        val client = NotionClient(ApplicationProvider.getApplicationContext<Context>())
        assertNull(client.exactCreateJson("db", "Title", "x".repeat(200001)))
        assertNull(client.exactCreateJson("db", "x".repeat(2001), "body"))
        assertNull(client.exactCreateJson("db", "Title", "bad\uD800unicode"))
    }
}
