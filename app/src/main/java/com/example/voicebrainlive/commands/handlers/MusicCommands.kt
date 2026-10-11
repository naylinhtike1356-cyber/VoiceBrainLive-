package com.example.voicebrainlive.commands.handlers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.MediaSessionHelper
import com.example.voicebrainlive.SpotifyManager
import com.example.voicebrainlive.UserPreferences
import com.example.voicebrainlive.ActionRecordManager
import com.example.voicebrainlive.memory.BehaviorMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Phase 2: Music and Media commands modularized from PhoneCommandExecutor.
 * Implements Autonomous Music Intelligence (Mood/Artist/Song detection and playback).
 */
class MusicCommands(private val context: Context) : CommandHandler {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Context-Mapped Query Rewriting: Myanmar/mood keywords → Spotify-friendly English.
     */
    private val QUERY_REWRITE_MAP = mapOf(
        // Mood / Feeling
        "ပျင်း" to "upbeat dance hits",
        "ပျင်းလို့" to "upbeat dance hits",
        "ပျင်းလို့မြူး" to "upbeat dance hits",
        "မြူး" to "dance party hits",
        "မြူးမြူး" to "dance party hits",
        "မြူးမြူးလေး" to "dance party hits",
        "အိပ်" to "sleep relaxing peaceful",
        "အိပ်ချင်" to "sleep relaxing",
        "အိပ်ချိန်" to "sleep relaxing piano",
        "အိပ်ပျော်" to "sleep relaxing",
        "အေးချမ်း" to "calm relaxing piano",
        "အေးဆေး" to "calm chill",
        "စိတ်လက်တွန်းရှင်း" to "upbeat happy",
        "ရင့်ကျတ်" to "sad ballads acoustic",
        
        // Sad / Melancholy
        "ဝမ်းနည်း" to "sad acoustic songs",
        "စိတ်ညစ်" to "sad emotional hits",
        "လွမ်း" to "sad ballads acoustic",
        "လွမ်းစရာ" to "sad ballads acoustic",
        "စိတ်ဓာတ်ကျ" to "sad feel good healing",
        "ငိုချင်" to "sad cry songs",
        "feelမလာ" to "upbeat feel good hits",
        "feel မလာ" to "upbeat feel good hits",
        "feelမယ်" to "sad acoustic songs",
        "sad" to "sad acoustic songs",
        "depressed" to "calming slow acoustic",
        "lonely" to "cozy slow acoustic pop",
        "အထီးကျန်" to "cozy acoustic indie",

        // Happy / Upbeat
        "ပျော်" to "feel good upbeat pop",
        "စိတ်လှုပ်ရှား" to "upbeat high energy pop",
        "လန်းဆန်း" to "fresh morning upbeat pop",
        "လန်း" to "fresh upbeat pop",
        "အားရှိ" to "motivation workout upbeat",
        "စိတ်တက်" to "motivation upbeat pop",
        "happy" to "feel good upbeat pop",
        "cheerful" to "happy cheerful pop",
        "upbeat" to "feel good upbeat pop",
        "energetic" to "high energy dance hits",
        "လန်းဆန်းစေမယ့်" to "fresh upbeat pop",

        // Stressed / Angry / Anxious
        "စိတ်ဖိစီး" to "stress relief acoustic piano",
        "ဒေါသ" to "calming piano instrumental",
        "ပူပန်" to "peaceful relaxing piano",
        "စိတ်ရှုပ်" to "calming lofi beats",
        "stress" to "stress relief piano instrumental",
        "angry" to "peaceful instrumental calm",
        "anxious" to "peaceful chill relaxing piano",
        "စိတ်ညစ်လို့" to "calming acoustic pop",
        "စိတ်ရှုပ်လို့" to "relaxing lofi beats",
        "ဒေါသထွက်" to "calming instrumental piano",

        // Genre / Category
        "မြန်မာ" to "myanmar burmese pop songs",
        "မြန်မာသီချင်း" to "myanmar burmese pop",
        "ဗမာ" to "myanmar burmese music",
        "ကိုရီးယား" to "k-pop korean hits",
        "ကိုရီးယားသီချင်း" to "k-pop korean hits",
        "kpop" to "k-pop",
        "K-pop" to "k-pop",
        "ဒီဂျေး" to "dance club hits",
        "DJ" to "dance club",
        "ဂျင်မ်" to "gym workout hits",
        "Gym" to "gym workout",
        "လေ့ကျင့်ခန်း" to "workout fitness",
        "စာကြည့်" to "study focus lofi",
        "စာလုပ်" to "study focus lofi",
        "လောဖိုင်" to "lofi beats",
        "lofi" to "lofi beats",
        "လို ဖိုင်" to "lofi beats",
        "ပါတီ" to "party dance hits",
        "ချစ်" to "romantic love songs",
        "အချစ်" to "romantic love songs",
        "ရိုမန်တစ်" to "romantic love songs",
        "ဂန္ထဝင်" to "classic old songs",
        "ဟောင်း" to "classic old songs",
        "ရွှေရတု" to "classic old songs",
        "အသစ်" to "latest new hits",

        // Time of Day
        "မနက်" to "morning acoustic breeze",
        "မနက်ခင်း" to "fresh morning acoustic pop",
        "morning" to "morning acoustic breeze",
        "sunrise" to "fresh morning acoustic",
        "နေ့လယ်" to "chill afternoon lounge",
        "နေ့လည်" to "chill afternoon lounge",
        "afternoon" to "chill afternoon lounge",
        "lunch" to "afternoon acoustic coffee",
        "ညနေ" to "sunset lounge chill",
        "ညနေခင်း" to "relaxing evening pop",
        "evening" to "sunset lounge chill",
        "sunset" to "sunset chill acoustic",
        "ညနက်" to "calming midnight lofi",
        "ညဉ့်" to "sleep lofi beats",
        "ညဥ့်" to "sleep lofi beats",
        "ညနက်ပိုင်း" to "calming midnight lofi",
        "midnight" to "calming midnight lofi",
        "late night" to "late night chill lofi",
        "ညဉ့်နက်" to "deep sleep ambient lofi"
    )

