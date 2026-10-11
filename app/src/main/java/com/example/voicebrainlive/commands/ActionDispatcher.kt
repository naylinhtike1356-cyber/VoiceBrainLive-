package com.example.voicebrainlive.commands

import android.util.Log
import org.json.JSONObject

/**
 * Voice Brain Core Upgrade: JSON-based Command Structure.
 * Parses Gemini function call args into (commandType, target, value) for PhoneCommandExecutor.
 * - Intent/Service မရောပါ - Gemini က JSON format နဲ့သာ အမိန့်ထုတ်
 * - Context-Aware: play_spotify အတွက် genre / artist / mood ကို သီးသန့် parameter အနေနဲ့ ထုတ်ယူ
 * - Backward compatible: ပုံမှန် command_type, target, value သာ ရှိရင် တန်းပဲ သုံးမယ်
 */
object ActionDispatcher {

    private const val TAG = "ActionDispatcher"

    data class ResolvedCommand(
        val commandType: String,
        val target: String?,
        val value: String?
    )

    /**
     * Parse function call args into clean (commandType, target, value).
     * For play_spotify: genre/artist/mood/mode ကို target + value ထဲ ပေါင်းစပ်သည်။
     */
    fun resolve(args: JSONObject): ResolvedCommand {
        val cmd = args.optString("command_type", "").trim().takeIf { it.isNotBlank() }
            ?: args.optString("name", "").trim().takeIf { it.isNotBlank() }
            ?: args.optString("command", "").trim().takeIf { it.isNotBlank() }
            ?: return ResolvedCommand("", null, null)

        // Standard params
        var target = args.optString("target", "").takeIf { it.isNotBlank() }
            ?: args.optString("recipient", "").takeIf { it.isNotBlank() }
            ?: args.optString("title", "").takeIf { it.isNotBlank() }
            ?: args.optString("query", "").takeIf { it.isNotBlank() }
            ?: args.optString("name", "").takeIf { it.isNotBlank() && cmd != it }
        var value = args.optString("value", "").takeIf { it.isNotBlank() }
            ?: args.optString("message", "").takeIf { it.isNotBlank() }
            ?: args.optString("text", "").takeIf { it.isNotBlank() }
            ?: args.optString("content", "").takeIf { it.isNotBlank() }
            ?: args.optString("body", "").takeIf { it.isNotBlank() }
            ?: args.optString("reply", "").takeIf { it.isNotBlank() }

        // Context-Aware: play_spotify - Autonomous Music Intelligence (Deduction & Creative Mapping)
        if (cmd in listOf("play_spotify", "spotify_play", "play_music")) {
            val artist = args.optString("artist", "").trim().takeIf { it.isNotBlank() }
            val genre = args.optString("genre", "").trim().takeIf { it.isNotBlank() }
            val mood = args.optString("mood", "").trim().takeIf { it.isNotBlank() }

            // 1. Build rationale (display target)
            if (target == null) {
                val parts = mutableListOf<String>()
                if (mood != null) parts.add(mood)
                if (genre != null) parts.add(genre)
                if (artist != null) parts.add(artist)
                if (parts.isNotEmpty()) {
                    target = parts.joinToString(" ")
                }
            }

            // 2. Build search query (value)
            if (value == null) {
                val queryParts = mutableListOf<String>()
                if (artist != null) {
                    queryParts.add(artist)
                }
                if (genre != null) {
                    queryParts.add(genreToSpotifyQuery(genre) ?: genre)
                } else if (mood != null) {
                    // Only add mood query if genre is not specified to keep search simple
                    queryParts.add(moodToSpotifyQuery(mood) ?: mood)
                }
                if (queryParts.isNotEmpty()) {
                    value = queryParts.joinToString(" ")
                }
            }
            Log.d(TAG, "Resolved play_spotify: target=$target, value=$value")
        }

        val normalized = listOfNotNull(target, value).joinToString(" ").lowercase()
        if (cmd in CALL_COMMANDS && looksLikeMusicRequest(normalized) && !looksLikeExplicitCallRequest(normalized)) {
            Log.w(TAG, "Rerouting likely music request away from call command: $normalized")
            return ResolvedCommand("play_spotify", target, value)
        }

        // Recipient and content remain separate. Missing fields require clarification,
        // never an ambient conversation or inferred payload.

        return ResolvedCommand(cmd, target, value)
    }

