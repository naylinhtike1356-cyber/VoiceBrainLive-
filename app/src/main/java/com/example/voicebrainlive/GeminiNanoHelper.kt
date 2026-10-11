package com.example.voicebrainlive

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline AI Helper - Provides intelligent responses when offline.
 * Uses local logic for time, date, math, greetings, help. Not the on-device Gemini Nano model.
 * Named "GeminiNanoHelper" for compatibility; consider renaming to OfflineAIHelper.
 */
class GeminiNanoHelper(private val context: Context) {

    companion object {
        const val TAG = "OfflineAI"

        fun isSupported(): Boolean = true
    }
    
    private var isInitialized = false
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        isInitialized = true
        Log.d(TAG, "Offline AI initialized")
        true
    }
    
    fun isReady(): Boolean = isInitialized
    
    /**
     * Generate response for offline queries
     */
    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        getOfflineResponse(prompt)
    }
    
    private fun getOfflineResponse(prompt: String): String {
        val lower = prompt.lowercase().trim()
        
        return when {
            // Greetings
            lower.contains("hello") || lower.contains("hi ") || lower == "hi" ||
            lower.contains("မင်္ဂလာ") || lower.contains("ဟယ်လို") -> 
                "မင်္ဂလာပါ! Offline mode မှာ ရှိနေပါတယ်။ 'အကူအညီ' လို့ ပြောပြီး သုံးလို့ရတဲ့ commands တွေ ကြည့်နိုင်ပါတယ်။"
            
            // Time queries (Settings timezone, 12h: မနက် ၉ နာရီ, ညနေ ၂ နာရီ မိနစ် ၃၀)
            lower.contains("time") || lower.contains("အချိန်") || 
            lower.contains("ဘယ်နှစ်နာရီ") || lower.contains("နာရီ") -> {
                val tz = SettingsActivity.getTimezone(context)
                val cal = java.util.Calendar.getInstance(tz)
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = cal.get(java.util.Calendar.MINUTE)
                val (period, h12) = when {
                    hour == 0 -> Pair("မနက်", 12)
                    hour < 12 -> Pair("မနက်", hour)
                    hour == 12 -> Pair("ညနေ", 12)
                    else -> Pair("ညနေ", hour - 12)
                }
                val timeStr = if (minute == 0) "$period $h12 နာရီ" else "$period $h12 နာရီ မိနစ် $minute"
                "အခု အချိန်က $timeStr ပါ။"
            }
            
            // Date queries (uses Settings timezone)
            lower.contains("date") || lower.contains("ရက်စွဲ") || 
            lower.contains("ဘယ်နေ့") || lower.contains("ဒီနေ့") -> {
                val tz = SettingsActivity.getTimezone(context)
                val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale("my"))
                fmt.timeZone = tz
                val date = fmt.format(java.util.Date())
                "ဒီနေ့က $date ပါ။"
            }
            
            // Day of week (uses Settings timezone)
            lower.contains("what day") || lower.contains("နေ့") -> {
                val tz = SettingsActivity.getTimezone(context)
                val fmt = java.text.SimpleDateFormat("EEEE", java.util.Locale("my"))
                fmt.timeZone = tz
                val day = fmt.format(java.util.Date())
                "ဒီနေ့က $day ပါ။"
            }
            
            // Weather (offline - can't get real data)
            lower.contains("weather") || lower.contains("ရာသီဥတု") || 
            lower.contains("မိုးရွာ") || lower.contains("နေပူ") ->
                "Offline mode မှာ ရာသီဥတု မသိနိုင်ပါ။ အင်တာနက် ချိတ်ဆက်ပါ။"
            
            // Math calculations
            lower.contains("+") || lower.contains("-") || lower.contains("*") || lower.contains("/") ||
            lower.contains("×") || lower.contains("÷") ||
            lower.contains("ပေါင်း") || lower.contains("နုတ်") || 
            lower.contains("မြှောက်") || lower.contains("စား") ||
            lower.contains("plus") || lower.contains("minus") ||
            lower.contains("times") || lower.contains("divided") -> {
                tryCalculate(prompt) ?: "တွက်ချက်မှု မအောင်မြင်ပါ။ နမူနာ: \"5 + 3\" သို့မဟုတ် \"10 * 2\""
            }
            
            // Help / Commands list
            lower.contains("help") || lower.contains("အကူအညီ") || 
            lower.contains("command") || lower.contains("ဘာတွေလုပ်လို့ရ") -> {
                """Offline mode မှာ သုံးလို့ရတဲ့ commands:

📞 ဖုန်းခေါ် — "ဖုန်းခေါ်" / "call"
💡 မီးအိမ် — "မီးအိမ်ဖွင့်" / "flashlight"
📷 ကင်မရာ — "ကင်မရာဖွင့်" / "camera"
🔊 အသံ — "အသံတိုး" / "volume up"
📶 WiFi — "WiFi ဖွင့်/ပိတ်"
📱 Bluetooth — "Bluetooth ဖွင့်/ပိတ်"
⚙️ Settings — "settings ဖွင့်"
🎵 သီချင်း — "music" / "youtube"
📧 Email — "gmail ဖွင့်"
🕐 အချိန် — "ဘယ်နှစ်နာရီလဲ"
📅 ရက်စွဲ — "ဒီနေ့ဘယ်နေ့လဲ"
🔢 ဂဏန်း — "5 + 3" / "10 * 2"

အင်တာနက်ချိတ်ရင် AI ပိုကောင်းပါတယ်!"""
            }
            
            // Thank you
            lower.contains("thank") || lower.contains("ကျေးဇူး") ->
                "ကျေးဇူးတင်ပါတယ်! နောက်ထပ် ကူညီစရာရှိရင် ပြောပါ။"
            
            // Goodbye
            lower.contains("bye") || lower.contains("သွားတော့မယ်") || lower.contains("ဘိုင်") ->
                "ဘိုင်ဘိုင်! နောက်မှ တွေ့မယ်နော်။"
            
            // Who are you
            lower.contains("who are you") || lower.contains("နင်ဘယ်သူလဲ") || 
            lower.contains("ဘယ်သူလဲ") || lower.contains("နာမည်") ->
                "ကျွန်တော်က Voice Brain ပါ။ သင့်ရဲ့ AI Assistant ပါ။ Offline mode မှာတောင် အကူအညီပေးနိုင်ပါတယ်။"
            
            // How are you
            lower.contains("how are you") || lower.contains("နေကောင်းလား") || 
            lower.contains("ဘယ်လိုလဲ") ->
                "ကောင်းပါတယ်! ကျေးဇူးတင်ပါတယ်။ ဘာကူညီပေးရမလဲ?"
            
            // Yes/No responses
            lower == "yes" || lower == "ဟုတ်" || lower == "အင်း" ->
                "ကောင်းပါပြီ! ဘာဆက်လုပ်ချင်ပါသလဲ?"
            
            lower == "no" || lower == "မဟုတ်" || lower == "ဟင့်အင်း" ->
                "ရပါတယ်။ တခြား ကူညီစရာ ရှိရင် ပြောပါ။"
            
            // Default response
            else -> "Offline mode မှာ ရှိနေပါတယ်။ 'အကူအညီ' လို့ ပြောပြီး သုံးလို့ရတဲ့ commands တွေ ကြည့်နိုင်ပါတယ်။ အင်တာနက် ချိတ်ဆက်ရင် AI ပိုကောင်းပါမယ်။"
        }
    }
    
    /**
     * Simple calculator for offline math
     */
    private fun tryCalculate(input: String): String? {
        return try {
            val cleaned = input
                .replace("×", "*")
                .replace("÷", "/")
                .replace("ပေါင်း", "+")
                .replace("နုတ်", "-")
                .replace("မြှောက်", "*")
                .replace("စား", "/")
                .replace("plus", "+")
                .replace("minus", "-")
                .replace("times", "*")
                .replace("divided by", "/")
                .replace(Regex("[^0-9+\\-*/.]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            
            // Pattern: number operator number
            val pattern = Regex("(\\d+\\.?\\d*)\\s*([+\\-*/])\\s*(\\d+\\.?\\d*)")
            val match = pattern.find(cleaned) ?: return null
            
            val (_, num1Str, op, num2Str) = match.groupValues
            val num1 = num1Str.toDouble()
            val num2 = num2Str.toDouble()
            
            val result = when (op) {
                "+" -> num1 + num2
                "-" -> num1 - num2
                "*" -> num1 * num2
                "/" -> if (num2 != 0.0) num1 / num2 else return "သုညနဲ့ စားလို့ မရပါ။"
                else -> return null
            }
            
            val resultStr = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                String.format("%.2f", result)
            }
            
            "$num1Str $op $num2Str = $resultStr"
        } catch (e: Exception) {
            null
        }
    }
    
    fun release() {
        isInitialized = false
    }
}
