package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.commands.handlers.ToolRegistry

/**
 * Media Handler - Handles media-related commands
 * - play_music, play_spotify, search_spotify
 * - pause_music, resume_music, next_track, previous_track
 * - volume_up, volume_down, set_volume, mute, unmute
 * - set_ringer_silent, set_ringer_normal, set_ringer_vibrate
 * 
 * This handler extracts media-related functionality from PhoneCommandExecutor
 * to improve code maintainability and reduce file size.
 */
class MediaHandler(
    private val context: Context,
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "MediaHandler"
    }

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /**
     * Execute media command
     */
    suspend fun execute(commandType: String, target: String?, value: String?): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            // Spotify / Music
            "play_music", "media_play" -> playSpotifyOrResume(target, value)
            "play_spotify", "spotify_play" -> playSpotifyOrResume(target ?: "", value ?: "")
            "search_spotify" -> searchSpotify(target ?: "")
            "pause_music", "pause", "media_pause", "media_stop" -> pauseMedia()
            "resume_music", "resume", "play" -> resumeMedia(target, value)
            "next_track", "next", "media_next" -> nextTrack()
            "previous_track", "previous", "media_previous" -> previousTrack()
            
            // Volume Control
            "volume_up" -> volumeUp()
            "volume_down" -> volumeDown()
            "set_volume" -> setVolume(parseVolumePercent(value))
            "mute" -> mute()
            "unmute" -> unmute()
            
            // Ringer / Silent / Vibration
            "set_ringer_silent", "silent_mode", "mute_ringer" -> setRingerSilent()
            "set_ringer_normal", "normal_mode", "unmute_ringer" -> setRingerNormal()
            "set_ringer_vibrate", "vibrate_mode", "vibration_on" -> setRingerVibrate()
            
            else -> "Unknown media command: $commandType"
        }
    }

    // ===== MEDIA CONTROL =====
    private suspend fun playSpotifyOrResume(target: String?, value: String?): String {
        val result = toolRegistry.executeCommandAsync("play_music", target, value)
        return result ?: "သီချင်းဖွင့်မရပါ။"
    }

    private suspend fun pauseMedia(): String {
        val result = toolRegistry.executeCommandAsync("pause_music", null, null)
        return result ?: "မီဒီယာ ခေတ္တရပ်ထားလိုက်ပါပြီ"
    }

    private suspend fun resumeMedia(target: String?, value: String?): String {
        val result = toolRegistry.executeCommandAsync("resume_music", target, value)
        return result ?: "သီချင်း ဆက်ဖွင့်လိုက်ပါပြီ"
    }

    private suspend fun nextTrack(): String {
        val result = toolRegistry.executeCommandAsync("next_track", null, null)
        return result ?: "Next"
    }

    private suspend fun previousTrack(): String {
        val result = toolRegistry.executeCommandAsync("previous_track", null, null)
        return result ?: "Previous"
    }

    private suspend fun searchSpotify(query: String): String {
        val result = toolRegistry.executeCommandAsync("search_spotify", query, null)
        return result ?: "Spotify ဖွင့်မရပါ။"
    }

    // ===== VOLUME CONTROL =====
    /** Parse volume percent from "50", "၅၀", "50%", "၁၀၀" etc. Supports 0-150 (user's phone range). */
    private fun parseVolumePercent(value: String?): Int {
        if (value.isNullOrBlank()) return 50
        val cleaned = value.trim().replace("%", "").replace(" ", "")
        if (cleaned.isEmpty()) return 50
        val arabic = cleaned.replace(Regex("[၀-၉]")) { m ->
            val myanmarToArab = "၀၁၂၃၄၅၆၇၈၉"
            (myanmarToArab.indexOf(m.value) + 48).toChar().toString()
        }
        return arabic.toIntOrNull()?.coerceIn(0, 150) ?: 50
    }

    private fun volumeUp(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) (current * 150 / max) else 0
        return "Volume up: $pct%"
    }

    private fun volumeDown(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) (current * 150 / max) else 0
        return "Volume down: $pct%"
    }

    /** set_volume: percent 0-150 (user's phone displays 0-150%). */
    private fun setVolume(percent: Int): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // Map 0-150% to 0-max steps: 150% = max
        val targetVolume = (max * percent / 150).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $percent%"
    }

    private fun mute(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Volume muted"
    }

    private fun unmute(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return "Volume unmuted"
    }

    // ===== RINGER / SILENT / VIBRATION =====
    private fun setRingerSilent(): String {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return "အသံပိတ်ပြီး ပုံမှန်အသံပြန်ဖွင့်ချင်ရင် အသံဖွင့်လို့ ပြောပေးပါ။"
    }

    private fun setRingerNormal(): String {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        return "အသံပြန်ဖွင့်ပြီးပါပြီ။"
    }

    private fun setRingerVibrate(): String {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        return "တုန်ခါမှုဖွင့်ပြီးပါပြီ။ ခေါ်ဆိုမှုကို တုန်ခါမှုနဲ့ သိရမယ်။"
    }
}