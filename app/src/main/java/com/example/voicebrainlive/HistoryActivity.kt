package com.example.voicebrainlive

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : ComponentActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearHistory.setOnClickListener {
            VoiceHistory.clear(this)
            loadHistory()
            Toast.makeText(this, "မှတ်တမ်း ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
        }

        binding.etHistorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                loadHistory()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        adapter = HistoryAdapter(mutableListOf()) { entry ->
            val text = buildString {
                val rawUser = entry.userText.trim()
                val displayUser = rawUser.removePrefix("🎤").removePrefix("⌨️").removePrefix("🔇").trim()
                val finalUser = if (displayUser.isNotBlank()) displayUser else if (rawUser.contains("🎤") || rawUser.isBlank()) "🎤 အသံဖြင့်မေးမြန်းမှု" else rawUser
                
                if (finalUser.isNotBlank()) append("You: ${finalUser}\n")
                if (entry.assistantText.isNotBlank()) append("Assistant: ${entry.assistantText}")
            }
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share"))
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
        loadHistory()
    }

    private fun loadHistory() {
        Log.d("HistoryActivity", "loadHistory() called")
        var list = VoiceHistory.getAll(this)
        Log.d("HistoryActivity", "VoiceHistory returned ${list.size} entries")
        
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            list = list.filter { entry ->
                entry.userText.lowercase().contains(q) || entry.assistantText.lowercase().contains(q)
            }
        }
        adapter.updateList(list)
        binding.tvEmptyHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        
        Log.d("HistoryActivity", "After update - list size: ${list.size}, empty shown: ${list.isEmpty()}")
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    class HistoryAdapter(
        private var entries: MutableList<HistoryEntry>,
        private val onShare: (HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        fun updateList(list: List<HistoryEntry>) {
            entries.clear()
            entries.addAll(list)
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tvHistoryDate)
            val tvUser: TextView = view.findViewById(R.id.tvHistoryUser)
            val tvAssistant: TextView = view.findViewById(R.id.tvHistoryAssistant)
            val btnShare: ImageButton = view.findViewById(R.id.btnShareHistory)
            val layoutUserBubble: View = view.findViewById(R.id.layoutUserBubble)
            val layoutAssistantBubble: View = view.findViewById(R.id.layoutAssistantBubble)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val e = entries[position]
            holder.tvDate.text = dateFormat.format(Date(e.timestamp))

            // --- User bubble ---
            val rawUser = e.userText.trim()
            val displayUser = rawUser
                .removePrefix("🎤").removePrefix("⌨️").removePrefix("🔇").trim()

            if (displayUser.isNotBlank()) {
                holder.layoutUserBubble.visibility = View.VISIBLE
                holder.tvUser.text = displayUser
            } else if (rawUser.contains("🎤") || rawUser.isBlank()) {
                holder.layoutUserBubble.visibility = View.VISIBLE
                holder.tvUser.text = "🎤 အသံဖြင့်မေးမြန်းမှု"
            } else {
                holder.layoutUserBubble.visibility = View.GONE
            }

            // --- Assistant bubble ---
            val displayAssistant = e.assistantText.trim()
            if (displayAssistant.isNotBlank()) {
                holder.layoutAssistantBubble.visibility = View.VISIBLE
                holder.tvAssistant.text = displayAssistant
            } else {
                holder.layoutAssistantBubble.visibility = View.GONE
            }

            holder.btnShare.setOnClickListener { onShare(e) }
        }

        override fun getItemCount() = entries.size
    }
}
