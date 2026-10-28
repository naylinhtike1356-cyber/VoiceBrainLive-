package com.example.voicebrainlive.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying chat messages in conversation view.
 * Handles user messages (right-aligned) and assistant messages (left-aligned with sparkle icon).
 */
class ChatMessageAdapter(
    private val onCopyMessage: (String) -> Unit = {},
    private val onShareMessage: (String) -> Unit = {},
    private val allowTransfer: Boolean = true
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatMessageDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isUserMessage) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_user_message, parent, false)
                UserMessageViewHolder(view, onCopyMessage, onShareMessage)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_assistant_message, parent, false)
                AssistantMessageViewHolder(view, onCopyMessage, onShareMessage)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        listOf(R.id.btnUserCopy, R.id.btnUserShare, R.id.btnAssistantCopy, R.id.btnAssistantShare).forEach { id ->
            holder.itemView.findViewById<View>(id)?.visibility = if (allowTransfer) View.VISIBLE else View.GONE
        }
        when (holder) {
            is UserMessageViewHolder -> holder.bind(getItem(position))
            is AssistantMessageViewHolder -> holder.bind(getItem(position))
        }
    }

    class UserMessageViewHolder(
        itemView: View,
        private val onCopy: (String) -> Unit,
        private val onShare: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvUserMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvUserTime)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnUserCopy)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btnUserShare)
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: ChatMessage) {
            tvMessage.text = message.content
            tvTime.text = dateFormat.format(Date(message.timestamp))

            btnCopy.setOnClickListener { onCopy(message.content) }
            btnShare.setOnClickListener { onShare(message.content) }
        }
    }

    class AssistantMessageViewHolder(
        itemView: View,
        private val onCopy: (String) -> Unit,
        private val onShare: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvAssistantMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvAssistantTime)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnAssistantCopy)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btnAssistantShare)
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: ChatMessage) {
            tvMessage.text = message.content
            tvTime.text = dateFormat.format(Date(message.timestamp))

            btnCopy.setOnClickListener { onCopy(message.content) }
            btnShare.setOnClickListener { onShare(message.content) }
        }
    }

    private class ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
    }
}
