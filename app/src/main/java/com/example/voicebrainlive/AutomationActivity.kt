package com.example.voicebrainlive

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.example.voicebrainlive.agent.background.ScheduledRequestsPanel
import com.example.voicebrainlive.databinding.ActivityAutomationBinding
import com.example.voicebrainlive.databinding.ActivityMacrosBinding
import com.example.voicebrainlive.routines.RoutineStorage
import com.example.voicebrainlive.routines.RoutineScheduler
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Samsung Modes & Routines Hub for VoiceBrainLive.
 * Consolidates Modes (Bedtime, Work, Focus, Morning),
 * Unified Routines & Macros with multi-action pipelines (Ringer, Volume, Music, Sleep Timer),
 * and Autonomous Background Agent Tasks.
 */
class AutomationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutomationBinding
    private lateinit var adapter: AgentTaskAdapter
    private lateinit var scheduled: ScheduledRequestsPanel
    private var macros: MacrosPanel? = null
    private var selectedTab = 0
    private var resumed = false

    sealed class AgentItem {
        data class Routine(val item: RoutineStorage.RoutineItem) : AgentItem()
        data class Reminder(val item: ReminderHelper.ReminderItem) : AgentItem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.voicebrainlive.agent.connected.ConnectedUi.theme(this)
        setTheme(if (ThemeUtils.getTheme(this) == "light") R.style.Theme_VoiceBrainLive_AutomationHub_Light else R.style.Theme_VoiceBrainLive_AutomationHub)
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddAutomation.setOnClickListener { showCreateDialog() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        // Setup background agent / notion task panel
        scheduled = ScheduledRequestsPanel(this)
        scheduled.attach(binding.scheduledBody)

        // Setup Quick Modes card actions
        setupQuickModes()

        // Tab listener
        binding.hubTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = selectTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { if (resumed) refreshSelectedPanel() }
        })

        // Unified RecyclerView Adapter
        adapter = AgentTaskAdapter(
            items = emptyList(),
            onEdit = { 
                if (it is AgentItem.Routine) showEditDialog(it.item)
                else Toast.makeText(this, "ဒီသတိပေးချက်ကို ပြင်လို့မရသေးပါ။ ဖျက်ပြီး အသစ်ပြန်ပြောပေးပါ။", Toast.LENGTH_SHORT).show()
            },
            onDelete = { 
                when (it) {
                    is AgentItem.Routine -> confirmDeleteRoutine(it.item)
                    is AgentItem.Reminder -> confirmDeleteReminder(it.item)
                }
            },
            onToggle = { item, checked ->
                RoutineStorage.setEnabled(this, item.id, checked)
                RoutineStorage.getById(this, item.id)?.let { RoutineScheduler.schedule(this, it) }
                loadAgentTasks()
            },
            onRun = { item ->
                executeRoutineNow(item)
            }
        )
        binding.recyclerAutomation.layoutManager = LinearLayoutManager(this)
        binding.recyclerAutomation.adapter = adapter

        val restored = savedInstanceState?.getInt("hub_tab", 0)?.coerceIn(0, 2) ?: 0
        binding.hubTabs.getTabAt(restored)?.select()
        selectTab(restored)
    }

    private fun setupQuickModes() {
        // Bedtime Mode
        binding.btnStartBedtime.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@AutomationActivity, "🌙 အိပ်စက်ချိန် မုဒ် စတင်နေပါသည်...", Toast.LENGTH_SHORT).show()
                val runner = AutomationCommandRunner(this@AutomationActivity)
                runner.run(
                    type = "bedtime",
                    message = "အိပ်ရာဝင်ချိန်",
                    musicQuery = "အိပ်ရာဝင်သီချင်း",
                    ringerMode = "silent",
                    volumePercent = 20,
                    sleepTimerMinutes = 30
                )
                Toast.makeText(this@AutomationActivity, "🌙 အိပ်စက်ချိန် မုဒ် ဖွင့်ပြီးပါပြီ (သီချင်း ၃၀ မိနစ်ကြာလျှင် အလိုအလျောက် ပိတ်မည်)", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnConfigBedtime.setOnClickListener {
            showCreateDialogInternal(
                RoutineStorage.RoutineItem(
                    id = -1,
                    name = "အိပ်စက်ချိန် မုဒ် (Bedtime)",
                    type = "bedtime",
                    hour = 22,
                    minute = 30,
                    musicQuery = "အိပ်ရာဝင်သီချင်း",
                    triggerPhrase = "အိပ်တော့မယ်",
                    ringerMode = "silent",
                    volumePercent = 20,
                    sleepTimerMinutes = 30
                )
            )
        }

        // Work Mode
        binding.btnStartWork.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@AutomationActivity, "💼 အလုပ်ခွင် မုဒ် စတင်နေပါသည်...", Toast.LENGTH_SHORT).show()
                val runner = AutomationCommandRunner(this@AutomationActivity)
                runner.run(
                    type = "work",
                    message = "အလုပ်ခွင်",
                    musicQuery = "ပျော်ရွှင်စေမည့်သီချင်း",
                    ringerMode = "silent",
                    volumePercent = 50
                )
                Toast.makeText(this@AutomationActivity, "💼 အလုပ်ခွင် မုဒ် ဖွင့်ပြီးပါပြီ (စိုင်းလန့် + အသံ ၅၀% + သီချင်းဖွင့်)", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnConfigWork.setOnClickListener {
            showCreateDialogInternal(
                RoutineStorage.RoutineItem(
                    id = -1,
                    name = "အလုပ်ခွင် မုဒ် (Work)",
                    type = "work",
                    hour = 8,
                    minute = 30,
                    musicQuery = "ပျော်ရွှင်စေမည့်သီချင်း",
                    triggerPhrase = "အလုပ်ဝင်တော့မယ်",
                    ringerMode = "silent",
                    volumePercent = 50,
                    dayOfWeek = 8,
                    repeatDays = listOf(2, 3, 4, 5, 6)
                )
            )
        }

        // Focus Mode
        binding.btnStartFocus.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@AutomationActivity, "🧘 အာရုံစိုက် မုဒ် စတင်နေပါသည်...", Toast.LENGTH_SHORT).show()
                val runner = AutomationCommandRunner(this@AutomationActivity)
                runner.run(
                    type = "focus",
                    message = "အာရုံစိုက်ချိန်",
                    musicQuery = "study focus ambient music",
                    ringerMode = "silent",
                    volumePercent = 40
                )
                Toast.makeText(this@AutomationActivity, "🧘 အာရုံစိုက် မုဒ် ဖွင့်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
            }
        }

        // Morning Mode
        binding.btnStartMorning.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@AutomationActivity, "🌅 မနက်ခင်း မုဒ် စတင်နေပါသည်...", Toast.LENGTH_SHORT).show()
                val runner = AutomationCommandRunner(this@AutomationActivity)
                val res = runner.run(
                    type = "weather",
                    message = "မနက်ခင်း ရာသီဥတု",
                    ringerMode = "normal",
                    volumePercent = 70
                )
                val msg = if (res.isNotBlank()) res else "🌅 မနက်ခင်း မုဒ် အောင်မြင်ပါသည်"
                Toast.makeText(this@AutomationActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun executeRoutineNow(item: RoutineStorage.RoutineItem) {
        lifecycleScope.launch {
            Toast.makeText(this@AutomationActivity, "⚡ '${item.name.ifBlank { item.type }}' လုပ်ဆောင်နေပါသည်...", Toast.LENGTH_SHORT).show()
            val runner = AutomationCommandRunner(this@AutomationActivity)
            val res = runner.run(
                type = item.type,
                message = item.message,
                musicQuery = item.musicQuery,
                ringerMode = item.ringerMode,
                volumePercent = item.volumePercent,
                sleepTimerMinutes = item.sleepTimerMinutes,
                actionsSequence = item.actionsSequence
            )
            val feedback = if (res.isNotBlank()) res else "အော်တိုမေးရှင်း အောင်မြင်စွာ လုပ်ဆောင်ပြီးပါပြီ"
            Toast.makeText(this@AutomationActivity, feedback, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshSelectedPanel()
    }

    override fun onPause() {
        resumed = false
        scheduled.pause()
        macros?.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("hub_tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun selectTab(position: Int) {
        if (selectedTab != position) {
            scheduled.pause()
            macros?.pause()
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                android.transition.TransitionManager.beginDelayedTransition(binding.hubContent, android.transition.Fade().setDuration(140))
            }
        }
        selectedTab = position
        binding.modesPanel.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.routinesPanel.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.scheduledPanel.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.macrosPanel.visibility = View.GONE

        binding.tvTabHeading.setText(when (position) {
            0 -> R.string.hub_modes_heading
            1 -> R.string.hub_routines_heading
            else -> R.string.hub_agent_tasks_heading
        })
        binding.tvTabExplanation.setText(when (position) {
            0 -> R.string.hub_modes_explanation
            1 -> R.string.hub_routines_explanation
            else -> R.string.hub_agent_tasks_explanation
        })
        binding.tvTabExplanation.visibility = View.VISIBLE
        if (resumed) refreshSelectedPanel()
    }

    private fun refreshSelectedPanel() {
        when (selectedTab) {
            0 -> { /* Modes panel needs no dynamic refresh */ }
            1 -> loadAgentTasks()
            2 -> scheduled.resume()
        }
    }

    private fun loadAgentTasks() {
        val routines = RoutineStorage.getAll(this).map { AgentItem.Routine(it) }
        val reminders = ReminderHelper.getReminders(this).map { AgentItem.Reminder(it) }
        
        val combined = (routines + reminders).sortedWith(compareBy<AgentItem> {
            when (it) {
                is AgentItem.Routine -> if (it.item.enabled) 0 else 1
                is AgentItem.Reminder -> if (it.item.isPast) 2 else 0
            }
        }.thenBy {
            when (it) {
                is AgentItem.Routine -> RoutineScheduler.nextTriggerTime(it.item.hour, it.item.minute, it.item.repeatDays, it.item.dayOfWeek)
                is AgentItem.Reminder -> it.item.triggerTime
            }
        })
        
        adapter.updateList(combined)
        binding.tvEmpty.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerAutomation.visibility = if (combined.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDeleteRoutine(item: RoutineStorage.RoutineItem) {
        val title = item.name.ifBlank {
            when (item.type) {
                "bedtime" -> "အိပ်စက်ချိန် မုဒ် (Bedtime)"
                "work" -> "အလုပ်ခွင် မုဒ် (Work)"
                "focus" -> "အာရုံစိုက် မုဒ် (Focus)"
                "weather" -> getString(R.string.automation_type_weather)
                "music" -> item.musicQuery.ifBlank { getString(R.string.automation_type_music) }
                else -> item.message.ifBlank { getString(R.string.automation_type_reminder) }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.automation_delete_confirm))
            .setMessage(title)
            .setPositiveButton(getString(R.string.desc_delete)) { _, _ ->
                RoutineStorage.delete(this, item.id)
                RoutineScheduler.cancel(this, item.id)
                loadAgentTasks()
                Toast.makeText(this, getString(R.string.automation_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun confirmDeleteReminder(item: ReminderHelper.ReminderItem) {
        AlertDialog.Builder(this)
            .setTitle("သတိပေးချက် ဖျက်မလား?")
            .setMessage(item.message)
            .setPositiveButton("ဖျက်မယ်") { _, _ ->
                ReminderHelper.deleteReminder(this, item.id)
                loadAgentTasks()
                Toast.makeText(this, "ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("မလုပ်တော့ဘူး", null)
            .show()
    }

    private fun showCreateDialog() {
        val newItem = RoutineStorage.RoutineItem(
            id = -1,
            name = "",
            type = "reminder",
            hour = 7,
            minute = 0
        )
        showCreateDialogInternal(newItem)
    }

    private fun showEditDialog(item: RoutineStorage.RoutineItem) {
        showCreateDialogInternal(item, isEdit = true)
    }

    private fun showCreateDialogInternal(template: RoutineStorage.RoutineItem, isEdit: Boolean = false) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_automation, null)
        val etRoutineName = view.findViewById<EditText>(R.id.etRoutineName)
        val etTriggerPhrase = view.findViewById<EditText>(R.id.etTriggerPhrase)
        val spinnerType = view.findViewById<Spinner>(R.id.spinnerType)
        val etHour = view.findViewById<EditText>(R.id.etHour)
        val etMinute = view.findViewById<EditText>(R.id.etMinute)
        val spinnerDay = view.findViewById<Spinner>(R.id.spinnerDay)
        val spinnerRingerMode = view.findViewById<Spinner>(R.id.spinnerRingerMode)
        val etVolumePercent = view.findViewById<EditText>(R.id.etVolumePercent)
        val etMusicQuery = view.findViewById<EditText>(R.id.etMusicQuery)
        val tvMusicLabel = view.findViewById<TextView>(R.id.tvMusicLabel)
        val spinnerSleepTimer = view.findViewById<Spinner>(R.id.spinnerSleepTimer)
        val tvSleepTimerLabel = view.findViewById<TextView>(R.id.tvSleepTimerLabel)
        val etMessage = view.findViewById<EditText>(R.id.etMessage)
        val tvMessageLabel = view.findViewById<TextView>(R.id.tvMessageLabel)
        val etReminderMinutesBefore = view.findViewById<EditText>(R.id.etReminderMinutesBefore)
        val etReminderText = view.findViewById<EditText>(R.id.etReminderText)
        val switchEnabled = view.findViewById<Switch>(R.id.switchEnabled)
        val switchLiveAgent = view.findViewById<Switch>(R.id.switchLiveAgent)

        // Routine Types
        val typeList = listOf(
            "🌙 အိပ်စက်ချိန် မုဒ် (Bedtime Mode)" to "bedtime",
            "💼 အလုပ်ခွင် မုဒ် (Work Mode)" to "work",
            "🧘 အာရုံစိုက် မုဒ် (Focus Mode)" to "focus",
            "🎵 သီချင်း / ဖွင့်ရန် (Music)" to "music",
            "🌤️ မနက်ခင်း ရာသီဥတု (Weather)" to "weather",
            "⏰ သတိပေးချက် (Reminder)" to "reminder",
            "📊 လစာမှတ်တမ်း စစ်ဆေး" to "daily_log",
            "⚡ စိတ်ကြိုက် မက်ခရို (Macro Sequence)" to "macro"
        )
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList.map { it.first }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentTypeIdx = typeList.indexOfFirst { it.second == template.type }.coerceAtLeast(0)
        spinnerType.setSelection(currentTypeIdx)

        // Day of Week options
        val dayList = listOf(
            "နေ့စဉ် (Everyday)",
            "တနင်္လာ မှ သောကြာ (ရုံးဖွင့်ရက်များ - Weekdays)",
            "စနေ နှင့် တနင်္ဂနွေ (ရုံးပိတ်ရက်များ - Weekends)",
            "တနင်္ဂနွေ (Sunday)",
            "တနင်္လာ (Monday)",
            "အင်္ဂါ (Tuesday)",
            "ဗုဒ္ဓဟူး (Wednesday)",
            "ကြာသပတေး (Thursday)",
            "သောကြာ (Friday)",
            "စနေ (Saturday)"
        )
        spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dayList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val daySelection = when {
            template.repeatDays.sorted() == listOf(2, 3, 4, 5, 6) || template.dayOfWeek == 8 -> 1
            template.repeatDays.sorted() == listOf(1, 7) || template.dayOfWeek == 9 -> 2
            template.dayOfWeek in 1..7 && template.repeatDays.size <= 1 -> template.dayOfWeek + 2
            else -> 0
        }
        spinnerDay.setSelection(daySelection)

        // Ringer Mode options
        val ringerList = listOf(
            "မပြောင်းလဲပါ (Keep current)" to "",
            "🔇 အသံတိတ် (Silent)" to "silent",
            "📳 တုန်ခါမှု (Vibrate)" to "vibrate",
            "🔔 ပုံမှန် အသံ (Normal)" to "normal"
        )
        spinnerRingerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ringerList.map { it.first }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val ringerIdx = ringerList.indexOfFirst { it.second.equals(template.ringerMode, ignoreCase = true) }.coerceAtLeast(0)
        spinnerRingerMode.setSelection(ringerIdx)

        // Sleep Timer options
        val sleepTimerList = listOf(
            "မပိတ်ပါ (No sleep timer)" to 0,
            "၁၅ မိနစ်ကြာလျှင် သီချင်းပိတ် (15 min)" to 15,
            "၃၀ မိနစ်ကြာလျှင် သီချင်းပိတ် (30 min)" to 30,
            "၄၅ မိနစ်ကြာလျှင် သီချင်းပိတ် (45 min)" to 45,
            "၆၀ မိနစ်ကြာလျှင် သီချင်းပိတ် (60 min)" to 60
        )
        spinnerSleepTimer.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sleepTimerList.map { it.first }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val sleepTimerIdx = sleepTimerList.indexOfFirst { it.second == template.sleepTimerMinutes }.coerceAtLeast(0)
        spinnerSleepTimer.setSelection(sleepTimerIdx)

        // Prepopulate text inputs
        etRoutineName.setText(template.name)
        etTriggerPhrase.setText(template.triggerPhrase)
        etMusicQuery.setText(template.musicQuery)
        etMessage.setText(template.message)
        etVolumePercent.setText(if (template.volumePercent in 0..100) template.volumePercent.toString() else "")
        etReminderMinutesBefore.setText(template.reminderMinutesBefore.takeIf { it > 0 }?.toString().orEmpty())
        etReminderText.setText(template.reminderText)
        switchEnabled.isChecked = template.enabled
        switchLiveAgent.isChecked = template.useLiveAgent

        // Time Picker setup
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, template.hour)
            set(Calendar.MINUTE, template.minute)
        }
        fun updateTimeDisplay() {
            val h = calendar.get(Calendar.HOUR_OF_DAY)
            val m = calendar.get(Calendar.MINUTE)
            val amPm = if (h < 12) "AM" else "PM"
            val h12 = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            etHour.setText(h12.toString())
            etMinute.setText(String.format("%02d %s", m, amPm))
        }
        updateTimeDisplay()

        val timePickerListener = View.OnClickListener {
            val listener = android.app.TimePickerDialog.OnTimeSetListener { _, h, m ->
                calendar.set(Calendar.HOUR_OF_DAY, h)
                calendar.set(Calendar.MINUTE, m)
                updateTimeDisplay()
            }
            android.app.TimePickerDialog(this, listener, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
        }
        etHour.setOnClickListener(timePickerListener)
        etMinute.setOnClickListener(timePickerListener)

        // Field visibility rules based on selected type
        fun updateFieldsForType() {
            val selectedType = typeList[spinnerType.selectedItemPosition].second
            when (selectedType) {
                "bedtime" -> {
                    if (etMusicQuery.text.isBlank()) etMusicQuery.setText("အိပ်ရာဝင်သီချင်း")
                    if (etVolumePercent.text.isBlank()) etVolumePercent.setText("20")
                    if (spinnerSleepTimer.selectedItemPosition == 0) spinnerSleepTimer.setSelection(2) // 30 mins
                    if (spinnerRingerMode.selectedItemPosition == 0) spinnerRingerMode.setSelection(1) // silent
                    if (etTriggerPhrase.text.isBlank()) etTriggerPhrase.setText("အိပ်တော့မယ်")
                }
                "work" -> {
                    if (etMusicQuery.text.isBlank()) etMusicQuery.setText("ပျော်ရွှင်စေမည့်သီချင်း")
                    if (etVolumePercent.text.isBlank()) etVolumePercent.setText("50")
                    if (spinnerRingerMode.selectedItemPosition == 0) spinnerRingerMode.setSelection(1) // silent
                    if (etTriggerPhrase.text.isBlank()) etTriggerPhrase.setText("အလုပ်ဝင်တော့မယ်")
                }
                "focus" -> {
                    if (etMusicQuery.text.isBlank()) etMusicQuery.setText("study focus music")
                    if (etVolumePercent.text.isBlank()) etVolumePercent.setText("40")
                    if (spinnerRingerMode.selectedItemPosition == 0) spinnerRingerMode.setSelection(1) // silent
                }
            }
        }
        spinnerType.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = updateFieldsForType()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) getString(R.string.desc_edit) else getString(R.string.automation_add))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val type = typeList[spinnerType.selectedItemPosition].second
                val name = etRoutineName.text.toString().trim()
                val triggerPhrase = etTriggerPhrase.text.toString().trim()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                val musicQuery = etMusicQuery.text.toString().trim()
                val message = etMessage.text.toString().trim()
                val ringerMode = ringerList[spinnerRingerMode.selectedItemPosition].second
                val volumePercent = etVolumePercent.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: -1
                val sleepTimerMinutes = sleepTimerList[spinnerSleepTimer.selectedItemPosition].second
                val reminderMinutesBefore = etReminderMinutesBefore.text.toString().toIntOrNull()?.coerceIn(0, 1440) ?: 0
                val reminderText = etReminderText.text.toString().trim()
                val enabled = switchEnabled.isChecked
                val useLiveAgent = switchLiveAgent.isChecked

                val (dayOfWeek, repeatDays) = when (spinnerDay.selectedItemPosition) {
                    1 -> 8 to listOf(2, 3, 4, 5, 6)
                    2 -> 9 to listOf(1, 7)
                    3 -> 1 to listOf(1)
                    4 -> 2 to listOf(2)
                    5 -> 3 to listOf(3)
                    6 -> 4 to listOf(4)
                    7 -> 5 to listOf(5)
                    8 -> 6 to listOf(6)
                    9 -> 7 to listOf(7)
                    else -> 0 to listOf(1, 2, 3, 4, 5, 6, 7)
                }

                if (isEdit && template.id > 0) {
                    RoutineStorage.update(
                        context = this,
                        id = template.id,
                        type = type,
                        hour = hour,
                        minute = minute,
                        message = message,
                        dayOfWeek = dayOfWeek,
                        musicQuery = musicQuery,
                        reminderText = reminderText,
                        reminderMinutesBefore = reminderMinutesBefore,
                        enabled = enabled,
                        useLiveAgent = useLiveAgent,
                        name = name,
                        triggerPhrase = triggerPhrase,
                        ringerMode = ringerMode,
                        volumePercent = volumePercent,
                        sleepTimerMinutes = sleepTimerMinutes,
                        repeatDays = repeatDays
                    )
                    RoutineScheduler.cancel(this, template.id)
                    RoutineStorage.getById(this, template.id)?.let { RoutineScheduler.schedule(this, it) }
                    Toast.makeText(this, getString(R.string.automation_updated), Toast.LENGTH_SHORT).show()
                } else {
                    val created = RoutineStorage.add(
                        context = this,
                        type = type,
                        hour = hour,
                        minute = minute,
                        message = message,
                        dayOfWeek = dayOfWeek,
                        musicQuery = musicQuery,
                        reminderText = reminderText,
                        reminderMinutesBefore = reminderMinutesBefore,
                        enabled = enabled,
                        useLiveAgent = useLiveAgent,
                        name = name,
                        triggerPhrase = triggerPhrase,
                        ringerMode = ringerMode,
                        volumePercent = volumePercent,
                        sleepTimerMinutes = sleepTimerMinutes,
                        repeatDays = repeatDays
                    )
                    if (!enabled) {
                        RoutineStorage.setEnabled(this, created.id, false)
                    }
                    RoutineScheduler.schedule(this, RoutineStorage.getById(this, created.id)!!)
                    Toast.makeText(this, getString(R.string.automation_created), Toast.LENGTH_SHORT).show()
                }
                loadAgentTasks()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    class AgentTaskAdapter(
        private var items: List<AgentItem>,
        private val onEdit: (AgentItem) -> Unit,
        private val onDelete: (AgentItem) -> Unit,
        private val onToggle: (RoutineStorage.RoutineItem, Boolean) -> Unit,
        private val onRun: (RoutineStorage.RoutineItem) -> Unit
    ) : RecyclerView.Adapter<AgentTaskAdapter.ViewHolder>() {

        private val dayNames = arrayOf("", "တနင်္ဂနွေ", "တနင်္လာ", "အင်္ဂါ", "ဗုဒ္ဓဟူး", "ကြာသပတေး", "သောကြာ", "စနေ")

        fun updateList(list: List<AgentItem>) {
            items = list
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon: TextView = view.findViewById(R.id.tvAutomationIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvAutomationTitle)
            val tvTriggerPhrase: TextView = view.findViewById(R.id.tvTriggerPhrase)
            val tvTime: TextView = view.findViewById(R.id.tvAutomationTime)
            val tvBadges: TextView = view.findViewById(R.id.tvAutomationBadges)
            val tvStatus: TextView = view.findViewById(R.id.tvAutomationStatus)
            val btnRun: ImageButton = view.findViewById(R.id.btnRunAutomation)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEditAutomation)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteAutomation)
            val switchToggle: Switch = view.findViewById(R.id.switchAutomationToggle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_automation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            
            when (item) {
                is AgentItem.Routine -> {
                    val r = item.item
                    holder.tvIcon.text = when (r.type) {
                        "bedtime" -> "🌙"
                        "work" -> "💼"
                        "focus" -> "🧘"
                        "weather" -> "🌤️"
                        "music" -> "🎵"
                        "daily_log" -> "📊"
                        else -> "⚡"
                    }

                    // Title
                    holder.tvTitle.text = if (r.name.isNotBlank()) {
                        r.name
                    } else {
                        when (r.type) {
                            "bedtime" -> "အိပ်စက်ချိန် မုဒ် (Bedtime)"
                            "work" -> "အလုပ်ခွင် မုဒ် (Work)"
                            "focus" -> "အာရုံစိုက် မုဒ် (Focus)"
                            "weather" -> ctx.getString(R.string.automation_type_weather)
                            "music" -> r.musicQuery.ifBlank { ctx.getString(R.string.automation_type_music) }
                            "daily_log" -> "နေ့စဉ် လစာမှတ်တမ်း စစ်ဆေး"
                            else -> r.message.ifBlank { ctx.getString(R.string.automation_type_reminder) }
                        }
                    }

                    // Voice Trigger Phrase
                    if (r.triggerPhrase.isNotBlank()) {
                        holder.tvTriggerPhrase.text = "🎙️ \"${r.triggerPhrase}\""
                        holder.tvTriggerPhrase.visibility = View.VISIBLE
                    } else {
                        holder.tvTriggerPhrase.visibility = View.GONE
                    }

                    // Schedule Time & Days
                    val timeStr = String.format("%02d:%02d", r.hour, r.minute)
                    val daysStr = when {
                        r.repeatDays.sorted() == listOf(2, 3, 4, 5, 6) || r.dayOfWeek == 8 -> "ရုံးဖွင့်ရက် (တနင်္လာ-သောကြာ)"
                        r.repeatDays.sorted() == listOf(1, 7) || r.dayOfWeek == 9 -> "ရုံးပိတ်ရက် (စနေ-တနင်္ဂနွေ)"
                        r.dayOfWeek in 1..7 -> dayNames[r.dayOfWeek]
                        else -> "နေ့စဉ်"
                    }
                    holder.tvTime.text = "$daysStr $timeStr"
                    if (r.enabled) {
                        val next = RoutineScheduler.nextTriggerTime(r.hour, r.minute, r.repeatDays, r.dayOfWeek)
                        holder.tvTime.append("\n" + ctx.getString(R.string.hub_next_run, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(next))))
                    }

                    // Action Badges
                    val badges = mutableListOf<String>()
                    if (r.ringerMode.isNotBlank()) {
                        badges.add(when (r.ringerMode.lowercase()) {
                            "silent" -> "🔇 အသံတိတ်"
                            "vibrate" -> "📳 တုန်ခါမှု"
                            "normal" -> "🔔 ပုံမှန်အသံ"
                            else -> r.ringerMode
                        })
                    }
                    if (r.volumePercent in 0..100) {
                        badges.add("🔊 ${r.volumePercent}%")
                    }
                    if (r.musicQuery.isNotBlank() || r.type in listOf("music", "bedtime", "work", "focus")) {
                        val q = r.musicQuery.ifBlank { "သီချင်း" }
                        badges.add("🎵 $q")
                    }
                    if (r.sleepTimerMinutes > 0) {
                        badges.add("⏱️ ${r.sleepTimerMinutes}မိနစ် အလိုအလျောက် ပိတ်")
                    }
                    if (badges.isNotEmpty()) {
                        holder.tvBadges.text = badges.joinToString(" • ")
                        holder.tvBadges.visibility = View.VISIBLE
                    } else {
                        holder.tvBadges.visibility = View.GONE
                    }

                    holder.btnRun.visibility = View.VISIBLE
                    holder.btnRun.setOnClickListener { onRun(r) }

                    holder.btnEdit.visibility = View.VISIBLE
                    holder.switchToggle.visibility = View.VISIBLE
                    holder.switchToggle.setOnCheckedChangeListener(null)
                    holder.switchToggle.isChecked = r.enabled
                    holder.switchToggle.contentDescription = holder.tvTitle.text
                    holder.tvStatus.setText(if (r.enabled) R.string.hub_active else R.string.hub_paused)
                    holder.switchToggle.setOnCheckedChangeListener { _, checked -> onToggle(r, checked) }
                    holder.tvTitle.alpha = if (r.enabled) 1f else 0.5f
                }
                is AgentItem.Reminder -> {
                    val rem = item.item
                    holder.tvIcon.text = "⏰"
                    holder.tvTitle.text = rem.message
                    holder.tvTriggerPhrase.visibility = View.GONE
                    holder.tvBadges.visibility = View.GONE
                    holder.tvTime.text = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(rem.triggerTime))
                    holder.tvStatus.setText(if (rem.isPast) R.string.hub_reminder_elapsed else R.string.hub_active)
                    holder.btnRun.visibility = View.GONE
                    holder.btnEdit.visibility = View.GONE
                    holder.switchToggle.visibility = View.GONE
                    holder.tvTitle.alpha = if (rem.isPast) 0.5f else 1f
                }
            }
            
            holder.btnEdit.setOnClickListener { onEdit(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount() = items.size
    }
}
