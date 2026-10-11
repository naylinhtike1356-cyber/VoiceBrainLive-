package com.example.voicebrainlive.commands

/**
 * Phase 2: Logic for parsing raw text and extracting commands, targets, and values.
 * Extracted from OfflineCommandMatcher to enable unit testing.
 */
object CommandParser {

    /**
     * Parses the given text to identify potential commands.
     * @return Triple of (commandType, target, value) or null if no match.
     */
    fun parse(text: String, offlineMap: Map<String, Triple<String, String?, String?>>): Triple<String, String?, String?>? {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return null

        if (isTimeQuery(normalized)) {
            return Triple("get_current_time", null, "time")
        }
        if (isDateQuery(normalized)) {
            return Triple("get_current_time", null, "date")
        }

        val volMatch = Regex("(?:အသံ|volume)\\s*([၀-၉0-9]+)|([၀-၉0-9]+)\\s*(?:ရာခိုင်နှုန်း|%|percent)").find(normalized)
        if (volMatch != null && (normalized.contains("အသံ") || normalized.contains("volume") || normalized.contains("ရာခိုင်နှုန်း") || normalized.contains("percent"))) {
            val num = normalizeDigits((volMatch.groupValues[1].ifBlank { volMatch.groupValues[2] }).replace(" ", ""))
            if (num.isNotBlank()) return Triple("set_volume", null, num)
        }

        offlineMap[normalized]?.let { return it }

        if (normalized.startsWith("open ") || normalized.startsWith("ဖွင့် ")) {
            val appName = normalized.removePrefix("open ").removePrefix("ဖွင့် ").trim()
            if (appName.isNotBlank()) return Triple("open_app", appName, null)
        }

        for ((phrase, result) in offlineMap) {
            if (normalized.contains(phrase)) return result
        }

        return null
    }

    private fun isTimeQuery(text: String): Boolean {
        return text.contains("ဘယ်နှစ်နာရီ") || text.contains("what time") ||
            text.contains("အခုအချိန်") || text.contains("အချိန်ပြော") ||
            ((text.contains("အချိန်") || text.contains("နာရီ")) && (text.contains("လဲ") || text.contains("ဘယ်") || text.contains("ပြော")))
    }

    private fun isDateQuery(text: String): Boolean {
        return text.contains("ဒီနေ့ဘယ်နေ့") || text.contains("ဘယ်နေ့လဲ") ||
            text.contains("ရက်နေ့") || text.contains("what day") || text.contains("ရက်စွဲပြော")
    }

    private fun normalizeDigits(text: String): String {
        val map = mapOf(
            '၀' to '0',
            '၁' to '1',
            '၂' to '2',
            '၃' to '3',
            '၄' to '4',
            '၅' to '5',
            '၆' to '6',
            '၇' to '7',
            '၈' to '8',
            '၉' to '9'
        )
        return buildString(text.length) {
            text.forEach { append(map[it] ?: it) }
        }
    }
}
