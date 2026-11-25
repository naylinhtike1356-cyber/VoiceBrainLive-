package com.example.voicebrainlive.ui.components

import android.content.Context
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicebrainlive.ReminderHelper
import com.example.voicebrainlive.SettingsActivity
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    reminders: List<ReminderHelper.ReminderItem>,
    onBack: () -> Unit,
    onDeleteReminder: (ReminderHelper.ReminderItem) -> Unit,
    onClearPast: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<ReminderHelper.ReminderItem?>(null) }
    var showClearPastDialog by remember { mutableStateOf(false) }
    val pastCount = remember(reminders) { reminders.count { it.isPast } }
    val context = LocalContext.current

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("သတိပေးချက် ဖျက်မယ်") },
            text = { Text("\"${item.message}\" ကို ဖျက်မလား") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    pendingDelete = null
                    onDeleteReminder(target)
                }) {
                    Text("ဖျက်မယ်")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("မလုပ်တော့ဘူး")
                }
            }
        )
    }

    if (showClearPastDialog) {
        AlertDialog(
            onDismissRequest = { showClearPastDialog = false },
            title = { Text("ပြီးသွားတာတွေ ဖျက်မယ်") },
            text = { Text("ပြီးသွားပြီး သတိပေးချက် $pastCount ခု ဖျက်ပါမယ်") },
            confirmButton = {
                TextButton(onClick = {
                    showClearPastDialog = false
                    onClearPast()
                }) {
                    Text("ဖျက်မယ်")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearPastDialog = false }) {
                    Text("မလုပ်တော့ဘူး")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("သတိပေးချက်များ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (pastCount > 0) {
                        TextButton(onClick = { showClearPastDialog = true }) {
                            Text("ပြီးသွားတာ ရှင်း")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (reminders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "သတိပေးချက် မရှိသေးပါ။",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(reminders, key = { it.id }) { item ->
                    val timeLabel = if (item.isPast) "ပြီးပြီ" else formatReminderTime(context, item.triggerTime)
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
                                    text = item.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = timeLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.alpha(if (item.isPast) 0.6f else 1f)
                                )
                            }
                            IconButton(onClick = { pendingDelete = item }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete reminder")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatReminderTime(context: Context, millis: Long): String {
    val diff = millis - System.currentTimeMillis()
    return when {
        diff < 60_000 -> "ယခု"
        diff < 3_600_000 -> "${diff / 60_000} မိနစ် ကြာမယ်"
        else -> {
            val cal = Calendar.getInstance(SettingsActivity.getTimezone(context))
            cal.timeInMillis = millis
            String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
    }
}
