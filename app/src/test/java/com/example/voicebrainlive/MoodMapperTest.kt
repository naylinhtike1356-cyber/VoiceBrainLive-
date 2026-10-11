package com.example.voicebrainlive

import com.example.voicebrainlive.spotify.MoodMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MoodMapper logic.
 */
class MoodMapperTest {

    @Test
    fun testMoodPlaylistMapping() {
        // Sleep / Relax
        assertEquals("spotify:playlist:37i9dQZF1DX4sWSpwq3LiO", MoodMapper.getMoodPlaylistUri("အိပ်ပျော်အောင် ဖွင့်ပေးပါဦး"))
        assertEquals("spotify:playlist:37i9dQZF1DX4sWSpwq3LiO", MoodMapper.getMoodPlaylistUri("အေးဆေး relax music"))
        
        // Party
        assertEquals("spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO", MoodMapper.getMoodPlaylistUri("မြူးတဲ့ DJ ပါတီ သီချင်းတွေ"))
        
        // K-Pop
        assertEquals("spotify:playlist:37i9dQZF1DX4RDXswvP6Mj", MoodMapper.getMoodPlaylistUri("k-pop dance party tracks"))
        
        // Study
        assertEquals("spotify:playlist:37i9dQZF1DWWQRwui0ExPn", MoodMapper.getMoodPlaylistUri("စာကြည့်ရင်း နားထောင်ဖို့ lofi beats"))
        
        // Gym
        assertEquals("spotify:playlist:37i9dQZF1DXdxcBWuJkbcy", MoodMapper.getMoodPlaylistUri("gym workout songs"))

        // New Moods (Sad, Happy, Stressed)
        assertEquals("spotify:playlist:37i9dQZF1DX3YSRcHeeTev", MoodMapper.getMoodPlaylistUri("ဝမ်းနည်းစရာ သီချင်းလေး ဖွင့်ဦး"))
        assertEquals("spotify:playlist:37i9dQZF1DX3spFlNs2kay", MoodMapper.getMoodPlaylistUri("လန်းဆန်းစေမယ့် သီချင်းမြူးမြူးလေး ဖွင့်ပါ"))
        assertEquals("spotify:playlist:37i9dQZF1DWZqJjqb7cr6P", MoodMapper.getMoodPlaylistUri("စိတ်ဖိစီးမှုတွေပြေပျောက်ဖို့ သီချင်းဖွင့်ပေး"))

        // Time of Day (Morning, Afternoon, Evening, Late Night)
        assertEquals("spotify:playlist:37i9dQZF1DX2MyUC727BOS", MoodMapper.getMoodPlaylistUri("မနက်ခင်းသီချင်းလေး ဖွင့်ပါဦး"))
        assertEquals("spotify:playlist:37i9dQZF1DX31Vj5Bui48E", MoodMapper.getMoodPlaylistUri("နေ့လည်ခင်း နားထောင်ဖို့ သီချင်းအေးအေးလေး"))
        assertEquals("spotify:playlist:37i9dQZF1DX3qCxuvpG86g", MoodMapper.getMoodPlaylistUri("ညနေခင်း sunset lounge chill"))
        assertEquals("spotify:playlist:37i9dQZF1DWWQRwui0ExPn", MoodMapper.getMoodPlaylistUri("ညနက်ပိုင်း midnight lo-fi"))
    }

    @Test
    fun testFuzzyMatchScores() {
        val query = "Taylor Swift Cruel Summer"
        
        // Exact match
        assertEquals(100, MoodMapper.fuzzyMatchScore(query, "Taylor Swift Cruel Summer"))
        
        // Contains match
        assertTrue(MoodMapper.fuzzyMatchScore(query, "Cruel Summer") >= 80)
        assertTrue(MoodMapper.fuzzyMatchScore("Summer", query) >= 70)
        
        // Overlap match
        assertTrue(MoodMapper.fuzzyMatchScore("Taylor Summer Swift", query) >= 60)
        
        // No match
        assertEquals(0, MoodMapper.fuzzyMatchScore("", "Some Song"))
    }

    @Test
    fun testEmptyQueryReturnsNull() {
        assertEquals(null, MoodMapper.getMoodPlaylistUri(""))
        assertEquals(null, MoodMapper.getMoodPlaylistUri("   "))
    }
}
