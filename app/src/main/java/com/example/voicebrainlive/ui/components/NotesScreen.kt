package com.example.voicebrainlive.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class NoteUiItem(
    val id: Long,
    val date: String,
    val content: String,
    val category: String,
    val timestamp: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    notes: List<NoteUiItem>,
    categories: List<String>,
    selectedCategory: String,
    sortNewestFirst: Boolean,
    onSelectCategory: (String) -> Unit,
    onToggleSort: () -> Unit,
    onBack: () -> Unit,
    onOpenNotion: () -> Unit,
    onExportNotes: () -> Unit,
    onImportNotes: () -> Unit,
    onClearAll: () -> Unit,
    onSaveEdit: (NoteUiItem, String, String) -> Unit,
    onDeleteNote: (Long) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteUiItem?>(null) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("မှတ်စုအားလုံး ဖျက်မလား") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearAll()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    editingNote?.let { note ->
        var editContent by remember(note.id) { mutableStateOf(note.content) }
        var editCategory by remember(note.id) { mutableStateOf(note.category) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("Edit Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val choices = listOf("") + categories.filter { it.isNotEmpty() }
                        choices.forEach { cat ->
                            FilterChip(
                                selected = editCategory.equals(cat, ignoreCase = true),
                                onClick = { editCategory = cat },
                                label = { Text(if (cat.isEmpty()) "—" else cat) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Content") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editContent.isNotBlank(),
                    onClick = {
                        val target = note
                        val content = editContent.trim()
                        val cat = editCategory
                        editingNote = null
                        onSaveEdit(target, content, cat)
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("မှတ်စုနှင့် Notion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onToggleSort) {
                        Text(if (sortNewestFirst) "Newest" else "Oldest")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Notion ဖွင့်မည်") },
                                onClick = { menuExpanded = false; onOpenNotion() }
                            )
                            DropdownMenuItem(
                                text = { Text("Export JSON") },
                                onClick = { menuExpanded = false; onExportNotes() }
                            )
                            DropdownMenuItem(
                                text = { Text("Import JSON") },
                                onClick = { menuExpanded = false; onImportNotes() }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All Notes") },
                                onClick = { menuExpanded = false; showClearDialog = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory.isEmpty(),
                    onClick = { onSelectCategory("") },
                    label = { Text("All") }
                )
                categories.filter { it.isNotEmpty() }.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory.equals(cat, ignoreCase = true),
                        onClick = { onSelectCategory(cat) },
                        label = { Text(cat) }
                    )
                }
            }

            if (notes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "မှတ်စု မရှိသေးပါ။",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = note.date,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row {
                                        IconButton(onClick = { editingNote = note }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit note")
                                        }
                                        IconButton(onClick = { onDeleteNote(note.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete note")
                                        }
                                    }
                                }
                                if (note.category.isNotEmpty()) {
                                    AssistChip(
                                        onClick = { onSelectCategory(note.category) },
                                        label = { Text(note.category) }
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = note.content,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
