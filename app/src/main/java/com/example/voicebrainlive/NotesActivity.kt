package com.example.voicebrainlive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.memory.NoteRepository
import com.example.voicebrainlive.ui.components.NoteUiItem
import com.example.voicebrainlive.ui.components.NotesScreen
import com.example.voicebrainlive.ui.theme.VoiceBrainLiveTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class NotesActivity : ComponentActivity() {

    private val noteRepository by lazy { NoteRepository(this) }
    private var notes by mutableStateOf<List<NoteUiItem>>(emptyList())
    private var sortNewestFirst by mutableStateOf(true)
    private var filterCategory by mutableStateOf("")

    private val categories = listOf("", "General", "Idea", "Quick Note", "Korean", "Work")

    private val importNotesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val arr = JSONArray(input.bufferedReader().readText())
                lifecycleScope.launch {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val date = obj.optString("date", "")
                        val category = obj.optString("category", "")
                        val content = obj.optString("content", "")
                        if (content.isNotBlank()) {
                            val title = if (category.isNotBlank()) "[$category] $date" else date
                            noteRepository.saveNote(title, content)
                        }
                    }
                    loadNotes()
                    Toast.makeText(this@NotesActivity, getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                }
            } ?: run { Toast.makeText(this@NotesActivity, getString(R.string.import_error), Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Toast.makeText(this@NotesActivity, getString(R.string.import_error) + ": " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        loadNotes()

        setContent {
            VoiceBrainLiveTheme(darkTheme = ThemeUtils.getTheme(this) != "light", dynamicColor = false) {
                NotesScreen(
                    notes = notes,
                    categories = categories,
                    selectedCategory = filterCategory,
                    sortNewestFirst = sortNewestFirst,
                    onSelectCategory = { cat ->
                        filterCategory = cat
                        loadNotes()
                    },
                    onToggleSort = {
                        sortNewestFirst = !sortNewestFirst
                        loadNotes()
                        Toast.makeText(this, if (sortNewestFirst) "Newest first" else "Oldest first", Toast.LENGTH_SHORT).show()
                    },
                    onBack = { finish() },
                    onOpenNotion = { openNotionDatabase() },
                    onExportNotes = { exportNotes() },
                    onImportNotes = { importNotesLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onClearAll = {
                        lifecycleScope.launch {
                            noteRepository.clearAll()
                            loadNotes()
                            Toast.makeText(this@NotesActivity, "All notes cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSaveEdit = { note, newContent, newCategory ->
                        lifecycleScope.launch {
                            val baseTitle = note.date.replace(Regex("\\[.*?\\] "), "")
                            val newTitle = if (newCategory.isNotBlank()) "[$newCategory] $baseTitle" else baseTitle
                            noteRepository.updateNote(note.id, newTitle, newContent)
                            loadNotes()
                        }
                    },
                    onDeleteNote = { noteId ->
                        lifecycleScope.launch {
                            val toDelete = noteRepository.getAllNotes().find { it.id == noteId }
                            if (toDelete != null) {
                                noteRepository.deleteNote(toDelete)
                                loadNotes()
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadNotes()
    }

    private fun loadNotes() {
        lifecycleScope.launch {
            val all = noteRepository.getAllNotes()
            val parsed = all.map { entity ->
                val category = Regex("\\[(.*?)\\]").find(entity.title)?.groupValues?.get(1) ?: ""
                NoteUiItem(entity.id, entity.title, entity.content, category, entity.timestamp)
            }
            val filtered = if (filterCategory.isEmpty()) parsed
            else parsed.filter { it.category.equals(filterCategory, ignoreCase = true) }

            notes = if (sortNewestFirst) filtered.sortedByDescending { it.timestamp }
            else filtered.sortedBy { it.timestamp }
        }
    }

    private fun exportNotes() {
        lifecycleScope.launch {
            val all = noteRepository.getAllNotes()
            val arr = JSONArray()
            all.forEach { note ->
                arr.put(JSONObject().apply {
                    put("id", note.id.toString())
                    put("date", note.title)
                    put("content", note.content)
                })
            }
            val file = File(cacheDir, "voice_notes_export.json")
            FileOutputStream(file).use { it.write(arr.toString().toByteArray()) }
            val uri = FileProvider.getUriForFile(this@NotesActivity, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, "Voice Brain Notes Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, getString(R.string.export_notes)) })
            Toast.makeText(this@NotesActivity, getString(R.string.export_success), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotionDatabase() {
        val dbId = SettingsActivity.getNotionDatabaseId(this)
            ?.replace("-", "")?.replace("\u2013", "")?.replace("\u2014", "")?.trim()

        if (dbId.isNullOrBlank()) {
            Toast.makeText(this, "Notion Database ID မရှိသေးပါ၊ Settings တွင် ထည့်သွင်းပေးပါ", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val url = "https://www.notion.so/$dbId"
        val customSchemeUrl = "notion://www.notion.so/$dbId"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("notion.id")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(customSchemeUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e3: Exception) {
                    Toast.makeText(this, "Notion ကို ဖွင့်၍မရပါ: ${e3.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
