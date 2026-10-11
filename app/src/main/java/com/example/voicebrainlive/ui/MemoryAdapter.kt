package com.example.voicebrainlive.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.databinding.ItemMemoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MemoryCategory {
    FACT,          // 📌 Persistent Facts (User facts, preferences)
    HABIT,         // 🧠 Learned Habits (Intent patterns)
    CONVERSATION,  // 💬 AI Conversation Embeddings
    NOTE           // 📝 Voice Notes
}

data class UnifiedMemoryItem(
    val id: String,
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rawKey: String? = null,
    val isEditable: Boolean = true,
    val source: String = "Unknown",
    val confidencePercent: Int? = null,
    val expiryAt: Long? = null
)

class MemoryAdapter(
    private val onEditClick: (UnifiedMemoryItem) -> Unit,
    private val onDeleteClick: (UnifiedMemoryItem) -> Unit
) : ListAdapter<UnifiedMemoryItem, MemoryAdapter.MemoryViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoryViewHolder {
        val binding = ItemMemoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MemoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MemoryViewHolder(private val binding: ItemMemoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UnifiedMemoryItem) {
            binding.tvMemoryTitle.text = item.title
            binding.tvMemoryText.text = item.content
            val age = formatAge(item.timestamp)
            val metaParts = mutableListOf<String>()
            if (item.source.isNotBlank() && item.source != "Unknown") {
                metaParts.add("📦 ${item.source}")
            }
            if (item.timestamp > 0L) {
                metaParts.add("🕒 $age")
            }
            if (item.confidencePercent != null) {
                metaParts.add("🎯 ${item.confidencePercent}% တိကျမှု")
            }
            if (item.expiryAt != null && item.expiryAt > 0L) {
                metaParts.add("⌛ သက်တမ်း: ${dateFormat.format(Date(item.expiryAt))}")
            }
            val metaStr = metaParts.joinToString("  •  ")
            binding.tvMemoryMetadata.text = metaStr
            binding.tvMemoryMetadata.visibility = if (metaStr.isBlank()) View.GONE else View.VISIBLE

            if (item.timestamp > 0) {
                binding.tvMemoryTimestamp.text = dateFormat.format(Date(item.timestamp))
                binding.tvMemoryTimestamp.visibility = View.VISIBLE
            } else {
                binding.tvMemoryTimestamp.visibility = View.GONE
            }

            when (item.category) {
                MemoryCategory.FACT -> {
                    binding.tvCategoryBadge.text = "📌 အချက်အလက်"
                    binding.tvCategoryBadge.setTextColor(0xFF34D399.toInt())
                    binding.tvCategoryBadge.setBackgroundColor(0x2210B981.toInt())
                }
                MemoryCategory.HABIT -> {
                    binding.tvCategoryBadge.text = "🧠 အလေ့အထ"
                    binding.tvCategoryBadge.setTextColor(0xFFA78BFA.toInt())
                    binding.tvCategoryBadge.setBackgroundColor(0x228B5CF6.toInt())
                }
                MemoryCategory.CONVERSATION -> {
                    binding.tvCategoryBadge.text = "💬 AI မှတ်ဉာဏ်"
                    binding.tvCategoryBadge.setTextColor(0xFF38BDF8.toInt())
                    binding.tvCategoryBadge.setBackgroundColor(0x220284C7.toInt())
                }
                MemoryCategory.NOTE -> {
                    binding.tvCategoryBadge.text = "📝 မှတ်စု"
                    binding.tvCategoryBadge.setTextColor(0xFFFBBF24.toInt())
                    binding.tvCategoryBadge.setBackgroundColor(0x22D97706.toInt())
                }
            }

            binding.btnEdit.visibility = if (item.isEditable) View.VISIBLE else View.GONE
            binding.btnEdit.setOnClickListener {
                onEditClick(item)
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(item)
            }
        }

        private fun formatAge(timestamp: Long): String {
            if (timestamp <= 0L) return "မသိရှိပါ"
            val days = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 86_400_000L)
            return when {
                days == 0L -> "ယနေ့"
                days == 1L -> "၁ ရက်"
                days < 30L -> "$days ရက်"
                days < 365L -> "${days / 30L} လ"
                else -> "${days / 365L} နှစ်"
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<UnifiedMemoryItem>() {
            override fun areItemsTheSame(oldItem: UnifiedMemoryItem, newItem: UnifiedMemoryItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: UnifiedMemoryItem, newItem: UnifiedMemoryItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}

