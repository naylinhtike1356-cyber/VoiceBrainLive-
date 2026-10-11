package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.UserPreferences
import com.example.voicebrainlive.memory.MemoryConfig
import com.example.voicebrainlive.memory.VectorStore
import com.example.voicebrainlive.notion.NotionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AutomationHandler(private val context: Context) : CommandHandler {

    private val notionClient by lazy { NotionClient(context) }
    private val musicCommands by lazy { MusicCommands(context) }

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "run_routine" -> runRoutine(target ?: "")
            "quiz_from_notion" -> quizFromNotion(target ?: "korean", value?.toIntOrNull() ?: 5)
            "get_relevant_memory", "search_memory" -> getRelevantMemory(target ?: value ?: "")
            else -> null
        }
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "run_routine",
        "quiz_from_notion",
        "get_relevant_memory",
        "search_memory"
    )

    private suspend fun runRoutine(name: String): String {
        return when (name.lowercase()) {
            "good_morning", "goodmorning", "morning" -> {
                val parts = mutableListOf<String>()
                val musicPref = UserPreferences.get(context, "play_music")?.takeIf { it.isNotBlank() } ?: "Lo-fi"
                parts.add(musicCommands.execute("play_music", musicPref, null) ?: "Playing music")
                if (notionClient.isConfigured()) {
                    val todos = notionClient.getTodoItems(5)
                    if (todos.isNotEmpty()) {
                        val todoList = todos.mapIndexed { i, (n, c) ->
                            val txt = if (c.isNotBlank()) "$n: $c" else n
                            "${i + 1}. $txt"
                        }.joinToString(". ")
                        parts.add("Your to-do for today: $todoList")
                    }
                }
                openNews()
                parts.add("Opening today's news. Good morning!")
                parts.joinToString(" ")
            }
            else -> "Unknown routine: $name. Try good_morning."
        }
    }

    private fun quizFromNotion(subject: String, count: Int): String {
        if (!notionClient.isConfigured()) return "Notion not configured. Add token and database ID in Settings."
        return try {
            val items = notionClient.getItemsForQuiz(subject, count)
            if (items.isEmpty()) {
                "No items to quiz."
            } else {
                val lines = items.mapIndexed { i, (name, content) ->
                    if (content.isNotBlank()) "Question ${i + 1}: What does $name mean? Answer: $content."
                    else "Item ${i + 1}: $name."
                }
                "Quiz time! Here are ${items.size} items. ${lines.joinToString(" ")}"
            }
        } catch (e: Exception) {
            Log.e("AutomationHandler", "quizFromNotion", e)
            "Error loading quiz: ${e.message}"
        }
    }

    private suspend fun getRelevantMemory(query: String): String {
        if (query.isBlank()) return "Query missing."
        val apiKey = SettingsActivity.getGeminiApiKey(context)
        if (apiKey.isBlank() || apiKey.length < 20) return "API key required."
        val results = VectorStore.search(context, apiKey, query, topK = 6)
            .filter { it.second > MemoryConfig.vectorSimilarityThreshold }
        return if (results.isEmpty()) "No related memory found."
        else results.joinToString("\n") { "- ${it.first}" }.take(650)
    }

    private fun openNews() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://news.google.com/search?q=" + java.net.URLEncoder.encode("Korea news today", "UTF-8"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "News"))
        } catch (e: Exception) {
            Log.w("AutomationHandler", "openNews", e)
        }
    }
}
