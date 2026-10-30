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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import com.example.voicebrainlive.agent.background.ScheduledRequestsPanel
import com.example.voicebrainlive.databinding.ActivityMacrosBinding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.databinding.ActivityAutomationBinding
import com.example.voicebrainlive.routines.RoutineStorage
import com.example.voicebrainlive.routines.RoutineScheduler
import java.util.Calendar

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
        scheduled = ScheduledRequestsPanel(this)
        scheduled.attach(binding.scheduledBody)
        binding.hubTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = selectTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { if (resumed) refreshSelectedPanel() }
        })

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
            }
        )
        binding.recyclerAutomation.layoutManager = LinearLayoutManager(this)
        binding.recyclerAutomation.adapter = adapter
        val restored = savedInstanceState?.getInt("hub_tab", 0)?.coerceIn(0, 2) ?: 0
        binding.hubTabs.getTabAt(restored)?.select()
        selectTab(restored)
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
        binding.scheduledPanel.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.routinesPanel.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.macrosPanel.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.tvTabHeading.setText(when (position) {
            0 -> R.string.hub_scheduled_heading
            1 -> R.string.hub_routines_heading
            else -> R.string.hub_macros_heading
        })
        if (resumed) refreshSelectedPanel()
    }

    private fun refreshSelectedPanel() {
        when (selectedTab) {
            0 -> scheduled.resume()
            1 -> loadAgentTasks()
            2 -> {
                if (macros == null) {
                    val macroBinding = ActivityMacrosBinding.inflate(layoutInflater, binding.macrosPanel, true)
                    macroBinding.btnBack.visibility = View.GONE
                    macros = MacrosPanel(this, macroBinding)
                    // The hub supplies its own heading and compact actions; the legacy screen retains its voice guide.
                    macroBinding.macroHeader.getChildAt(1).visibility = View.GONE
                    macroBinding.btnAddMacro.visibility = View.GONE
                    macroBinding.layoutVoiceGuide.visibility = View.GONE
                    val add = com.google.android.material.button.MaterialButton(this).apply {
                        text = getString(R.string.hub_add_macro)
                        tag = "hub_macro_add"
                        isAllCaps = false
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                        setOnClickListener { macroBinding.btnAddMacro.performClick() }
                    }
                    macroBinding.macroHeader.addView(add, 0, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                macros?.loadMacros()
            }
        }
    }

    private fun loadAgentTasks() {
        val routines = RoutineStorage.getAll(this).map { AgentItem.Routine(it) }
        val reminders = ReminderHelper.getReminders(this).map { AgentItem.Reminder(it) }
        
        val combined = (routines + reminders).sortedByDescending { 
            when (it) {
                is AgentItem.Routine -> 1000L // Routines stay on top
                is AgentItem.Reminder -> if (it.item.isPast) 0L else 500L
            }
        }
        
        adapter.updateList(combined)
        binding.tvEmpty.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerAutomation.visibility = if (combined.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDeleteRoutine(item: RoutineStorage.RoutineItem) {
        val title = when (item.type) {
            "weather" -> getString(R.string.automation_type_weather)
            "music" -> item.musicQuery.ifBlank { getString(R.string.automation_type_music) }
            else -> item.message.ifBlank { getString(R.string.automation_type_reminder) }
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

    private fun showEditDialog(item: RoutineStorage.RoutineItem) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_automation, null)
        val spinnerType = view.findViewById<Spinner>(R.id.spinnerType)
        val etHour = view.findViewById<EditText>(R.id.etHour)
        val etMinute = view.findViewById<EditText>(R.id.etMinute)
        val etMessage = view.findViewById<EditText>(R.id.etMessage)
        val tvMessageLabel = view.findViewById<TextView>(R.id.tvMessageLabel)
        val tvMusicLabel = view.findViewById<TextView>(R.id.tvMusicLabel)
        val etMusicQuery = view.findViewById<EditText>(R.id.etMusicQuery)
        val etReminderMinutesBefore = view.findViewById<EditText>(R.id.etReminderMinutesBefore)
        val etReminderText = view.findViewById<EditText>(R.id.etReminderText)
        val spinnerDay = view.findViewById<Spinner>(R.id.spinnerDay)
        val switchEnabled = view.findViewById<android.widget.Switch>(R.id.switchEnabled)

        val typeList = listOf(
            getString(R.string.automation_type_weather) to "weather",
            getString(R.string.automation_type_reminder) to "reminder",
            getString(R.string.automation_type_music) to "music",
            "လစာမှတ်တမ်း စစ်ဆေး" to "daily_log"
        )
        val typeLabels = typeList.map { it.first }
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerType.setSelection(typeList.indexOfFirst { it.second == item.type }.coerceAtLeast(0))

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
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
            android.app.TimePickerDialog(this, listener, 
                calendar.get(Calendar.HOUR_OF_DAY), 
                calendar.get(Calendar.MINUTE), 
                false).show()
        }
        etHour.setFocusable(false)
        etMinute.setFocusable(false)
        etHour.setOnClickListener(timePickerListener)
        etMinute.setOnClickListener(timePickerListener)

        etMessage.setText(item.message)
        etMusicQuery.setText(item.musicQuery)
        etReminderMinutesBefore.setText(item.reminderMinutesBefore.takeIf { it > 0 }?.toString().orEmpty())
        etReminderText.setText(item.reminderText)

        val dayLabels = listOf("နေ့စဉ်", "တနင်္ဂနွေ", "တနင်္လာ", "အင်္ဂါ", "ဗုဒ္ဓဟူး", "ကြာသပတေး", "သောကြာ", "စနေ")
        spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dayLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerDay.setSelection(item.dayOfWeek)
        switchEnabled.isChecked = item.enabled

        fun updateMessageVisibility() {
            val type = typeList[spinnerType.selectedItemPosition].second
            val isReminder = type == "reminder"
            val isMusic = type == "music"
            etMessage.visibility = if (isReminder) View.VISIBLE else View.GONE
            tvMessageLabel.visibility = if (isReminder) View.VISIBLE else View.GONE
            etMusicQuery.visibility = if (isMusic) View.VISIBLE else View.GONE
            tvMusicLabel.visibility = if (isMusic) View.VISIBLE else View.GONE
        }
        updateMessageVisibility()
        spinnerType.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = updateMessageVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.desc_edit))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val type = typeList[spinnerType.selectedItemPosition].second
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                val message = if (type == "reminder") etMessage.text.toString().trim() else ""
                val dayOfWeek = spinnerDay.selectedItemPosition
                val musicQuery = if (type == "music") etMusicQuery.text.toString().trim() else ""
                val reminderMinutesBefore = etReminderMinutesBefore.text.toString().toIntOrNull()?.coerceIn(0, 1440) ?: 0
                val reminderText = etReminderText.text.toString().trim()
                val enabled = switchEnabled.isChecked

                RoutineStorage.update(
                    this,
                    item.id,
                    type,
                    hour,
                    minute,
                    message,
                    dayOfWeek,
                    musicQuery = musicQuery,
                    reminderText = reminderText,
                    reminderMinutesBefore = reminderMinutesBefore,
                    enabled = enabled,
                    useLiveAgent = item.useLiveAgent
                )
                RoutineScheduler.cancel(this, item.id)
                RoutineStorage.getById(this, item.id)?.let { RoutineScheduler.schedule(this, it) }
                loadAgentTasks()
                Toast.makeText(this, getString(R.string.automation_updated), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCreateDialog() {
        val newItem = RoutineStorage.RoutineItem(
            id = -1,
            type = "reminder",
            hour = 7,
            minute = 0
        )
        showCreateDialogInternal(newItem)
    }

    private fun showCreateDialogInternal(template: RoutineStorage.RoutineItem) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_automation, null)
        val spinnerType = view.findViewById<Spinner>(R.id.spinnerType)
        val etHour = view.findViewById<EditText>(R.id.etHour)
        val etMinute = view.findViewById<EditText>(R.id.etMinute)
        val etMessage = view.findViewById<EditText>(R.id.etMessage)
        val tvMessageLabel = view.findViewById<TextView>(R.id.tvMessageLabel)
        val tvMusicLabel = view.findViewById<TextView>(R.id.tvMusicLabel)
        val etMusicQuery = view.findViewById<EditText>(R.id.etMusicQuery)
        val etReminderMinutesBefore = view.findViewById<EditText>(R.id.etReminderMinutesBefore)
        val etReminderText = view.findViewById<EditText>(R.id.etReminderText)
        val spinnerDay = view.findViewById<Spinner>(R.id.spinnerDay)
        val switchEnabled = view.findViewById<android.widget.Switch>(R.id.switchEnabled)

        val typeList = listOf(
            getString(R.string.automation_type_weather) to "weather",
            getString(R.string.automation_type_reminder) to "reminder",
            getString(R.string.automation_type_music) to "music",
            "လစာမှတ်တမ်း စစ်ဆေး" to "daily_log"
        )
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeList.map { it.first }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerType.setSelection(1)

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
            android.app.TimePickerDialog(this, listener, 
                calendar.get(Calendar.HOUR_OF_DAY), 
                calendar.get(Calendar.MINUTE), 
                false).show()
        }
        etHour.setFocusable(false)
        etMinute.setFocusable(false)
        etHour.setOnClickListener(timePickerListener)
        etMinute.setOnClickListener(timePickerListener)

        etMessage.setText("")
        etMusicQuery.setText("")

        val dayLabels = listOf("နေ့စဉ်", "တနင်္ဂနွေ", "တနင်္လာ", "အင်္ဂါ", "ဗုဒ္ဓဟူး", "ကြာသပတေး", "သောကြာ", "စနေ")
        spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dayLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerDay.setSelection(0)
        switchEnabled.isChecked = true

        fun updateVisibility() {
            val type = typeList[spinnerType.selectedItemPosition].second
            val isReminder = type == "reminder"
            val isMusic = type == "music"
            etMessage.visibility = if (isReminder) View.VISIBLE else View.GONE
            tvMessageLabel.visibility = if (isReminder) View.VISIBLE else View.GONE
            etMusicQuery.visibility = if (isMusic) View.VISIBLE else View.GONE
            tvMusicLabel.visibility = if (isMusic) View.VISIBLE else View.GONE
        }
        updateVisibility()
        spinnerType.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = updateVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.automation_add))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val type = typeList[spinnerType.selectedItemPosition].second
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                val dayOfWeek = spinnerDay.selectedItemPosition
                val message = if (type == "reminder") etMessage.text.toString().trim() else ""
                val musicQuery = if (type == "music") etMusicQuery.text.toString().trim() else ""
                val reminderMinutesBefore = etReminderMinutesBefore.text.toString().toIntOrNull()?.coerceIn(0, 1440) ?: 0
                val reminderText = etReminderText.text.toString().trim()
                val enabled = switchEnabled.isChecked

                val created = RoutineStorage.add(
                    this,
                    type,
                    hour,
                    minute,
                    message,
                    dayOfWeek,
                    musicQuery = musicQuery,
                    reminderText = reminderText,
                    reminderMinutesBefore = reminderMinutesBefore
                )
                if (!enabled) {
                    RoutineStorage.setEnabled(this, created.id, false)
                }
                RoutineScheduler.schedule(this, RoutineStorage.getById(this, created.id)!!)
                loadAgentTasks()
                Toast.makeText(this, getString(R.string.automation_created), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    class AgentTaskAdapter(
        private var items: List<AgentItem>,
        private val onEdit: (AgentItem) -> Unit,
        private val onDelete: (AgentItem) -> Unit,
        private val onToggle: (RoutineStorage.RoutineItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<AgentTaskAdapter.ViewHolder>() {

        private val dayNames = arrayOf("", "တနင်္ဂနွေ", "တနင်္လာ", "အင်္ဂါ", "ဗုဒ္ဓဟူး", "ကြာသပတေး", "သောကြာ", "စနေ")

        fun updateList(list: List<AgentItem>) {
            items = list
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon: TextView = view.findViewById(R.id.tvAutomationIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvAutomationTitle)
            val tvTime: TextView = view.findViewById(R.id.tvAutomationTime)
            val tvStatus: TextView = view.findViewById(R.id.tvAutomationStatus)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEditAutomation)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteAutomation)
            val switchToggle: android.widget.Switch = view.findViewById(R.id.switchAutomationToggle)
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
                        "weather" -> "🌤️"
                        "music" -> "🎵"
                        "daily_log" -> "📊"
                        else -> "⚙️"
                    }
                    holder.tvTitle.text = when (r.type) {
                        "weather" -> ctx.getString(R.string.automation_type_weather)
                        "music" -> r.musicQuery.ifBlank { ctx.getString(R.string.automation_type_music) }
                        "daily_log" -> "နေ့စဉ် လစာမှတ်တမ်း စစ်ဆေး"
                        else -> r.message.ifBlank { ctx.getString(R.string.automation_type_reminder) }
                    }
                    val timeStr = String.format("%02d:%02d", r.hour, r.minute)
                    holder.tvTime.text = if (r.dayOfWeek in 1..7) "${dayNames[r.dayOfWeek]} $timeStr" else "နေ့စဉ် $timeStr"
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
                    holder.tvTime.text = rem.formatTime(ctx)
                    holder.tvStatus.setText(if (rem.isPast) R.string.hub_completed else R.string.hub_active)
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
