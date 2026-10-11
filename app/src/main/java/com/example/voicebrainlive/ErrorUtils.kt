package com.example.voicebrainlive

import android.content.Context
import org.json.JSONObject

/**
 * Converts raw API/network errors into user-friendly messages.
 */
object ErrorUtils {
    private const val FALLBACK = "အမှား ဖြစ်သွားပါပြီ။ ပြန်ကြိုးစားပါ။"
    private const val FALLBACK_SHORT = "အမှား ဖြစ်သွားပါပြီ။"

    /**
     * Resolves raw error message using localized string resources.
     */
    fun toUserMessage(context: Context, raw: String): String {
        if (raw.isBlank()) return context.getString(R.string.error_fallback_short)
        if (raw.trimStart().startsWith("{")) return parseJsonError(context, raw) ?: context.getString(R.string.error_fallback_short)

        val resId = when {
            raw.contains("API_KEY_REQUIRED", ignoreCase = true) ->
                R.string.error_api_key_required
            raw.contains("SpotifyConnectionTerminatedException", ignoreCase = true) ->
                R.string.error_spotify_terminated
            raw.contains("UserNotAuthorizedException", ignoreCase = true) ->
                R.string.error_spotify_unauthorized
            raw.contains("SpotifyRemoteServiceException", ignoreCase = true) ->
                R.string.error_spotify_remote
            raw.contains("Recognizer", ignoreCase = true) && raw.contains("error", ignoreCase = true) ->
                R.string.error_mic_failed
            raw.contains("Audio recording", ignoreCase = true) ->
                R.string.error_mic_in_use
            raw.contains("API key not valid", ignoreCase = true) || raw.contains("API key invalid", ignoreCase = true) ->
                R.string.error_api_key_invalid
            raw.contains("object_not_found", ignoreCase = true) ->
                R.string.error_notion_db_not_found
            raw.contains("unauthorized", ignoreCase = true) || raw.contains("invalid token", ignoreCase = true) ->
                R.string.error_notion_invalid_token
            raw.contains("validation_error", ignoreCase = true) ->
                R.string.error_validation
            raw.contains("Failed to connect", ignoreCase = true) || raw.contains("Failed to connect to", ignoreCase = true) ->
                R.string.error_cannot_connect_server
            raw.contains("Software caused connection abort", ignoreCase = true) || raw.contains("Connection reset", ignoreCase = true) || raw.contains("Broken pipe", ignoreCase = true) ->
                R.string.error_connection_aborted
            raw.contains("403", ignoreCase = true) || raw.contains("Forbidden", ignoreCase = true) || raw.contains("PERMISSION_DENIED", ignoreCase = true) ->
                R.string.error_api_forbidden
            raw.contains("503", ignoreCase = true) || raw.contains("Service Unavailable", ignoreCase = true) || raw.contains("500", ignoreCase = true) || raw.contains("Internal Server Error", ignoreCase = true) ->
                R.string.error_server_temporary_unavailable
            raw.contains("network", ignoreCase = true) || raw.contains("Unable to resolve host", ignoreCase = true) ->
                R.string.error_no_internet
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                R.string.error_timeout
            raw.contains("Closed: 1000", ignoreCase = true) || raw.contains("WebSocket Closed: 1000", ignoreCase = true) ->
                R.string.error_connection_closed
            raw.contains("SocketException", ignoreCase = true) ->
                R.string.error_connection_lost
            raw.contains("429", ignoreCase = true) || raw.contains("rate limit", ignoreCase = true) ->
                R.string.error_rate_limited
            raw.contains("Internal error occurred", ignoreCase = true) || raw.contains("1011", ignoreCase = true) ->
                R.string.error_gemini_server
            raw.contains("Unknown error", ignoreCase = true) ->
                R.string.error_fallback
            raw.contains("error", ignoreCase = true) ->
                return parseJsonError(context, raw) ?: context.getString(R.string.error_fallback)
            else -> R.string.error_fallback
        }
        return context.getString(resId)
    }

