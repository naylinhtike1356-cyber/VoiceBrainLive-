package com.example.voicebrainlive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.voicebrainlive.databinding.FragmentChatBinding
import com.example.voicebrainlive.memory.SimpleConversationStorage
import java.util.UUID

/**
 * Quick-start example for using ChatFragment
 * Place this in your MainActivity or a hosting Fragment
 */
class ChatQuickStartExample : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Example 1: Simple chat initialization
        simple_initialize_chat()

        // Example 2: Display chat for current session
        display_chat_for_session()

        // Example 3: Add messages programmatically
        add_messages_programmatically()
    }

    // ==================== Example 1: Simple Chat Initialization ====================
    fun simple_initialize_chat() {
        val sessionId = UUID.randomUUID().toString()
        val chatFragment = ChatFragment.newInstance(sessionId)

        childFragmentManager.beginTransaction()
            .replace(android.R.id.content, chatFragment) // Replace with actual container ID
            .commit()
    }

    // ==================== Example 2: Display Chat for Current Session ====================
    fun display_chat_for_session() {
        val currentSessionId = "user_session_12345" // Get from your session management

        val chatFragment = ChatFragment.newInstance(currentSessionId)

        childFragmentManager.beginTransaction()
            .add(android.R.id.content, chatFragment) // Or use replace()
            .addToBackStack("chat")
            .commit()
    }

    // ==================== Example 3: Add Messages Programmatically ====================
    fun add_messages_programmatically() {
        val chatFragment = childFragmentManager.findFragmentByTag("chat_fragment") as? ChatFragment
        if (chatFragment != null) {
            // Add user message
            chatFragment.addUserMessage("Hello! အဆင်ပြေလား?")

            // Simulate assistant response after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                chatFragment.addAssistantMessage("ကျွန်ုပ်စုံလင်ပါသည်။ ကျွန်ုပ်သည်ကဘယ်လိုကူညီပေးနိုင်ပါသလဲ?")
            }, 1000)
        }
    }

    // ==================== Example 4: Integration with Voice Recording ====================
    /**
     * Call this when voice transcript is received from Gemini API
     */
    fun handleVoiceTranscript(transcriptText: String) {
        val chatFragment = childFragmentManager.findFragmentByTag("chat_fragment") as? ChatFragment
        chatFragment?.addUserMessage(transcriptText)
    }

    // ==================== Example 5: Integration with Gemini Response ====================
    /**
     * Call this when assistant response is received
     */
    fun handleGeminiResponse(responseText: String) {
        val chatFragment = childFragmentManager.findFragmentByTag("chat_fragment") as? ChatFragment
        chatFragment?.addAssistantMessage(responseText)
    }

    // ==================== Example 6: Stream Long Responses ====================
    /**
     * Example of streaming a long response character by character
     */
    fun streamLongResponse(fullResponse: String) {
        val chatFragment = childFragmentManager.findFragmentByTag("chat_fragment") as? ChatFragment
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        var index = 0
        val chunkSize = 50 // Characters per chunk

        fun sendNextChunk() {
            if (index < fullResponse.length) {
                val chunk = fullResponse.substring(index, minOf(index + chunkSize, fullResponse.length))
                index += chunkSize

                if (index - chunkSize == 0) {
                    // First chunk - add message
                    chatFragment?.addAssistantMessage(chunk)
                } else {
                    // Subsequent chunks - update existing message
                    // (This would require enhanced implementation)
                }

                handler.postDelayed({ sendNextChunk() }, 200) // 200ms between chunks
            }
        }

        sendNextChunk()
    }

    // ==================== Example 7: Multi-turn Conversation ====================
    /**
     * Example of a complete Q&A conversation
     */
    fun example_multi_turn_conversation() {
        val chatFragment = childFragmentManager.findFragmentByTag("chat_fragment") as? ChatFragment
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        val conversations = listOf(
            Pair("ကမ္ဘာ့နံ့သာကင်္သက်ရှည်ဆုံးတွေဘာတွေလဲ?", "အတူတကျ စံချိုင်း အဖြူရောင် အလွင် အယောင်း!"),
            Pair("အဆိုတွေအကြောင်းကျွန်ုပ်စိတ်ဝင်စားနေပါသည်။", "အဆိုတွေကို နားထောင်သည်ဆိုင်ရာ အကြောင်းအရာများ အတူတကျ ဖြေလည်းပေးနိုင်ပါသည်။"),
            Pair("ကျွန်ုပ်အတွက် သီခ်ားများ အကြုံးပြုဆိုငါ့ပေးနိုင်ပါသလား?", "အင်္ဂလိပ်သို့မဟုတ်ခြားခြားဘာသာထဲ ကျွန်ုပ်သီခ်ားများ ပြုလုပ်ပေးနိုင်ပါသည်။")
        )

        var delay = 500L
        for ((question, answer) in conversations) {
            handler.postDelayed({ chatFragment?.addUserMessage(question) }, delay)
            delay += 1000
            handler.postDelayed({ chatFragment?.addAssistantMessage(answer) }, delay)
            delay += 1000
        }
    }

    // ==================== Example 8: Integration with AssistantActivity ====================
    /**
     * How to integrate with existing AssistantActivity
     * Add this code in your AssistantActivity
     */
    fun assistant_activity_integration() {
        /*
        // In AssistantActivity.kt

        private lateinit var chatFragment: ChatFragment
        private val sessionId by lazy { UUID.randomUUID().toString() }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // ... existing code ...

            // Initialize chat fragment
            chatFragment = ChatFragment.newInstance(sessionId)
            supportFragmentManager.beginTransaction()
                .replace(R.id.chat_container, chatFragment)
                .commit()

            // In your GeminiLiveClient callback:
            geminiClient.onTranscriptReceived = { transcript ->
                chatFragment.addUserMessage(transcript)
            }

            geminiClient.onResponseReceived = { response ->
                chatFragment.addAssistantMessage(response)
            }
        }
        */
    }
}

/**
 * Usage in any Fragment/Activity:
 *
 * // 1. Add ChatFragment to layout
 * val chatFragment = ChatFragment.newInstance("session_123")
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.chat_container, chatFragment)
 *     .commit()
 *
 * // 2. Add messages programmatically
 * chatFragment.addUserMessage("Hello!")
 * chatFragment.addAssistantMessage("Hi there!")
 *
 * // 3. Listen for message actions (copy/share)
 * // Already handled in ChatMessageAdapter with callbacks
 */