    private val QUERY_REWRITE_KEYS = QUERY_REWRITE_MAP.keys.sortedByDescending { it.length }

    override fun getSupportedCommands(): List<String> = listOf(
        "play_music", "media_play", "play_spotify", "spotify_play", "search_spotify",
        "pause_music", "pause", "media_pause", "media_stop",
        "resume_music", "resume", "play",
        "next_track", "next", "media_next", "previous_track", "previous", "media_previous",
        "open_youtube", "youtube", "play_youtube", "resume_last_music",
        "create_playlist", "add_to_playlist", "create_and_play_playlist"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val result = when (commandType.lowercase()) {
            "play_music", "media_play", "play_spotify", "spotify_play" -> playSpotifyOrResume(target, value)
            "search_spotify" -> searchSpotify(target ?: value ?: "")
            "pause_music", "pause", "media_pause", "media_stop" -> pauseMedia()
            "resume_music", "resume", "play" -> resumeMedia()
            "next_track", "next", "media_next" -> nextTrack()
            "previous_track", "previous", "media_previous" -> previousTrack()
            "open_youtube", "youtube", "play_youtube" -> openYouTube(target ?: value)
            "resume_last_music" -> resumeLastMusic()
            "create_playlist" -> createPlaylist(target ?: value ?: "")
            "add_to_playlist" -> addToPlaylist(target ?: "", value ?: "")
            "create_and_play_playlist" -> createAndPlayPlaylist(target ?: "", value ?: "")
            else -> null
        }
        
        if (result != null) {
            ActionRecordManager.recordAction(context, "MusicCommand", "Command: $commandType, Result: $result")
        }
        result
    }

    private fun isTimeMatchingRequest(query: String): Boolean {
        val q = query.lowercase()
        return q.contains("အချိန်နဲ့") || q.contains("အချိန်အလိုက်") || 
               q.contains("လိုက်ဖက်တာ") || q.contains("ကိုက်ညီတာ") ||
               q.contains("လက်ရှိအချိန်") || q.contains("fit the time") || 
               q.contains("suitable for the time") || q.contains("current time")
    }

