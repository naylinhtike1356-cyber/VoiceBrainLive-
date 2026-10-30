package com.example.voicebrainlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.SettingsActivity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Manages Spotify App Remote connection, Web API search, and playback.
 * All heavy work (connect, search, play) runs on background thread - never blocks Main.
 * Call from runBlocking(Dispatchers.IO) or equivalent.
 */
object SpotifyManager {

    private const val TAG = "SpotifyManager"
    private val spotifyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val CONNECT_TIMEOUT_SEC = 2L  // Shorter - avoid long blocks when SDK slow
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private const val SPOTIFY_WEB_API = "https://api.spotify.com/v1"
    private const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"

    private var isSpotifyPremium: Boolean? = null

    fun isPremiumUser(context: Context): Boolean {
        isSpotifyPremium?.let { return it }
        val token = SettingsActivity.getSpotifyAccessToken(context) ?: return false

        val prefs = context.getSharedPreferences("spotify_manager_prefs", Context.MODE_PRIVATE)
        if (prefs.contains("spotify_is_premium")) {
            val cachedPremium = prefs.getBoolean("spotify_is_premium", false)
            isSpotifyPremium = cachedPremium
            // Refresh in background so it stays updated
            spotifyScope.launch {
                val freshPremium = checkPremiumStatus(token)
                if (freshPremium != cachedPremium) {
                    isSpotifyPremium = freshPremium
                    prefs.edit().putBoolean("spotify_is_premium", freshPremium).apply()
                }
            }
            return cachedPremium
        }

        // Optimistically set to true, fetch in background, save to prefs
        isSpotifyPremium = true
        spotifyScope.launch {
            val freshPremium = checkPremiumStatus(token)
            isSpotifyPremium = freshPremium
            prefs.edit().putBoolean("spotify_is_premium", freshPremium).apply()
        }
        return true
    }

    fun resetPremiumStatus(context: Context) {
        isSpotifyPremium = null
        try {
            context.getSharedPreferences("spotify_manager_prefs", Context.MODE_PRIVATE)
                .edit().remove("spotify_is_premium").apply()
        } catch (e: Exception) {
            Log.w(TAG, "resetPremiumStatus: ${e.message}")
        }
    }

