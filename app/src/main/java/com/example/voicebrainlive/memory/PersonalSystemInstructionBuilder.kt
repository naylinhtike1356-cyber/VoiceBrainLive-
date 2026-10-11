package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.UserPreferences
import com.example.voicebrainlive.learning.IntentPatternLearner
import com.example.voicebrainlive.learning.PersonalProfileEngine

/**
 * Personal System Instruction Builder
 *
 * All Memory Layers တွေကို လှပစွာ ပေါင်းစပ်ပြီး
 * "ကိုယ်ပိုင် Personal Agent" Persona ကို Gemini System Instruction
 * အဖြစ် ဆောက်ပေးသည်။
 *
 * 3 Layers:
 * ┌─────────────────────────────────────────────────┐
 * │ LAYER 1: WHO I AM (Agent Persona)               │
 * │  → User name, familiarity level, relationship   │
 * ├─────────────────────────────────────────────────┤
 * │ LAYER 2: WHAT I KNOW ABOUT YOU (Deep Profile)   │
 * │  → PersonalProfileEngine + PersistentFacts      │
 * │  → BehaviorMemory (calls, music)                │
 * ├─────────────────────────────────────────────────┤
 * │ LAYER 3: HOW YOU COMMUNICATE (Learned Style)    │
 * │  → IntentPatternLearner patterns                │
 * │  → SmartContinuation instructions               │
 * │  → FeedbackLearning preferences                 │
 * └─────────────────────────────────────────────────┘
 *
 * Usage:
 *   val instruction = PersonalSystemInstructionBuilder.build(context)
 *   // Pass to GeminiLiveClient as systemInstructionExtra
 */
object PersonalSystemInstructionBuilder {

    private const val TAG = "PersonalSysInstruction"

