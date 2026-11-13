package com.example.voicebrainlive.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.R
import com.example.voicebrainlive.databinding.FragmentChatBinding
import com.example.voicebrainlive.memory.SimpleConversationStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for displaying real-time Q&A chat messages during voice interaction.
 * Shows user messages on the right and assistant messages on the left in a chat bubble format.
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var chatAdapter: ChatMessageAdapter
    private var sessionId: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get session ID from arguments
        sessionId = arguments?.getString(ARG_SESSION_ID) ?: ""

        // Setup RecyclerView with chat adapter
        setupChatRecyclerView()

        // Setup button listeners
        binding.btnSendMessage.setOnClickListener { sendMessage() }
        binding.btnClearChat.setOnClickListener { clearChat() }

        // Load initial messages
        loadMessages()
    }

    private fun setupChatRecyclerView() {
        chatAdapter = ChatMessageAdapter(
            onCopyMessage = { content -> copyToClipboard(content) },
            onShareMessage = { content -> shareMessage(content) }
        )

        binding.recyclerViewChat.apply {
            adapter = chatAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // Show latest messages at bottom
            }
        }
    }

    private fun loadMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val turns = withContext(Dispatchers.IO) {
                SimpleConversationStorage.getSessionTurns(context, sessionId)
            }
            val messages = turns
                .map { turn ->
                    ChatMessage(
                        role = turn.role,
                        content = turn.content,
                        timestamp = turn.timestamp
                    )
                }
            
            chatAdapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    scrollToBottom()
                }
            }
        }
    }

    private fun sendMessage() {
        val messageText = binding.etMessageInput.text.toString().trim()

        if (messageText.isEmpty()) {
            Toast.makeText(requireContext(), "စာသား ရေးသားပေးပါ", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear input field
        binding.etMessageInput.text.clear()

        // Delegate to MainActivityViewModel to process commands & send to Gemini Live / Offline engine
        val mainActivity = activity as? com.example.voicebrainlive.MainActivity
        val vm = mainActivity?.viewModel
        if (vm != null) {
            vm.processUserText(messageText)
        } else {
            addUserMessage(messageText)
        }
    }

    /**
     * Add user message to chat and database
     */
    fun addUserMessage(content: String) {
        val text = content.trim()
        if (text.isNotBlank() && sessionId.isNotEmpty() && isAdded) {
            viewLifecycleOwner.lifecycleScope.launch {
                val currentList = chatAdapter.currentList.toMutableList()
                // Prevent duplicate consecutive entries
                val lastMsg = currentList.lastOrNull()
                if (lastMsg?.role == "user" && lastMsg.content == text) {
                    return@launch
                }

                val message = ChatMessage(
                    role = "user",
                    content = text
                )
                currentList.add(message)
                chatAdapter.submitList(currentList) {
                    scrollToBottom()
                }

                // Save to database on IO thread
                withContext(Dispatchers.IO) {
                    SimpleConversationStorage.insert(
                        context = requireContext(),
                        sessionId = sessionId,
                        role = "user",
                        content = text
                    )
                }
            }
        }
    }

    /**
     * Add assistant message to chat and database
     */
    fun addAssistantMessage(content: String) {
        val text = content.trim()
        if (text.isNotBlank() && sessionId.isNotEmpty() && isAdded) {
            viewLifecycleOwner.lifecycleScope.launch {
                val currentList = chatAdapter.currentList.toMutableList()
                // Prevent duplicate consecutive entries
                val lastMsg = currentList.lastOrNull()
                if (lastMsg?.role == "model" && lastMsg.content == text) {
                    return@launch
                }

                val message = ChatMessage(
                    role = "model",
                    content = text
                )
                currentList.add(message)
                chatAdapter.submitList(currentList) {
                    scrollToBottom()
                }

                // Save to database on IO thread
                withContext(Dispatchers.IO) {
                    SimpleConversationStorage.insert(
                        context = requireContext(),
                        sessionId = sessionId,
                        role = "model",
                        content = text
                    )
                }
            }
        }
    }

    private fun scrollToBottom() {
        binding.recyclerViewChat.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun clearChat() {
        if (sessionId.isBlank()) return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("စကားပြောမှတ်တမ်း ဖျက်မလား")
            .setMessage("ဒီ Chat ထဲမှာ သိမ်းထားတဲ့ စကားပြောမှတ်တမ်းကို ဖျက်ပါမယ်။")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("ဖျက်မည်") { _, _ ->
                val context = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { SimpleConversationStorage.clearSession(context, sessionId) }
                        chatAdapter.submitList(emptyList())
                        Toast.makeText(context, "စကားပြောမှတ်တမ်း ဖျက်ပြီးပါပြီ။", Toast.LENGTH_SHORT).show()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, "စကားပြောမှတ်တမ်း ဖျက်၍မရပါ။", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("chat message", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "အကူးအပြောင်းလုပ်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage(content: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "မျှဝေပါ"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SESSION_ID = "session_id"

        /**
         * Create a new instance of ChatFragment with specified session ID
         */
        fun newInstance(sessionId: String): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                }
            }
        }
    }
}
