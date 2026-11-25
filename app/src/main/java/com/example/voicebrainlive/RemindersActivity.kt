package com.example.voicebrainlive

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.voicebrainlive.ui.components.RemindersScreen
import com.example.voicebrainlive.ui.theme.VoiceBrainLiveTheme

class RemindersActivity : ComponentActivity() {

    private var reminders by mutableStateOf<List<ReminderHelper.ReminderItem>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        loadReminders()

        setContent {
            VoiceBrainLiveTheme(darkTheme = ThemeUtils.getTheme(this) != "light", dynamicColor = false) {
                RemindersScreen(
                    reminders = reminders,
                    onBack = { finish() },
                    onDeleteReminder = { item ->
                        ReminderHelper.deleteReminder(this, item.id)
                        loadReminders()
                        Toast.makeText(this, "ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                    },
                    onClearPast = {
                        ReminderHelper.clearAllPast(this)
                        loadReminders()
                        Toast.makeText(this, "ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadReminders()
    }

    private fun loadReminders() {
        reminders = ReminderHelper.getReminders(this)
    }
}