    private fun getQueryForCurrentTime(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 5..11 -> "fresh morning acoustic"
            hour in 12..16 -> "chill afternoon lounge"
            hour in 17..20 -> "sunset lounge chill"
            else -> "calming midnight lofi"
        }
    }
    
    private fun getMyanmarExplanationForTime(hour: Int): String {
        return when {
            hour in 5..11 -> "မနက်ခင်း လန်းဆန်းစေမယ့် တေးသံသာလေးများ"
            hour in 12..16 -> "နေ့လယ်ခင်း စိတ်ပေါ့ပါးစေမယ့် သီချင်းအေးအေးလေးများ"
            hour in 17..20 -> "ညနေခင်း အမောပြေစေမယ့် Sunset Chill သီချင်းလေးများ"
            else -> "ညဉ့်နက်ပိုင်း နားထောင်လို့ကောင်းမယ့် Midnight Lo-Fi တေးသံများ"
        }
    }

    private fun playSpotifyOrResume(target: String?, value: String?): String {
        val rationale = target?.trim()?.takeIf { it.isNotBlank() }
        val searchPhrase = value?.trim()?.takeIf { it.isNotBlank() }

        // Read user's preference
        val defaultApp = UserPreferences.get(context, "default_music_app")?.lowercase() ?: "spotify"

        return when {
            // Case 1: No input or reference to "that song" -> resume/learning
            (rationale == null && searchPhrase == null) || (rationale?.let { isThatSongReference(it) } == true) -> {
                AppLog.d("MusicCommands", "Resuming last media")
                resumeMedia()
            }
            // Case 2: Intent-driven playback (Autonomous Intelligence)
            else -> {
                var finalQuery = searchPhrase ?: rationale ?: "top hits"
                var displayRationale = if (rationale != null && rationale != searchPhrase) rationale else "'$finalQuery'"
                
                if (isTimeMatchingRequest(finalQuery)) {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    finalQuery = getQueryForCurrentTime()
                    displayRationale = getMyanmarExplanationForTime(hour)
                    AppLog.d("MusicCommands", "Time-matched playback: hour=$hour, query=$finalQuery")
                }
                
                AppLog.d("MusicCommands", "Intent-driven play: rationale=$displayRationale, query=$finalQuery using $defaultApp")
                playOnPreferredApp(displayRationale, defaultApp, aiDecidedQuery = finalQuery)
            }
        }
    }

    private fun playOnPreferredApp(songName: String, appName: String, aiDecidedQuery: String? = null): String {
        return when (appName) {
            "youtube_music", "yt_music", "youtube music" -> playOnYouTubeMusic(songName, aiDecidedQuery)
            "local", "device", "player" -> playOnLocalPlayer(songName, aiDecidedQuery)
            else -> playOnSpotify(songName, aiDecidedQuery)
        }
    }

    private fun playOnYouTubeMusic(songName: String, aiDecidedQuery: String? = null, isFallback: Boolean = false): String {
        AppLog.d("MusicCommands", "YouTube Music request: $songName, AI query=$aiDecidedQuery, isFallback=$isFallback")
        if (songName.isBlank()) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return "YouTube Music သီချင်း ပြန်ဖွင့်ပေးလိုက်ပါပြီ"
        }
        val finalEnglishQuery = rewriteQueryForSpotify(aiDecidedQuery ?: songName)
        pauseMedia() // Pause previous music first
        try {
            val (searchType, _) = classifyRequest(aiDecidedQuery ?: songName)
            val focus = when (searchType) {
                SearchType.PLAYLIST -> "vnd.android.cursor.item/playlist"
                else -> "vnd.android.cursor.item/audio"
            }
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, focus)
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, finalEnglishQuery)
                putExtra("query", finalEnglishQuery)
                setPackage("com.google.android.apps.youtube.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            
            BehaviorMemory.recordMusicPlayed(context, finalEnglishQuery)
            return "$songName ကို YouTube Music တွင် ဖွင့်ပေးလိုက်ပါပြီ။"
        } catch (ex: Exception) {
            Log.e("MusicCommands", "YouTube Music play failed, trying Spotify fallback", ex)
            if (isFallback) {
                return "'$songName' ကို ဖွင့်ပေးရန် ကြိုးစားနေသော်လည်း Spotify နှင့် YouTube Music နှစ်ခုလုံး အလုပ်မလုပ်ပါ။"
            }
            return playOnSpotify(songName, aiDecidedQuery, isFallback = true) // Fallback to Spotify
        }
    }

    private fun playOnLocalPlayer(songName: String, aiDecidedQuery: String? = null): String {
        AppLog.d("MusicCommands", "Local Player request: $songName, AI query=$aiDecidedQuery")
        if (songName.isBlank()) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return "သီချင်း ပြန်ဖွင့်ပေးလိုက်ပါပြီ"
        }
        val finalEnglishQuery = rewriteQueryForSpotify(aiDecidedQuery ?: songName)
        pauseMedia() // Pause previous music first
        try {
            val (searchType, _) = classifyRequest(aiDecidedQuery ?: songName)
            val focus = when (searchType) {
                SearchType.PLAYLIST -> "vnd.android.cursor.item/playlist"
                else -> "vnd.android.cursor.item/audio"
            }
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, focus)
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, finalEnglishQuery)
                putExtra("query", finalEnglishQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            
            BehaviorMemory.recordMusicPlayed(context, finalEnglishQuery)
            return "$songName ကို Device Player တွင် ဖွင့်ပေးလိုက်ပါပြီ။"
        } catch (ex: Exception) {
            Log.e("MusicCommands", "Local player play failed", ex)
            return "Device Player ဖွင့်၍မရပါ။"
        }
    }

    private fun resumeLastMusic(): String {
        return resumeMedia()
    }

    private fun createPlaylist(name: String): String {
        if (name.isBlank()) return "ဖန်တီးမည့် Playlist နာမည် ပြောပေးပါ။"
        return SpotifyManager.createPlaylist(context, name)
    }

    private fun createAndPlayPlaylist(playlistName: String, songsParam: String): String {
        val name = playlistName.trim()
        val songsList = songsParam.split(Regex("[,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (name.isBlank()) return "ဖန်တီးမည့် Playlist နာမည် ပြောပေးပါ။"
        if (songsList.isEmpty()) return "Playlist ထဲထည့်သွင်းမည့် သီချင်းများ ပြောပေးပါ။"
        return SpotifyManager.createAndPlayPlaylist(context, name, songsList)
    }

    private fun addToPlaylist(songAndPlaylist: String, playlistParam: String): String {
        val songName = songAndPlaylist.trim()
        val playlistName = playlistParam.trim()
        if (songName.isBlank() || playlistName.isBlank()) {
            return "သီချင်းနာမည်နှင့် Playlist နာမည် နှစ်ခုလုံး ပြောပေးပါ။"
        }
        return SpotifyManager.addToPlaylist(context, songName, playlistName)
    }

    private fun playOnSpotify(songName: String, aiDecidedQuery: String? = null, isFallback: Boolean = false): String {
        AppLog.d("MusicCommands", "Spotify request: $songName, AI query=$aiDecidedQuery, isFallback=$isFallback")
        
        if (songName.isBlank()) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return "သီချင်း ပြန်ဖွင့်ပေးလိုက်ပါပြီ"
        }

        // Check Spotify availability
        val hasSpotify = com.spotify.android.appremote.api.SpotifyAppRemote.isSpotifyInstalled(context)

        // 1. Lo-fi special case
        if (songName.equals("Lo-fi", ignoreCase = true) || songName.equals("Lofi", ignoreCase = true)) {
            val loFiUri = "spotify:playlist:37i9dQZF1DWWQRwui0ExPn"
            if (hasSpotify && SpotifyManager.playUri(context, loFiUri)) {
                BehaviorMemory.recordMusicPlayed(context, "Lo-fi")
                return "Lo-fi သီချင်း ဖွင့်ပေးလိုက်ပါပြီ"
            } else {
                if (isFallback) {
                    return "Lo-fi သီချင်း ဖွင့်မရပါ။"
                }
                return playOnYouTubeMusic("Lo-fi", "lofi beats", isFallback = true)
            }
        }

        if (!hasSpotify) {
            if (isFallback) {
                return "'$songName' ကို ဖွင့်ပေးရန် ကြိုးစားနေသော်လည်း Spotify နှင့် YouTube Music နှစ်ခုလုံး အလုပ်မလုပ်ပါ။"
            }
            Log.d("MusicCommands", "Spotify not available, redirecting to YouTube Music")
            return playOnYouTubeMusic(songName, aiDecidedQuery, isFallback = true)
        }

        val displayName = songName.take(60)
        val finalEnglishQuery = rewriteQueryForSpotify(aiDecidedQuery ?: songName)

        // 2. Play via SpotifyManager (includes Expert Playback logic with forceStop)
        val (searchType, _) = classifyRequest(songName)
        var success = false
        if (searchType == SearchType.PLAYLIST) {
            if (SpotifyManager.searchAndPlayPlaylist(context, songName, apiQueryOverride = finalEnglishQuery)) {
                success = true
            }
        }
        if (!success) {
            success = SpotifyManager.searchAndPlayFirst(context, finalEnglishQuery)
        }

        if (success) {
            BehaviorMemory.recordMusicPlayed(context, finalEnglishQuery)
            return songName
        }

        // 3. Final Fallback: Force Search Intent
        pauseMedia() // Pause previous music first
        try {
            val focus = when (searchType) {
                SearchType.PLAYLIST -> "vnd.android.cursor.item/playlist"
                else -> "vnd.android.cursor.item/audio"
            }
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, focus)
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, finalEnglishQuery)
                putExtra("query", finalEnglishQuery)
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            
            BehaviorMemory.recordMusicPlayed(context, finalEnglishQuery)
            return songName
        } catch (ex: Exception) {
            Log.e("MusicCommands", "Fallback play failed, trying YouTube Music fallback", ex)
            if (isFallback) {
                return "'$songName' ကို ဖွင့်ပေးရန် ကြိုးစားနေသော်လည်း Spotify နှင့် YouTube Music နှစ်ခုလုံး အလုပ်မလုပ်ပါ။"
            }
            return try {
                pauseMedia() // Pause previous music first
                val focus = when (searchType) {
                    SearchType.PLAYLIST -> "vnd.android.cursor.item/playlist"
                    else -> "vnd.android.cursor.item/audio"
                }
                val ytIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, focus)
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, finalEnglishQuery)
                    putExtra("query", finalEnglishQuery)
                    setPackage("com.google.android.apps.youtube.music")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytIntent)
                
                BehaviorMemory.recordMusicPlayed(context, finalEnglishQuery)
                "Spotify ဖြင့် ဖွင့်မရသောကြောင့် YouTube Music တွင် '$songName' ကို ရှာဖွင့်ပေးလိုက်ပါပြီ။"
            } catch (ytEx: Exception) {
                Log.e("MusicCommands", "YouTube Music fallback failed", ytEx)
                "'$songName' ကို ဖွင့်ပေးရန် ကြိုးစားနေသော်လည်း Spotify နှင့် YouTube Music နှစ်ခုလုံး အလုပ်မလုပ်ပါ။"
            }
        }

        return "'$songName' ကို ဖွင့်ပေးဖို့ ကြိုးစားနေပါတယ်။"
    }

    private fun pauseMedia(): String {
        if (MediaSessionHelper.isNotificationAccessEnabled(context)) {
            MediaSessionHelper.tryPauseViaMediaController(context)
        } else {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        return "မီဒီယာ ခေတ္တရပ်ထားလိုက်ပါပြီ"
    }

    private fun resumeMedia(): String {
        if (MediaSessionHelper.isNotificationAccessEnabled(context)) {
            MediaSessionHelper.tryPlayViaMediaController(context)
        } else {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        return "သီချင်း ဆက်ဖွင့်လိုက်ပါပြီ"
    }

    private fun nextTrack(): String {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        return "Next song"
    }

    private fun previousTrack(): String {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return "Previous song"
    }

    private fun searchSpotify(query: String): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("spotify:search:${Uri.encode(query)}")
            setPackage("com.spotify.music")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Spotify မှာ '$query' ကို ရှာဖွေနေပါတယ်"
        } catch (ex: Exception) { "Spotify ဖွင့်မရပါ။" }
    }

    private fun openYouTube(query: String?): String {
        val intent = if (query.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        } else {
            Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
            }
        }
        return try {
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            if (query.isNullOrBlank()) "YouTube ဖွင့်ပေးလိုက်ပါပြီ" else "YouTube မှာ '$query' ကို ရှာဖွင့်ပါမယ်"
        } catch (ex: Exception) { "YouTube ဖွင့်မရပါ။" }
    }

    private fun sendMediaKey(keyCode: Int, targetPackage: String? = null) {
        val now = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(now, now + 50, KeyEvent.ACTION_UP, keyCode, 0)

        if (!targetPackage.isNullOrBlank()) {
            try {
                val intentDown = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                val intentUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                
                if (targetPackage == "com.spotify.music") {
                    val comp = ComponentName("com.spotify.music", "com.spotify.music.internal.receiver.MediaButtonReceiver")
                    intentDown.component = comp
                    intentUp.component = comp
                } else {
                    intentDown.setPackage(targetPackage)
                    intentUp.setPackage(targetPackage)
                }
                context.sendOrderedBroadcast(intentDown, null)
                context.sendOrderedBroadcast(intentUp, null)
                AppLog.d("MusicCommands", "Sent targeted media key $keyCode to $targetPackage")
            } catch (e: Exception) {
                Log.w("MusicCommands", "Failed to send targeted media key: ${e.message}")
            }
        }

        // Also dispatch via AudioManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(downEvent)
        am.dispatchMediaKeyEvent(upEvent)
    }

    private fun isThatSongReference(s: String): Boolean {
        val t = s.trim().lowercase()
        return t in listOf(
            "ဒီသီချင်း", "ဒီသီချင်းကို", "ဒီသီချင်းပဲ", "ဒီသီချင်းလေး",
            "that song", "that track", "ဒီကို", "ပြန်ဖွင့်", "ပြန်ဖွင့်ပေး", "ပြန်ဖွင့်ပေး",
            "ဒီလို", "အဲဒီသီချင်း", "နောက်ဆုံးဖွင့်ခဲ့တာ", "သီချင်းပြန်ဖွင့်"
        )
    }

    private fun rewriteQueryForSpotify(raw: String): String {
        val cleaned = extractSpotifyQuery(raw).ifBlank { raw.take(80).trim() }
        if (cleaned.isBlank()) return ""
        val r = cleaned.lowercase()
        for (key in QUERY_REWRITE_KEYS) {
            if (r.contains(key.lowercase())) return QUERY_REWRITE_MAP[key] ?: cleaned
        }
        return cleaned
    }

    private fun classifyRequest(input: String): Pair<SearchType, String> {
        val cleaned = extractSpotifyQuery(input).ifBlank { input.take(80).trim() }
        if (cleaned.isBlank()) return Pair(SearchType.PLAYLIST, "top hits")
        
        val cleanedLower = cleaned.lowercase()
        for (key in QUERY_REWRITE_KEYS) {
            if (cleanedLower.contains(key.lowercase())) {
                return Pair(SearchType.PLAYLIST, QUERY_REWRITE_MAP[key] ?: cleaned)
            }
        }
        
        if (cleanedLower.contains("ပလေးလစ်") || cleanedLower.contains("playlist") ||
            cleanedLower.contains("ငါ့ရဲ့") || cleanedLower.contains("ငါ့") ||
            cleanedLower.contains("ကိုယ်ပိုင်")) {
            return Pair(SearchType.PLAYLIST, cleaned)
        }
        
        return Pair(SearchType.TRACK, cleaned)
    }

    private fun extractSpotifyQuery(raw: String): String {
        var s = raw.trim()
        val markers = listOf(
            "ဖွင့်ပေးလိုက်ပီ", "ဖွင့်ပေးလိုက်ပီ", "ဖွင့်ပေးပါ", "ဖွင့်ပေးပါ",
            "ဖွင့်ပေး", "ဖွင့်ပေး", "ဖွင့်ပါ", "ဖွင့်ပါ", "ဖွင့်ပြပါ", "ဖွင့်ပြ",
            "ဖွင့်", "တီး", "နားထောင်ချင်တယ်", "နားထောင်မယ်",
            "ရှာပေးပါ", "ရှာပေးလိုက်", "ရှာပေး", " play"
        )
        for (m in markers) {
            val idx = s.indexOf(m)
            if (idx > 0) {
                s = s.substring(0, idx).trim()
                break
            }
        }
        return s.removeSuffix("ကို").removeSuffix("က").removeSuffix("လို").trim().take(80)
    }

    private enum class SearchType { TRACK, PLAYLIST }
}
