package com.example.voicebrainlive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.example.voicebrainlive.R
import com.example.voicebrainlive.gemini.GeminiLiveClient
import java.util.UUID

/**
 * Example Fragment demonstrating ChatFragment integration with AssistantViewModel
 * This shows how to display Q&A in chat format during voice recording sessions
 */
class HomeFragmentWithChatExample : Fragment() {

    private var chatFragment: ChatFragment? = null
    private var currentSessionId: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_with_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Generate a unique session ID for this conversation
        currentSessionId = UUID.randomUUID().toString()

        // Initialize ChatFragment
        createChatFragment()
    }

    private fun createChatFragment() {
        chatFragment = ChatFragment.newInstance(sessionId = currentSessionId)

        childFragmentManager.commit {
            replace(R.id.fragment_chat_container, chatFragment!!, TAG_CHAT_FRAGMENT)
            setReorderingAllowed(true)
        }
    }

    /**
     * Call this method when user voice is transcribed
     * Integrates with GeminiLiveClient
     */
    fun onUserTranscriptReceived(transcript: String) {
        chatFragment?.addUserMessage(transcript)
        Toast.makeText(requireContext(), "အမေး: $transcript", Toast.LENGTH_SHORT).show()
    }

    /**
     * Call this method when assistant response is received
     * Integrates with GeminiLiveClient
     */
    fun onAssistantResponseReceived(response: String) {
        chatFragment?.addAssistantMessage(response)
        Toast.makeText(requireContext(), "အဖြေ: $response", Toast.LENGTH_SHORT).show()
    }

    /**
     * Example of integration with AssistantViewModel
     * This should be called from AssistantViewModel when new messages arrive
     * 
     * Note: Adapt this to your actual GeminiLiveClient callback methods
     */
    fun setupGeminiLiveClientListener(geminiClient: GeminiLiveClient) {
        com.example.voicebrainlive.commands.ChatUiBridge.onUserMessage = { transcript ->
            activity?.runOnUiThread { onUserTranscriptReceived(transcript) }
        }
        com.example.voicebrainlive.commands.ChatUiBridge.onAssistantMessage = { response ->
            activity?.runOnUiThread { onAssistantResponseReceived(response) }
        }
    }

    /**
     * Clear chat when starting a new session
     */
    fun startNewSession() {
        currentSessionId = UUID.randomUUID().toString()
        chatFragment = ChatFragment.newInstance(sessionId = currentSessionId)
        createChatFragment()
    }

    /**
     * Get the current session ID for database storage
     */
    fun getCurrentSessionId(): String = currentSessionId

    override fun onDestroyView() {
        super.onDestroyView()
        chatFragment = null
    }

    companion object {
        private const val TAG_CHAT_FRAGMENT = "ChatFragment"
    }
}

/**
 * Example layout integration
 * Place this in fragment_home_with_chat.xml:
 *
 * <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:orientation="vertical">
 *
 *     <!-- Existing home UI above -->
 *     <FrameLayout
 *         android:id="@+id/fragment_chat_container"
 *         android:layout_width="match_parent"
 *         android:layout_height="0dp"
 *         android:layout_weight="1" />
 *
 * </LinearLayout>
 */