    /**
     * Fallback to raw string-based mapping for unit tests that do not have Android Context.
     */
    fun toUserMessage(raw: String): String {
        if (raw.isBlank()) return FALLBACK_SHORT
        if (raw.trimStart().startsWith("{")) return parseJsonError(raw) ?: FALLBACK_SHORT

        return when {
            raw.contains("API_KEY_REQUIRED", ignoreCase = true) ->
                "API Key လိုအပ်ပါတယ်။ Settings မှာ Gemini API Key ထည့်ပါ။"
            raw.contains("SpotifyConnectionTerminatedException", ignoreCase = true) ->
                "Spotify ချိတ်ဆက်မှု ပြတ်သွားပါပြီ။ Spotify App ကို ဖွင့်ထားပါ။"
            raw.contains("UserNotAuthorizedException", ignoreCase = true) ->
                "Spotify အသုံးပြုခွင့် မရှိသေးပါ။ Settings မှာ ချိတ်ဆက်ပေးပါ။"
            raw.contains("SpotifyRemoteServiceException", ignoreCase = true) ->
                "Spotify App မှာ ပြဿနာရှိနေပါတယ်။ Spotify ကို ပိတ်ပြီး ပြန်ဖွင့်ကြည့်ပါ။"
            raw.contains("Recognizer", ignoreCase = true) && raw.contains("error", ignoreCase = true) ->
                "အသံဖမ်းယူမှု အဆင်မပြေပါ။ ခဏနေရင်မှ ပြန်ကြိုးစားပါ။"
            raw.contains("Audio recording", ignoreCase = true) ->
                "မိုက်ကရိုဖုန်းကို အခြား App က သုံးနေပါတယ်။"
            raw.contains("API key not valid", ignoreCase = true) || raw.contains("API key invalid", ignoreCase = true) ->
                "API Key မမှန်ပါ။ Settings မှာ ပြန်ထည့်ပါ။"
            raw.contains("object_not_found", ignoreCase = true) ->
                "Notion database မတွေ့ပါ။ Database ကို integration နဲ့ share ပါ။"
            raw.contains("unauthorized", ignoreCase = true) || raw.contains("invalid token", ignoreCase = true) ->
                "Notion token မမှန်ပါ။ Settings မှာ integration token ပြန်ထည့်ပါ။"
            raw.contains("validation_error", ignoreCase = true) ->
                "အချက်အလက် မမှန်ပါ။ ပြန်စစ်ပါ။"
            raw.contains("Failed to connect", ignoreCase = true) || raw.contains("Failed to connect to", ignoreCase = true) ->
                "ဆာဗာနှင့် ချိတ်ဆက်၍မရပါ။ အင်တာနက်ကို စစ်ဆေးပါ။"
            raw.contains("Software caused connection abort", ignoreCase = true) || raw.contains("Connection reset", ignoreCase = true) || raw.contains("Broken pipe", ignoreCase = true) ->
                "အင်တာနက်ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်။"
            raw.contains("403", ignoreCase = true) || raw.contains("Forbidden", ignoreCase = true) || raw.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "API Key ခွင့်ပြုချက်မရှိပါ။ Settings တွင် API Key ပြန်စစ်ပါ။"
            raw.contains("503", ignoreCase = true) || raw.contains("Service Unavailable", ignoreCase = true) || raw.contains("500", ignoreCase = true) || raw.contains("Internal Server Error", ignoreCase = true) ->
                "ဆာဗာ ယာယီအလုပ်မလုပ်ပါ။ ခဏစောင့်ပြီး ပြန်ကြိုးစားပါ။"
            raw.contains("network", ignoreCase = true) || raw.contains("Unable to resolve host", ignoreCase = true) ->
                "အင်တာနက် ချိတ်ဆက်ပါ။"
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "အချိန်ကျော်သွားပါပြီ။ ပြန်ကြိုးစားပါ။"
            raw.contains("Closed: 1000", ignoreCase = true) || raw.contains("WebSocket Closed: 1000", ignoreCase = true) ->
                "ချိတ်ဆက်မှု ပိတ်သွားပါပြီ။"
            raw.contains("SocketException", ignoreCase = true) ->
                "ချိတ်ဆက်မှု ပြတ်သွားပါပြီ။ ပြန်ကြိုးစားပါ။"
            raw.contains("429", ignoreCase = true) || raw.contains("rate limit", ignoreCase = true) ->
                "အသုံးပြုမှု များသွားပါပြီ။ ခဏစောင့်ပြီး ပြန်ကြိုးစားပါ။"
            raw.contains("Internal error occurred", ignoreCase = true) || raw.contains("1011", ignoreCase = true) ->
                "Gemini ဆာဗာမှာ ပြဿနာရှိပါသည်။ ခဏလောက် စောင့်ပြီး ပြန်ကြိုးစားပါ။"
            raw.contains("Unknown error", ignoreCase = true) ->
                FALLBACK
            raw.trimStart().startsWith("{") ->
                parseJsonError(raw) ?: FALLBACK_SHORT
            raw.contains("error", ignoreCase = true) ->
                parseJsonError(raw) ?: FALLBACK
            else -> FALLBACK
        }
    }

    private fun parseJsonError(context: Context, raw: String): String? {
        if (raw.contains("\"code\":404")) return context.getString(R.string.error_source_not_found)
        if (raw.contains("\"code\":401") || raw.contains("API key", ignoreCase = true)) return context.getString(R.string.error_api_key_invalid)

        return try {
            val json = JSONObject(raw)
            val err = json.optJSONObject("error") ?: return null
            val msg = err.optString("message", "")
            val code = err.optInt("code", 0)
            when {
                msg.contains("API key", ignoreCase = true) -> context.getString(R.string.error_api_key_invalid)
                code == 401 -> context.getString(R.string.error_api_key_invalid)
                code == 404 -> context.getString(R.string.error_source_not_found)
                msg.isNotBlank() -> msg.take(80)
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.d("ErrorUtils", "Ignored JSON parse error: ${e.message}")
            null
        }
    }

    private fun parseJsonError(raw: String): String? {
        if (raw.contains("\"code\":404")) return "ရှာဖွေနေသော အချက်အလက် မတွေ့ရှိပါ။"
        if (raw.contains("\"code\":401") || raw.contains("API key", ignoreCase = true)) return "API Key မှားယွင်းနေပါသည်။ ပြန်လည်စစ်ဆေးပေးပါ။"

        return try {
            val json = JSONObject(raw)
            val err = json.optJSONObject("error") ?: return null
            val msg = err.optString("message", "")
            val code = err.optInt("code", 0)
            when {
                msg.contains("API key", ignoreCase = true) -> "API Key မှားယွင်းနေပါသည်။ ပြန်လည်စစ်ဆေးပေးပါ။"
                code == 401 -> "API Key မှားယွင်းနေပါသည်။ ပြန်လည်စစ်ဆေးပေးပါ။"
                code == 404 -> "ရှာဖွေနေသော အချက်အလက် မတွေ့ရှိပါ။"
                msg.isNotBlank() -> msg.take(80)
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.d("ErrorUtils", "Ignored JSON parse error: ${e.message}")
            null
        }
    }
}
