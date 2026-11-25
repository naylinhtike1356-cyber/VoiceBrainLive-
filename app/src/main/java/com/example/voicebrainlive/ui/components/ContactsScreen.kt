package com.example.voicebrainlive.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.voicebrainlive.ContactsActivity.Contact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    draftName: String,
    draftPhone: String,
    onDraftNameChange: (String) -> Unit,
    onDraftPhoneChange: (String) -> Unit,
    onPickPhoneForAdd: () -> Unit,
    onAddContact: () -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCall: (Contact) -> Unit,
    onUpdate: (Contact, String, String) -> Unit,
    onDelete: (Contact) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }

    editingContact?.let { contact ->
        var editName by remember(contact) { mutableStateOf(contact.name) }
        var editPhone by remember(contact) { mutableStateOf(contact.phone) }
        AlertDialog(
            onDismissRequest = { editingContact = null },
            title = { Text("Edit Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPhone,
                        onValueChange = { editPhone = it },
                        label = { Text("Phone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editName.isNotBlank() && editPhone.isNotBlank(),
                    onClick = {
                        val target = contact
                        val n = editName.trim()
                        val p = editPhone.trim()
                        editingContact = null
                        onUpdate(target, n, p)
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingContact = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("အဆက်အသွယ်များ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Export JSON") },
                                onClick = { menuExpanded = false; onExport() }
                            )
                            DropdownMenuItem(
                                text = { Text("Import JSON") },
                                onClick = { menuExpanded = false; onImport() }
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
                .padding(horizontal = 16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = onDraftNameChange,
                        label = { Text("အမည် (ဥပမာ- မေမေ)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draftPhone,
                        onValueChange = onDraftPhoneChange,
                        label = { Text("ဖုန်းနံပါတ်") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickPhoneForAdd,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null)
                            Spacer(Modifier.padding(2.dp))
                            Text("ဖုန်းမှ ရွေး")
                        }
                        Button(
                            onClick = onAddContact,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.padding(2.dp))
                            Text("ထည့်မည်")
                        }
                    }
                }
            }

            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Quick Contacts မရှိသေးပါ။",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(contacts, key = { "${it.name}|${it.phone}" }) { contact ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = contact.phone,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    IconButton(onClick = { onCall(contact) }) {
                                        Icon(Icons.Default.Call, contentDescription = "Call")
                                    }
                                    IconButton(onClick = { editingContact = contact }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = { onDelete(contact) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
