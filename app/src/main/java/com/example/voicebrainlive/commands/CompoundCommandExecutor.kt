package com.example.voicebrainlive.commands

import com.example.voicebrainlive.OfflineCommandMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.Locale

/**
 * Executes multi-step / compound commands sequentially in order.
 * Example utterances:
 * - "စိုင်းလန့်လုပ်ပြီး အသံကို ၅၀ ရာခိုင်နှုန်းထားပြီး သီချင်းဖွင့်ပါ"
 * - "ဓာတ်မီးဖွင့်ပြီး အသံတိုးပေးပါ"
 * - "မနက် ၇ နာရီ နှိုးစက်ထားပြီး ဓာတ်မီးဖွင့်ပါ"
 * - "သီချင်းဖွင့်ပြီး ၃၀ မိနစ်ကြာရင် သီချင်းပိတ်ထားပေးပါ"
 * - "silent mode and volume 50 and play music"
 */
object CompoundCommandExecutor {

    private val allowed = setOf(
        "turn_on_flashlight", "turn_off_flashlight", "volume_up", "volume_down",
        "set_volume", "mute", "unmute", "media_play", "media_pause", "media_stop", "media_next",
        "media_previous", "open_app", "set_alarm", "set_timer", "stop_wake_alarm",
        "dismiss_alarm", "cancel_alarm", "clear_alarm", "stop_alarm", "cancel_wake_alarm",
        "set_music_sleep_timer", "start_bedtime_music", "cancel_music_sleep_timer",
        "set_ringer_silent", "silent_mode", "set_ringer_normal", "normal_mode",
        "set_ringer_vibrate", "vibrate_mode", "play_music", "play_spotify"
    )

    // Conjunctions splitting compound actions (both formal ပြီး and informal ပီး)
    private val conjunctionRegex = Regex(
        "(?:ပြီးတော့|ပြီးရင်|ပြီးလျှင်|ပြီးမှ|ပြီး|ပီးတော့|ပီးရင်|ပီးလျှင်|ပီးမှ|ပီး|နှင့်|\\s+နဲ့\\s+|and\\s+then|\\bthen\\b|\\band\\b|;|၊)",
        RegexOption.IGNORE_CASE
    )

    private val directSuffix = Regex("(?:ပေး(?:လိုက်)?(?:ပါ)?(?:ဦး|ဦး|နော်)?|လိုက်(?:ပါ)?|ပါ(?:ဦး|ဦး|နော်)?|ဦး|နော်)$")

    private val timedStopRegex = Regex(
        "(?:([0-9]{1,3})\\s*(?:မိနစ်|မီနစ်|minutes?)(?:လောက်)?(?:ကြာရင်|နေရင်|အကြာမှာ|ပြီးရင်|ပီးရင်|ပြည့်ရင်|ကြာမှ|later)?\\s*(?:သီချင်း|သီးချင်း|မီဒီယာ)?\\s*(?:ပြန်)?(?:ပိတ်|ရပ်)(?:ထား)?|" +
        "(?:သီချင်း|သီးချင်း|မီဒီယာ)?\\s*(?:ပြန်)?(?:ပိတ်|ရပ်)(?:ထား)?\\s*([0-9]{1,3})\\s*(?:မိနစ်|မီနစ်|minutes?))",
        RegexOption.IGNORE_CASE
    )

    private val customMusicRegex = Regex(
        "^(?:(.+?)(?:ကို)?(?:လေး)?)?(?:သီချင်း|သီးချင်း)(?:ကို)?(?:လေး)?(?:ဖွင့်|ဆက်ဖွင့်|ပြန်ဖွင့်|play)$",
        RegexOption.IGNORE_CASE
    )

    private fun myanmarToArabic(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c in '၀'..'၉') sb.append('0' + (c - '၀'))
            else sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Parses the text into individual command steps if it is a compound command.
     * Returns null if not a compound command (less than 2 actions) or if any action is unrecognized.
     */
    fun parseSteps(text: String): List<Triple<String, String?, String?>>? {
        if (text.length !in 3..400) return null

        var s = Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT).replace("\u200b", "").replace("\ufeff", "").trim()

        // Strip prefix "please" so "ပြီး" in "ကျေးဇူးပြုပြီး" does not falsely split
        s = s.removePrefix("ကျေးဇူးပြုပြီး").removePrefix("please ").trim()

        if (!conjunctionRegex.containsMatchIn(s)) return null

        val rawParts = s.split(conjunctionRegex)
            .map { it.trim().trimEnd('။', '.', '!').trim() }
            .filter { it.isNotBlank() }

        if (rawParts.size < 2) return null

        val steps = mutableListOf<Triple<String, String?, String?>>()
        for (part in rawParts) {
            val reviewed = ReviewedCallInput.target(part)
            if (reviewed != null) {
                steps.add(Triple("make_call", reviewed, null))
                continue
            }

            // Check if this part is a delayed sleep-timer or delayed pause (e.g. "၅ မိနစ်ကြာရင် ပိတ်ပေးပါ")
            val partNormalized = myanmarToArabic(part).replace(directSuffix, "").trim()
            val timedStop = timedStopRegex.find(partNormalized)
            if (timedStop != null) {
                val num = (timedStop.groupValues[1].ifBlank { timedStop.groupValues[2] }).toIntOrNull()
                if (num != null && num in 1..180) {
                    steps.add(Triple("set_music_sleep_timer", null, num.toString()))
                    continue
                }
            }

            // Check if this part is a music query (e.g. "အလုပ်ထဲမှာ ပျော်ရွှင်စေမယ့် သီချင်းဖွင့်")
            val cleanPart = partNormalized.replace(" ", "")
            val musicMatch = customMusicRegex.find(cleanPart)
            if (musicMatch != null) {
                val query = musicMatch.groupValues[1].trim()
                if (query.isNotBlank()) {
                    steps.add(Triple("play_music", query, null))
                    continue
                } else {
                    steps.add(Triple("media_play", null, null))
                    continue
                }
            }

            val direct = OfflineCommandMatcher.matchDirectAction(part)
                ?: OfflineCommandMatcher.matchDirectAction(part.replace(directSuffix, "").trim())
                ?: OfflineCommandMatcher.matchDirectAction("ကျေးဇူးပြုပြီး $part")

            if (direct != null && direct.first in allowed &&
                CommandSafetyPolicy.classify(direct.first) == CommandSafetyPolicy.Classification.SAFE) {
                steps.add(direct)
            } else {
                // If any segment cannot be safely matched as a direct action, abort compound execution
                return null
            }
        }

        return if (steps.size >= 2) steps else null
    }

    /**
     * Executes parsed compound actions sequentially.
     * Returns formatted combined message, or null if input was not a valid compound command.
     */
    suspend fun execute(
        text: String,
        current: () -> Boolean = { true },
        dispatch: suspend (String, String?, String?) -> String
    ): String? {
        val steps = parseSteps(text) ?: return null
        currentCoroutineContext().ensureActive()
        if (!current()) return "BLOCKED:STALE_DIRECT_INPUT"

        val results = mutableListOf<String>()
        for (step in steps) {
            currentCoroutineContext().ensureActive()
            if (!current()) return "BLOCKED:STALE_DIRECT_INPUT"
            try {
                val rawResult = dispatch(step.first, step.second, step.third)
                val formatted = CommandResultFormatter.format(rawResult)
                results.add(formatted)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                results.add("ဆောင်ရွက်ရန် မအောင်မြင်ပါ")
            }
        }

        return results.joinToString("\n")
    }
}
