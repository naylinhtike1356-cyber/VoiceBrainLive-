package com.example.voicebrainlive.commands

import android.content.Context
import android.media.AudioManager

/** Read back only explicit local device goals after the existing common guard has dispatched.
 * Opaque provider/media/app results still stop the runner; a success string is never evidence.
 */
internal class MacroDeviceVerification(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun capture(leaf: MacroLeaf): (() -> Boolean)? {
        if (leaf.risk != MacroLeafRisk.SAFE) return null
        return when (leaf.command) {
            "set_focus_mode" -> {
                val minutes = leaf.value?.toIntOrNull() ?: 30
                if (minutes <= 0) return null
                val duration = minutes * 60_000L
                val earliest = System.currentTimeMillis() + duration
                ({ com.example.voicebrainlive.FocusMode.endTimeMillis(context) in earliest..(System.currentTimeMillis() + duration) })
            }
            "end_focus_mode" -> ({ com.example.voicebrainlive.FocusMode.endTimeMillis(context) == 0L })
            "set_ringer_silent", "silent_mode", "mute_ringer" -> ({ audio.ringerMode == AudioManager.RINGER_MODE_SILENT })
            "set_ringer_normal", "normal_mode", "unmute_ringer" -> ({ audio.ringerMode == AudioManager.RINGER_MODE_NORMAL })
            "set_ringer_vibrate", "vibrate_mode", "vibration_on" -> ({ audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE })
            "mute" -> ({ audio.isStreamMute(AudioManager.STREAM_MUSIC) })
            "unmute" -> ({ !audio.isStreamMute(AudioManager.STREAM_MUSIC) })
            "volume_up", "volume_down" -> {
                val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val expected = (before + if (leaf.command == "volume_up") 1 else -1).coerceIn(0, max)
                ({ audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expected })
            }
            "set_volume" -> {
                // Match the existing 0–150 media volume contract, including Burmese digits.
                val raw = leaf.value.orEmpty().trim().replace("%", "").replace(" ", "")
                    .map { if (it in '၀'..'၉') '0' + (it - '၀') else it }.joinToString("")
                val percent = raw.toIntOrNull()?.coerceIn(0, 150) ?: 50
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val expected = (max * percent / 150).coerceIn(0, max)
                ({ audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expected })
            }
            else -> null
        }
    }

    suspend fun dispatch(leaf: MacroLeaf, guard: suspend () -> String): MacroLeafResult {
        val verify = try { capture(leaf) } catch (_: Exception) { null }
        val result = OpaqueMacroResults.classify(guard())
        if (result.status in setOf(MacroOutcomeStatus.BLOCKED, MacroOutcomeStatus.WAITING_FOR_APPROVAL, MacroOutcomeStatus.FAILED, MacroOutcomeStatus.OUTCOME_UNKNOWN)) {
            return result
        }
        if (verify != null) {
            return if (try { verify() } catch (_: Exception) { false })
                MacroLeafResult(MacroOutcomeStatus.VERIFIED, result.message)
            else MacroLeafResult(MacroOutcomeStatus.UNVERIFIED, result.message + "\nDevice state could not be verified; remaining steps did not run.")
        }
        // Opaque results without explicit local device verification stop the runner as UNVERIFIED.
        return MacroLeafResult(MacroOutcomeStatus.UNVERIFIED, result.message)
    }
}