    fun isExplicitReplyRequest(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val text = raw.trim()

        val burmeseDismissals = listOf(
            "မပို့နဲ့", "မပြောနဲ့", "မပြောပါနဲ့", "ရပြီ", "တော်ပြီ", "တော်ပါပြီ",
            "ပိတ်လိုက်", "ပိတ်တော့", "မလိုဘူး", "မလိုတော့ဘူး", "မပြောတော့ဘူး"
        )
        if (burmeseDismissals.any { text.contains(it) }) return false

        val englishDismissalPhrases = listOf(
            "don't reply", "dont reply", "don't send", "dont send"
        )
        if (englishDismissalPhrases.any { text.contains(it, ignoreCase = true) }) return false

        val lowerWords = text.lowercase().split("\\s+".toRegex()).map { it.trim(',', '.', '!', '?', '"', '\'') }
        val englishSingleDismissals = setOf("no", "stop", "cancel")
        if (lowerWords.any { it in englishSingleDismissals }) return false

        val passiveEchoes = listOf(
            "စာပို့ထား", "နိုတီ", "notification"
        )
        if (passiveEchoes.any { text.contains(it, ignoreCase = true) }) return false

        val replyMarkers = listOf(
            "ပြန်ပြော", "ပြန်ပို့", "ပြန်စာ", "စာပြန်", "reply", "send",
            "ပြောလိုက်", "ပို့လိုက်", "ပြောပေး", "ပို့ပေး", "စာပို့"
        )
        return replyMarkers.any { text.contains(it, ignoreCase = true) }
    }

    fun cleanReplyMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var msg = raw.trim().trim('"', '\'', '“', '”').trim()

