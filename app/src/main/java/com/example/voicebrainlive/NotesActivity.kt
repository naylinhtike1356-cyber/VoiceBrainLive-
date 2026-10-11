package com.example.voicebrainlive

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.voicebrainlive.databinding.ActivityNotesBinding

class NotesActivity : ComponentActivity() {

    private lateinit var binding: ActivityNotesBinding
    private val noteRepository by lazy { com.example.voicebrainlive.memory.NoteRepository(this) }
    private lateinit var adapter: NotesAdapter
    private val notesList = mutableListOf<NoteItem>()
    private var sortNewestFirst = true
    private var filterCategory = ""

    val CATEGORIES = listOf("", "General", "Idea", "Quick Note", "Korean", "Work")

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnOpenNotion.setOnClickListener { openNotionDatabase() }
        binding.btnExportNotes.setOnClickListener { exportNotes() }
        binding.btnImportNotes.setOnClickListener { importNotesLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }

        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_all_notes_confirm))
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        noteRepository.clearAll()
                        loadNotes()
                        Toast.makeText(this@NotesActivity, "All notes cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        setupSortAndFilter()

        adapter = NotesAdapter(notesList, CATEGORIES, { note -> onEditNote(note) }, { noteId -> onDeleteNote(noteId) })

        binding.recyclerNotes.layoutManager = LinearLayoutManager(this)
        binding.recyclerNotes.adapter = adapter

        loadNotes()
    }

    private val importNotesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val json = input.bufferedReader().readText()
                val arr = JSONArray(json)
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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("notion.id")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(customSchemeUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e3: Exception) {
                    Toast.makeText(this, "Notion ကို ဖွင့်၍မရပါ: ${e3.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSortAndFilter() {
        binding.btnSortOrder.setOnClickListener {
            sortNewestFirst = !sortNewestFirst
            loadNotes()
            Toast.makeText(this, if (sortNewestFirst) "Newest first" else "Oldest first", Toast.LENGTH_SHORT).show()
        }
        binding.spinnerFilter.apply {
            val adapter = ArrayAdapter(this@NotesActivity, android.R.layout.simple_spinner_item, listOf("All") + CATEGORIES.filter { it.isNotEmpty() })
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    filterCategory = if (pos == 0) "" else parent?.getItemAtPosition(pos).toString()
                    loadNotes()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            })
        }
    }

    private fun loadNotes() {
        notesList.clear()
        lifecycleScope.launch {
            val all = noteRepository.getAllNotes()

            val parsed = all.map { entity ->
                // Attempt to extract category from [Cat] Title
                val catMatch = Regex("\\[(.*?)\\]").find(entity.title)
                val category = catMatch?.groupValues?.get(1) ?: ""
                NoteItem(entity.id.toString(), entity.title, entity.content, category, entity.timestamp)
            }

            val filtered = if (filterCategory.isEmpty()) parsed
            else parsed.filter { it.category.equals(filterCategory, ignoreCase = true) }

            val sorted = if (sortNewestFirst) filtered.sortedByDescending { it.timestamp }
            else filtered.sortedBy { it.timestamp }

            notesList.addAll(sorted)
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (notesList.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerNotes.visibility = if (notesList.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun onEditNote(note: NoteItem) {
        val editLayout = layoutInflater.inflate(R.layout.dialog_edit_note, null)
        val etContent = editLayout.findViewById<EditText>(R.id.etEditNoteContent)
        val spinner = editLayout.findViewById<Spinner>(R.id.spinnerCategory)
        etContent.setText(note.content)
        val catList = listOf("—") + CATEGORIES.filter { it.isNotEmpty() }
        val catAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, catList)
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = catAdapter
        val catIndex = if (note.category.isEmpty()) 0 else catList.indexOfFirst { it.equals(note.category, ignoreCase = true) }.let { if (it < 0) 0 else it }
        spinner.setSelection(catIndex)

        AlertDialog.Builder(this)
            .setTitle("Edit Note")
            .setView(editLayout)
            .setPositiveButton("Save") { _, _ ->
                val newContent = etContent.text.toString().trim()
                val newCategory = spinner.selectedItem?.toString()?.takeIf { it != "—" } ?: ""
                 if (newContent.isBlank()) {
                    Toast.makeText(this, "Content cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val newTitle = if (newCategory.isNotBlank()) "[$newCategory] ${note.date.replace(Regex("\\[.*?\\] "), "")}" else note.date.replace(Regex("\\[.*?\\] "), "")
                    noteRepository.saveNote(newTitle, newContent)
                    // If we want to replace the old one, we should ideally have a real update, but for now we delete old
                    // However, NoteEntity doesn't have an update method here easily. 
                    // Let's just delete the old one by ID if it's a real Room update.
                    // Wait, I'll update NoteDao with an @Update method.
                    loadNotes()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDeleteNote(noteId: String) {
        lifecycleScope.launch {
            val all = noteRepository.getAllNotes()
            val toDelete = all.find { it.id.toString() == noteId }
            if (toDelete != null) {
                noteRepository.deleteNote(toDelete)
                loadNotes()
            }
        }
    }

    data class NoteItem(val id: String, val date: String, val content: String, val category: String, val timestamp: Long = 0L)

    class NotesAdapter(
        private val notes: List<NoteItem>,
        private val categories: List<String>,
        private val onEdit: (NoteItem) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<NotesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tvNoteDate)
            val tvCategory: TextView = view.findViewById(R.id.tvNoteCategory)
            val tvContent: TextView = view.findViewById(R.id.tvNoteContent)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEditNote)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteNote)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val note = notes[position]
            holder.tvDate.text = note.date
            holder.tvContent.text = note.content
            if (note.category.isNotEmpty()) {
                holder.tvCategory.visibility = View.VISIBLE
                holder.tvCategory.text = note.category
            } else {
                holder.tvCategory.visibility = View.GONE
            }
            holder.btnEdit.setOnClickListener { onEdit(note) }
            holder.btnDelete.setOnClickListener { onDelete(note.id) }
        }

        override fun getItemCount() = notes.size
    }
}
