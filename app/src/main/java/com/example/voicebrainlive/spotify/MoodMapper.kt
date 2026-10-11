package com.example.voicebrainlive.spotify

import android.net.Uri

/**
 * Phase 2: Logic for mapping moods and genres to Spotify playlist URIs.
 * Extracted from SpotifyManager for testability.
 */
object MoodMapper {

    private val MOOD_GENRE_MAP = mapOf(
        // Sleep / Relax (Peaceful Piano)
        "အိပ်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အိပ်ပျော်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အိပ်ချိန်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အိပ်ယာဝင်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အိပ်ရာဝင်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အိပ်ချင်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အေးချမ်း" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အေးဆေး" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "relax" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "calm" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "chill" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "relaxing" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အမောပြေ" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "ပေါ့ပါး" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "စိတ်အေး" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အနားယူ" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "အမောပြေစေမယ့်" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",
        "စိတ်အပန်းဖြေ" to "spotify:playlist:37i9dQZF1DX4sWSpwq3LiO",

        // Happy / Upbeat / Energetic (Happy Hits)
        "ပျော်" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "စိတ်လှုပ်ရှား" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "လန်းဆန်း" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "လန်း" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "အားရှိ" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "စိတ်တက်" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "happy" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "cheerful" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "upbeat" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "energetic" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",
        "လန်းဆန်းစေမယ့်" to "spotify:playlist:37i9dQZF1DX3spFlNs2kay",

        // Sad / Melancholy / Cry (Sad Songs)
        "ဝမ်းနည်း" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "စိတ်ညစ်" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "လွမ်း" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "လွမ်းစရာ" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "စိတ်ဓာတ်ကျ" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "ငိုချင်" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "feelမလာ" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "feel မလာ" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "feelမယ်" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "sad" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "depressed" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "lonely" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",
        "အထီးကျန်" to "spotify:playlist:37i9dQZF1DX3YSRcHeeTev",

        // Stressed / Angry / Anxious (Stress Relief)
        "စိတ်ဖိစီး" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "ဒေါသ" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "ပူပန်" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "စိတ်ရှုပ်" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "stress" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "angry" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "anxious" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "စိတ်ညစ်လို့" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "စိတ်ရှုပ်လို့" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",
        "ဒေါသထွက်" to "spotify:playlist:37i9dQZF1DWZqJjqb7cr6P",

        // Dance / DJ / Party (Dance Hits)
        "မြူး" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "မြူးမြူး" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "မြူးမြူးလေး" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "ဒီဂျေး" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "dj" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "ပါတီ" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "party" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",
        "ကော်ပီ" to "spotify:playlist:37i9dQZF1DX0BcQWzuB7ZO",

        // K-Pop (K-Pop Dance Party)
        "ကိုရီးယား" to "spotify:playlist:37i9dQZF1DX4RDXswvP6Mj",
        "kpop" to "spotify:playlist:37i9dQZF1DX4RDXswvP6Mj",
        "k-pop" to "spotify:playlist:37i9dQZF1DX4RDXswvP6Mj",

        // Focus / Study (Lo-fi Beats)
        "စာကြည့်" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "စာလုပ်" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "lofi" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "study" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "focus" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",

        // Gym / Workout (Gym Hits)
        "ဂျင်မ်" to "spotify:playlist:37i9dQZF1DXdxcBWuJkbcy",
        "gym" to "spotify:playlist:37i9dQZF1DXdxcBWuJkbcy",
        "workout" to "spotify:playlist:37i9dQZF1DXdxcBWuJkbcy",
        "လေ့ကျင့်ခန်း" to "spotify:playlist:37i9dQZF1DXdxcBWuJkbcy",

        // Time of Day: Morning (Morning Acoustic)
        "မနက်" to "spotify:playlist:37i9dQZF1DX2MyUC727BOS",
        "မနက်ခင်း" to "spotify:playlist:37i9dQZF1DX2MyUC727BOS",
        "morning" to "spotify:playlist:37i9dQZF1DX2MyUC727BOS",
        "sunrise" to "spotify:playlist:37i9dQZF1DX2MyUC727BOS",

        // Time of Day: Afternoon (Afternoon Acoustic)
        "နေ့လယ်" to "spotify:playlist:37i9dQZF1DX31Vj5Bui48E",
        "နေ့လည်" to "spotify:playlist:37i9dQZF1DX31Vj5Bui48E",
        "afternoon" to "spotify:playlist:37i9dQZF1DX31Vj5Bui48E",
        "lunch" to "spotify:playlist:37i9dQZF1DX31Vj5Bui48E",

        // Time of Day: Evening (Sunset Chill)
        "ညနေ" to "spotify:playlist:37i9dQZF1DX3qCxuvpG86g",
        "ညနေခင်း" to "spotify:playlist:37i9dQZF1DX3qCxuvpG86g",
        "evening" to "spotify:playlist:37i9dQZF1DX3qCxuvpG86g",
        "sunset" to "spotify:playlist:37i9dQZF1DX3qCxuvpG86g",

        // Time of Day: Late Night / Midnight (Lo-Fi Beats)
        "ညနက်" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "ညဉ့်" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "ညဥ့်" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "ညနက်ပိုင်း" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "midnight" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "late night" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn",
        "ညဉ့်နက်" to "spotify:playlist:37i9dQZF1DWWQRwui0ExPn"
    )

    /**
     * Check if query matches a mood/genre key. Returns URI if match, else null.
     */
    fun getMoodPlaylistUri(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        
        val bestKey = MOOD_GENRE_MAP.keys
            .mapNotNull { key ->
                val index = q.indexOf(key.lowercase())
                if (index >= 0) Triple(key, index, key.length) else null
            }
            .sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            .firstOrNull()
            ?.first

        return bestKey?.let { MOOD_GENRE_MAP[it] }
    }

    /**
     * Fuzzy matching score: query vs display name.
     */
    fun fuzzyMatchScore(query: String, displayName: String): Int {
        val q = query.lowercase().trim()
        val n = displayName.lowercase().trim()
        if (q.isBlank() || n.isBlank()) return 0
        if (q == n) return 100
        if (n.contains(q)) return 90
        if (q.contains(n)) return 80
        
        val qWords = q.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        val nWords = n.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (qWords.isEmpty()) return if (n.startsWith(q) || n.contains(q)) 70 else 20
        
        val overlap = qWords.count { qw -> nWords.any { nw -> nw.contains(qw) || qw.contains(nw) } }
        return (overlap * 60 / qWords.size).coerceAtLeast(10)
    }
}