        val suffixes = listOf(
            " လို့ ပြန်ပြောလိုက်ပါ", "လို့ ပြန်ပြောလိုက်ပါ", " လို့ပြန်ပြောလိုက်ပါ", "လို့ပြန်ပြောလိုက်ပါ",
            " လို့ ပြန်ပြောလိုက်", "လို့ ပြန်ပြောလိုက်", " လို့ပြန်ပြောလိုက်", "လို့ပြန်ပြောလိုက်",
            " လို့ ပြန်ပြောပေးပါ", "လို့ ပြန်ပြောပေးပါ", " လို့ပြန်ပြောပေးပါ", "လို့ပြန်ပြောပေးပါ",
            " လို့ ပြန်ပြောပေး", "လို့ ပြန်ပြောပေး", " လို့ပြန်ပြောပေး", "လို့ပြန်ပြောပေး",
            " လို့ ပြန်ပို့ပေးပါ", "လို့ ပြန်ပို့ပေးပါ", " လို့ပြန်ပို့ပေးပါ", "လို့ပြန်ပို့ပေးပါ",
            " လို့ ပြန်ပို့ပေး", "လို့ ပြန်ပို့ပေး", " လို့ပြန်ပို့ပေး", "လို့ပြန်ပို့ပေး",
            " လို့ ပြန်ပို့လိုက်ပါ", "လို့ ပြန်ပို့လိုက်ပါ", " လို့ပြန်ပို့လိုက်ပါ", "လို့ပြန်ပို့လိုက်ပါ",
            " လို့ ပြန်ပို့လိုက်", "လို့ ပြန်ပို့လိုက်", " လို့ပြန်ပို့လိုက်", "လို့ပြန်ပို့လိုက်",
            " လို့ ပြောလိုက်ပါ", "လို့ ပြောလိုက်ပါ", " လို့ပြောလိုက်ပါ", "လို့ပြောလိုက်ပါ",
            " လို့ ပြောလိုက်", "လို့ ပြောလိုက်", " လို့ပြောလိုက်", "လို့ပြောလိုက်",
            " လို့ ပို့ပေးပါ", "လို့ ပို့ပေးပါ", " လို့ပို့ပေးပါ", "လို့ပို့ပေးပါ",
            " လို့ ပို့ပေး", "လို့ ပို့ပေး", " လို့ပို့ပေး", "လို့ပို့ပေး",
            " လို့ ပို့လိုက်ပါ", "လို့ ပို့လိုက်ပါ", " လို့ပို့လိုက်ပါ", "လို့ပို့လိုက်ပါ",
            " လို့ ပို့လိုက်", "လို့ ပို့လိုက်", " လို့ပို့လိုက်", "လို့ပို့လိုက်",
            " လို့ ပြန်ပြော", "လို့ ပြန်ပြော", " လို့ပြန်ပြော", "လို့ပြန်ပြော",
            " လို့ ပြန်ပို့", "လို့ ပြန်ပို့", " လို့ပြန်ပို့", "လို့ပြန်ပို့",
            " လို့ ပြောပေးပါ", "လို့ ပြောပေးပါ", " လို့ပြောပေးပါ", "လို့ပြောပေးပါ",
            " လို့ ပြောပေး", "လို့ ပြောပေး", " လို့ပြောပေး", "လို့ပြောပေး",
            " လို့ ပြောပါ", "လို့ ပြောပါ", " လို့ပြောပါ", "လို့ပြောပါ",
            " လို့ ပြော", "လို့ ပြော", " လို့ပြော", "လို့ပြော",
            " လို့ ပို့", "လို့ ပို့", " လို့ပို့", "လို့ပို့",
            " ပြန်ပြောလိုက်ပါ", "ပြန်ပြောလိုက်ပါ", " ပြန်ပြောလိုက်", "ပြန်ပြောလိုက်",
            " ပြန်ပြောပေးပါ", "ပြန်ပြောပေးပါ", " ပြန်ပြောပေး", "ပြန်ပြောပေး",
            " ပြန်ပို့ပေးပါ", "ပြန်ပို့ပေးပါ", " ပြန်ပို့ပေး", "ပြန်ပို့ပေး",
            " ပြန်ပို့လိုက်ပါ", "ပြန်ပို့လိုက်ပါ", " ပြန်ပို့လိုက်", "ပြန်ပို့လိုက်",
            " ပြောလိုက်ပါ", "ပြောလိုက်ပါ", " ပြောလိုက်", "ပြောလိုက်",
            " ပြောပေးပါ", "ပြောပေးပါ", " ပြောပေး", "ပြောပေး",
            " ပို့ပေးပါ", "ပို့ပေးပါ", " ပို့ပေး", "ပို့ပေး",
            " ပို့လိုက်ပါ", "ပို့လိုက်ပါ", " ပို့လိုက်", "ပို့လိုက်"
        ).sortedByDescending { it.length }

        for (suffix in suffixes) {
            if (msg.endsWith(suffix, ignoreCase = true)) {
                msg = msg.substring(0, msg.length - suffix.length).trim()
                break
            }
        }

        if (msg.endsWith("လို့")) {
            msg = msg.removeSuffix("လို့").trim()
        }

        val aiIntroPrefixes = setOf(
            "မင်္ဂလာပါခင်ဗျာ ", "မင်္ဂလာပါရှင် ", "မင်္ဂလာပါ ",
            "ဟုတ်ကဲ့ပါခင်ဗျာ ", "ဟုတ်ကဲ့ခင်ဗျာ ", "ဟုတ်ကဲ့ပါ ", "ဟုတ်ကဲ့ ",
            "သခင်က အခုလိုပြောခိုင်းလိုက်ပါတယ် ", "သခင်က ပြောခိုင်းလိုက်ပါတယ် ", "သခင်က ပြောလိုက်ပါတယ် ",
            "သူက ပြောလိုက်ပါတယ် ", "အသုံးပြုသူက ပြောလိုက်ပါတယ် "
        )

