package com.example.voicebrainlive.commands

import com.example.voicebrainlive.OfflineCommandMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Direct user-text entry only. The supplied dispatcher MUST be the existing executor/registry.
 * SAFE is not read-only; this narrow allowlist deliberately excludes messages, calls and macros.
 * No learning, network fallback, planner, biometric grant or private-payload logging occurs here.
 */
object OfflineDirectCommands {
    private val allowed = setOf("turn_on_flashlight", "turn_off_flashlight", "volume_up", "volume_down",
        "set_volume", "mute", "unmute", "media_play", "media_pause", "media_stop", "media_next",
        "media_previous", "open_app", "set_alarm", "set_timer", "stop_wake_alarm",
        "set_music_sleep_timer", "start_bedtime_music", "cancel_music_sleep_timer")

    suspend fun execute(text: String, current: () -> Boolean = { true },
                        dispatch: suspend (String, String?, String?) -> String): String? {
        val action = OfflineCommandMatcher.matchDirectAction(text) ?: return null
        if (action.first !in allowed || CommandSafetyPolicy.classify(action.first) != CommandSafetyPolicy.Classification.SAFE) return null
        currentCoroutineContext().ensureActive()
        if (!current()) return "BLOCKED:STALE_DIRECT_INPUT"
        return try { dispatch(action.first, action.second, action.third) }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { "FAILED:LOCAL_COMMAND_UNAVAILABLE" }
    }
}
