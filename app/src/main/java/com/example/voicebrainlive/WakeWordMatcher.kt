package com.example.voicebrainlive

import java.util.Locale

/**
 * Result of matching a speech transcript against wake phrases, including an optional
 * one-shot command spoken immediately after the wake word (e.g. "Hey Jarvis, turn on flashlight").
 */
data class WakeMatchResult(
    val matched: Boolean,
    val matchedPhrase: String? = null,
    val trailingCommand: String? = null
)

/**
 * Conservative, offline-safe phrase matching for background wake-word results.
 * General ASR is noisy, so this matcher accepts only a short phrase at the
 * beginning of the final transcript; it never matches an arbitrary substring.
 */
object WakeWordMatcher {
    private const val MAX_TRANSCRIPT_CHARS = 48
    private const val MAX_ONE_SHOT_CHARS = 120
    private const val MIN_CONFIDENCE = 0.62f

    val defaultPhrases: List<String> = listOf(
        // J.A.R.V.I.S. / F.R.I.D.A.Y.
        "hey jarvis",
        "ok jarvis",
        "hi jarvis",
        "jarvis",
        "ဟေး ဂျားဗစ်",
        "ဟေ့ ဂျားဗစ်",
        "ဂျားဗစ်",
        "hey friday",
        "ok friday",
        "ဟေး ဖရိုက်ဒေး",
        // Voice Brain / Luma
        "hey voice brain",
        "hay voice brain",
        "ဟေး ဗွိုက်စ် ဘရိန်း",
        "ဟေး ဗိုက်စ် ဘရိန်း",
        "ဗွိုက်စ် ဘရိန်း",
        "ဗိုက်စ် ဘရိန်း",
        "hey luma",
        "ok luma",
        "ဟေး လူမာ",
        "ဟေး လူးမား",
        "ဟေး လူမား",
        "ဟေ့ လူမာ",
        "ဟေ့ လူးမား"
    )

    fun matchesFinalTranscript(transcript: String): Boolean =
        matchesFinalTranscript(transcript, confidence = null)

    fun matchesFinalTranscript(transcript: String, confidence: Float?): Boolean =
        matchWithCommand(
            transcript = transcript,
            confidence = confidence,
            customPhrase = null,
            maxTranscriptChars = MAX_TRANSCRIPT_CHARS,
            maxTrailingCompactChars = 12
        ).matched

    /**
     * Matches wake phrase and extracts any trailing one-shot command spoken in the same utterance.
     */
    fun matchWithCommand(
        transcript: String,
        confidence: Float? = null,
        customPhrase: String? = null,
        maxTranscriptChars: Int = MAX_ONE_SHOT_CHARS,
        maxTrailingCompactChars: Int = 96
    ): WakeMatchResult {
        val normalized = normalize(transcript)
        if (normalized.isBlank() || normalized.length > maxTranscriptChars) {
            return WakeMatchResult(matched = false)
        }
        if (confidence != null && confidence.isFinite() && confidence < MIN_CONFIDENCE) {
            return WakeMatchResult(matched = false)
        }

        val candidates = buildList {
            if (!customPhrase.isNullOrBlank()) {
                val customNorm = normalize(customPhrase)
                if (customNorm.replace(" ", "").length >= 4) {
                    add(customNorm to 4)
                }
            }
            defaultPhrases.forEach { add(it to 6) }
        }

        val compact = normalized.replace(" ", "")
        // Map each non-space character in `normalized` back to its index in `normalized`.
        val nonSpaceIndices = normalized.indices.filter { normalized[it] != ' ' }

        for ((rawPhrase, minCompactLen) in candidates) {
            val phraseCompact = normalize(rawPhrase).replace(" ", "")
            if (phraseCompact.length < minCompactLen) continue

            if (compact == phraseCompact) {
                return WakeMatchResult(matched = true, matchedPhrase = rawPhrase, trailingCommand = null)
            }

            val trailingCompactLen = compact.length - phraseCompact.length
            if (!compact.startsWith(phraseCompact) || trailingCompactLen !in 1..maxTrailingCompactChars) {
                continue
            }

            // Ensure ASCII wake phrases end on a word boundary ("luma stop" yes, "lumatic" no).
            val phraseEndNormIdx = nonSpaceIndices[phraseCompact.lastIndex] + 1
            if (phraseCompact.last() in 'a'..'z' && phraseEndNormIdx < normalized.length) {
                val next = normalized[phraseEndNormIdx]
                if (next in 'a'..'z' || next in '0'..'9') continue
            }

            val trailing = normalized.substring(phraseEndNormIdx).trim()
            return WakeMatchResult(
                matched = true,
                matchedPhrase = rawPhrase,
                trailingCommand = trailing.takeIf { it.isNotBlank() }
            )
        }

        return WakeMatchResult(matched = false)
    }

    fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\u200B၊။၊]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