        val prefixes = listOf(
            "မက်ဆင်ဂျာကနေ ပြန်ပြော ", "မက်ဆင်ဂျာကနေ ပြန်ပို့ ", "မက်ဆင်ဂျာကနေ ပြော ", "မက်ဆင်ဂျာကနေ ပို့ ",
            "ပြန်ပြောလိုက်ပါ ", "ပြန်ပြောလိုက် ", "ပြန်ပြောပေးပါ ", "ပြန်ပြောပေး ",
            "ပြန်ပို့လိုက်ပါ ", "ပြန်ပို့လိုက် ", "ပြန်ပို့ပေးပါ ", "ပြန်ပို့ပေး ",
            "စာပြန်ပို့ပေးပါ ", "စာပြန်ပြောပေးပါ ", "စာပြန်ပို့ပေး ", "စာပြန်ပြောပေး ",
            "စာပြန်ပို့လိုက်ပါ ", "စာပြန်ပြောလိုက်ပါ ", "စာပြန်ပို့ ", "စာပြန်ပြော ",
            "သူ့ကို ပြန်ပြောလိုက်ပါ ", "သူ့ကို ပြန်ပြောပေးပါ ", "သူ့ကို ပြောလိုက်ပါ ", "သူ့ကို ပြောပေးပါ ",
            "သူ့ကို ပြန်ပြောလိုက် ", "သူ့ကို ပြန်ပြောပေး ", "သူ့ကို ပြောလိုက် ", "သူ့ကို ပြောပေး ",
            "သူ့ကို ပြော ", "သူ့ကို ပြန်ပြော ",
            "သူ့ဆီ ပြန်ပို့လိုက်ပါ ", "သူ့ဆီ ပြန်ပြောလိုက်ပါ ", "သူ့ဆီ ပို့လိုက်ပါ ", "သူ့ဆီ ပြောလိုက်ပါ ",
            "သူ့ဆီ ပြန်ပို့ပေးပါ ", "သူ့ဆီ ပို့ပေးပါ ", "သူ့ဆီ ပြောပေးပါ ",
            "မင်္ဂလာပါခင်ဗျာ ", "မင်္ဂလာပါရှင် ", "မင်္ဂလာပါ ",
            "ဟုတ်ကဲ့ပါခင်ဗျာ ", "ဟုတ်ကဲ့ခင်ဗျာ ", "ဟုတ်ကဲ့ပါ ", "ဟုတ်ကဲ့ ",
            "သခင်က အခုလိုပြောခိုင်းလိုက်ပါတယ် ", "သခင်က ပြောခိုင်းလိုက်ပါတယ် ", "သခင်က ပြောလိုက်ပါတယ် ",
            "သူက ပြောလိုက်ပါတယ် ", "အသုံးပြုသူက ပြောလိုက်ပါတယ် ",
            "ပြန်ပြော ", "ပြန်ပို့ ", "ပြောလိုက် ", "ပို့လိုက် ", "ပြောပေး ", "ပို့ပေး ",
            "reply ", "send_messenger ", "tell him ", "tell her ", "tell them ", "say to him ", "say to her ", "say ",
            "မက်ဆင်ဂျာကနေ ပြန်ပြော", "မက်ဆင်ဂျာကနေ ပြန်ပို့", "မက်ဆင်ဂျာကနေ ပြော", "မက်ဆင်ဂျာကနေ ပို့",
            "ပြန်ပြောလိုက်ပါ", "ပြန်ပြောလိုက်", "ပြန်ပြောပေးပါ", "ပြန်ပြောပေး",
            "ပြန်ပို့လိုက်ပါ", "ပြန်ပို့လိုက်", "ပြန်ပို့ပေးပါ", "ပြန်ပို့ပေး",
            "စာပြန်ပို့ပေးပါ", "စာပြန်ပြောပေးပါ", "စာပြန်ပို့ပေး", "စာပြန်ပြောပေး",
            "စာပြန်ပို့လိုက်ပါ", "စာပြန်ပြောလိုက်ပါ", "စာပြန်ပို့", "စာပြန်ပြော",
            "သူ့ကို ပြန်ပြောလိုက်ပါ", "သူ့ကို ပြန်ပြောပေးပါ", "သူ့ကို ပြောလိုက်ပါ", "သူ့ကို ပြောပေးပါ",
            "သူ့ကို ပြန်ပြော", "သူ့ကို ပြော",
            "ပြန်ပြော", "ပြန်ပို့"
        ).sortedByDescending { it.length }

