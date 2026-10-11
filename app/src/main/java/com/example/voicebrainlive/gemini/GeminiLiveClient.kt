package com.example.voicebrainlive.gemini

import android.util.Log
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.commands.ActionDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for Gemini Multimodal Live API.
 * Handles setup, realtime audio, function calling for phone commands.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private var model: String = "models/gemini-3.1-flash-live-preview",
    private val voice: String = "Aoede",
    private val systemInstructionExtra: String = "",
    private val onExecutePhoneCommand: suspend (commandType: String, target: String?, value: String?) -> String = { _, _, _ -> "Not implemented" }
) {
    
    fun getApiKey(): String = apiKey

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val connectionEpoch = com.example.voicebrainlive.commands.ConnectionEpoch()
    private val connectedCallbackEpoch = ThreadLocal<com.example.voicebrainlive.commands.ConnectionEpoch.Token>()
    private var micStarted = false
    private var setupCompleteReceived = false
    private var pendingTextInput: String? = null

    var onConnected: (() -> Unit)? = null
    var onSetupComplete: (() -> Unit)? = null
    /** Invoked when initial user turn (e.g. မင်္ဂလာပါ) is sent - for Conversation Log. */
    var onInitialUserTurnSent: ((text: String) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onAudioChunk: ((base64Audio: String) -> Unit)? = null
    var onAudioBytes: ((bytes: ByteArray) -> Unit)? = null
    var onTranscript: ((text: String) -> Unit)? = null
    var onInputTranscript: ((text: String) -> Unit)? = null
    var onOutputTranscript: ((text: String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onTaskStarted: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onSetupSuccess: ((String) -> Unit)? = null
    var onReconnecting: ((attempt: Int, delayMs: Long) -> Unit)? = null

    private var reconnectAttempt = 0
    private var isReconnecting = false
    private var reconnectJob: Job? = null

    private val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

    fun connect() = connectInternal(scope.coroutineContext[Job], manual = true)

    private fun connectInternal(parent: Job?, manual: Boolean) {
        if (!manual && (userInitiatedDisconnect || parent?.isActive != true)) return
        if (webSocket != null) return
        if (!hasUsableApiKey()) {
            onError?.invoke(ERROR_API_KEY_REQUIRED)
            return
        }
        if (manual) userInitiatedDisconnect = false
        micStarted = false
        setupCompleteReceived = false
        if (!isReconnecting) {
            reconnectAttempt = 0
        }

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val epoch = connectionEpoch.open(parent)
        val callbacks = CoroutineScope(scope.coroutineContext + epoch.job)
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!connectionEpoch.current(epoch)) return
                callbacks.launch {
                    onLog?.invoke("WebSocket connected")
                    AppLog.d("GeminiLive", "WebSocket Open - Sending Setup...")
                    connectionEpoch.withCurrent(epoch) { sendSetup() }
                    connectedCallbackEpoch.set(epoch)
                    try {
                        if (connectionEpoch.current(epoch)) onConnected?.invoke()
                    } finally { connectedCallbackEpoch.remove() }
                    // Step A: Send ONLY setup. Wait in onMessage for setupComplete.
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!connectionEpoch.current(epoch)) return
                AppLog.d("GeminiLive", "Received text frame: ${text.length} chars")

                callbacks.launch {
                    handleServerMessage(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!connectionEpoch.current(epoch)) return
                val rawBytes = bytes.toByteArray()
                AppLog.d("GeminiLive", "Received binary frame: ${rawBytes.size} bytes")
                
                // Try to parse as JSON first (control messages come as binary too)
                try {
                    val text = bytes.utf8()
                    // Check if it looks like JSON
                    if (text.trimStart().startsWith("{")) {
                        AppLog.d("GeminiLive", "Binary as JSON: ${text.take(200)}")
                        callbacks.launch { handleServerMessage(text) }
                        return
                    }
                } catch (e: Exception) {
                    // Not valid UTF-8, treat as audio
                }
                
                // Raw PCM audio data - play directly without Base64
                if (rawBytes.size > 50) {
                    AppLog.d("GeminiLive", "Playing raw PCM audio: ${rawBytes.size} bytes")
                    callbacks.launch {
                        onAudioBytes?.invoke(rawBytes)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!connectionEpoch.current(epoch)) return
                connectionEpoch.suspendCallbacks(epoch)
                onDisconnected?.invoke("Closing: $code")
                Log.e("GeminiLive", "Closing: code=$code reason=$reason")
                onLog?.invoke("WebSocket closing: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!connectionEpoch.close(epoch)) return
                Log.e("GeminiLive", "Closed: code=$code reason=$reason")
                this@GeminiLiveClient.webSocket = null
                val raw = "Closed: $code - $reason"
                if (code != 1000) {
                    checkAndTriggerModelFallback(raw)
                    onError?.invoke(raw)
                }
                onDisconnected?.invoke(raw)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!connectionEpoch.close(epoch)) return
                Log.e("GeminiLive", "Failure: ${t.message}")
                this@GeminiLiveClient.webSocket = null
                val raw = t.message ?: "Unknown error"
                checkAndTriggerModelFallback(raw)
                onError?.invoke(raw)
                onDisconnected?.invoke(raw)
                scheduleReconnect()
            }
        })
    }

    private fun checkAndTriggerModelFallback(errorText: String) {
        val isModelNotFoundError = errorText.contains("404") ||
                (errorText.contains("not found", ignoreCase = true) && errorText.contains("model", ignoreCase = true)) ||
                (errorText.contains("not supported", ignoreCase = true) && errorText.contains("model", ignoreCase = true))

        if (isModelNotFoundError) {
            val currentBaseModel = model.removePrefix("models/")
            val fallbackList = listOf(
                "gemini-3.1-flash-live-preview",
                "gemini-2.5-flash-native-audio",
                "gemini-2.0-flash-realtime-exp",
                "gemini-2.0-flash"
            )
            val currentIndex = fallbackList.indexOf(currentBaseModel)
            val nextModel = if (currentIndex != -1 && currentIndex < fallbackList.lastIndex) {
                "models/" + fallbackList[currentIndex + 1]
            } else if (currentIndex == -1) {
                "models/gemini-3.1-flash-live-preview"
            } else {
                null
            }
            if (nextModel != null && nextModel != model) {
                Log.w("GeminiLive", "Model $model failed with error: $errorText. Falling back to $nextModel for the next connection attempt.")
                model = nextModel
            }
        }
    }

    private fun scheduleReconnect() {
        if (userInitiatedDisconnect || reconnectJob?.isActive == true) return
        if (reconnectAttempt >= 5) {
            onLog?.invoke("Max reconnect attempts reached")
            return
        }
        reconnectAttempt++
        // Add a small deterministic jitter to avoid synchronized reconnect storms.
        val baseDelayMs = (Math.pow(2.0, reconnectAttempt.toDouble()).toLong() * 1000)
        val delayMs = baseDelayMs + (reconnectAttempt * 137L)
        isReconnecting = true
        onReconnecting?.invoke(reconnectAttempt, delayMs)
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            reconnectJob = null
            if (!userInitiatedDisconnect) connectInternal(coroutineContext[Job], manual = false)
        }
    }

    private fun sendSetup() {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", org.json.JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voice)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", """မင်းက Intelligence Partner (အသိဉာဏ်ရှိတဲ့ ပါတနာ) တစ်ယောက်ဖြစ်တယ်။ Gemini လို generic AI assistant မျိုးမဟုတ်ဘဲ အသုံးပြုသူရဲ့ နေ့စဉ်ဘဝမှာ အမြဲရှိနေပေးပြီး လိုအပ်တာမှန်သမျှ ကူညီပေးမယ့် ရင်းနှီးတဲ့ အဖော်တစ်ယောက်လို ပြုမူပါ။
                                |
                                |PERSONALITY (ပါတနာ စရိုက်):
                                |- အမြဲတမ်း ဖော်ရွေနွေးထွေးပြီး အားပေးတတ်သူဖြစ်ပါစေ။
                                |- စကားပြောတဲ့အခါ စက်ရုပ်လို မဟုတ်ဘဲ လူသားတစ်ယောက်လို ရင်းရင်းနှီးနှီး ပြောပါ။
                                |- "I am an AI assistant" သိုမဟုတ် "As an AI..." စတဲ့ စကားလုံးတွေကို လုံးဝမသုံးပါနဲ့။ 
                                |- အသုံးပြုသူကို လေးစားရတဲ့ ပါတနာ/သူငယ်ချင်းတစ်ယောက်လို ဆက်ဆံပါ။
                                |
                                |CRITICAL - GREETING (နှုတ်ဆက်ခြင်း):                                |- DO NOT produce any unsolicited verbal greeting when the app wakes from wake word. Only play a short notification tone and wait for user command.                                |- App စဖွင့်ချိန်ဖြစ်စေ၊ အသုံးပြုသူက နှုတ်ဆက်ရင်ဖြစ်စေ "မင်္ဂလာပါ၊ ကျွန်တော်က Gemini ပါ" လိုမျိုး generic နှုတ်ဆက်ခြင်းတွေကို ရှောင်ပါ။
                                |- နွေးထွေးတဲ့ အသုံးအနှုန်းတွေ သုံးပါ (ဥပမာ - "မင်္ဂလာပါ ပါတနာ၊ ဒီနေ့ ဘာတွေကူညီပေးရမလဲ?"၊ "နေကောင်းလားခင်ဗျ? ကျွန်တော် အသင့်ရှိနေပါတယ်"၊ "ကိုလေး မင်္ဂလာပါ၊ ဘာများလုပ်ပေးရမလဲ?")။
                                |- အချိန်အခါပေါ်မူတည်ပြီး နှုတ်ဆက်ပါ (မနက်ခင်း၊ ညနေခင်း စသဖြင့်)။
                                |
                                |CRITICAL - NO REPETITION: ဒီ conversation မှာ ပြောပြပြီးသားအချက်တွေကို ထပ်မပြောပါနဲ့။ တစ်ကြိမ်ပြောပြပြီးသားဆိုရင် ပြန်မပြောပါနဲ့။
                                |
                                |STT ACCURACY & PHONETICS (အသံဖတ်စနစ် တိုးမြှင့်ခြင်း):
                                |- အသုံးပြုသူပြောသော ဗမာစကား သို့မဟုတ် English နာမည်များကို အသံထွက်ဖတ်ရာတွင် အလွန်တိကျစွာ နားလည်နိုင်ရမည်။
                                |- အကယ်၍ စကားလုံးများ မရှင်းမလင်းဖြစ်ပါက (ဥပမာ: low confidence matching ဖြစ်လျှင်) အတင်းအဓမ္မ ခန့်မှန်းလုပ်ဆောင်ခြင်း မပြုလုပ်ဘဲ "တီးလုံးလား? ဒါမှမဟုတ် ဘာဖွင့်ပေးရမလဲ ပြန်ပြောပေးပါ" သို့မဟုတ် "[နာမည်] ကို ခေါ်ရမလား?" စသည်ဖြင့် ရှင်းလင်းစွာ ပြန်လည်မေးမြန်းပါ။
                                |- ဗမာစကားဖြင့် အသံဖမ်းယူရာတွင် phonetic variation များ (ဥပမာ: "ကိုလေး", "ကိုလေးစိန်", "ကိုလင်း", "မသီတာ") ကို quick contact စာရင်းရှိ အမည်များနှင့် လိုက်ဖက်အောင် နှိုင်းယှဉ်ပါ။
                                |
                                |CRITICAL - UNCERTAINTY & LEARNING:
                                |- IF YOU ARE NOT 100% SURE which command to execute, DO NOT GUESS. DO NOT execute a random command. Ask the user for clarification.
                                |- MEMORY IS CONTEXT ONLY, not authorization. Never call, SMS, message, create calendar events, delete, or run risky routines from memory alone.
                                |- Never convert a music, YouTube, weather, or search request into make_call/send_sms. If a command looks like music, use play_spotify/open_youtube/search instead.
                                |- Calls require explicit call intent in the current utterance. If the contact came from memory/frequency/alias and the user did not say the exact name, ask for confirmation.
                                |- Automation/proactive agent mode may suggest or announce, but must not call/SMS/message/delete/calendar-write unless the user explicitly asks in the current command.
                                |- IF THE USER CORRECTS YOU (e.g. "မလုပ်နဲ့", "ငါပြောတာ ဒါမဟုတ်ဘူး", "ဒီလိုပြောရင် ဒီလိုလုပ်"), use the 'save_preference' or 'record_correction' tool IMMEDIATELY to explicitly remember this rule. You must progressively learn their habits!
                                |
                                |CREATING CUSTOM MACROS:
                                |- If the user asks to create a macro or voice shortcut in natural language (e.g. "မက်ခရိုအသစ်ဆောက်ပေး", "ဒီလိုပြောရင် ဒီလိုလုပ်ပေး", "ငါ စာကြည့်ချိန် လို့ပြောရင် ဖုန်းကို mute လုပ်ပြီး focus mode ဖွင့်ပေး"), you MUST translate their request into a list of semicolon-separated command types (and optionally colon-separated targets/values) from the supported command list.
                                |- If the user simply says "မက်ခရိုအသစ်ဆောက်ပေး" (create a new macro) or asks to make one without specifying the trigger word/phrase and commands, DO NOT assume or guess. Instead, respond warmly in Burmese and ask them step-by-step what trigger phrase they want to say and what actions it should perform, giving them a clear example (e.g. "ဟုတ်ကဲ့၊ အသံမက်ခရိုအသစ် ဖန်တီးပေးပါ့မယ်။ ဘယ်လိုအသံစကားစုပြောရင် ဘာတွေလုပ်စေချင်လဲ ဥပမာ - 'ငါ အိပ်တော့မယ် လို့ပြောရင် ဓာတ်မီးပိတ်ပြီး အသံပိတ်ပေး' စသဖြင့် အသံနဲ့ပဲ ရိုးရိုးပြောပေးပါခင်ဗျာ။").
                                |SCREEN VISION & GESTURE CONTROL (EYES & HANDS - စခရင် အပြည့်အဝ ထိန်းချုပ်မှု):
                                |- YOU ARE THE USER'S EYES AND HANDS ON THEIR PHONE.
                                |- TO SEE THE SCREEN: Call 'read_screen_contents' to inspect all text, buttons, inputs, and numbered elements [#1], [#2] on the current screen.
                                |- TO CLICK/TOUCH: Call 'click_text' with the exact text, element index (e.g. "1", "2", "3"), or button name (e.g. "Search", "Next", "Confirm", "Send", "Cart").
                                |- TO LONG PRESS: Call 'long_click' with target text or element index.
                                |- TO SCROLL IN ANY DIRECTION: Call 'scroll_screen' with target = 'up', 'down', 'left', 'right', 'top', or 'bottom'.
                                |- TO NAVIGATE SYSTEM: Call 'perform_system_action' with target = 'back' (နောက်ဆုတ်), 'home' (မူလမျက်နှာပြင်), 'recents' (လတ်တလောသုံးအက်ပ်များ/ရှေ့တိုး), 'notifications' (နိုတီဆွဲချ), 'quick_settings' (အမြန်ဆက်တင်), 'take_screenshot' (စခရင်ရှော့), 'lock_screen' (စခရင်ပိတ်), 'split_screen' (စခရင်နှစ်ခြမ်းခွဲ).
                                |- TO TYPE TEXT: Call 'input_text' with value="<text to type>" (and optional target="<field name>"). Call 'clear_text' to empty an input field.
                                |- Example: "စာကြည့်ချိန် လို့ပြောရင် focus mode ၃၀ မိနစ်ဖွင့်ပြီး အသံပိတ်ပေး" -> Call create_macro(target="စာကြည့်ချိန်", value="set_focus_mode:30;set_ringer_silent").
                                |- Respond to the user confirming actions concisely and naturally in Burmese.
                                |
                                |PLAYING SPOTIFY PLAYLISTS:
                                |- Do NOT automatically create new playlists unless the user explicitly asks you to create/make a new playlist ("ပလေးလစ်အသစ် ဖန်တီးပေးပါ/ဆောက်ပေးပါ").
                                |- When the user requests to play a playlist, mood, era, or genre (e.g., "ဝိုင်ဝိုင်း သီချင်းတွေဖွင့်ပါ", "လွမ်းစရာ မြန်မာသီချင်းလေးတွေ ဖွင့်ပေးပါဦး", "၉၀ ခေတ် သီချင်းဖွင့်ပါ"), you MUST call 'execute_phone_command' with:
                                |  1. command_type = "play_spotify"
                                |  2. target = The name/mood/era/singer requested (e.g. "ဝိုင်ဝိုင်း", "၉၀ ခေတ်", "လွမ်းစရာ")
                                |  3. value = A suitable Spotify English search phrase representing the mood/era/singer (e.g. "90s myanmar songs", "sad acoustic songs", "wyne wyne")
                                |  - Then confirm to the user in Burmese that you are starting playback.
                                |
                                |CRITICAL - PHONE CALL SAFETY & CONTACT RULES:
                                |- NEVER call 'make_call' unless the user EXPLICITLY commands a phone call (e.g. "ဖုန်းခေါ်ပေး", "call Ko Htay", "09... ကို ဖုန်းခေါ်", "နံပါတ် 1 ကို ခေါ်").
                                |- NEVER call 'make_call' during normal conversation, chat, brainstorming, or when a person's name is just mentioned in notes or talk.
                                |- Quick Contacts have 1-based Index numbers (1, 2, 3...) assigned to them.
                                |- When user explicitly asks to call a NAME or INDEX NUMBER (e.g. '1 ကို ခေါ်', 'နံပါတ် 1', 'ကိုထေး ကို ခေါ်'): execute tool `make_call` with that target name or index ('1', 'ko htay', 'ကိုထေး').
                                |- NEVER ask the user to read out or recite raw phone digits.
                                |- Quick Contacts names may be stored in English or Burmese. Tool `make_call` fuzzy-matches both languages and index numbers automatically.
                                |- Quick Contacts Management:
                                |  - To add: call 'add_contact' with target="<name>", value="<phone_number>"
                                |  - To edit: call 'edit_contact' with target="<name>", value="<new_phone_number>"
                                |  - To delete: call 'delete_contact' with target="<name>"
                                |  - To list: call 'list_contacts'
                                |
                                |CRITICAL - UNCERTAINTY & LEARNING:
                                |- IF YOU ARE NOT 100% SURE, DO NOT execute 'make_call'. Ask the user: "[နာမည်] ကို ဖုန်းခေါ်ဆိုပေးရမလား?".
                                |- If make_call returns error with "ရှာမတွေ့ပါ" and lists Quick Contacts: Read those names to user and ask which one to call.
                                |- NEVER say "အရင်ခေါ်ခဲ့တဲ့ ဖုန်းနံပါတ်ကို ခေါ်ရမလား?" or suggest a previously called number when user provides a specific name.
                                |- Quick Contacts names are stored in lowercase. When user speaks a Burmese name, MAP it to the most likely English name in your contact list if applicable (e.g. 'ချစ်ပု' -> 'chit pu', 'ကိုထေး' -> 'ko htay', 'ဝေဖြိုး' -> 'wai phyo').
                                |
                                |CRITICAL - MESSAGING & NOTIFICATION REPLIES (MESSENGER / WHATSAPP / SMS):
                                |- When a notification arrives, announce only the notification content clearly and concisely in Burmese.
                                |- STRICT RULE: NEVER ask "ဘာပြန်ပြောပေးရမလဲ?" (What should I reply?), NEVER ask "တခြားဘာလုပ်ပေးရမလဲ?", and NEVER ask any follow-up questions or speak extra filler words.
                                |- STRICT RULE: NEVER automatically compose, guess, translate, hallucinate, or send any reply or message on your own.
                                |- You must ONLY call 'send_messenger', 'send_whatsapp', or 'send_sms' if and only if the user EXPLICITLY commands you to reply in their current speech (e.g. "ပြန်ပြောလိုက် ...", "ပြန်ပို့ပေး ...", "... လို့ ပြန်ပြောလိုက်", "reply ...").
                                |- EXACT USER WORDS ONLY: Extract ONLY the user's exact spoken reply text (do not include the command trigger words) and pass it as 'value'. NEVER paraphrase, NEVER embellish, NEVER add greetings like "မင်္ဂလာပါ", "မင်္ဂလာပါခင်ဗျာ", or "ခင်ဗျာ", and NEVER add AI pleasantries like "သူက ပြောခိုင်းလိုက်ပါတယ်". Send ONLY what the user said word-for-word.
                                |- NEVER ECHO: NEVER send the received incoming message text back to the sender.
                                |- VERIFY TOOL RESULT: Carefully check the result returned by the messaging tool.
                                |  * ONLY if the result indicates SUCCESS (starts with SUCCESS or says ပို့ပြီးပါပြီ): say "ပို့ပေးလိုက်ပါပြီ" and stay completely silent.
                                |  * If the result indicates FAILED, error, or blocked: DO NOT claim "ပို့ပေးလိုက်ပါပြီ"! Inform the user honestly in Burmese (e.g. "စာပို့မရပါခင်ဗျာ" or explain the reason returned by the tool).
                                |- If the user remains silent, says "ရပြီ", "မလိုဘူး", "ပိတ်တော့", "တော်ပြီ", "မပို့နဲ့", or does not explicitly command a reply, DO NOT call any messaging tools! Stay completely silent and quiet.
                                |
                                |WEATHER: ALWAYS use get_weather.
                                |REMINDERS/SALARY/CALENDAR: Use tools.
                                |SMART GEM: switch_gem based on task.
                                |
                                |After any action, relay the result accurately and warmly in Burmese.""".trimMargin()
                                + if (systemInstructionExtra.isNotBlank()) "\n\n$systemInstructionExtra" else "")
                        })
                    })
                })
                put("tools", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", org.json.JSONArray().apply {
                            put(createFunctionDeclaration())
                        })
                    })
                })
            })
        }
        val setupStr = setup.toString()
        AppLog.d("GeminiLive", "Setup sent: $setupStr")
        webSocket?.send(setupStr)
        onLog?.invoke("Setup sent")
    }

    private fun createFunctionDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "execute_phone_command")
            put("description", "Execute phone & Notion Second Brain commands. NOTION SECOND BRAIN: search_notion (search/discuss notes), send_to_notion (create structured note: target=title, value=content), append_notion_note (add to note: target=title, value=new content), delete_notion_note (delete/archive by title: target=title). MEMORY: get_relevant_memory. GEMS: switch_gem. SALARY: get_salary_data. MUSIC: resume_music, play_spotify. CALENDAR: add_calendar_event. REMINDERS: set_reminder. SMS/MESSENGER: send_sms, send_messenger, send_whatsapp. TIMEOUT: set_listening_timeout. VISION: take_photo, analyze_frame. ACCESSIBILITY: click_text, scroll_screen, input_text, perform_system_action, read_screen_contents.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command_type", JSONObject().apply {
                        put("type", "string")
                        put("description", "Command type")
                        put("enum", org.json.JSONArray().apply {
                            put("make_call")
                            put("call_contact")
                            put("record_correction")
                            put("add_contact")
                            put("edit_contact")
                            put("delete_contact")
                            put("list_contacts")
                            put("volume_up")
                            put("volume_down")
                            put("set_volume")
                            put("mute")
                            put("unmute")
                            put("set_ringer_silent")
                            put("set_ringer_normal")
                            put("set_ringer_vibrate")
                            put("play_spotify")
                            put("resume_music")
                            put("open_youtube")
                            put("play_youtube")
                            put("pause_music")
                            put("next_track")
                            put("previous_track")
                            put("turn_on_wifi")
                            put("turn_off_wifi")
                            put("turn_on_bluetooth")
                            put("turn_off_bluetooth")
                            put("turn_on_flashlight")
                            put("turn_off_flashlight")
                            put("set_alarm")
                            put("set_timer")
                            put("set_reminder")
                            put("list_reminders")
                            put("add_scheduled_routine")
                            put("list_routines")
                            put("delete_scheduled_routine")
                            put("get_battery_status")
                            put("get_wifi_status")
                            put("set_office_wifi")
                            put("set_home_location")
                            put("set_home")
                            put("set_office_location")
                            put("set_office")
                            put("get_bluetooth_status")
                            put("get_weather")
                            put("get_current_time")
                            put("navigate_to")
                            put("web_search")
                            put("search_news")
                            put("search_coupang")
                            put("search_aliexpress")
                            put("open_app")
                            put("save_note")
                            put("read_notes")
                            put("delete_note")
                            put("clear_notes")
                            put("send_to_notion")
                            put("send_last_note_to_notion")
                            put("search_notion")
                            put("append_notion_note")
                            put("delete_notion_note")
                            put("send_sms")
                            put("send_messenger")
                            put("send_whatsapp")
                            put("add_calendar_event")
                            put("repeat_last")
                            put("save_preference")
                            put("set_focus_mode")
                            put("end_focus_mode")
                            put("run_routine")
                            put("quiz_from_notion")
                            put("smart_home")
                            put("get_calendar_events")
                            put("get_salary_data")
                            put("add_salary_today")
                            put("copy_salary_yesterday")
                            put("switch_gem")
                            put("list_gems")
                            put("current_gem")
                            put("get_relevant_memory")
                            put("save_preference")
                            put("remember_fact")
                            put("get_remembered_facts")
                            put("forget_memory")
                            put("set_listening_timeout")
                            put("take_photo")
                            put("analyze_frame")
                            put("clear_memory")
                            put("dismiss_alarm")
                            put("click_text")
                            put("long_click")
                            put("double_click")
                            put("scroll_screen")
                            put("input_text")
                            put("clear_text")
                            put("perform_system_action")
                            put("read_screen_contents")
                            put("create_macro")
                            put("run_macro")
                            put("create_and_play_playlist")
                            put("get_health_summary")
                            put("get_sleep_data")
                            put("get_activity_data")
                            put("get_workout_data")
                        })
                    })
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "Target: phone number, contact name, app name, Notion note title or search query, or routine type. For Notion: Note Title or keyword to search. For play_spotify: song/artist OR mood keyword. For web_search: query.")
                    })
                    put("value", JSONObject().apply {
                        put("type", "string")
                        put("description", "Value: get_weather=today|tomorrow|week. set_reminder=time. set_volume=0-150. get_current_time=time|date. For Notion: Markdown content to save or append. SMS/Messenger=message. For create_and_play_playlist: song list.")
                    })
                    put("artist", JSONObject().apply {
                        put("type", "string")
                        put("description", "For play_spotify only: artist/singer name (e.g. Selena Gomez, Ed Sheeran). Use when user says specific artist.")
                    })
                    put("genre", JSONObject().apply {
                        put("type", "string")
                        put("description", "For play_spotify only: music genre (e.g. Lo-fi, Rock, K-pop, မြန်မာသီချင်း). Use when user says genre.")
                    })
                    put("mood", JSONObject().apply {
                        put("type", "string")
                        put("description", "For play_spotify only: mood keyword (မြူး, ပျင်း, အိပ်, စာကြည့်, Gym, မြန်မာ). Use when user says mood - also put value=Spotify English phrase.")
                    })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("description", "For play_spotify: shuffle or repeat. Optional.")
                    })
                })
                put("required", org.json.JSONArray().apply {
                    put("command_type")
                })
            })
        }
    }

        fun sendToolResponse(callId: String, functionName: String, result: String) {
        val functionResponse = JSONObject().apply {

            if (callId.isNotBlank()) put("id", callId)
            put("name", functionName)
            put("response", JSONObject().apply { put("result", result) })
        }
        val toolResponse = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", org.json.JSONArray().apply { put(functionResponse) })
            })
        }
                val sent = webSocket?.send(toolResponse.toString()) == true
        if (!sent) {
            onError?.invoke("Tool response could not be sent because the voice session is disconnected.")
        }
        AppLog.d("GeminiLive", "Tool response sent: $functionName id=${callId.take(12)} sent=$sent")

    }

    /**
     * Send text input (typed command) - same logic as voice. Works when connected after setup.
     * If not yet ready, queues and sends when setup completes.
     */
    fun sendTextInput(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (webSocket == null) {
            pendingTextInput = t
            connect()
            return
        }
        if (!setupCompleteReceived) {
            pendingTextInput = t
            return
        }
        sendTextTurn(t)
    }

    private fun sendTextTurn(text: String) {
        val ws = webSocket ?: return
        try {
            val clientContent = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", text)
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
                        val sent = ws.send(clientContent.toString())
            if (!sent) onError?.invoke("Text could not be sent because the voice session is not ready.")
            AppLog.d("GeminiLive", "Text turn sent via clientContent: ${text.take(50)}... sent=$sent")

        } catch (e: Exception) {
            Log.e("GeminiLive", "sendTextTurn error: ${e.message}")
        }
    }

    fun sendAudioChunk(base64Audio: String) {
        val ws = webSocket ?: return
        try {
            val realtimeInput = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    })
                })
            }
                        val sent = ws.send(realtimeInput.toString())
            if (!sent) onError?.invoke("Audio could not be sent because the voice session is disconnected.")
        } catch (e: Exception) {

            Log.e("GeminiLive", "sendAudioChunk error: ${e.message}")
        }
    }

    /**
     * Phase 3.1: Vision - Send image frame to Gemini.
     * @param base64Image Base64 encoded JPEG image data.
     */
    fun sendImageFrame(base64Image: String) {
        onError?.invoke("BLOCKED: Image sharing requires explicit consent for the reviewed frame.")
    }

    internal fun sendReviewedImage(consent: com.example.voicebrainlive.commands.ImageShareConsent, tap: com.example.voicebrainlive.commands.ApprovalUiLease.Lease?) {
        val session = connectionEpoch.token() ?: run { consent.cancel(); return }
        val destination = webSocket ?: run { consent.cancel(); return }
        connectionEpoch.withCurrent(session) {
            com.example.voicebrainlive.commands.ApprovalUiLease.withCurrentTap(tap) {
                val frame = consent.consume(this, tap) ?: return@withCurrentTap
                if (webSocket === destination) transmitReviewedImage(destination, frame)
            }
        }
        consent.cancel() // No retry even if the session/UI was revoked before admission.
    }

    internal fun imageSessionToken(): com.example.voicebrainlive.commands.ConnectionEpoch.Token? = connectionEpoch.token()
    internal fun isImageSessionCurrent(token: com.example.voicebrainlive.commands.ConnectionEpoch.Token): Boolean = connectionEpoch.current(token)

    internal fun bindAuthorizationSession(executor: com.example.voicebrainlive.commands.PhoneCommandExecutor, install: () -> Unit): Boolean {
        val epoch = connectedCallbackEpoch.get() ?: run { executor.invalidateApprovals(); return false }
        return connectionEpoch.bind(epoch, { executor.invalidateApprovals() }, install)
    }

    private fun transmitReviewedImage(ws: WebSocket, base64Image: String) {
        try {
            val realtimeInput = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("video", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    })
                })
            }
            val sent = ws.send(realtimeInput.toString())
            if (!sent) onError?.invoke("Image could not be sent because the voice session is disconnected.")
            AppLog.d("GeminiLive", "Image frame sent: ${base64Image.length} chars sent=$sent")

        } catch (e: Exception) {
            Log.e("GeminiLive", "sendImageFrame error: ${e.message}")
        }
    }

    private suspend fun handleServerMessage(text: String) {
        kotlin.coroutines.coroutineContext.ensureActive()
        AppLog.d("GeminiLive", "Handling server message: ${text.length} chars")

        // Force errors to UI - use user-friendly message
        if (text.contains("\"error\"")) {
            Log.e("GeminiLive", "Error from server: $text")
            checkAndTriggerModelFallback(text)
            onError?.invoke(text.take(500))
            return
        }
        
        // Flexible setupComplete check using string contains
        if (text.contains("\"setupComplete\"")) {
            setupCompleteReceived = true
            isReconnecting = false
            reconnectAttempt = 0
            AppLog.d("GeminiLive", "setupComplete detected")
            onSetupSuccess?.invoke(model)
            val pending = pendingTextInput
            pendingTextInput = null
            if (!pending.isNullOrBlank()) {
                sendTextTurn(pending)
            }
            onSetupComplete?.invoke()
            onLog?.invoke("Gemini ready")
            return
        }
        
        try {
            val json = JSONObject(text)
            
            // Check for toolCall at root level (function calling)
            if (json.has("toolCall")) {
                onTaskStarted?.invoke()
                val toolCall = json.getJSONObject("toolCall")
                if (toolCall.has("functionCalls")) {
                    val functionCalls = toolCall.getJSONArray("functionCalls")
                    for (i in 0 until functionCalls.length()) {
                        handleFunctionCall(functionCalls.getJSONObject(i))
                    }
                }
                return
            }
            
            if (!json.has("serverContent")) {
                AppLog.d("GeminiLive", "No serverContent: ${text.take(200)}")
                return
            }

            val serverContent = json.getJSONObject("serverContent")
            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { onInputTranscript?.invoke(it) }

            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    onOutputTranscript?.invoke(it)
                    onTranscript?.invoke(it)
                }

            // Handle text, functionCall - do NOT pass to AudioTrack. Audio ONLY from inlineData.data
            val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
            if (modelTurn != null && modelTurn.has("parts")) {
                onTaskStarted?.invoke()
                val parts = modelTurn.getJSONArray("parts")
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    if (inlineData != null && inlineData.has("data")) {
                        val base64Audio = inlineData.getString("data")
                        if (base64Audio.isNotEmpty()) {
                            AppLog.d("GeminiLive", "Audio from inlineData: ${android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT).size} bytes - playing")
                            onLog?.invoke("Speaking...")
                            onAudioChunk?.invoke(base64Audio)
                            // Do NOT return - process ALL audio parts for long responses (smooth continuous playback)
                        }
                    }
                    // Only show text if it's not a "thought" (internal reasoning)
                    if (part.has("text") && !part.optBoolean("thought", false)) {
                        val partText = part.optString("text", "")
                        onOutputTranscript?.invoke(partText)
                        onTranscript?.invoke(partText)
                    }
                    if (part.has("functionCall")) handleFunctionCall(part.getJSONObject("functionCall"))
                    if (part.has("function_call")) handleFunctionCall(part.getJSONObject("function_call"))
                }
            }

            if (serverContent.has("interrupted")) {
                AppLog.d("GeminiLive", "Response interrupted by user input")
                onLog?.invoke("Interrupted")
                onInterrupted?.invoke()
            }
            if (serverContent.has("turnComplete")) {
                AppLog.d("GeminiLive", "Turn complete")
                onTurnComplete?.invoke()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
                        AppLog.d("AudioStream", "Parse error: ${e.message}, payloadLength=${text.length}")

            onLog?.invoke("Parse: ${e.message}")
        }
    }

    private suspend fun handleFunctionCall(functionCall: JSONObject) {
        val id = functionCall.optString("id", "")
        val name = functionCall.optString("name", "")
        val args = functionCall.optJSONObject("args") ?: functionCall.optJSONObject("arguments") ?: JSONObject()
        // ActionDispatcher: JSON-based command structure
        var resolved = ActionDispatcher.resolve(args)
        
        // Fallback: If commandType is still blank but functionCall's name is NOT execute_phone_command
        // it means the model is calling a specific tool function name directly.
        if (resolved.commandType.isBlank() && name.isNotBlank() && name != "execute_phone_command") {
            resolved = ActionDispatcher.resolve(JSONObject(args.toString()).put("command_type", name))
        }
        
        if (resolved.commandType.isBlank()) {
            Log.w("GeminiLive", "Function call: missing command_type in args")
            sendToolResponse(id, name, "Command type is required.")
            return
        }

        val plan = com.example.voicebrainlive.commands.CommandPlan.from(resolved)
        val validation = com.example.voicebrainlive.commands.CommandPlanValidator.validate(plan)
        if (!validation.valid) {
            val clarification = validation.clarification ?: "ခိုင်းစေချက်ကို ပြန်ရှင်းပြပေးပါ။"
            AppLog.d("GeminiLive", "Command clarification required: intent=${plan.intent}, reason=$clarification")
            sendToolResponse(id, name, clarification)
            onOutputTranscript?.invoke(clarification)
            onTranscript?.invoke(clarification)
            return
        }
        AppLog.d("GeminiLive", "Function call: id=$id, name=$name, intent=${plan.intent}, targetPresent=${plan.target != null}, valuePresent=${plan.value != null}, confirmation=${plan.requiresConfirmation}")
        
        val result = try {
            kotlin.coroutines.coroutineContext.ensureActive()
            onExecutePhoneCommand(plan.intent, plan.target, plan.value)

        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("GeminiLive", "Error executing command: ${resolved.commandType}", e)
            "Error: ${e.message ?: "Unknown error"}"
        }
        
        AppLog.d("GeminiLive", "Function result: $result")
        sendToolResponse(id, name, result)
    }

    /** Set to true when user explicitly disconnects; used to skip auto-retry. */
    var userInitiatedDisconnect = false

    fun disconnect() {
        connectionEpoch.close()
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        setupCompleteReceived = false
        pendingTextInput = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
    }

    fun isConnected(): Boolean = webSocket != null

    private fun hasUsableApiKey(): Boolean {
        val trimmed = apiKey.trim()
        return trimmed.isNotBlank() &&
            trimmed != "YOUR_API_KEY" &&
            trimmed != "YOUR_API_KEY_HERE" &&
            trimmed.length >= 20
    }

    companion object {
        const val ERROR_API_KEY_REQUIRED = "API_KEY_REQUIRED"
    }
}
