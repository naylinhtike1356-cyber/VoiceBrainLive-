package com.example.voicebrainlive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicebrainlive.memory.SimpleConversationStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for managing chat messages and conversation state.
 * Provides reactive updates for chat UI using StateFlow.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentSessionId: String = ""

    /**
     * Initialize chat for a given session
     */
    fun initializeSession(sessionId: String) {
        currentSessionId = sessionId
        loadMessages(sessionId)
    }

    /**
     * Load messages for a specific session from database
     */
    private fun loadMessages(sessionId: String) {
        viewModelScope.launch {
            _isLoading.emit(true)
            try {
                val allTurns = withContext(Dispatchers.IO) {
                    SimpleConversationStorage.getRecentForContext(
                        context = getApplication(),
                        limit = 100
                    )
                }
                
                val messages = allTurns
                    .filter { it.sessionId == sessionId }
                    .map { turn ->
                        ChatMessage(
                            role = turn.role,
                            content = turn.content,
                            timestamp = turn.timestamp
                        )
                    }
                _chatMessages.emit(messages)
            } finally {
                _isLoading.emit(false)
            }
        }
    }

    /**
     * Add a new user message to the chat and database
     */
    fun addUserMessage(content: String) {
        viewModelScope.launch {
            if (content.isNotBlank()) {
                appendMessage(
                    ChatMessage(
                        role = "user",
                        content = content.trim(),
                        timestamp = System.currentTimeMillis()
                    )
                )
                withContext(Dispatchers.IO) {
                    SimpleConversationStorage.insert(
                        context = getApplication(),
                        sessionId = currentSessionId,
                        role = "user",
                        content = content.trim()
                    )
                }
            }
        }
    }

    /**
     * Add a new assistant message to the chat and database
     */
    fun addAssistantMessage(content: String) {
        viewModelScope.launch {
            if (content.isNotBlank()) {
                appendMessage(
                    ChatMessage(
                        role = "model",
                        content = content.trim(),
                        timestamp = System.currentTimeMillis()
                    )
                )
                withContext(Dispatchers.IO) {
                    SimpleConversationStorage.insert(
                        context = getApplication(),
                        sessionId = currentSessionId,
                        role = "model",
                        content = content.trim()
                    )
                }
            }
        }
    }

    /**
     * Append a message to the current chat without saving to DB
     * Useful for immediate UI updates
     */
    fun appendMessage(message: ChatMessage) {
        val currentList = _chatMessages.value.toMutableList()
        currentList.add(message)
        _chatMessages.value = currentList
    }

    /**
     * Clear all messages in current session
     */
    fun clearMessages() {
        _chatMessages.value = emptyList()
    }

    /**
     * Get current session ID
     */
    fun getSessionId(): String = currentSessionId

    /**
     * Reload messages from database
     */
    fun reloadMessages() {
        if (currentSessionId.isNotBlank()) {
            loadMessages(currentSessionId)
        }
    }
}

/**
 * Data class representing a single chat message
 */
data class ChatMessage(
    val id: Long = 0,
    val role: String, // "user" or "model"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isUserMessage: Boolean get() = role == "user"
    val isAssistantMessage: Boolean get() = role == "model"
}
