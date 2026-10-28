package com.example.voicebrainlive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.voicebrainlive.MainActivityViewModel
import com.example.voicebrainlive.R
import com.example.voicebrainlive.commands.ChatUiBridge
import com.example.voicebrainlive.databinding.FragmentChatSessionBinding

/**
 * Main nav chat tab: embeds [ChatFragment] and streams Gemini transcripts via [ChatUiBridge].
 */
class ChatSessionFragment : Fragment() {

    private var _binding: FragmentChatSessionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainActivityViewModel by activityViewModels()
    private var chatFragment: ChatFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnConnectedAgent.setOnClickListener {
            startActivity(android.content.Intent(requireContext(),com.example.voicebrainlive.agent.connected.ConnectedAgentActivity::class.java)
                .setPackage(requireContext().packageName))
        }
        if (savedInstanceState == null) {
            chatFragment = ChatFragment.newInstance(sessionId = viewModel.getChatSessionId())
            childFragmentManager.beginTransaction()
                .replace(R.id.chatSessionContainer, chatFragment!!)
                .commit()
        } else {
            chatFragment = childFragmentManager.findFragmentById(R.id.chatSessionContainer) as? ChatFragment
        }
    }

    override fun onResume() {
        super.onResume()
        ChatUiBridge.onUserMessage = { text ->
            activity?.runOnUiThread { chatFragment?.addUserMessage(text) }
        }
        ChatUiBridge.onAssistantMessage = { text ->
            activity?.runOnUiThread { chatFragment?.addAssistantMessage(text) }
        }
    }

    override fun onPause() {
        ChatUiBridge.onUserMessage = null
        ChatUiBridge.onAssistantMessage = null
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatFragment = null
        _binding = null
    }
}
