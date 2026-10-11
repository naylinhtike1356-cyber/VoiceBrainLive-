package com.example.voicebrainlive

import java.util.Locale

/**
 * Conservative, offline-safe phrase matching for background wake-word results.
 * General ASR is noisy, so this matcher accepts only a short phrase at the
 * beginning of the final transcript; it never matches an arbitrary substring.
 */
object WakeWordMatcher {
    private const val MAX_TRANSCRIPT_CHARS = 48
    private const val MIN_CONFIDENCE = 0.62f

    val defaultPhrases: List<String> = listOf(
        "hey voice brain",
        "hay voice brain",
        "ဟေး ဗွိုက်စ် ဘရိန်း",
        "ဟေး ဗိုက်စ် ဘရိန်း",
        "ဗွိုက်စ် ဘရိန်း",
        "ဗိုက်စ် ဘရိန်း",
        "hey luma",
        "ဟေး လူမာ",
        "ဟေး လူးမား",
        "ဟေး လူမား",
        "ဟေ့ လူမာ",
        "ဟေ့ လူးမား"
    )

    fun matchesFinalTranscript(transcript: String): Boolean =
        matchesFinalTranscript(transcript, confidence = null)

    fun matchesFinalTranscript(transcript: String, confidence: Float?): Boolean {
        val normalized = normalize(transcript)
        if (normalized.isBlank() || normalized.length > MAX_TRANSCRIPT_CHARS) return false
        if (confidence != null && confidence.isFinite() && confidence < MIN_CONFIDENCE) return false
        val compact = normalized.replace(" ", "")
        return defaultPhrases.any { phrase ->
            val phraseCompact = normalize(phrase).replace(" ", "")
            if (phraseCompact.length < 6) return@any false
            if (compact == phraseCompact) return@any true
            if (!compact.startsWith(phraseCompact) ||
                compact.length - phraseCompact.length !in 1..12) return@any false
            if (phraseCompact.last() !in 'a'..'z') return@any true

            // Use the original spacing to distinguish "luma stop" from "lumatic".
            val phraseEnd = normalized.indices.filter { normalized[it] != ' ' }
                .get(phraseCompact.lastIndex) + 1
            val next = normalized[phraseEnd]
            next !in 'a'..'z' && next !in '0'..'9'
        }
    }

    fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\u200B၊။၊]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