        for (prefix in prefixes) {
            if (msg.startsWith(prefix, ignoreCase = true)) {
                val remainder = msg.substring(prefix.length).trim()
                if (prefix in aiIntroPrefixes && remainder.isEmpty()) {
                    continue
                }
                msg = remainder
                break
            }
        }

        msg = msg.trim('"', '\'', '“', '”').trim()
        return msg.takeIf { it.isNotBlank() }
    }

    private val CALL_COMMANDS = setOf("make_call", "call", "call_contact")

    private fun looksLikeMusicRequest(text: String): Boolean {
        if (text.isBlank()) return false
        return MUSIC_HINTS.any { text.contains(it) }
    }

    private fun looksLikeExplicitCallRequest(text: String): Boolean {
        if (text.isBlank()) return false
        return CALL_HINTS.any { text.contains(it) }
    }

    private val MUSIC_HINTS = listOf(
        "music", "song", "spotify", "youtube", "playlist", "track", "album",
        "play ", "သီချင်း", "နားထောင်"
    )

    private val CALL_HINTS = listOf(
        "call", "phone", "dial", "ဖုန်း", "ခေါ်", "ဆက်"
    )

    private fun moodToSpotifyQuery(mood: String?): String? = when (mood?.lowercase()) {
        "မြူး", "မြူးမြူး", "မြူးမြူးလေး", "ပျင်းလို့", "ပျင်း", "happy", "upbeat", "energetic", "ပျော်", "လန်းဆန်း" -> "feel good upbeat pop"
        "အိပ်", "အိပ်ချိန်", "ငြိမ်", "အိပ်ပျော်", "အိပ်ယာဝင်", "အိပ်ရာဝင်", "sleep", "bedtime" -> "deep sleep relaxing piano"
        "စာကြည့်", "အာရုံစူးစိုက်", "စာလုပ်", "study", "focus", "lofi" -> "lofi study focus beats"
        "ဂျင်မ်", "လေ့ကျင့်ခန်း", "gym", "workout", "fitness" -> "gym workout playlist upbeat"
        "မြန်မာ", "မြန်မာသီချင်း", "ဗမာ" -> "myanmar burmese pop"
        "ကိုရီးယား", "ကေပေါ့", "k-pop", "kpop" -> "k-pop korean hits"
        "ဝမ်းနည်း", "စိတ်ညစ်", "လွမ်း", "စိတ်ဓာတ်ကျ", "ငိုချင်", "sad", "depressed", "lonely", "အထီးကျန်" -> "sad acoustic songs"
        "စိတ်ဖိစီး", "ဒေါသ", "ပူပန်", "စိတ်ရှုပ်", "stress", "angry", "anxious" -> "stress relief acoustic piano"
        "အေးဆေး", "အေးချမ်း", "စိတ်အေး", "အနားယူ", "relax", "calm", "chill" -> "calm acoustic chill"
        "မနက်", "မနက်ခင်း", "morning" -> "morning acoustic breeze"
        "နေ့လယ်", "နေ့လည်", "afternoon" -> "chill afternoon lounge"
        "ညနေ", "ညနေခင်း", "evening" -> "sunset lounge chill"
        "ညနက်", "ညဉ့်", "ညနက်ပိုင်း", "midnight", "late night" -> "calming midnight lofi"
        else -> null
    }

    private fun genreToSpotifyQuery(genre: String?): String? = when (genre?.lowercase()) {
        "လိုဖိုင်", "lofi" -> "lofi hip hop chill"
        "ရော့", "rock" -> "rock hits"
        "ပေါ့", "pop" -> "pop hits"
        "hiphop", "hip-hop", "ဟစ်ဟော့" -> "hip hop hits"
        "jazz", "ဂျက်ဇ်" -> "jazz classics"
        "acoustic", "အကူးစတစ်" -> "acoustic hits"
        else -> null
    }
}
