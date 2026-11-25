package com.example.voicebrainlive

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.voicebrainlive.ui.components.HistoryScreen
import com.example.voicebrainlive.ui.theme.VoiceBrainLiveTheme

class HistoryActivity : ComponentActivity() {

    private var entries by mutableStateOf<List<HistoryEntry>>(emptyList())
    private var searchQuery by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        loadHistory()

        setContent {
            VoiceBrainLiveTheme(darkTheme = ThemeUtils.getTheme(this) != "light", dynamicColor = false) {
                HistoryScreen(
                    entries = entries,
                    searchQuery = searchQuery,
                    onSearchChange = { q ->
                        searchQuery = q
                        loadHistory()
                    },
                    onBack = { finish() },
                    onClearAll = {
                        VoiceHistory.clear(this)
                        loadHistory()
                        Toast.makeText(this, "မှတ်တမ်း ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                    },
                    onShare = { entry -> shareEntry(entry) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        val all = VoiceHistory.getAll(this)
        val q = searchQuery.trim().lowercase()
        entries = if (q.isBlank()) {
            all
        } else {
            all.filter { it.userText.lowercase().contains(q) || it.assistantText.lowercase().contains(q) }
        }
    }

    private fun shareEntry(entry: HistoryEntry) {
        val rawUser = entry.userText.trim()
        val displayUser = rawUser.removePrefix("🎤").removePrefix("⌨️").removePrefix("🔇").trim()
        val finalUser = if (displayUser.isNotBlank()) displayUser else if (rawUser.contains("🎤") || rawUser.isBlank()) "🎤 အသံဖြင့်မေးမြန်းမှု" else rawUser
        val text = buildString {
            if (finalUser.isNotBlank()) append("You: $finalUser\n")
            if (entry.assistantText.isNotBlank()) append("Assistant: ${entry.assistantText}")
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share"))
    }
}