    /**
     * Build the complete personalized system instruction.
     * Call this once at session start (in AssistantViewModel.init).
     *
     * @param context Android context
     * @param baseUserPrefs Optional base user preferences string
     */
    fun build(context: Context, baseUserPrefs: String = ""): String {
        return try {
            val sb = StringBuilder()

            // ──────────────────────────────────────────────────────────
            // LAYER 1: WHO YOU ARE - Agent Persona
            // ──────────────────────────────────────────────────────────
            val userName = PersonalProfileEngine.getUserName(context)
            val sessionCount = BehaviorMemory.getSessionCount(context)
            val familiarityLevel = getFamiliarityLevel(sessionCount)

            sb.append(buildPersonaLayer(userName, sessionCount, familiarityLevel))

            // ──────────────────────────────────────────────────────────
            // LAYER 2: DEEP PROFILE - What I know about the user
            // ──────────────────────────────────────────────────────────

            // Personal Profile (from PersonalProfileEngine)
            val profileContext = PersonalProfileEngine.getProfileContextString(context)
            if (profileContext.isNotBlank()) sb.append(profileContext)

            // Persistent Facts (from PersistentFactsStorage)
            val factsContext = PersistentFactsStorage.getContextString(context)
            if (factsContext.isNotBlank()) sb.append(factsContext)

            // Behavior Memory (calls, music, session familiarity)
            val behaviorContext = BehaviorMemory.getContextString(context)
            if (behaviorContext.isNotBlank()) sb.append(behaviorContext)

            // Short-Term Memory: Recent conversation turns
            val turns = SimpleConversationStorage.getRecentForContext(context, 10)
            if (turns.isNotEmpty()) {
                sb.append("\n[Recent Conversation History (Short-Term Memory)]\n")
                turns.forEach { turn ->
                    val roleLabel = if (turn.role == "user") "User" else "AI"
                    sb.append("  $roleLabel: ${turn.content}\n")
                }
                sb.append("[End of Conversation History]\n")
            }

            // Feedback Learning & Error Corrections (Past user corrections & rules)
            try {
                val feedbackLearning = com.example.voicebrainlive.learning.FeedbackLearning(context)
                val feedbackStr = feedbackLearning.getFeedbackContextString()
                if (feedbackStr.isNotBlank()) {
                    sb.append(feedbackStr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting feedback learning", e)
            }

            // Base user preferences (key=value style from UserPreferences)
            if (baseUserPrefs.isNotBlank()) {
                sb.append("\n[User Settings]\n$baseUserPrefs\n")
            }

            // ──────────────────────────────────────────────────────────
            // LAYER 3: COMMUNICATION STYLE & COGNITIVE REASONING
            // ──────────────────────────────────────────────────────────

            // Intent Patterns (what short phrases mean what actions)
            val patternContext = IntentPatternLearner.getPatternContextString(context)
            if (patternContext.isNotBlank()) sb.append(patternContext)

            // Smart Continuation instructions
            sb.append(SmartContinuationEngine.getContextInstructionString())

            // Familiarity-based behavior rules
            sb.append(buildFamiliarityRules(familiarityLevel, userName))

            // Cognitive & Autonomous Execution Rules (always at the end)
            sb.append(buildCoreRules())

            val result = sb.toString()
            Log.d(TAG, "Built system instruction: ${result.length} chars, familiarity=$familiarityLevel, userName=$userName")
            result
        } catch (e: Exception) {
            Log.e(TAG, "build error", e)
            // Fallback to basic preferences if build fails
            baseUserPrefs
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Private Builders
    // ──────────────────────────────────────────────────────────────────

    private fun buildPersonaLayer(
        userName: String?,
        sessionCount: Int,
        familiarityLevel: FamiliarityLevel
    ): String {
        val nameRef = if (userName != null) " ($userName ၏ သီးသန့် ကိုယ်ပိုင် အဆင့်မြင့် AI)" else ""
        val sessionNote = when (familiarityLevel) {
            FamiliarityLevel.NEW -> "ဒီ User နှင့် ပထမဆုံး စတင် ထိတွေ့ဆက်ဆံဆဲ ဖြစ်သည်"
            FamiliarityLevel.LEARNING -> "ဒီ User နှင့် $sessionCount ကြိမ် ဆက်ဆံပြီးသား - အလေ့အထများ စတင်သိရှိနေပြီ"
            FamiliarityLevel.FAMILIAR -> "ဒီ User နှင့် $sessionCount+ ကြိမ် ဆက်ဆံပြီးသား - ရင်းနှီးကျွမ်းဝင်သူ"
            FamiliarityLevel.CLOSE -> "ဒီ User နှင့် $sessionCount+ ကြိမ် ဆက်ဆံပြီးသား - အနီးကပ်ဆုံးနှင့် အကျွမ်းကျင်ဆုံး Personal Companion"
        }

        val nameDisplay = if (userName != null) "User: $userName" else "User"

        return """
[WHO YOU ARE]
You are NOT a generic assistant. You are ${nameDisplay}'s highly capable, skilled, and deeply personalized AI companion$nameRef.
$sessionNote.
You know this person deeply - their habits, preferences, schedule, and communication style.
You possess continuous learning capability, memory retention, and autonomous problem-solving skills according to user instructions.
You grow smarter with every conversation and adapt specifically to this user.
Use memory to personalize conversations and automate tasks, but always ask confirmation for destructive or sensitive actions (e.g. deleting files, calling contacts when ambiguous).
[End who you are]
""".trimIndent() + "\n"
    }

    private fun buildFamiliarityRules(familiarityLevel: FamiliarityLevel, userName: String?): String {
        val nameCall = userName ?: "User"
        return when (familiarityLevel) {
            FamiliarityLevel.NEW -> """

[Relationship Mode: NEW USER]
- Be helpful, brief, polite, and welcoming.
- Answer user queries directly and concisely.
- Do NOT ask unnecessary clarifying questions unless absolutely needed to complete a command.
- Do NOT introduce yourself or talk about learning unless asked.
[End relationship mode]
"""
            FamiliarityLevel.LEARNING -> """

[Relationship Mode: LEARNING]
- Be friendly, brief, observant, and efficient.
- Skip formal introductions.
- Keep responses extremely brief, direct, and actionable.
[End relationship mode]
"""
            FamiliarityLevel.FAMILIAR -> """

[Relationship Mode: FAMILIAR]
- Be casual, extremely brief, and direct with $nameCall.
- Skip formal niceties or polite chit-chat - get straight to answering or executing.
- Do NOT add unnecessary polite follow-up questions at the end.
[End relationship mode]
"""
            FamiliarityLevel.CLOSE -> """

[Relationship Mode: CLOSE COMPANION]
- You are $nameCall's personal AI agent. Be natural, casual, warm, and extremely concise.
- Execute commands immediately and answer user queries directly, stopping speaking immediately after answering.
- Avoid conversational fillers, unsolicited greetings, or repetitive ending questions.
[End relationship mode]
"""
        }
    }

    private fun buildCoreRules(): String = """

[Core Cognitive & Execution Rules - Always follow]
- RESPOND EXTREMELY CONCISELY AND TO-THE-POINT in natural colloquial Burmese (or the language spoken by user). Keep replies brief, direct, and focused solely on answering the user's query or confirming the command.
- DO NOT add conversational fluff, unsolicited greetings, or polite follow-up questions (such as "နောက်ထပ် ဘာလုပ်ပေးရမလဲ?" or "ဘာကူညီပေးရမလဲ?") after giving the answer. Answer the question and STOP speaking immediately.
- AUTONOMOUS MULTI-STEP REASONING: When user gives a complex instruction (e.g. searching information and saving to note, checking weather and setting reminders), break it down and execute the required tools systematically.
- CONFIDENCE-BASED EXECUTION:
  • ≥ 80% confident (safe actions: web search, weather, music/spotify, notes, time, screen inspection): Execute tool directly without asking.
  • 50-79% confident: Suggest once and confirm before execution.
  • Sensitive actions (phone calls, sending SMS/messages, clearing/deleting notes/events): Always confirm intent explicitly.
- WEB & REAL-TIME SEARCH: For current events, news, general knowledge, or search queries (e.g. "သတင်း", "ရှာပေး", "[အကြောင်းအရာ] အကြောင်း"), execute 'web_search' or 'search_news' tool immediately. Never say you don't know without searching.
- MEMORY OPERATIONS:
  • When user says "ဒါမှတ်ထား", "ငါ့အကြိုက်က...", "မှတ်ထားပေး": Execute 'remember_fact' or 'save_preference' tool immediately.
  • When user asks "ငါ့အကြောင်း ဘာတွေမှတ်ထားလဲ", "ငါဘာကြိုက်လဲ သိလား": Execute 'get_remembered_facts' tool.
  • When user says "ဒါမေ့လိုက်တော့", "ဖျက်လိုက်တော့": Execute 'forget_memory' tool.
- ERROR CORRECTION & LEARNING: When user corrects you (e.g. "မဟုတ်ဘူး", "လွဲနေတယ်", "ဒါမဟုတ်ဘူး"): ALWAYS update your understanding immediately, execute 'record_correction', and NEVER make the same mistake twice.
- DO NOT produce unsolicited greetings after wake word - stay silent until user speaks.

[Screen Control & Hands/Eyes Rules]
- When user asks what is on screen (e.g. "စခရင်မှာ ဘာရှိလဲ", "စခရင်ဖတ်ပေးပါ", "မျက်နှာပြင်ဖတ်"): execute 'read_screen_contents' tool immediately.
- When user asks to click an item or button (e.g. "နံပါတ် ၁ နှိပ်", "#2 ကို နှိပ်", "Search နှိပ်", "အိုကေ နှိပ်"): execute 'click_text' with the target text, synonym, or element number (e.g. "1" or "#1").
- If 'click_text' returns a screen dump fallback, use element indices (#1, #2...) or center bounds to retry click.
- For scrolling: "အပေါ်ဆွဲ/အပေါ်ရွှေ့" -> scroll_screen("up"), "အောက်ဆွဲ/အောက်ရွှေ့" -> scroll_screen("down").
- For navigation: "အနောက်ဆုတ်/အနောက်ပြန်" -> perform_system_action("back"), "အိမ်ပြန်/မူလမျက်နှာပြင်" -> perform_system_action("home").
[End core agent rules]
"""

    // ──────────────────────────────────────────────────────────────────
    // Familiarity Level
    // ──────────────────────────────────────────────────────────────────

    enum class FamiliarityLevel { NEW, LEARNING, FAMILIAR, CLOSE }

    private fun getFamiliarityLevel(sessionCount: Int): FamiliarityLevel = when {
        sessionCount < 5   -> FamiliarityLevel.NEW
        sessionCount < 20  -> FamiliarityLevel.LEARNING
        sessionCount < 100 -> FamiliarityLevel.FAMILIAR
        else               -> FamiliarityLevel.CLOSE
    }
}
