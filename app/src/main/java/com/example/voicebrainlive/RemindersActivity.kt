package com.example.voicebrainlive

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.databinding.ActivityRemindersBinding

class RemindersActivity : ComponentActivity() {

    private lateinit var binding: ActivityRemindersBinding
    private lateinit var adapter: RemindersAdapter
    private val remindersList = mutableListOf<ReminderHelper.ReminderItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRemindersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearPast.setOnClickListener {
            val past = remindersList.filter { it.isPast }
            if (past.isEmpty()) {
                Toast.makeText(this, "ပြီးသွားတဲ့ သတိပေးချက် မရှိပါ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("ပြီးသွားတာတွေ ဖျက်မယ်")
                .setMessage("ပြီးသွားပြီး သတိပေးချက် ${past.size} ခု ဖျက်ပါမယ်")
                .setPositiveButton("ဖျက်မယ်") { _, _ ->
                    ReminderHelper.clearAllPast(this)
                    loadReminders()
                    Toast.makeText(this, "ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("မလုပ်တော့ဘူး", null)
                .show()
        }

        adapter = RemindersAdapter(remindersList) { item ->
            AlertDialog.Builder(this)
                .setTitle("သတိပေးချက် ဖျက်မယ်")
                .setMessage("\"${item.message}\" ကို ဖျက်မလား")
                .setPositiveButton("ဖျက်မယ်") { _, _ ->
                    ReminderHelper.deleteReminder(this, item.id)
                    loadReminders()
                    Toast.makeText(this, "ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("မလုပ်တော့ဘူး", null)
                .show()
        }

        binding.recyclerReminders.layoutManager = LinearLayoutManager(this)
        binding.recyclerReminders.adapter = adapter

        loadReminders()
    }

    override fun onResume() {
        super.onResume()
        loadReminders()
    }

    private fun loadReminders() {
        remindersList.clear()
        remindersList.addAll(ReminderHelper.getReminders(this))
        adapter.notifyDataSetChanged()

        binding.tvEmpty.visibility = if (remindersList.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerReminders.visibility = if (remindersList.isEmpty()) View.GONE else View.VISIBLE
    }

    class RemindersAdapter(
        private val items: List<ReminderHelper.ReminderItem>,
        private val onDelete: (ReminderHelper.ReminderItem) -> Unit
    ) : RecyclerView.Adapter<RemindersAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reminder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvMessage.text = item.message
            val ctx = holder.itemView.context
            val timeLabel = if (item.isPast) "ပြီးပြီ" else formatTriggerTime(ctx, item.triggerTime)
            holder.tvTime.text = timeLabel
            holder.tvTime.alpha = if (item.isPast) 0.6f else 1f
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount() = items.size

        private fun formatTriggerTime(context: Context, millis: Long): String {
            val now = System.currentTimeMillis()
            val diff = millis - now
            return when {
                diff < 60_000 -> "ယခု"
                diff < 3600_000 -> "${diff / 60_000} မိနစ် ကြာမယ်"
                else -> {
                    val cal = java.util.Calendar.getInstance(SettingsActivity.getTimezone(context))
                    cal.timeInMillis = millis
                    String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                }
            }
        }
    }
}
