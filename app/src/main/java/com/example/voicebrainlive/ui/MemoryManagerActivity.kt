package com.example.voicebrainlive.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voicebrainlive.R
import com.example.voicebrainlive.databinding.ActivityMemoryManagerBinding
import com.example.voicebrainlive.learning.IntentPatternLearner
import com.example.voicebrainlive.memory.NoteRepository
import com.example.voicebrainlive.memory.PersistentFactsStorage
import com.example.voicebrainlive.memory.VoiceBrainDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoryManagerBinding
    private lateinit var adapter: MemoryAdapter

    private val masterList = mutableListOf<UnifiedMemoryItem>()
    private var selectedCategory: MemoryCategory? = null
    private var currentSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        adapter = MemoryAdapter(
            onEditClick = { item -> showEditMemoryDialog(item) },
            onDeleteClick = { item -> showDeleteConfirmationDialog(item) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        setupViewModeSwitcher()
        setupNeuralBrainInteraction()
        setupSearchAndFilters()
        setupAddButton()

        loadAllMemories()
    }

    private fun setupViewModeSwitcher() {
        binding.btnModeNeural.setOnClickListener {
            binding.containerNeuralBrain.visibility = View.VISIBLE
            binding.containerDetailedList.visibility = View.GONE
            binding.btnModeNeural.setBackgroundResource(R.drawable.bg_gem)
            binding.btnModeNeural.setTextColor(android.graphics.Color.WHITE)
            binding.btnModeNeural.typeface = android.graphics.Typeface.DEFAULT_BOLD
            binding.btnModeList.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.btnModeList.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            binding.btnModeList.typeface = android.graphics.Typeface.DEFAULT
        }

        binding.btnModeList.setOnClickListener {
            binding.containerNeuralBrain.visibility = View.GONE
            binding.containerDetailedList.visibility = View.VISIBLE
            binding.btnModeList.setBackgroundResource(R.drawable.bg_gem)
            binding.btnModeList.setTextColor(android.graphics.Color.WHITE)
            binding.btnModeList.typeface = android.graphics.Typeface.DEFAULT_BOLD
            binding.btnModeNeural.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.btnModeNeural.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            binding.btnModeNeural.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    private fun setupNeuralBrainInteraction() {
        binding.neuralBrainView.onNodeClickListener = { item ->
            showSelectedNodeCard(item)
        }

        binding.btnCloseSelectedCard.setOnClickListener {
            binding.cardSelectedNode.visibility = View.GONE
            binding.neuralBrainView.selectMemory(null)
        }
    }

    private fun showSelectedNodeCard(item: UnifiedMemoryItem) {
        binding.cardSelectedNode.visibility = View.VISIBLE
        binding.tvSelectedNodeTitle.text = item.title
        binding.tvSelectedNodeContent.text = item.content

        val (catName, catBg) = when (item.category) {
            MemoryCategory.FACT -> Pair("📌 အချက်အလက် (Fact)", "#0284C7")
            MemoryCategory.HABIT -> Pair("🧠 အလေ့အထ (Habit)", "#7C3AED")
            MemoryCategory.CONVERSATION -> Pair("💬 စကားပြောမှတ်ဉာဏ် (AI)", "#2563EB")
            MemoryCategory.NOTE -> Pair("📝 မှတ်စု (Note)", "#D97706")
        }
        binding.tvSelectedNodeCategory.text = catName
        binding.tvSelectedNodeCategory.setBackgroundColor(android.graphics.Color.parseColor(catBg))

        binding.btnEditSelectedNode.visibility = if (item.isEditable) View.VISIBLE else View.GONE
        binding.btnEditSelectedNode.setOnClickListener {
            showEditMemoryDialog(item)
        }

        binding.btnDeleteSelectedNode.setOnClickListener {
            showDeleteConfirmationDialog(item)
        }
    }

    private fun setupSearchAndFilters() {
        binding.etSearchMemory.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchMemory.text.clear()
        }

        binding.chipGroupCategory.setOnCheckedChangeListener { _, checkedId ->
            selectedCategory = when (checkedId) {
                R.id.chipFacts -> MemoryCategory.FACT
                R.id.chipHabits -> MemoryCategory.HABIT
                R.id.chipConversations -> MemoryCategory.CONVERSATION
                R.id.chipNotes -> MemoryCategory.NOTE
                else -> null // All
            }
            applyFilters()
        }
    }

    private fun setupAddButton() {
        binding.fabAddMemory.setOnClickListener {
            showAddMemoryDialog()
        }
    }

    private fun loadAllMemories() {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = mutableListOf<UnifiedMemoryItem>()

            // 1. Load Persistent Facts
            try {
                val facts = PersistentFactsStorage.getAllFacts(this@MemoryManagerActivity)
                facts.forEach { (key, value) ->
                    items.add(
                        UnifiedMemoryItem(
                            id = "fact_$key",
                            category = MemoryCategory.FACT,
                            title = key,
                            content = value,
                            timestamp = 0L,
                            rawKey = key,
                            isEditable = true,
                            source = "Persistent facts",
                            confidencePercent = 100,
                            expiryAt = null
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MemoryManager", "Error loading facts", e)
            }

            // 2. Load Learned Habits / Intent Patterns
            try {
                val patterns = IntentPatternLearner.getAllPatterns(this@MemoryManagerActivity)
                patterns.forEach { pattern ->
                    val desc = if (pattern.lastTarget.isNotBlank()) {
                        "လုပ်ဆောင်ချက်: ${pattern.commandType} (${pattern.lastTarget})"
                    } else {
                        "လုပ်ဆောင်ချက်: ${pattern.commandType}"
                    }
                    items.add(
                        UnifiedMemoryItem(
                            id = "habit_${pattern.phrase}",
                            category = MemoryCategory.HABIT,
                            title = "\"${pattern.phrase}\"",
                            content = "$desc • တိကျမှု: ${pattern.confidence}% (အသုံးပြုမှု ${pattern.hits} ကြိမ်)",
                            rawKey = pattern.phrase,
                            isEditable = false,
                            source = "Learned intent",
                            confidencePercent = pattern.confidence,
                            expiryAt = null
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MemoryManager", "Error loading habits", e)
            }

            // 3. Load Conversation AI Embeddings
            try {
                val db = VoiceBrainDatabase.getInstance(this@MemoryManagerActivity)
                val dao = db.conversationEmbeddingDao()
                val embeddings = dao.getAll().sortedByDescending { it.timestamp }
                embeddings.forEach { emb ->
                    items.add(
                        UnifiedMemoryItem(
                            id = "emb_${emb.id}",
                            category = MemoryCategory.CONVERSATION,
                            title = "စကားပြောမှတ်ဉာဏ် #${emb.id}",
                            content = emb.text,
                            timestamp = emb.timestamp,
                            rawKey = emb.id.toString(),
                            isEditable = false,
                            source = "Conversation embedding",
                            confidencePercent = null,
                            expiryAt = emb.timestamp + 90L * 24L * 60L * 60L * 1000L
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MemoryManager", "Error loading conversation embeddings", e)
            }

            // 4. Load Voice Notes
            try {
                val noteRepo = NoteRepository(this@MemoryManagerActivity)
                val notes = noteRepo.getAllNotes()
                notes.forEach { note ->
                    items.add(
                        UnifiedMemoryItem(
                            id = "note_${note.id}",
                            category = MemoryCategory.NOTE,
                            title = note.title.ifBlank { "အမြန်မှတ်စု" },
                            content = note.content,
                            timestamp = note.timestamp,
                            rawKey = note.id.toString(),
                            isEditable = true,
                            source = "Voice note",
                            confidencePercent = 100,
                            expiryAt = null
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MemoryManager", "Error loading notes", e)
            }

            withContext(Dispatchers.Main) {
                masterList.clear()
                masterList.addAll(items)
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        val filtered = masterList.filter { item ->
            val matchCategory = selectedCategory == null || item.category == selectedCategory
            val matchSearch = if (currentSearchQuery.isBlank()) true else {
                item.title.contains(currentSearchQuery, ignoreCase = true) ||
                item.content.contains(currentSearchQuery, ignoreCase = true)
            }
            matchCategory && matchSearch
        }

        binding.tvMemoryCount.text = "စုစုပေါင်း မှတ်ဉာဏ် ${filtered.size} ခု တွေ့ရှိသည်"
        binding.neuralBrainView.setMemories(filtered)
        binding.cardSelectedNode.visibility = View.GONE

        if (filtered.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            if (currentSearchQuery.isNotBlank()) {
                binding.tvEmptyState.text = "\"$currentSearchQuery\" နှင့် ကိုက်ညီသော မှတ်ဉာဏ် မတွေ့ပါ။"
            } else {
                binding.tvEmptyState.text = "ဤကဏ္ဍတွင် မှတ်ဉာဏ် မရှိသေးပါ။\n+ ခလုတ်ကို နှိပ်၍ အချက်အလက်အသစ် ထည့်သွင်းနိုင်ပါသည်။"
            }
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        adapter.submitList(filtered)
    }

    private fun showAddMemoryDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_fact, null, false)
        val etKey = dialogView.findViewById<EditText>(R.id.etFactKey)
        val etValue = dialogView.findViewById<EditText>(R.id.etFactValue)

        AlertDialog.Builder(this)
            .setTitle("အချက်အလက်အသစ် မှတ်သားရန်")
            .setView(dialogView)
            .setPositiveButton("သိမ်းမည်") { _, _ ->
                val key = etKey.text.toString().trim()
                val value = etValue.text.toString().trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        PersistentFactsStorage.addOrUpdateFact(this@MemoryManagerActivity, key, value)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MemoryManagerActivity, "'$key' ကို မှတ်သားလိုက်ပါပြီ", Toast.LENGTH_SHORT).show()
                            loadAllMemories()
                        }
                    }
                } else {
                    Toast.makeText(this, "ခေါင်းစဉ်နှင့် အချက်အလက် အပြည့်အစုံ ဖြည့်ပေးပါ", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("မလုပ်တော့ပါ", null)
            .show()
    }

    private fun showEditMemoryDialog(item: UnifiedMemoryItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_fact, null, false)
        val etKey = dialogView.findViewById<EditText>(R.id.etFactKey)
        val etValue = dialogView.findViewById<EditText>(R.id.etFactValue)

        etKey.setText(item.title)
        etValue.setText(item.content)

        if (item.category == MemoryCategory.FACT) {
            etKey.isEnabled = false // Keep key stable or allow value editing
        }

        AlertDialog.Builder(this)
            .setTitle("မှတ်ဉာဏ် ပြင်ဆင်ရန်")
            .setView(dialogView)
            .setPositiveButton("ပြင်ဆင်မည်") { _, _ ->
                val newKey = etKey.text.toString().trim()
                val newValue = etValue.text.toString().trim()
                if (newKey.isNotBlank() && newValue.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        when (item.category) {
                            MemoryCategory.FACT -> {
                                val originalKey = item.rawKey ?: item.title
                                PersistentFactsStorage.addOrUpdateFact(this@MemoryManagerActivity, originalKey, newValue)
                            }
                            MemoryCategory.NOTE -> {
                                val noteId = item.rawKey?.toLongOrNull()
                                if (noteId != null) {
                                    val noteRepo = NoteRepository(this@MemoryManagerActivity)
                                    noteRepo.updateNote(noteId, newKey, newValue)
                                }
                            }
                            else -> {}
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MemoryManagerActivity, "ပြင်ဆင်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                            loadAllMemories()
                        }
                    }
                }
            }
            .setNegativeButton("မလုပ်တော့ပါ", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(item: UnifiedMemoryItem) {
        AlertDialog.Builder(this)
            .setTitle("မှတ်ဉာဏ် ဖျက်ရန် အတည်ပြုပါ")
            .setMessage("\"${item.title}\" ကို ဖျက်ရန် သေချာပါသလား?")
            .setPositiveButton("ဖျက်မည်") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    when (item.category) {
                        MemoryCategory.FACT -> {
                            val key = item.rawKey ?: item.title
                            PersistentFactsStorage.deleteFact(this@MemoryManagerActivity, key)
                        }
                        MemoryCategory.HABIT -> {
                            val phrase = item.rawKey ?: item.title.trim('"')
                            IntentPatternLearner.deletePattern(this@MemoryManagerActivity, phrase)
                        }
                        MemoryCategory.CONVERSATION -> {
                            val id = item.rawKey?.toLongOrNull()
                            if (id != null) {
                                val db = VoiceBrainDatabase.getInstance(this@MemoryManagerActivity)
                                db.conversationEmbeddingDao().deleteById(id)
                            }
                        }
                        MemoryCategory.NOTE -> {
                            val noteId = item.rawKey?.toLongOrNull()
                            if (noteId != null) {
                                val noteRepo = NoteRepository(this@MemoryManagerActivity)
                                noteRepo.deleteNote(noteId)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MemoryManagerActivity, "ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                        loadAllMemories()
                    }
                }
            }
            .setNegativeButton("မလုပ်တော့ပါ", null)
            .show()
    }
}