    private fun checkPremiumStatus(token: String): Boolean {
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/me")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyString = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyString)
                    val product = json.optString("product", "free")
                    val premium = product.lowercase() == "premium"
                    isSpotifyPremium = premium
                    Log.d(TAG, "Spotify Product Type: $product (isPremium = $premium)")
                    return premium
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkPremiumStatus failed: ${e.message}")
        }
        return false
    }

    /** Check if query matches a mood/genre key. Returns URI if match, else null. */
    fun getMoodPlaylistUri(query: String): String? {
        return com.example.voicebrainlive.spotify.MoodMapper.getMoodPlaylistUri(query)
    }

    /**
     * Mood & Context aware: play playlist by static map or API search.
     * 1) Static map (getMoodPlaylistUri), 2) type=playlist API search.
     * @param query Original input (for static map match).
     * @param apiQueryOverride Rewritten query for Spotify API (e.g. "myanmar burmese pop").
     */
    fun searchAndPlayPlaylist(context: Context, query: String, apiQueryOverride: String? = null): Boolean {
        val appCtx = context.applicationContext
        
        // 1. Static map first (uses original query) - local & instant!
        val staticUri = getMoodPlaylistUri(query)
        if (staticUri != null) {
            if (playUri(appCtx, staticUri)) {
                Log.d(TAG, "PLAYLIST FOUND: [Mood/Genre Map] -> $staticUri")
                return true
            }
        }

        // 2. API: Search user's playlists second
        val apiQuery = apiQueryOverride?.takeIf { it.isNotBlank() } ?: query
        var token = SettingsActivity.getSpotifyAccessToken(appCtx)
        var result: Pair<String, String>? = null
        
        if (!token.isNullOrBlank()) {
            result = searchUserPlaylistWithPagination(token!!, query, apiQuery)
            if (result == null) {
                val refreshed = refreshAccessToken(appCtx)
                if (refreshed) {
                    token = SettingsActivity.getSpotifyAccessToken(appCtx)
                    if (!token.isNullOrBlank()) {
                        result = searchUserPlaylistWithPagination(token!!, query, apiQuery)
                    }
                }
            }
        }
        
        if (result != null) {
            val (name, uri) = result!!
            if (playUri(appCtx, uri)) {
                Log.d(TAG, "USER PLAYLIST MATCHED & PLAYING: [$name] -> $uri")
                return true
            }
        }
        
        // 3. Fallback: Search globally
        if (result == null && !token.isNullOrBlank()) {
            Log.d(TAG, "No matching user playlist found for '$apiQuery'. Searching global playlists...")
            result = searchFirstPlaylist(token!!, apiQuery)
            if (result == null) {
                val refreshed = refreshAccessToken(appCtx)
                if (refreshed) {
                    token = SettingsActivity.getSpotifyAccessToken(appCtx)
                    if (!token.isNullOrBlank()) {
                        result = searchFirstPlaylist(token!!, apiQuery)
                    }
                }
            }
        }
        
        if (result != null) {
            val (name, uri) = result!!
            if (playUri(appCtx, uri)) {
                Log.d(TAG, "GLOBAL PLAYLIST FOUND & PLAYING: [$name] -> $uri")
                return true
            }
        }
        return false
    }

    private fun searchUserPlaylistWithPagination(accessToken: String, rawQuery: String, rewrittenQuery: String): Pair<String, String>? {
        val playlists = fetchAllUserPlaylists(accessToken)
        if (playlists.isEmpty()) return null
        return findBestUserPlaylist(playlists, rawQuery, rewrittenQuery)
    }

    private fun fetchAllUserPlaylists(accessToken: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        var url: String? = "$SPOTIFY_WEB_API/me/playlists?limit=50"
        var pageCount = 0
        
        while (url != null && pageCount < 3) {
            val req = Request.Builder()
                .url(url!!)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "fetchAllUserPlaylists page failed: HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items") ?: return@use
                    for (i in 0 until items.length()) {
                        val ob = items.optJSONObject(i) ?: continue
                        val name = ob.optString("name", "")
                        val uri = ob.optString("uri").takeIf { it.isNotBlank() } ?: continue
                        list.add(Pair(name, uri))
                    }
                    url = json.optString("next", "").takeIf { it.isNotBlank() }
                    pageCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchAllUserPlaylists exception: ${e.message}")
                break
            }
        }
        return list
    }

    private val KEYWORD_GROUPS = listOf(
        listOf("အိပ်", "အိပ်ပျော်", "အိပ်ချိန်", "အိပ်ရာဝင်", "အေးချမ်း", "အေးဆေး", "အမောပြေ", "အနားယူ", "relax", "calm", "chill", "sleep", "relaxing", "peaceful", "piano"),
        listOf("ဝမ်းနည်း", "စိတ်ညစ်", "လွမ်း", "လွမ်းစရာ", "စိတ်ဓာတ်ကျ", "ငိုချင်", "feelမယ်", "sad", "depressed", "lonely", "acoustic", "emotional"),
        listOf("ပျော်", "လန်းဆန်း", "လန်း", "အားရှိ", "မြူး", "မြူးမြူး", "ဒီဂျေး", "dj", "ပါတီ", "party", "happy", "cheerful", "upbeat", "dance", "pop", "workout"),
        listOf("မနက်", "မနက်ခင်း", "morning", "sunrise"),
        listOf("နေ့လယ်", "နေ့လည်", "afternoon", "lunch"),
        listOf("ညနေ", "ညနေခင်း", "evening", "sunset"),
        listOf("ည", "ညနက်", "ညဉ့်", "ညဥ့်", "night", "midnight", "late night"),
        listOf("၉၀", "90s", "90"),
        listOf("၈၀", "80s", "80"),
        listOf("၂၀၀၀", "2000s", "2000"),
        listOf("ခေတ်ဟောင်း", "classic", "oldies", "retro", "vintage")
    )

    private fun checkKeywordMatch(query: String, playlistName: String): Boolean {
        val q = query.lowercase()
        val p = playlistName.lowercase()
        for (group in KEYWORD_GROUPS) {
            val qMatch = group.any { q.contains(it) }
            val pMatch = group.any { p.contains(it) }
            if (qMatch && pMatch) return true
        }
        return false
    }

    private fun findBestUserPlaylist(playlists: List<Pair<String, String>>, rawQuery: String, rewrittenQuery: String): Pair<String, String>? {
        val rq = rawQuery.lowercase().trim()
        val wq = rewrittenQuery.lowercase().trim()
        if (rq.isBlank() && wq.isBlank()) return null
        
        var best: Pair<String, String>? = null
        var bestScore = -1
        
        for (playlist in playlists) {
            val name = playlist.first.lowercase()
            
            // Priority 1: Exact match
            if (name == rq || name == wq) {
                Log.d(TAG, "EXACT PLAYLIST MATCH: [${playlist.first}] -> ${playlist.second}")
                return playlist
            }
            
            // Priority 2: Substring matches
            var finalScore = 0
            if (name.contains(rq) || name.contains(wq)) {
                finalScore = 95
            } else if (rq.contains(name) || wq.contains(name)) {
                finalScore = 90
            } else {
                // Priority 3: Fuzzy word matching
                val scoreRaw = fuzzyMatchScore(rq, name)
                val scoreRewritten = fuzzyMatchScore(wq, name)
                finalScore = maxOf(scoreRaw, scoreRewritten)
            }
            
            // Priority 4: Category Keyword match
            if (checkKeywordMatch(rq, name) || checkKeywordMatch(wq, name)) {
                finalScore = maxOf(finalScore, 85)
            }
            
            if (finalScore > bestScore && finalScore >= 60) {
                bestScore = finalScore
                best = playlist
            }
        }
        
        if (best != null) {
            Log.d(TAG, "USER PLAYLIST MATCHED: [${best.first}] -> ${best.second} (score=$bestScore)")
        }
        return best
    }

    /**
     * Search type=playlist only (limit=5). Picks best by fuzzy match.
     * Returns (name, uri) or null. Log: D PLAYLIST FOUND: [Name] -> [URI]
     */
    private fun searchFirstPlaylist(accessToken: String, query: String): Pair<String, String>? {
        val encoded = Uri.encode(query)
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/search?q=$encoded&type=playlist&limit=5")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val items = json.optJSONObject("playlists")?.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val q = query.trim()
            var best: Pair<String, String>? = null
            var bestScore = -1
            for (i in 0 until minOf(items.length(), 5)) {
                val ob = items.optJSONObject(i) ?: continue  // Skip null items (Spotify sometimes returns null)
                val name = ob.optString("name", "")
                val uri = ob.optString("uri").takeIf { it.isNotBlank() } ?: continue
                val score = fuzzyMatchScore(q, name)
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(name, uri)
                }
            }
            best
        } catch (e: Exception) {
            Log.e(TAG, "searchFirstPlaylist failed: ${e.message}", e)
            null
        }
    }

    private var appRemote: SpotifyAppRemote? = null
    private val connectionLock = Any()

    fun isConnected(): Boolean = appRemote?.isConnected == true

    fun hasAccessToken(context: Context): Boolean =
        !SettingsActivity.getSpotifyAccessToken(context).isNullOrBlank()

    fun disconnect() {
        synchronized(connectionLock) {
            try {
                SpotifyAppRemote.disconnect(appRemote)
            } catch (e: Exception) {
                Log.w(TAG, "disconnect: ${e.message}")
            }
            appRemote = null
        }
    }

    /**
     * Search via Web API, get first track URI, then play.
     * Strategy: Intent with spotify:track:URI (reliable) > SDK (unreliable on many devices).
     * Returns true if successful, false to trigger spotify:search fallback.
     */
    fun searchAndPlayFirst(context: Context, query: String): Boolean {
        val appCtx = context.applicationContext
        var token = SettingsActivity.getSpotifyAccessToken(appCtx)
        if (token.isNullOrBlank()) {
            AppLog.d(TAG, "No access token -> fallback")
            return false
        }
        var uri = searchFirstTrack(token, query)
        if (uri == null) {
            val refreshed = refreshAccessToken(appCtx)
            if (refreshed) {
                token = SettingsActivity.getSpotifyAccessToken(appCtx)
                uri = token?.let { searchFirstTrack(it, query) }
            } else {
                SettingsActivity.clearSpotifyTokens(appCtx)
                resetPremiumStatus(appCtx)
                AppLog.d(TAG, "Token expired, refresh failed - cleared. Re-connect in Settings.")
            }
        }
        if (uri == null) {
            AppLog.d(TAG, "Search returned no track for: $query")
            return false
        }
        AppLog.d(TAG, "Playing URI: $uri (query=$query)")
        // Use Expert Playback (includes Intent, Polling, SDK, and Forced Web API)
        return playUri(appCtx, uri)
    }

    /**
     * Open Spotify and play via spotify:track/playlist/album URI Intent.
     * Works without App Remote SDK - reliable on all devices.
     */
    private fun convertSpotifyUriToHttp(uri: String): String {
        val clean = uri.removeSuffix(":play").trim()
        if (!clean.startsWith("spotify:")) return clean
        val parts = clean.split(":")
        if (parts.size >= 3) {
            if (parts[1] == "user" && parts.size >= 5 && parts[3] == "playlist") {
                return "https://open.spotify.com/playlist/${parts[4]}"
            }
            val type = parts[1] // track, playlist, album, artist
            val id = parts[2]
            return "https://open.spotify.com/$type/$id"
        }
        return clean
    }

    private fun playViaTrackIntent(context: Context, uri: String): Boolean {
        if (!uri.startsWith("spotify:")) return false
        
        // Fix: Use the raw spotify URI instead of converting to HTTP. 
        // Converting to HTTP can cause the Spotify app to show "Content is no longer available" pages.
        // We also append :play if it's not already there, to encourage autoplay.
        val playUri = if (uri.endsWith(":play")) uri else "$uri:play"
        
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(playUri)
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            Log.d(TAG, "playViaTrackIntent launched: $playUri")
            true
        } catch (e: Exception) {
            Log.w(TAG, "playViaTrackIntent failed: ${e.message}")
            false
        }
    }

    /**
     * Spotify Web API: PUT /me/player/play with context_uri.
     * အပေါ်က playlist ခလုပ်အကြီး နှိပ်သလို အသစ် playback စသည်။ Mini-player resume မဟုတ်။
     */
    private fun playViaWebApi(context: Context, uri: String): Boolean {
        if (!uri.startsWith("spotify:")) return false
        var token = SettingsActivity.getSpotifyAccessToken(context)
        if (token.isNullOrBlank()) return false
        val body = when {
            uri.contains(":playlist:") || uri.contains(":album:") || uri.contains(":artist:") ->
                """{"context_uri":"$uri"}"""
            uri.contains(":track:") ->
                """{"uris":["$uri"]}"""
            else -> return false
        }
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/me/player/play")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            var resp = httpClient.newCall(req).execute()
            if (resp.code == 401) {
                if (refreshAccessToken(context)) {
                    token = SettingsActivity.getSpotifyAccessToken(context) ?: return false
                    val retryReq = Request.Builder()
                        .url("$SPOTIFY_WEB_API/me/player/play")
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .put(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    resp = httpClient.newCall(retryReq).execute()
                }
            }
            if (resp.isSuccessful) {
                Log.d(TAG, "playViaWebApi: 204 OK -> $uri")
                return true
            }
            Log.w(TAG, "playViaWebApi: ${resp.code} - ${resp.body?.string()?.take(200)} (no active device?)")
            false
        } catch (e: Exception) {
            Log.e(TAG, "playViaWebApi failed: ${e.message}", e)
            false
        }
    }

    /**
     * Play track/playlist/album URI.
     * အသစ်ဖွင့်ခိုင်းရင်: အပေါ်က playlist ခလုပ် (အကြီး) နဲ့ တူအောင် play စေသည်။
     * SDK မရရင် Web API play (context_uri) သုံးသည် - mini-player resume မဟုတ်။
     */
    /**
     * EXPERT PLAYBACK: Selects best URI and clicks the BIG PLAY button programmatically.
     * Use Polling to ensure the music actually starts even if Spotify is slow to load.
     */
    fun playUri(context: Context, uri: String): Boolean {
        val appCtx = context.applicationContext
        
        // (1) STOP & SYNC: Kill old playback immediately
        forceStopPlayback(appCtx)
        
        // (2) SOURCE UPDATE: Open Spotify to target URI (UI Update)
        if (!playViaTrackIntent(appCtx, uri)) return false
        
        // If they are not Premium, we don't need Web API / SDK remote polling.
        // The intent with :play suffix is sufficient to open and start playback.
        if (!isPremiumUser(appCtx)) {
            Log.d(TAG, "playUri: Spotify Free user - playback initiated via Intent with :play suffix")
            return true
        }
        
        // (3) IMMEDIATE TRIGGER: Do not wait for polling to start the first hit
        // Running on background to not block the main command flow
        spotifyScope.launch {
            Log.d(TAG, "Expert Play: Immediate source update trigger for $uri")
            playViaWebApiForced(appCtx, uri)
        }
        
        // (4) EXPERT POLLING: Confirm and keep trying until actual audio starts
        pollAndPlay(appCtx, uri, 1, 10) 
        
        return true
    }

    /** KILL playback before starting new one to ensure old music doesn't linger. */
    @Suppress("DEPRECATION")
    private fun forceStopPlayback(context: Context) {
        try {
            // (1) MediaController Pause (most reliable if we have access) - only pause other music apps, not Spotify
            if (MediaSessionHelper.isNotificationAccessEnabled(context)) {
                MediaSessionHelper.tryPauseOtherMediaSessions(context, "com.spotify.music")
            } else {
                // (2) Audio Focus nudge (stops most music apps even without notification access)
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val result = am?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am?.abandonAudioFocus(null)
                }
            }
            Log.d(TAG, "forceStopPlayback: Triggered pause for other apps & audio focus nudge")
        } catch (e: Exception) { Log.w(TAG, "forceStopPlayback err: ${e.message}") }
    }

    /**
     * Polling Step: Continuously checks for active connection/device and triggers playback.
     * This solves the "stops at search result" problem.
     */
    private fun pollAndPlay(context: Context, uri: String, attempt: Int, maxAttempts: Int) {
        spotifyScope.launch {
            pollAndPlaySuspended(context, uri, attempt, maxAttempts)
        }
    }

    private suspend fun pollAndPlaySuspended(context: Context, uri: String, attempt: Int, maxAttempts: Int) {
        if (attempt > maxAttempts) {
            Log.w(TAG, "Expert Play: Failed after $maxAttempts attempts. Final fallback to media keys.")
            schedulePlayKeyAfterDelay(context, 100)
            return
        }

        // Delay strategy: 1st poll is fast (800ms) to catch the immediate intent, 
        // subsequent ones are 1.5s to allow for network/SDK initialization.
        val delayMs = if (attempt == 1) 800L else 1500L
        delay(delayMs)

        var played = false
        
        // PRIORITY 1: Forced Web API (Ensures the SOURCE matches our URI)
        if (playViaWebApiForced(context, uri)) {
            played = true
            Log.d(TAG, "Expert Play: Target source enforced via Web API (Attempt $attempt)")
        }

        // PRIORITY 2: SDK Play (Fastest if session is already active)
        if (!played && ensureConnectedWithRetry(context, BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI, maxRetries = 1)) {
            if (playUriInternal(uri)) {
                played = true
                Log.d(TAG, "Expert Play: Target source enforced via SDK (Attempt $attempt)")
            }
        }

        // PRIORITY 3: MediaController Fallback
        // CRITICAL: We only use this generic play if URI-specific methods failed 
        // OR if we are deep into polling (Attempt > 3).
        if (!played && attempt > 2 && MediaSessionHelper.isNotificationAccessEnabled(context)) {
            if (MediaSessionHelper.tryPlayViaMediaController(context)) {
                played = true
                Log.d(TAG, "Expert Play: Generic play fallback (Attempt $attempt)")
            }
        }
        
        // Step D: Virtual Media Keys (Last resort)
        if (played) {
            // Success! But confirm with a small nudge only if not already playing
            if (!MediaSessionHelper.isNotificationAccessEnabled(context) || !MediaSessionHelper.isSpotifyOrMusicPlaying(context)) {
                schedulePlayKeyAfterDelay(context, 100)
            }
        } else if (attempt > 4) {
            schedulePlayKeyAfterDelay(context, 100)
            bringSpotifyToForeground(context)
        }

        if (played) {
            Log.d(TAG, "Expert Play: SUCCESS at attempt $attempt for $uri")
            // Final verification: 2s later, if not playing, nudge it again
            delay(2000)
            if (MediaSessionHelper.isNotificationAccessEnabled(context)) {
                if (!MediaSessionHelper.isSpotifyOrMusicPlaying(context)) {
                    MediaSessionHelper.tryPlayViaMediaController(context)
                }
            } else {
                schedulePlayKeyAfterDelay(context, 50)
            }
        } else {
            Log.d(TAG, "Expert Play: Attempt $attempt failed, retrying...")
            pollAndPlaySuspended(context, uri, attempt + 1, maxAttempts)
        }
    }

    /**
     * Web API Playback with Forced Device Activation (Transfer).
     * Solves "No Active Device" errors common in standard API calls.
     */
    private fun playViaWebApiForced(context: Context, uri: String): Boolean {
        val token = SettingsActivity.getSpotifyAccessToken(context) ?: return false
        
        // 1. Get Device Details (ID and active status)
        val deviceInfo = getDeviceDetails(token)
        
        // 2. If we found a device, force play directly if active, otherwise transfer and play
        return if (deviceInfo != null) {
            val (deviceId, isActive) = deviceInfo
            if (isActive) {
                Log.d(TAG, "playViaWebApiForced: Device $deviceId is already active. Playing directly.")
                playOnDevice(token, deviceId, uri)
            } else {
                Log.d(TAG, "playViaWebApiForced: Device $deviceId is not active. Transferring first.")
                transferAndPlay(token, deviceId, uri)
            }
        } else {
            // No device found, try standard play and hope for the best
            playViaWebApi(context, uri)
        }
    }

    private fun getDeviceDetails(token: String): Pair<String, Boolean>? {
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/me/player/devices")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                val devices = json.optJSONArray("devices")
                if (devices != null && devices.length() > 0) {
                    var firstId = ""
                    for (i in 0 until devices.length()) {
                        val d = devices.getJSONObject(i)
                        val id = d.optString("id")
                        if (d.optBoolean("is_active")) return Pair(id, true)
                        if (i == 0) firstId = id
                    }
                    if (firstId.isNotBlank()) return Pair(firstId, false)
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun playOnDevice(token: String, deviceId: String, uri: String): Boolean {
        val playBody = if (uri.contains(":track:")) """{"uris":["$uri"]}""" else """{"context_uri":"$uri"}"""
        val playReq = Request.Builder()
            .url("$SPOTIFY_WEB_API/me/player/play?device_id=$deviceId")
            .addHeader("Authorization", "Bearer $token")
            .put(playBody.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val pResp = httpClient.newCall(playReq).execute()
            pResp.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "playOnDevice failed: ${e.message}")
            false
        }
    }

    private fun transferAndPlay(token: String, deviceId: String, uri: String): Boolean {
        // Stop current before transfer to ensure "New" session starts clean
        try {
            val pauseReq = Request.Builder()
                .url("$SPOTIFY_WEB_API/me/player/pause?device_id=$deviceId")
                .addHeader("Authorization", "Bearer $token")
                .put("{}".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(pauseReq).execute()
        } catch (_: Exception) {}

        // 1. Transfer playback - 'play: false' ensures old music doesn't resume during transfer
        val transferBody = """{"device_ids":["$deviceId"],"play":false}"""
        val transferReq = Request.Builder()
            .url("$SPOTIFY_WEB_API/me/player")
            .addHeader("Authorization", "Bearer $token")
            .put(transferBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            val tResp = httpClient.newCall(transferReq).execute()
            if (tResp.isSuccessful) {
                // 2. Play the specific URI (This simulates clicking the BIG PLAY BUTTON for the search result)
                val playBody = if (uri.contains(":track:")) """{"uris":["$uri"]}""" else """{"context_uri":"$uri"}"""
                val playReq = Request.Builder()
                    .url("$SPOTIFY_WEB_API/me/player/play?device_id=$deviceId")
                    .addHeader("Authorization", "Bearer $token")
                    .put(playBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val pResp = httpClient.newCall(playReq).execute()
                return pResp.isSuccessful
            }
        } catch (e: Exception) { Log.e(TAG, "transferAndPlay failed: ${e.message}") }
        return false
    }

    private fun schedulePlayKeyAfterDelay(ctx: Context, delayMs: Long) {
        android.os.Handler(ctx.mainLooper).postDelayed({
            try {
                val now = android.os.SystemClock.uptimeMillis()
                val downEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                val upEvent = android.view.KeyEvent(now, now + 50, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                // (1) Target Spotify's MediaButtonReceiver
                val comp = ComponentName("com.spotify.music", "com.spotify.music.internal.receiver.MediaButtonReceiver")
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setComponent(comp)
                    putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setComponent(comp)
                    putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                ctx.sendOrderedBroadcast(downIntent, null)
                ctx.sendOrderedBroadcast(upIntent, null)
                // (2) Also dispatch via AudioManager -> routes to active media session (Spotify when in foreground)
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.dispatchMediaKeyEvent(downEvent)
                am?.dispatchMediaKeyEvent(upEvent)
            } catch (e: Exception) { Log.w(TAG, "schedulePlayKey: ${e.message}") }
        }, delayMs)
    }

    private fun bringSpotifyToForeground(context: Context) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
            launch?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "bringSpotifyToForeground: ${e.message}")
        }
    }

    /**
     * Force Reset: အဟောင်းကို ရပ်ပြီး အသစ် playlist/track စသည်။
     * 1) pause() ချက်ချင်း ခေါ်ပါ (အဟောင်းကို အတင်းရပ်လိုက်သည်)
     * 2) delay(500) - Coroutine သုံး
     * 3) play(uri) ခေါ်ပါ
     * 4) PlayerState subscribe လုပ် - track.uri က ကိုယ်ပိုလိုက်တဲ့ URI နဲ့ မတူရင် play(uri) ထပ်ပိုပါ
     */
    private suspend fun playUriInternal(uri: String): Boolean = withContext(Dispatchers.Main) {
        val remote = appRemote
        if (remote == null || !remote.isConnected) {
            Log.w(TAG, "playUri: appRemote null or not connected")
            return@withContext false
        }
        val isTrackUri = uri.contains(":track:")
        try {
            // (1) pause() ချက်ချင်း ခေါ်ပါ - အဟောင်းကို ရပ်လိုက်သည်
            try {
                remote.playerApi.pause()
                Log.d(TAG, "playUriInternal: pause() first to stop old playback")
            } catch (e: Exception) { Log.w(TAG, "pause: ${e.message}") }

            // (2) delay(500) - Coroutine
            delay(500)

            // (3) play(uri)
            remote.playerApi.play(uri)
            Log.d(TAG, "playerApi.play ok: $uri")

            // (4) PlayerState subscribe - track.uri မတူရင် play(uri) ထပ်ပိုပါ
            val sub = remote.playerApi.subscribeToPlayerState()
            var verified = false
            sub.setEventCallback { state ->
                if (verified) return@setEventCallback
                val currentUri = state.track?.uri ?: return@setEventCallback
                if (isTrackUri && currentUri != uri) {
                    Log.d(TAG, "PlayerState: track.uri ($currentUri) != target ($uri), re-playing")
                    try {
                        remote.playerApi.play(uri)
                    } catch (e: Exception) { Log.w(TAG, "re-play: ${e.message}") }
                }
                verified = true
            }
            sub.setErrorCallback { Log.w(TAG, "subscribeToPlayerState error: ${it.message}") }
            // Brief wait for first state; subscription continues in background
            delay(800)
            try { sub.cancel() } catch (e: Exception) { /* ignore */ }

            true
        } catch (e: Exception) {
            Log.e(TAG, "playUri failed: ${e.message}", e)
            false
        }
    }

    private suspend fun ensureConnectedWithRetry(
        context: Context,
        clientId: String,
        redirectUri: String,
        maxRetries: Int = 3
    ): Boolean {
        if (appRemote?.isConnected == true) return true
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            Log.w(TAG, "Spotify app not installed")
            return false
        }
        for (attempt in 1..maxRetries) {
            Log.d(TAG, "ensureConnected attempt $attempt/$maxRetries")
            if (attempt > 1) {
                disconnect()
                delay(1500)
            }
            if (tryConnect(context, clientId, redirectUri)) return true
        }
        return false
    }

    private suspend fun tryConnect(context: Context, clientId: String, redirectUri: String): Boolean {
        if (appRemote?.isConnected == true) return true
        if (appRemote != null && !appRemote!!.isConnected) {
            Log.d(TAG, "Clearing stale connection before reconnect")
            disconnect()
            delay(500)
        }
        val latch = CountDownLatch(1)
        val success = AtomicReference(false)
        // showAuthView(false) - user already authorized in Settings; avoids auth popup blocking
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()
        val listener = object : Connector.ConnectionListener {
            override fun onConnected(connected: SpotifyAppRemote) {
                appRemote = connected
                success.set(true)
                Log.d(TAG, "Spotify App Remote connected")
                latch.countDown()
            }
            override fun onFailure(throwable: Throwable) {
                if (throwable.javaClass.simpleName == "SpotifyConnectionTerminatedException") {
                    Log.w(TAG, "Connection terminated - clearing for reconnect")
                    disconnect()
                }
                if (throwable.javaClass.simpleName == "UserNotAuthorizedException") {
                    Log.w(TAG, "User not authorized - try showAuthView(true) or re-connect in Settings")
                }
                Log.e(TAG, "Spotify connect failed: ${throwable.message} | ${throwable.javaClass.simpleName}", throwable)
                latch.countDown()
            }
        }
        // Call connect() on the Main thread to satisfy SDK requirements, but wait on background thread
        withContext(Dispatchers.Main) {
            SpotifyAppRemote.connect(context, params, listener)
        }
        val ok = latch.await(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!ok) {
            Log.w(TAG, "Spotify connect TIMEOUT after ${CONNECT_TIMEOUT_SEC}s (no callback from SDK)")
        }
        return ok && success.get()
    }

    /**
     * Fuzzy matching score: query vs display name (track/playlist/album).
     * Higher = better match. Used to pick best from limit=5 results.
     */
    private fun fuzzyMatchScore(query: String, displayName: String): Int {
        val q = query.lowercase().trim()
        val n = displayName.lowercase().trim()
        if (q.isBlank() || n.isBlank()) return 0
        if (q == n) return 100
        if (n.contains(q)) return 90  // display contains full query
        if (q.contains(n)) return 80  // query contains full display
        val qWords = q.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        val nWords = n.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (qWords.isEmpty()) return if (n.startsWith(q) || n.contains(q)) 70 else 20
        val overlap = qWords.count { qw -> nWords.any { nw -> nw.contains(qw) || qw.contains(nw) } }
        return (overlap * 60 / qWords.size).coerceAtLeast(10)
    }

    /**
     * Search Spotify: track first (limit=5 + fuzzy pick), then playlist, then album fallback.
     */
    private fun searchFirstTrack(accessToken: String, query: String): String? {
        val encoded = Uri.encode(query)
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/search?q=$encoded&type=track,playlist,album&limit=5")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "searchFirstTrack: HTTP ${resp.code} for '$query' - ${if (resp.code == 401) "token expired" else "check response"}")
                return null
            }
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val q = query.trim()

            // 1. Tracks: Spotify API already ranks by relevance! Trust the first result.
            val trackItems = json.optJSONObject("tracks")?.optJSONArray("items")
            if (trackItems != null && trackItems.length() > 0) {
                // Return the very first result from Spotify's own search algorithm
                val firstTrack = trackItems.optJSONObject(0)
                val bestUri = firstTrack?.optString("uri")?.takeIf { it.isNotBlank() }
                if (bestUri != null) {
                    Log.d(TAG, "searchFirstTrack: query='$query' -> track uri=$bestUri (trusting Spotify ranking)")
                    return bestUri
                }
            }

            // 2. Fallback: playlist
            val playlistItems = json.optJSONObject("playlists")?.optJSONArray("items")
            if (playlistItems != null && playlistItems.length() > 0) {
                var bestUri: String? = null
                var bestScore = -1
                for (i in 0 until minOf(playlistItems.length(), 5)) {
                    val ob = playlistItems.optJSONObject(i) ?: continue
                    val name = ob.optString("name", "")
                    val score = fuzzyMatchScore(q, name)
                    if (score > bestScore) {
                        bestScore = score
                        bestUri = ob.optString("uri").takeIf { it.isNotBlank() }
                    }
                }
                if (bestUri != null) {
                    Log.d(TAG, "searchFirstTrack: query='$query' -> playlist uri=$bestUri (fallback)")
                    return bestUri
                }
            }

            // 3. Fallback: album
            val albumItems = json.optJSONObject("albums")?.optJSONArray("items")
            if (albumItems != null && albumItems.length() > 0) {
                for (i in 0 until minOf(albumItems.length(), 5)) {
                    val ob = albumItems.optJSONObject(i) ?: continue
                    val uri = ob.optString("uri").takeIf { it.isNotBlank() }
                    if (uri != null) {
                        Log.d(TAG, "searchFirstTrack: query='$query' -> album uri=$uri (fallback)")
                        return uri
                    }
                }
            }

            Log.d(TAG, "searchFirstTrack: no result for '$query'")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            null
        }
    }

    private fun refreshAccessToken(context: Context): Boolean {
        val refreshToken = SettingsActivity.getSpotifyRefreshToken(context)
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "refreshAccessToken: no refresh_token - re-connect in Settings")
            return false
        }
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val req = Request.Builder()
            .url(SPOTIFY_TOKEN_URL)
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.w(TAG, "refreshAccessToken: HTTP ${resp.code} - $respBody")
                return false
            }
            val json = JSONObject(respBody)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return false
            val newRefresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
            SettingsActivity.saveSpotifyTokens(context, accessToken, newRefresh)
        } catch (e: Exception) {
            Log.e(TAG, "refresh token failed: ${e.message}", e)
            false
        }
    }

    fun createPlaylist(context: Context, name: String): String {
        val appCtx = context.applicationContext
        var token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
        
        var userId = getUserId(token)
        if (userId == null && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            userId = getUserId(token)
        }
        if (userId == null) return "Failed to retrieve Spotify user details."

        var playlistId = createPlaylistInternal(token, userId, name)
        if (playlistId == null && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            playlistId = createPlaylistInternal(token, userId, name)
        }
        return if (playlistId != null) {
            "Playlist '$name' created successfully."
        } else {
            "Failed to create playlist on Spotify."
        }
    }

    fun addToPlaylist(context: Context, songName: String, playlistName: String): String {
        val appCtx = context.applicationContext
        var token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."

        var trackUri = searchFirstTrack(token, songName)
        var playlist = searchFirstPlaylist(token, playlistName)

        if ((trackUri == null || playlist == null) && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            if (trackUri == null) trackUri = searchFirstTrack(token, songName)
            if (playlist == null) playlist = searchFirstPlaylist(token, playlistName)
        }

        if (trackUri == null) return "Track '$songName' not found on Spotify."
        if (playlist == null) return "Playlist '$playlistName' not found on Spotify."

        val playlistId = playlist.second.substringAfterLast(":")
        var success = addTrackToPlaylistInternal(token, playlistId, trackUri)
        if (!success && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            success = addTrackToPlaylistInternal(token, playlistId, trackUri)
        }

        return if (success) {
            "Added '$songName' to playlist '$playlistName' successfully."
        } else {
            "Failed to add track to Spotify playlist."
        }
    }

    private fun getUserId(token: String): String? {
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/me")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("id").takeIf { it.isNotBlank() }
            } else {
                Log.e(TAG, "getUserId failed: HTTP ${resp.code} - ${resp.body?.string()?.take(500)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUserId exception: ${e.message}", e)
            null
        }
    }

    private fun createPlaylistInternal(token: String, userId: String, name: String): String? {
        val body = """{"name":"$name","description":"Created by VoiceBrainLive","public":false}"""
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/users/$userId/playlists")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("id").takeIf { it.isNotBlank() }
            } else {
                Log.e(TAG, "createPlaylistInternal failed: HTTP ${resp.code} - ${resp.body?.string()?.take(500)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPlaylistInternal exception: ${e.message}", e)
            null
        }
    }

    private fun addTrackToPlaylistInternal(token: String, playlistId: String, trackUri: String): Boolean {
        val body = """{"uris":["$trackUri"]}"""
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/playlists/$playlistId/tracks")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                true
            } else {
                Log.e(TAG, "addTrackToPlaylistInternal failed: HTTP ${resp.code} - ${resp.body?.string()?.take(500)}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "addTrackToPlaylistInternal exception: ${e.message}", e)
            false
        }
    }

    private fun addTracksToPlaylistInternal(token: String, playlistId: String, trackUris: List<String>): Boolean {
        val urisJson = trackUris.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val body = """{"uris":$urisJson}"""
        val req = Request.Builder()
            .url("$SPOTIFY_WEB_API/playlists/$playlistId/tracks")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                true
            } else {
                Log.e(TAG, "addTracksToPlaylistInternal failed: HTTP ${resp.code} - ${resp.body?.string()?.take(500)}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "addTracksToPlaylistInternal exception: ${e.message}", e)
            false
        }
    }

    fun createAndPlayPlaylist(context: Context, playlistName: String, songs: List<String>): String {
        val appCtx = context.applicationContext
        var token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found. Please connect in Settings."
        
        var userId = getUserId(token)
        if (userId == null && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            userId = getUserId(token)
        }
        if (userId == null) return "Failed to retrieve Spotify user details."

        var playlistId = createPlaylistInternal(token, userId, playlistName)
        if (playlistId == null && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            playlistId = createPlaylistInternal(token, userId, playlistName)
        }
        if (playlistId == null) return "Failed to create playlist on Spotify."

        val trackUris = mutableListOf<String>()
        val foundSongs = mutableListOf<String>()
        for (song in songs) {
            val trimmedSong = song.trim()
            if (trimmedSong.isEmpty()) continue
            val trackUri = searchFirstTrack(token, trimmedSong)
            if (trackUri != null) {
                trackUris.add(trackUri)
                foundSongs.add(trimmedSong)
            }
        }

        if (trackUris.isEmpty()) {
            return "No songs from the list could be found on Spotify."
        }

        var added = addTracksToPlaylistInternal(token, playlistId, trackUris)
        if (!added && refreshAccessToken(appCtx)) {
            token = SettingsActivity.getSpotifyAccessToken(appCtx) ?: return "Spotify token not found."
            added = addTracksToPlaylistInternal(token, playlistId, trackUris)
        }

        if (!added) {
            return "Failed to add tracks to the playlist."
        }

        // Play the playlist immediately!
        val playUriStr = "spotify:playlist:$playlistId"
        val played = playUri(appCtx, playUriStr)

        return if (played) {
            "Spotify Playlist '$playlistName' ကို ဖန်တီးပြီး သီချင်းများဖြစ်ကြသော ${foundSongs.joinToString(", ")} တို့ကို ထည့်သွင်းကာ ဖွင့်ပေးလိုက်ပါပြီ။"
        } else {
            "Playlist '$playlistName' ကို ဖန်တီးပြီး သီချင်းများ ထည့်သွင်းပြီးပါပြီ။ သို့သော် အလိုအလျောက်ဖွင့်ရန် မအောင်မြင်ပါ။"
        }
    }
}
