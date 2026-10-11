package com.example.voicebrainlive.usecases

object ProactivePromptHelper {

    fun buildPrompt(proactive: String, type: String?): String {
        return when (type) {
            "calendar_partner" -> "PROACTIVE CALENDAR ALERT: $proactive. Skip greetings, start in Burmese."
            "location_home" -> "PROACTIVE LOCATION GREETING: Home. Warm welcome in Burmese."
            "routine_weather" -> "PROACTIVE WEATHER ROUTINE: Tell the user today's weather. You MUST call the 'get_weather' tool (command_type='get_weather') immediately to fetch the forecast, format the result naturally in Burmese, and say it to the user. Start immediately by calling 'get_weather'."
            "routine_health" -> "PROACTIVE HEALTH ROUTINE: It is time for your Samsung Health Smartwatch report! You MUST call the 'get_health_summary' tool (command_type='get_health_summary', target='all') immediately to fetch the user's sleep, activity, workout, and heart rate telemetry. Summarize their health metrics in natural, warm Burmese as a personal assistant, and offer actionable personalized health advice. Start immediately by calling 'get_health_summary'."
            "routine_sleep" -> "PROACTIVE BEDTIME ROUTINE: It is time to sleep and rest! Greet the user in a warm, soothing Burmese voice. Remind them to rest well for good health, call 'get_health_summary' (target='sleep') if they want to review sleep goals, or ask if they'd like quiet relaxation music. Start immediately in warm Burmese."
            "routine_music" -> "PROACTIVE MUSIC ROUTINE: It is time for music. Say to the user in Burmese that it's time for music. Suggest playing some music or ask if they want to listen to something specific. Use the 'play_music' tool (command_type='play_music') if they say yes. Start naturally in Burmese."
            "routine_daily_log" -> "PROACTIVE DAILY LOG ROUTINE: Ask the user if they want to check today's salary record/log. If they want to check it, call the 'get_salary_data' tool. Start naturally in Burmese."
            "routine_reminder" -> "PROACTIVE ROUTINE REMINDER: $proactive. Announce this reminder to the user naturally in Burmese, and ask if they need anything else."
            "routine_command" -> "PROACTIVE ROUTINE COMMAND: $proactive. Announce that you are starting this action, call the corresponding tool if applicable, and report the results. Start naturally in Burmese."
            "messenger_reply" -> {
                var sender = "Someone"
                var message = "a message"
                var appLabel = "Messenger"
                
                if (proactive.startsWith("MESSENGER_REPLY_V2:")) {
                    try {
                        val encoded = proactive.removePrefix("MESSENGER_REPLY_V2:")
                        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
                        // Format: sender=NAME&msg=TEXT&pkg=PKG&app=APP
                        val pairs = decoded.split("&")
                        sender = pairs.find { it.startsWith("sender=") }?.substringAfter("sender=")?.let {
                            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        } ?: "Someone"
                        message = pairs.find { it.startsWith("msg=") }?.substringAfter("msg=")?.let {
                            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        } ?: "no text"
                        appLabel = pairs.find { it.startsWith("app=") }?.substringAfter("app=")?.let {
                            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        } ?: "Messenger"
                    } catch (e: Exception) {
                        sender = "Participant"
                    }
                } else {
                    // Legacy split
                    val parts = proactive.split(":", limit = 4)
                    sender = parts.getOrNull(1) ?: "Someone"
                    message = parts.getOrNull(2) ?: "a message"
                }

                val toolName = when {
                    appLabel.equals("WhatsApp", ignoreCase = true) -> "send_whatsapp"
                    appLabel.contains("SMS", ignoreCase = true) -> "send_sms"
                    else -> "send_messenger"
                }

                val naturalBurmeseBase = if (message.isNotBlank() && message != "no text") {
                    "အစ်ကိုခင်ဗျာ၊ $appLabel ကနေ $sender က '$message' လို့ စာပို့ထားပါတယ်ခင်ဗျာ။"
                } else {
                    "အစ်ကိုခင်ဗျာ၊ $appLabel ကနေ $sender ဆီက မက်ဆေ့ခ်ျ ရောက်ရှိလာပါတယ်ခင်ဗျာ။"
                }

                """
                CRITICAL INSTRUCTION - PERSONAL ASSISTANT MESSAGE NOTIFICATION:
                You are the user's dedicated personal AI assistant speaking warmly, politely, and naturally in Burmese (စက်ရုပ်အသံလုံးဝမဟုတ်ဘဲ လူသားကိုယ်ပိုင်လက်ထောက်ပီပီ သဘာဝကျကျ အသိပေးပြောကြားပါ).
                Announce this incoming message to the user:
                App: $appLabel
                Sender: $sender
                Message: $message

                Preferred Natural Phrasing:
                "$naturalBurmeseBase"

                STRICT ZERO-TOLERANCE RULES:
                1. DO NOT translate the message '$message' into English if it is already in Burmese. Keep the sender's original words authentic and clear.
                2. If the message is in English or foreign language, convey what $sender sent naturally and clearly in Burmese so the user understands effortlessly.
                3. Deliver the announcement like a real respectful personal assistant ("အစ်ကိုခင်ဗျာ / လူကြီးမင်းခင်ဗျာ ...").
                4. CRITICAL: DO NOT CALL ANY TOOL. DO NOT call $toolName or any other tool on your own without explicit user command.
                5. Stop speaking immediately after announcing and wait silently.
                6. STRICT ZERO-UNSOLICITED SENDING: If the user stays silent, says nothing, makes background noise, or dismisses ("ရပြီ", "တော်ပြီ", "မလိုဘူး", "မပို့နဲ့", "cancel"), NEVER call $toolName or any reply tool. Stay completely silent.
                7. EXACT USER WORDS ONLY: When the user explicitly tells you to reply (e.g. "အပြင်ရောက်နေတယ် လို့ ပြန်ပြောပေးပါ"), extract ONLY the user's exact spoken words ("အပြင်ရောက်နေတယ်") as the 'value' argument. DO NOT paraphrase, DO NOT embellish, DO NOT add greetings like "မင်္ဂလာပါ" or "ခင်ဗျာ", and DO NOT add AI pleasantries like "သူက ပြောခိုင်းလိုက်ပါတယ်". Send ONLY what the user said word-for-word.
                8. NEVER ECHO: NEVER send the incoming sender's message ('$message') back to them.
                """.trimIndent()
            }
            "app_notification" -> {
                var appLabel = "Notification"
                var title = ""
                var message = ""
                
                if (proactive.startsWith("APP_NOTIFICATION_V1:")) {
                    try {
                        val encoded = proactive.removePrefix("APP_NOTIFICATION_V1:")
                        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
                        val pairs = decoded.split("&")
                        appLabel = pairs.find { it.startsWith("app=") }?.substringAfter("app=")?.let {
                            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        } ?: "App"
                        title = pairs.find { it.startsWith("title=") }?.substringAfter("title=")?.let {
                            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        } ?: ""
                        message = pairs.find { it.startsWith("msg=") }?.substringAfter("msg=")?.let {
                            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                        } ?: ""
                    } catch (e: Exception) {
                        message = proactive
                    }
                } else {
                    message = proactive
                }

                val content = if (title.isNotBlank() && message.isNotBlank() && !title.equals(message, ignoreCase = true)) {
                    "$title - $message"
                } else {
                    title.ifBlank { message }
                }

                val naturalBurmeseBase = if (content.isNotBlank()) {
                    "အစ်ကိုခင်ဗျာ၊ $appLabel ကနေ '$content' ဆိုတဲ့ အသိပေးချက် ရောက်ရှိပါတယ်ခင်ဗျာ။"
                } else {
                    "အစ်ကိုခင်ဗျာ၊ $appLabel ကနေ အသိပေးချက် အသစ်တစ်ခု ရောက်ရှိလာပါတယ်ခင်ဗျာ။"
                }

                """
                CRITICAL INSTRUCTION - PERSONAL ASSISTANT APP NOTIFICATION:
                You are the user's dedicated personal AI assistant speaking warmly, politely, and naturally in Burmese (စက်ရုပ်အသံလုံးဝမဟုတ်ဘဲ လူသားကိုယ်ပိုင်လက်ထောက်ပီပီ သဘာဝကျကျ အသိပေးပြောကြားပါ).
                Announce this app notification to the user:
                App: $appLabel
                Content: $content

                Preferred Natural Phrasing:
                "$naturalBurmeseBase"

                STRICT ZERO-TOLERANCE RULES:
                1. DO NOT sound like a cold robot reading raw code or template strings. Speak fluidly, warmly, and respectfully in natural colloquial Burmese ("အစ်ကိုခင်ဗျာ / လူကြီးမင်းခင်ဗျာ ...").
                2. If the notification is from a bank or payment app (e.g. KBZPay, Wave, AYA, K-Bank), summarize the transaction clearly and accurately in Burmese.
                3. If the content is in English or foreign language, explain what it means naturally in Burmese so the user understands immediately. If in Burmese, preserve the meaning accurately.
                4. CRITICAL: DO NOT CALL ANY TOOL. Wait silently after announcing.
                """.trimIndent()
            }
            else -> "CRITICAL: SKIP GREETINGS. Content: $proactive"
        }
    }
}
